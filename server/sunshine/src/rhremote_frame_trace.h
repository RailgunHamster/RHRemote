/**
 * @file src/rhremote_frame_trace.h
 * @brief Bounded, metadata-only frame tracing shared by capture, encoder and sender.
 */
#pragma once

#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <ostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

namespace rhremote {
  /** @brief Return the host steady-clock timestamp in microseconds. @return Monotonic microseconds. */
  inline std::int64_t frame_tick_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
  }

  /** @brief Validate a non-path client trace label. @param tag Client-provided label. @return Whether it is safe JSON metadata. */
  inline bool valid_trace_tag(std::string_view tag) {
    if (tag.empty() || tag.size() > 96 || tag == "." || tag == "..") {
      return false;
    }
    for (const unsigned char c : tag) {
      if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.')) {
        return false;
      }
    }
    return true;
  }

  /** @brief Decide whether a paired launch requested valid frame statistics. @param flag Opt-in query value. @param tag Client label. @return Whether tracing is requested. */
  inline bool frame_trace_requested(std::string_view flag, std::string_view tag) {
    return flag == "1" && valid_trace_tag(tag);
  }

  /** @brief Source-image identity; an unknown presentation timestamp remains explicitly unknown. */
  struct capture_stamp {
    std::uint64_t id {};  ///< Identity of a newly delivered captured image.
    std::int64_t source_present_us {};  ///< Existing platform presentation timestamp in the host steady clock.
    std::int64_t ready_us {};  ///< Time the capture callback delivered this image.
    bool source_time_known {};  ///< Whether the platform actually supplied the presentation timestamp.
  };

  /** @brief Assign a new source identity at capture delivery. @param source Optional platform presentation time. @return Source metadata without image content. */
  inline capture_stamp stamp_capture(std::optional<std::chrono::steady_clock::time_point> source) {
    static std::atomic<std::uint64_t> serial {};
    return {++serial, source ? std::chrono::duration_cast<std::chrono::microseconds>(source->time_since_epoch()).count() : 0, frame_tick_us(), source.has_value()};
  }

  /** @brief Stages emitted by actual pipeline operations, including unsuccessful operations. */
  enum class frame_stage {
    capture_ready,  ///< A new image was delivered to this session.
    convert_begin,  ///< Conversion or device import started.
    convert_end,  ///< Conversion or device import returned.
    encode_begin,  ///< A numbered frame was submitted for encoding.
    encode_end,  ///< The encoder call returned, which is distinct from packet output.
    encode_output,  ///< A packet with an actual output PTS was produced.
    metadata_evicted,  ///< A pending PTS mapping was evicted before output.
    metadata_missing,  ///< Output could not be matched to its original submission.
    send_begin,  ///< The sender dequeued an encoded packet.
    send_end,  ///< Packetization and send API calls returned.
    send_error  ///< The sender failed before successful completion.
  };

  /** @brief Name a fixed pipeline stage. @param stage Stage value. @return Stable schema name. */
  inline std::string_view frame_stage_name(frame_stage stage) {
    switch (stage) {
      case frame_stage::capture_ready:
        return "capture_ready";
      case frame_stage::convert_begin:
        return "convert_begin";
      case frame_stage::convert_end:
        return "convert_end";
      case frame_stage::encode_begin:
        return "encode_begin";
      case frame_stage::encode_end:
        return "encode_end";
      case frame_stage::encode_output:
        return "encode_output";
      case frame_stage::metadata_evicted:
        return "metadata_evicted";
      case frame_stage::metadata_missing:
        return "metadata_missing";
      case frame_stage::send_begin:
        return "send_begin";
      case frame_stage::send_end:
        return "send_end";
      case frame_stage::send_error:
        return "send_error";
    }
    return "unknown";
  }

  /** @brief Fixed-size event copied by producers; it contains no payload or user strings. */
  struct frame_event {
    frame_stage stage {};  ///< Actual pipeline stage.
    std::int64_t at_us {};  ///< Host steady-clock event timestamp.
    std::int64_t frame {-1};  ///< Encoder/network frame number, or -1 before assignment.
    std::uint64_t generation {};  ///< Encoder instance identity within the host process.
    capture_stamp source;  ///< Original source-image metadata.
    std::uint64_t packet {};  ///< Per-trace encoded packet identity.
    std::uint64_t bytes {};  ///< Encoded bytes or sent UDP payload bytes, according to stage.
    std::uint64_t packets {};  ///< Number of data/FEC datagrams passed to send calls.
    std::int64_t fec_us {};  ///< Wall time spent generating FEC.
    std::int64_t pacing_us {};  ///< Actual wall time spent waiting for pacing deadlines.
    std::int64_t send_us {};  ///< Wall time inside send API calls.
    std::uint32_t rtp_timestamp {};  ///< Actual outgoing RTP 90 kHz timestamp.
    int result {};  ///< Zero for success, nonzero for a failure or missing mapping.
    bool fresh {};  ///< Whether this encoder submission consumed a new capture.
    bool metadata_known {};  ///< Whether output was matched to its original submission.
    bool rtp_known {};  ///< Whether the sender assigned an actual RTP timestamp.
    bool wire_duplicate {};  ///< Whether RTP required a synthetic timestamp for repeated input.
  };

  /** @brief Recorder resource bounds, configurable for deterministic regression tests. */
  struct frame_trace_options {
    std::size_t queue_capacity {4096};  ///< Preallocated producer ring, at most 65536 events.
    std::uint64_t max_bytes {64ULL * 1024 * 1024};  ///< Whole-file limit, including reserved footer space.
  };

  /** @brief Metadata recorder whose background writer outlives queued packet references. */
  class frame_trace {
    static_assert(std::atomic<std::uint64_t>::is_always_lock_free && std::atomic<std::uint32_t>::is_always_lock_free && std::atomic<bool>::is_always_lock_free, "Frame producers require lock-free atomics");

    /** @brief One producer-reserved slot, released only after the consumer copies its metadata. */
    struct alignas(64) slot {
      frame_event row;  ///< POD data published before ready becomes true.
      std::atomic<bool> ready {};  ///< Release/acquire publication, independent of the sleeping writer.
    };

    /** @brief Shared writer state; the detached worker owns this state, not the public wrapper. */
    struct state {
      std::unique_ptr<std::ostream> output;  ///< Stream written only by the writer thread.
      std::string id, client;  ///< Generated trace identity and validated client label.
      const std::int64_t steady_origin {frame_tick_us()};  ///< Steady epoch sampled before producers can emit.
      const std::int64_t unix_origin {std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::system_clock::now().time_since_epoch()).count()};  ///< Approximate wall-clock correspondence, not synchronization.
      frame_trace_options options;  ///< Resource limits fixed at construction.
      std::vector<slot> ring;  ///< Fixed-capacity multi-producer, single-consumer buffer.
      std::atomic<std::uint64_t> claimed {}, consumed {};  ///< Reserved and copied positions; depth never exceeds capacity.
      std::atomic<std::uint32_t> emitters {};  ///< In-flight producers that must publish before close can finish.
      std::mutex mutex;  ///< Only writer sleep and exporter completion; producers never acquire it.
      std::condition_variable wake, closed;  ///< Writer work and completion signals.
      std::atomic<std::uint64_t> lost {}, packet_serial {};  ///< Lost-event and packet identity counters.
      std::atomic<bool> failed {}, capped {}, done {}, stopping {};  ///< Writer health and nonblocking close request.
      std::function<void(std::string_view)> diagnostic;  ///< Optional writer-thread failure notification.

      /** @brief Initialize preallocated writer state. @param sink Owned stream. @param trace_id Generated ID. @param tag Validated client label. @param limits Bounds. @param notify Failure notification. */
      state(std::unique_ptr<std::ostream> sink, std::string trace_id, std::string tag, frame_trace_options limits, std::function<void(std::string_view)> notify):
          output(std::move(sink)),
          id(std::move(trace_id)),
          client(std::move(tag)),
          options(limits),
          ring(limits.queue_capacity),
          diagnostic(std::move(notify)) {}
    };

    std::shared_ptr<state> state_;  ///< State retained by the writer until its footer is closed.
    std::filesystem::path path_;  ///< Metadata file path, empty for an injected test sink.

    /** @brief Serialize one POD event on the writer thread. @param row Event. @param shared Immutable trace metadata. @return One JSON line. */
    static std::string serialize(const frame_event &row, const state &shared) {
      std::ostringstream out;
      out << std::boolalpha << "{\"event\":\"" << frame_stage_name(row.stage) << "\",\"trace_id\":\"" << shared.id << "\",\"client_id\":\"" << shared.client << "\",\"at_us\":" << row.at_us << ",\"frame\":" << row.frame
          << ",\"generation\":" << row.generation << ",\"source\":" << row.source.id << ",\"source_present_us\":" << row.source.source_present_us
          << ",\"source_time_known\":" << row.source.source_time_known << ",\"capture_ready_us\":" << row.source.ready_us
          << ",\"packet\":" << row.packet << ",\"bytes\":" << row.bytes << ",\"packets\":" << row.packets
          << ",\"fec_us\":" << row.fec_us << ",\"pacing_us\":" << row.pacing_us << ",\"send_us\":" << row.send_us
          << ",\"rtp_timestamp\":" << row.rtp_timestamp << ",\"rtp_known\":" << row.rtp_known << ",\"wire_duplicate\":" << row.wire_duplicate
          << ",\"fresh\":" << row.fresh << ",\"metadata_known\":" << row.metadata_known << ",\"result\":" << row.result << "}\n";
      return out.str();
    }

    /** @brief Drain a bounded ring without doing file I/O on producer threads. @param shared State owned until completion. */
    static void write_loop(const std::shared_ptr<state> &shared) noexcept {
      std::uint64_t bytes = 0;
      try {
        std::ostringstream header;
        header << "{\"event\":\"trace_begin\",\"schema\":\"rhremote.host-frame/v1\",\"trace_id\":\"" << shared->id
               << "\",\"client_id\":\"" << shared->client << "\",\"steady_origin_us\":" << shared->steady_origin
               << ",\"unix_origin_us\":" << shared->unix_origin
               << ",\"physical_scanout_measured\":false,\"clocks_synchronized\":false,\"send_endpoint\":\"send_call_return\"}\n";
        const auto first = header.str();
        *shared->output << first;
        bytes += first.size();
        std::array<frame_event, 256> batch;
        const auto ready = [&] {
          const auto position = shared->consumed.load(std::memory_order_relaxed);
          return shared->ring[position % shared->ring.size()].ready.load(std::memory_order_acquire);
        };
        while (true) {
          std::size_t size = 0;
          while (size < batch.size() && ready()) {
            const auto position = shared->consumed.load(std::memory_order_relaxed);
            auto &item = shared->ring[position % shared->ring.size()];
            batch[size++] = item.row;
            item.ready.store(false, std::memory_order_relaxed);
            shared->consumed.store(position + 1, std::memory_order_release);
          }
          if (!size) {
            std::unique_lock lock(shared->mutex);
            // Producers never acquire this mutex. A bounded wait also covers a
            // notification between the predicate check and entering the wait.
            shared->wake.wait_for(lock, std::chrono::milliseconds(10), [&] {
              return ready() || (shared->stopping && shared->emitters == 0);
            });
            // Check publication only after the SC emitter count: close cannot
            // skip the last producer's event by observing ready too early.
            if (shared->stopping && shared->emitters == 0 && !ready()) {
              break;
            }
            continue;
          }
          for (std::size_t index = 0; index < size; ++index) {
            const auto line = serialize(batch[index], *shared);
            if (bytes + line.size() > shared->options.max_bytes - 1024) {
              shared->capped = true;
              ++shared->lost;
              continue;
            }
            *shared->output << line;
            if (!shared->output->good()) {
              shared->failed = true;
              ++shared->lost;
            } else {
              bytes += line.size();
            }
          }
        }
        *shared->output << "{\"event\":\"trace_end\",\"at_us\":" << frame_tick_us() << ",\"lost_events\":" << shared->lost.load()
                        << ",\"size_limit_reached\":" << (shared->capped ? "true" : "false") << ",\"write_failed\":" << (shared->failed ? "true" : "false") << "}\n";
        shared->output->flush();
        if (!shared->output->good()) {
          shared->failed = true;
        }
      } catch (...) {
        shared->failed = true;
      }
      if ((shared->failed || shared->capped) && shared->diagnostic) {
        try {
          shared->diagnostic(shared->failed ? "write_failed" : "size_limit");
        } catch (...) {}
      }
      // Close the file before publishing completion to exporters and tests.
      shared->output.reset();
      {
        std::lock_guard lock(shared->mutex);
        shared->done = true;
      }
      shared->closed.notify_all();
    }

  public:
    /** @brief Start a recorder with an owned sink. @param sink Writer-only sink. @param id Generated safe ID. @param client Client label. @param options Bounds. @param diagnostic Writer failure notification. */
    frame_trace(std::unique_ptr<std::ostream> sink, std::string id, std::string client, frame_trace_options options = {}, std::function<void(std::string_view)> diagnostic = {}) {
      if (!sink || !valid_trace_tag(id) || !valid_trace_tag(client) || options.queue_capacity == 0 || options.queue_capacity > 65536 || options.max_bytes < 4096) {
        throw std::invalid_argument("Invalid frame trace configuration");
      }
      state_ = std::make_shared<state>(std::move(sink), std::move(id), std::move(client), options, std::move(diagnostic));
      std::thread([shared = state_] {
        write_loop(shared);
      }).detach();
    }

    /** @brief Request a nonblocking close; the writer drains its bounded queue. */
    ~frame_trace() {
      close();
    }

    /** @brief Recorder wrappers cannot duplicate lifecycle ownership. */
    frame_trace(const frame_trace &) = delete;
    /** @brief Recorder wrappers cannot replace lifecycle ownership. */
    frame_trace &operator=(const frame_trace &) = delete;

    /** @brief Create a unique session file without replacing earlier traces. @param root Trusted host statistics directory. @param client Client metadata label. @param diagnostic Failure notification. @return Recorder or null on creation failure. */
    static std::shared_ptr<frame_trace> create(const std::filesystem::path &root, const std::string &client, std::function<void(std::string_view)> diagnostic = {}) noexcept {
      if (!valid_trace_tag(client)) {
        return {};
      }
      try {
        std::filesystem::create_directories(root);
        static std::atomic<std::uint64_t> serial {};
        for (int attempt = 0; attempt < 16; ++attempt) {
          const auto id = std::to_string(std::chrono::system_clock::now().time_since_epoch().count()) + "-" + std::to_string(++serial);
          const auto directory = root / id;
          if (!std::filesystem::create_directory(directory)) {
            continue;
          }
          const auto path = directory / "frames.jsonl";
          auto sink = std::make_unique<std::ofstream>(path, std::ios::binary);
          if (!sink->good()) {
            std::error_code ignored;
            std::filesystem::remove(directory, ignored);
            return {};
          }
          auto trace = std::make_shared<frame_trace>(std::move(sink), id, client, frame_trace_options {}, std::move(diagnostic));
          trace->path_ = path;
          return trace;
        }
      } catch (...) {}
      return {};
    }

    /** @brief Publish metadata without a mutex, allocation or unbounded retry. @param event Fixed-size event. @return Whether this record was accepted. */
    bool emit(frame_event event) noexcept {
      auto &shared = *state_;
      ++shared.emitters;
      bool accepted = false;
      if (!shared.stopping && !shared.failed && !shared.done) {
        if (!event.at_us) {
          event.at_us = frame_tick_us();
        }
        auto position = shared.claimed.load(std::memory_order_relaxed);
        for (unsigned attempt = 0; attempt < 256; ++attempt) {
          const auto consumed = shared.consumed.load(std::memory_order_acquire);
          if (position - consumed >= shared.ring.size()) {
            const auto current = shared.claimed.load(std::memory_order_relaxed);
            if (current != position) {
              position = current;
              continue;
            }
            break;
          }
          if (shared.claimed.compare_exchange_weak(position, position + 1, std::memory_order_relaxed)) {
            auto &item = shared.ring[position % shared.ring.size()];
            item.row = event;
            item.ready.store(true, std::memory_order_release);
            accepted = true;
            break;
          }
        }
        if (!accepted) {
          ++shared.lost;
        }
      }
      --shared.emitters;
      shared.wake.notify_one();
      return accepted;
    }

    /** @brief End recording without blocking capture, encoder or sender teardown. */
    void close() noexcept {
      state_->stopping = true;
      state_->wake.notify_one();
    }

    /** @brief Wait from an exporter/test thread, never a pipeline thread. @param timeout Bounded wait. @return Whether the file was closed. */
    bool wait_closed(std::chrono::milliseconds timeout) {
      std::unique_lock lock(state_->mutex);
      return state_->closed.wait_for(lock, timeout, [&] {
        return state_->done.load();
      });
    }

    /** @brief Allocate an encoded packet identity. @return Per-trace packet number. */
    std::uint64_t next_packet() {
      return ++state_->packet_serial;
    }

    /** @brief Report lost metadata. @return Lost-event count. */
    std::uint64_t lost_events() const {
      return state_->lost;
    }

    /** @brief Report writer failure. @return Whether a write failed. */
    bool failed() const {
      return state_->failed;
    }

    /** @brief Report the file cap. @return Whether records were capped. */
    bool capped() const {
      return state_->capped;
    }

    /** @brief Return the safe trace identity. @return Generated session ID. */
    const std::string &id() const {
      return state_->id;
    }

    /** @brief Return the host-owned metadata path. @return Path or empty for an injected sink. */
    const std::filesystem::path &path() const {
      return path_;
    }
  };

  /** @brief Metadata retained from capture through encoded packet transmission. */
  struct frame_context {
    std::shared_ptr<frame_trace> trace;  ///< Keeps this real session isolated until its last packet is released.
    capture_stamp source;  ///< Original image identity and platform timestamp.
    std::uint64_t generation {};  ///< Encoder instance identity.
    bool fresh {};  ///< Submission consumed a new captured image.
    bool known {};  ///< Submission/output metadata was matched.
  };

  /** @brief Build a stage event from retained frame metadata. @param context Frame context. @param stage Actual stage. @param frame Actual frame number. @param packet Encoded packet ID. @return Fixed-size event. */
  inline frame_event frame_row(const frame_context &context, frame_stage stage, std::int64_t frame, std::uint64_t packet = 0) {
    frame_event row;
    row.stage = stage;
    row.at_us = frame_tick_us();
    row.frame = frame;
    row.packet = packet;
    row.generation = context.generation;
    row.source = context.source;
    row.fresh = context.fresh;
    row.metadata_known = context.known;
    return row;
  }

  /** @brief Fixed-capacity PTS history; delayed and multi-packet output never borrow the current input. */
  class frame_tracker {
    /** @brief One original submission retained until its history slot is reused. */
    struct entry {
      std::int64_t frame {-1};  ///< Original encoder input PTS.
      frame_context context;  ///< Original metadata.
      bool output_seen {};  ///< At least one output was associated with this PTS.
      std::weak_ptr<frame_trace> trace;  ///< Encoder teardown must not retain an otherwise finished trace.
    };

    std::array<entry, 256> entries_;  ///< Fixed history, independent of queue or output delay.
    std::weak_ptr<frame_trace> trace_;  ///< Diagnostic destination for unmatched output.
    std::uint64_t generation_;  ///< Stable encoder generation, including across object moves.

  public:
    /** @brief Allocate a distinct encoder generation. */
    frame_tracker() {
      static std::atomic<std::uint64_t> serial {};
      generation_ = ++serial;
    }

    /** @brief Return the encoder generation. @return Process-local encoder identity. */
    std::uint64_t generation() const {
      return generation_;
    }

    /** @brief Remember an input before submitting it. @param frame Input PTS. @param context Original source metadata. */
    void submit(std::int64_t frame, frame_context context) {
      if (!context.trace || frame < 0) {
        return;
      }
      trace_ = context.trace;
      context.generation = generation_;
      context.known = true;
      auto &slot = entries_[static_cast<std::uint64_t>(frame) % entries_.size()];
      if (slot.frame >= 0 && !slot.output_seen) {
        if (auto prior = slot.trace.lock()) {
          auto row = frame_row(slot.context, frame_stage::metadata_evicted, slot.frame);
          row.result = 1;
          prior->emit(row);
        }
      }
      auto owner = context.trace;
      context.trace.reset();
      slot = {frame, std::move(context), false, owner};
    }

    /** @brief Resolve the actual output PTS without consuming other outputs of that PTS. @param frame Actual output PTS. @return Original metadata, or explicitly unknown metadata. */
    frame_context lookup(std::int64_t frame) {
      if (frame >= 0) {
        auto &slot = entries_[static_cast<std::uint64_t>(frame) % entries_.size()];
        if (slot.frame == frame) {
          slot.output_seen = true;
          auto context = slot.context;
          context.trace = slot.trace.lock();
          return context;
        }
      }
      frame_context missing;
      missing.trace = trace_.lock();
      missing.generation = generation_;
      if (missing.trace) {
        auto row = frame_row(missing, frame_stage::metadata_missing, frame);
        row.result = 1;
        missing.trace->emit(row);
      }
      return missing;
    }
  };
}  // namespace rhremote
