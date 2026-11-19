/**
 * @file src/rhremote_clipboard.h
 * @brief Platform-neutral clipboard synchronization broker (format announcements, delayed rendering, chunked transfers).
 *
 * Protocol summary (paired HTTPS on the GameStream port, active stream required):
 *  - The client long-polls `GET /rhremote/clipboard/events`; the host answers with format announcements
 *    (`formats`), render requests (`render`) and cancellations (`cancel`).
 *  - `POST /rhremote/clipboard/formats` announces the client's clipboard; the host takes delayed ownership.
 *  - `GET /rhremote/clipboard/data` returns chunks of one host format snapshot.
 *  - `POST /rhremote/clipboard/render` uploads chunks answering one host render request.
 *
 * Only metadata and requested data cross the wire; nothing is transferred until an application pastes.
 */
#pragma once

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <random>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

#include <nlohmann/json.hpp>

namespace rhremote::clipboard {
  using namespace std::chrono_literals;

  constexpr int protocol_version = 1;  ///< Wire protocol version accepted by both ends.
  constexpr std::size_t max_formats = 64;  ///< Maximum announced formats per clipboard change.
  constexpr std::size_t max_format_name = 255;  ///< Maximum registered format name length in UTF-8 bytes.
  constexpr std::size_t chunk_size = 4u << 20;  ///< Maximum payload of one data or render chunk.
  constexpr std::uint64_t max_total = std::uint64_t {4} << 30;  ///< Hard ceiling for one format, independent of memory.
  constexpr std::size_t max_events = 64;  ///< Queued events retained per peer.
  constexpr auto max_poll_wait = 25s;  ///< Longest long-poll hold.
  constexpr auto render_budget = 10s;  ///< Time a pasting application waits before the transfer continues in the background.
  constexpr auto transfer_idle = 15s;  ///< Transfers without any progress for this long fail.
  constexpr auto peer_check_interval = 2s;  ///< Interval for stream liveness checks.

  /** @brief One clipboard format as seen by its source. Standard formats have an empty name. */
  struct format_t {
    std::uint32_t id = 0;  ///< Source-local format identifier.
    std::string name;  ///< Registered format name; empty for standard formats.

    bool operator==(const format_t &) const = default;
  };

  // Windows standard clipboard format identifiers, shared by both ends of the protocol.
  constexpr std::uint32_t cf_text = 1;
  constexpr std::uint32_t cf_oemtext = 7;
  constexpr std::uint32_t cf_dib = 8;
  constexpr std::uint32_t cf_unicodetext = 13;
  constexpr std::uint32_t cf_locale = 16;
  constexpr std::uint32_t cf_dibv5 = 17;
  constexpr std::uint32_t cf_registered_first = 0xC000;

  /**
   * @brief Check whether a standard format holds plain memory that can be copied between machines.
   * @param id Standard format identifier (< 0xC000).
   * @return False for handles (bitmaps, metafiles, palettes), files, owner-display and private ranges.
   */
  constexpr bool standard_transferable(std::uint32_t id) {
    switch (id) {
      case 1:  // CF_TEXT
      case 4:  // CF_SYLK
      case 5:  // CF_DIF
      case 6:  // CF_TIFF
      case 7:  // CF_OEMTEXT
      case 8:  // CF_DIB
      case 11:  // CF_RIFF
      case 12:  // CF_WAVE
      case 13:  // CF_UNICODETEXT
      case 16:  // CF_LOCALE
      case 17:  // CF_DIBV5
        return true;
      default:
        return false;
    }
  }

  /**
   * @brief Reject registered formats that reference process, OLE, shell or file-system state.
   * @param name Registered format name.
   * @return Whether the format must not be forwarded.
   */
  inline bool registered_blocked(std::string_view name) {
    static constexpr std::string_view blocked[] {
      "DataObject",
      "Ole Private Data",
      "Object Descriptor",
      "Link Source Descriptor",
      "Embed Source",
      "Link Source",
      "Embedded Object",
      "OwnerLink",
      "ObjectLink",
      "Native",
      "Shell IDList Array",
      "Shell Object Offsets",
      "FileName",
      "FileNameW",
      "FileNameMap",
      "FileNameMapW",
      "FileGroupDescriptor",
      "FileGroupDescriptorW",
      "FileContents",
      "Preferred DropEffect",
      "Performed DropEffect",
      "Logical Performed DropEffect",
      "Paste Succeeded",
      "InShellDragLoop",
      "TargetCLSID",
      "DragContext",
      "DragImageBits",
      "DragSourceHelperFlags",
      "DropDescription",
      "UsingDefaultDragImage",
      "IsShowingLayered",
      "IsShowingText",
      "IsComputingImage",
      "ComputedDragImage",
      "DisableDragText",
      "UntrustedDragDrop",
    };
    if (name.empty() || name.size() > max_format_name) {
      return true;
    }
    return std::find(std::begin(blocked), std::end(blocked), name) != std::end(blocked);
  }

  /**
   * @brief Choose the formats to announce from a source's ordered clipboard enumeration.
   * @param available Formats in the source's preference order, including synthesized ones.
   * @return Transferable formats, without formats Windows synthesizes from a better one, capped at max_formats.
   */
  inline std::vector<format_t> select(const std::vector<format_t> &available) {
    const auto has = [&](std::uint32_t id) {
      return std::any_of(available.begin(), available.end(), [id](const format_t &f) {
        return f.id == id && f.name.empty();
      });
    };
    const bool unicode = has(cf_unicodetext);
    const bool dibv5 = has(cf_dibv5);
    std::vector<format_t> selected;
    for (const auto &format : available) {
      if (selected.size() >= max_formats) {
        break;
      }
      if (format.id >= cf_registered_first) {
        if (registered_blocked(format.name)) {
          continue;
        }
      } else if (!format.name.empty() || !standard_transferable(format.id)) {
        continue;
      } else if ((unicode && (format.id == cf_text || format.id == cf_oemtext)) || (dibv5 && format.id == cf_dib)) {
        continue;  // The receiver's system synthesizes these from the announced richer format.
      }
      if (std::find(selected.begin(), selected.end(), format) == selected.end()) {
        selected.push_back(format);
      }
    }
    return selected;
  }

  /** @brief Serialize formats for the wire. @param formats Selected formats. @return JSON array. */
  inline nlohmann::json to_json(const std::vector<format_t> &formats) {
    auto array = nlohmann::json::array();
    for (const auto &format : formats) {
      nlohmann::json entry {{"id", format.id}};
      if (!format.name.empty()) {
        entry["name"] = format.name;
      }
      array.push_back(std::move(entry));
    }
    return array;
  }

  /**
   * @brief Validate and parse an announced format list.
   * @param value JSON array from the peer.
   * @return Parsed transferable formats, or empty on any malformed entry.
   */
  inline std::optional<std::vector<format_t>> parse_formats(const nlohmann::json &value) {
    if (!value.is_array() || value.size() > max_formats) {
      return std::nullopt;
    }
    std::vector<format_t> formats;
    for (const auto &entry : value) {
      if (!entry.is_object() || !entry.contains("id") || !entry["id"].is_number_unsigned()) {
        return std::nullopt;
      }
      const auto id = entry["id"].get<std::uint64_t>();
      if (id == 0 || id > 0xFFFF) {
        return std::nullopt;
      }
      format_t format {static_cast<std::uint32_t>(id), {}};
      if (entry.contains("name")) {
        if (!entry["name"].is_string()) {
          return std::nullopt;
        }
        format.name = entry["name"].get<std::string>();
      }
      if (format.id >= cf_registered_first ? registered_blocked(format.name) : (!format.name.empty() || !standard_transferable(format.id))) {
        return std::nullopt;
      }
      formats.push_back(std::move(format));
    }
    return formats;
  }

  /** @brief Outcome of one uploaded render chunk. */
  enum class chunk_result {
    accepted,  ///< Stored; more data expected.
    completed,  ///< Final chunk stored.
    cancelled,  ///< The host no longer wants this request.
    invalid,  ///< Offset, total or ownership mismatch.
    too_large,  ///< The declared total exceeds the receiver's memory budget.
  };

  /** @brief Outcome of waiting for a render inside the pasting application's budget. */
  enum class wait_result {
    completed,  ///< Data is available now.
    pending,  ///< Still transferring; delivery continues through the late callback.
    failed,  ///< The peer failed, went away or stopped making progress.
  };

  /**
   * @brief Per-peer event queues, long-poll waiters and render transfers.
   *
   * The broker never touches the operating system clipboard. A platform backend calls begin_render()/wait_render()
   * from its clipboard thread and publish_formats() when the local clipboard changes.
   */
  class broker {
  public:
    using clock = std::chrono::steady_clock;
    using responder = std::function<void(nlohmann::json)>;  ///< Completes one long-poll exactly once.
    using late_delivery = std::function<void(std::uint64_t request, std::vector<std::uint8_t> data)>;
    using peer_gone = std::function<void(const std::string &peer)>;

    /**
     * @brief Create a broker.
     * @param alive Whether a peer still owns a running stream.
     * @param memory_budget Bytes one incoming format may occupy now.
     */
    broker(std::function<bool(const std::string &)> alive, std::function<std::uint64_t()> memory_budget):
        alive_(std::move(alive)),
        memory_budget_(std::move(memory_budget)),
        epoch_(std::random_device {}() | (std::uint64_t(std::random_device {}()) << 32)) {}

    ~broker() {
      stop();
    }

    broker(const broker &) = delete;
    broker &operator=(const broker &) = delete;

    /** @brief Install backend callbacks before start(). @param late Late completion. @param gone Peer removal. */
    void set_callbacks(late_delivery late, peer_gone gone) {
      std::lock_guard lock {mutex_};
      late_ = std::move(late);
      gone_ = std::move(gone);
    }

    /** @brief Start the timer that expires polls, idle transfers and ended peers. */
    void start() {
      std::lock_guard lock {mutex_};
      if (timer_.joinable() || stopping_) {
        return;
      }
      timer_ = std::thread([this] {
        run_timer();
      });
    }

    /** @brief Answer outstanding polls, fail transfers and join the timer. */
    void stop() {
      std::vector<responder> waiters;
      {
        std::lock_guard lock {mutex_};
        if (stopping_) {
          return;
        }
        stopping_ = true;
        for (auto &[peer, state] : peers_) {
          if (state.waiter) {
            waiters.push_back(std::move(state.waiter));
          }
        }
        for (auto &[id, render] : renders_) {
          render.state = render_state::failed;
        }
      }
      changed_.notify_all();
      for (auto &waiter : waiters) {
        waiter(empty_events(0));
      }
      if (timer_.joinable()) {
        timer_.join();
      }
    }

    /** @brief Random identifier of this broker instance; clients reset cursors when it changes. */
    std::uint64_t epoch() const {
      return epoch_;
    }

    /**
     * @brief Long-poll for events after a cursor.
     * @param peer Verified peer certificate.
     * @param epoch Epoch the cursor belongs to; another epoch restarts from the beginning.
     * @param cursor Last event sequence the peer processed.
     * @param wait Maximum hold, clamped to max_poll_wait.
     * @param respond Called exactly once, possibly on another thread.
     */
    void poll(const std::string &peer, std::uint64_t epoch, std::uint64_t cursor, std::chrono::milliseconds wait, responder respond) {
      responder replaced;
      std::optional<nlohmann::json> immediate;
      {
        std::lock_guard lock {mutex_};
        if (stopping_) {
          immediate = empty_events(0);
        } else {
          auto &state = peers_[peer];
          state.last_seen = clock::now();
          if (epoch != epoch_) {
            cursor = 0;
          }
          auto events = collect(state, cursor);
          if (!events.empty() || wait <= 0ms) {
            immediate = make_events(state, std::move(events));
          } else {
            replaced = std::move(state.waiter);
            state.waiter = std::move(respond);
            state.waiter_cursor = cursor;
            state.waiter_deadline = clock::now() + std::min<std::chrono::milliseconds>(wait, max_poll_wait);
          }
        }
      }
      if (replaced) {
        replaced(empty_events(0));
      }
      if (immediate) {
        respond(std::move(*immediate));
      }
    }

    /**
     * @brief Announce a host clipboard change to every connected peer except the one that caused it.
     * @param seq Host clipboard sequence used to validate later data reads.
     * @param formats Selected formats.
     * @param except Peer whose own announcement produced this change, or empty.
     */
    void publish_formats(std::uint64_t seq, const std::vector<format_t> &formats, const std::string &except = {}) {
      push_all({{"type", "formats"}, {"seq", seq}, {"formats", to_json(formats)}}, except);
    }

    /**
     * @brief Ask a peer to render one of its announced formats.
     * @param peer Owner of the delayed clipboard.
     * @param format Peer-local format identifier.
     * @return Request identifier, or 0 when the peer is unknown.
     */
    std::uint64_t begin_render(const std::string &peer, std::uint32_t format) {
      std::uint64_t id;
      {
        std::lock_guard lock {mutex_};
        if (stopping_ || !peers_.contains(peer)) {
          return 0;
        }
        id = ++next_render_;
        auto &render = renders_[id];
        render.peer = peer;
        render.format = format;
        render.last_progress = clock::now();
      }
      push(peer, {{"type", "render"}, {"request", id}, {"format", format}});
      return id;
    }

    /**
     * @brief Wait for a render until a deadline; afterwards the transfer may continue in the background.
     * @param request Identifier from begin_render().
     * @param deadline End of the pasting application's budget.
     * @param out Receives the data on completion.
     * @return Completed, pending (late delivery follows) or failed.
     */
    wait_result wait_render(std::uint64_t request, clock::time_point deadline, std::vector<std::uint8_t> &out) {
      std::unique_lock lock {mutex_};
      for (;;) {
        const auto found = renders_.find(request);
        if (found == renders_.end()) {
          return wait_result::failed;
        }
        auto &render = found->second;
        if (render.state == render_state::completed) {
          out = std::move(render.data);
          renders_.erase(found);
          return wait_result::completed;
        }
        if (render.state == render_state::failed) {
          renders_.erase(found);
          return wait_result::failed;
        }
        const auto now = clock::now();
        if (now >= deadline) {
          render.detached = true;
          return wait_result::pending;
        }
        changed_.wait_until(lock, std::min(deadline, now + 250ms));
      }
    }

    /**
     * @brief Abandon a render, telling the peer to stop uploading.
     * @param request Identifier from begin_render().
     */
    void cancel_render(std::uint64_t request) {
      std::string peer;
      {
        std::lock_guard lock {mutex_};
        const auto found = renders_.find(request);
        if (found == renders_.end()) {
          return;
        }
        peer = found->second.peer;
        renders_.erase(found);
      }
      changed_.notify_all();
      push(peer, {{"type", "cancel"}, {"request", request}});
    }

    /**
     * @brief Store one uploaded chunk.
     * @param peer Verified uploader; must own the request.
     * @param request Render request identifier.
     * @param offset Byte offset of this chunk; chunks arrive in order.
     * @param total Declared size of the whole format.
     * @param data Chunk payload.
     * @return Chunk outcome.
     */
    chunk_result accept_chunk(const std::string &peer, std::uint64_t request, std::uint64_t offset, std::uint64_t total, std::string_view data) {
      late_delivery late;
      std::vector<std::uint8_t> delivered;
      chunk_result result;
      {
        std::lock_guard lock {mutex_};
        const auto found = renders_.find(request);
        if (found == renders_.end() || found->second.state == render_state::failed) {
          return chunk_result::cancelled;
        }
        auto &render = found->second;
        if (render.peer != peer) {
          return chunk_result::invalid;
        }
        if (offset == 0 && render.received == 0 && !render.total) {
          if (total > max_total || total > memory_budget()) {
            render.state = render_state::failed;
            changed_.notify_all();
            return chunk_result::too_large;
          }
          render.total = total;
          render.data.reserve(static_cast<std::size_t>(total));
        }
        if (!render.total || *render.total != total || offset != render.received || data.size() > chunk_size || offset + data.size() > total ||
            (data.empty() && offset != total)) {
          return chunk_result::invalid;
        }
        render.data.insert(render.data.end(), data.begin(), data.end());
        render.received += data.size();
        render.last_progress = clock::now();
        if (render.received < total) {
          return chunk_result::accepted;
        }
        render.state = render_state::completed;
        result = chunk_result::completed;
        if (render.detached) {
          late = late_;
          delivered = std::move(render.data);
          renders_.erase(found);
        }
      }
      changed_.notify_all();
      if (late) {
        late(request, std::move(delivered));
      }
      return result;
    }

    /**
     * @brief Record that the peer cannot render a request.
     * @param peer Verified uploader; must own the request.
     * @param request Render request identifier.
     */
    void fail_render(const std::string &peer, std::uint64_t request) {
      {
        std::lock_guard lock {mutex_};
        const auto found = renders_.find(request);
        if (found == renders_.end() || found->second.peer != peer) {
          return;
        }
        if (found->second.detached) {
          renders_.erase(found);
        } else {
          found->second.state = render_state::failed;
        }
      }
      changed_.notify_all();
    }

    /** @brief Progress snapshot for diagnostics. @param request Identifier. @return Received and total bytes. */
    std::optional<std::pair<std::uint64_t, std::uint64_t>> progress(std::uint64_t request) {
      std::lock_guard lock {mutex_};
      const auto found = renders_.find(request);
      if (found == renders_.end()) {
        return std::nullopt;
      }
      return std::pair {found->second.received, found->second.total.value_or(0)};
    }

    /** @brief Run one maintenance pass; exposed for deterministic tests. @param now Current time. @param check_peers Whether to query liveness. */
    void tick(clock::time_point now, bool check_peers) {
      std::vector<responder> expired;
      std::vector<std::pair<std::string, std::uint64_t>> cancelled;
      std::vector<std::string> gone_peers;
      peer_gone gone;
      {
        std::lock_guard lock {mutex_};
        for (auto &[peer, state] : peers_) {
          if (state.waiter && now >= state.waiter_deadline) {
            expired.push_back(std::move(state.waiter));
            state.waiter = {};
          }
        }
        for (auto it = renders_.begin(); it != renders_.end();) {
          if (it->second.state != render_state::completed && now - it->second.last_progress > transfer_idle) {
            cancelled.emplace_back(it->second.peer, it->first);
            if (it->second.detached) {
              it = renders_.erase(it);
              continue;
            }
            it->second.state = render_state::failed;
          }
          ++it;
        }
        if (check_peers) {
          for (const auto &[peer, state] : peers_) {
            if (!alive_ || !alive_(peer)) {
              gone_peers.push_back(peer);
            }
          }
          for (const auto &peer : gone_peers) {
            if (auto &waiter = peers_[peer].waiter) {
              expired.push_back(std::move(waiter));
            }
            peers_.erase(peer);
            for (auto it = renders_.begin(); it != renders_.end();) {
              if (it->second.peer != peer) {
                ++it;
              } else if (it->second.detached) {
                it = renders_.erase(it);
              } else {
                it->second.state = render_state::failed;
                ++it;
              }
            }
          }
          gone = gone_;
        }
      }
      if (!cancelled.empty() || !gone_peers.empty()) {
        changed_.notify_all();
      }
      for (auto &waiter : expired) {
        waiter(empty_events(0));
      }
      for (const auto &[peer, request] : cancelled) {
        push(peer, {{"type", "cancel"}, {"request", request}});
      }
      if (gone) {
        for (const auto &peer : gone_peers) {
          gone(peer);
        }
      }
    }

  private:
    enum class render_state { waiting, completed, failed };

    struct render_t {
      std::string peer;
      std::uint32_t format = 0;
      std::optional<std::uint64_t> total;
      std::uint64_t received = 0;
      std::vector<std::uint8_t> data;
      clock::time_point last_progress;
      render_state state = render_state::waiting;
      bool detached = false;  ///< The pasting application gave up; deliver through late_.
    };

    struct peer_t {
      std::deque<std::pair<std::uint64_t, nlohmann::json>> events;
      std::uint64_t next_event = 0;
      responder waiter;
      std::uint64_t waiter_cursor = 0;
      clock::time_point waiter_deadline;
      clock::time_point last_seen;
    };

    std::uint64_t memory_budget() const {
      return memory_budget_ ? memory_budget_() : max_total;
    }

    static std::vector<nlohmann::json> collect(const peer_t &state, std::uint64_t cursor) {
      std::vector<nlohmann::json> events;
      for (const auto &[sequence, event] : state.events) {
        if (sequence > cursor) {
          events.push_back(event);
        }
      }
      return events;
    }

    nlohmann::json make_events(const peer_t &state, std::vector<nlohmann::json> events) const {
      return {{"ok", true}, {"epoch", epoch_}, {"cursor", state.next_event}, {"events", std::move(events)}};
    }

    nlohmann::json empty_events(std::uint64_t) const {
      return {{"ok", true}, {"epoch", epoch_}, {"events", nlohmann::json::array()}};
    }

    void push(const std::string &peer, nlohmann::json event) {
      responder waiter;
      nlohmann::json reply;
      {
        std::lock_guard lock {mutex_};
        const auto found = peers_.find(peer);
        if (stopping_ || found == peers_.end()) {
          return;
        }
        enqueue(found->second, std::move(event));
        if (found->second.waiter) {
          waiter = std::move(found->second.waiter);
          found->second.waiter = {};
          reply = make_events(found->second, collect(found->second, found->second.waiter_cursor));
        }
      }
      if (waiter) {
        waiter(std::move(reply));
      }
    }

    void push_all(const nlohmann::json &event, const std::string &except) {
      std::vector<std::pair<responder, nlohmann::json>> ready;
      {
        std::lock_guard lock {mutex_};
        if (stopping_) {
          return;
        }
        for (auto &[peer, state] : peers_) {
          if (peer == except) {
            continue;
          }
          enqueue(state, event);
          if (state.waiter) {
            ready.emplace_back(std::move(state.waiter), make_events(state, collect(state, state.waiter_cursor)));
            state.waiter = {};
          }
        }
      }
      for (auto &[waiter, reply] : ready) {
        waiter(std::move(reply));
      }
    }

    static void enqueue(peer_t &state, nlohmann::json event) {
      if (event.value("type", "") == "formats") {
        // Only the newest clipboard announcement matters; older ones would reference stale sequences.
        std::erase_if(state.events, [](const auto &entry) {
          return entry.second.value("type", "") == "formats";
        });
      }
      state.events.emplace_back(++state.next_event, std::move(event));
      while (state.events.size() > max_events) {
        state.events.pop_front();
      }
    }

    void run_timer() {
      auto next_check = clock::now() + peer_check_interval;
      for (;;) {
        {
          std::unique_lock lock {mutex_};
          stop_wait_.wait_for(lock, 200ms, [this] {
            return stopping_;
          });
          if (stopping_) {
            return;
          }
        }
        const auto now = clock::now();
        const bool check = now >= next_check;
        if (check) {
          next_check = now + peer_check_interval;
        }
        tick(now, check);
      }
    }

    std::function<bool(const std::string &)> alive_;
    std::function<std::uint64_t()> memory_budget_;
    const std::uint64_t epoch_;
    std::mutex mutex_;
    std::condition_variable changed_;
    std::condition_variable stop_wait_;
    std::map<std::string, peer_t> peers_;
    std::unordered_map<std::uint64_t, render_t> renders_;
    std::uint64_t next_render_ = 0;
    late_delivery late_;
    peer_gone gone_;
    bool stopping_ = false;
    std::thread timer_;
  };

  /** @brief Last host format snapshot kept while a client reads it chunk by chunk. */
  class snapshot_cache {
  public:
    using data_t = std::shared_ptr<const std::vector<std::uint8_t>>;

    /** @brief Find a snapshot. @param seq Host sequence. @param format Format identifier. @return Cached data or empty. */
    data_t find(std::uint64_t seq, std::uint32_t format) {
      std::lock_guard lock {mutex_};
      if (data_ && seq_ == seq && format_ == format && std::chrono::steady_clock::now() - used_ < 60s) {
        used_ = std::chrono::steady_clock::now();
        return data_;
      }
      return {};
    }

    /** @brief Replace the snapshot. @param seq Host sequence. @param format Format identifier. @param data Owned bytes. */
    void store(std::uint64_t seq, std::uint32_t format, data_t data) {
      std::lock_guard lock {mutex_};
      seq_ = seq;
      format_ = format;
      data_ = std::move(data);
      used_ = std::chrono::steady_clock::now();
    }

    /** @brief Drop the snapshot, e.g. after the host clipboard changes. */
    void clear() {
      std::lock_guard lock {mutex_};
      data_.reset();
    }

  private:
    std::mutex mutex_;
    std::uint64_t seq_ = 0;
    std::uint32_t format_ = 0;
    data_t data_;
    std::chrono::steady_clock::time_point used_;
  };
}  // namespace rhremote::clipboard
