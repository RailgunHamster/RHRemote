/**
 * @file tests/unit/test_rhremote_frame_trace.cpp
 * @brief Exercise real bounded writer behavior and frame/PTS identity without a GPU or network.
 */
#include "src/rhremote_frame_trace.h"

#include <cstdlib>
#include <iterator>
#ifndef RHREMOTE_FRAME_TRACE_STANDALONE
  #include <gtest/gtest.h>
#endif

using namespace std::chrono_literals;

namespace {
  /** @brief Enforce an invariant in both GoogleTest and standalone execution. @param value Required condition. */
  void require(bool value) {
    if (!value) {
      std::abort();
    }
  }

  /** @brief A writer gate proves that pipeline producers do not wait for slow storage. */
  struct gated_buffer: std::stringbuf {
    std::mutex mutex;  ///< Protects the test gate only, never the recorder ring.
    std::condition_variable wake;  ///< Writer-entry and release notification.
    bool entered {}, released {};  ///< Gate observations.

    /** @brief Block the actual writer until released. @param data Serialized metadata. @param size Byte length. @return Bytes written after release. */
    std::streamsize xsputn(const char *data, std::streamsize size) override {
      {
        std::unique_lock lock(mutex);
        entered = true;
        wake.notify_all();
        wake.wait(lock, [&] {
          return released;
        });
      }
      return std::stringbuf::xsputn(data, size);
    }

    /** @brief Wait for the writer to reach its first write. */
    void await_writer() {
      std::unique_lock lock(mutex);
      require(wake.wait_for(lock, 5s, [&] {
        return entered;
      }));
    }

    /** @brief Let the writer finish; no pipeline thread is needed to unblock it. */
    void release() {
      {
        std::lock_guard lock(mutex);
        released = true;
      }
      wake.notify_all();
    }
  };

  /** @brief A failing sink exercises stream errors and throwing stream configurations. */
  struct failing_buffer: std::streambuf {
    /** @brief Reject a write. @param data Unused bytes. @param size Unused length. @return No bytes written. */
    std::streamsize xsputn(const char *, std::streamsize) override {
      return 0;
    }

    /** @brief Reject a character. @param value Unused character. @return EOF. */
    int_type overflow(int_type = traits_type::eof()) override {
      return traits_type::eof();
    }
  };

  /** @brief A newly created and narrowly validated test directory. */
  struct temporary_directory {
    std::filesystem::path path;  ///< Directory created exclusively by this test.

    /** @brief Allocate a unique fixture directory. */
    temporary_directory() {
      static std::atomic<std::uint64_t> serial {};
      const auto base = std::filesystem::temp_directory_path();
      path = base / ("rhremote-frame-test-" + std::to_string(rhremote::frame_tick_us()) + "-" + std::to_string(++serial));
      require(std::filesystem::create_directory(path));
    }

    /** @brief Delete only the test-owned child of the system temporary directory. */
    ~temporary_directory() {
      if (path.parent_path() == std::filesystem::temp_directory_path() && path.filename().string().starts_with("rhremote-frame-test-")) {
        std::error_code ignored;
        std::filesystem::remove_all(path, ignored);
      }
    }
  };

  /** @brief Read a closed metadata file. @param path Owned fixture file. @return Complete file contents. */
  std::string read_file(const std::filesystem::path &path) {
    std::ifstream file(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(file), {}};
  }

  /** @brief Retry fixture emission after an intentional nonblocking mutex rejection. @param trace Recorder. @param row Fixture event. */
  void emit_fixture(const std::shared_ptr<rhremote::frame_trace> &trace, rhremote::frame_event row) {
    const auto deadline = std::chrono::steady_clock::now() + 5s;
    while (!trace->emit(row)) {
      require(std::chrono::steady_clock::now() < deadline);
      std::this_thread::yield();
    }
  }

  /** @brief Validate opt-in and labels without treating user input as a path. */
  void request_validation() {
    require(rhremote::frame_trace_requested("1", "1792000000-client_A.v1"));
    for (const auto *flag : {"", "0", "true", "01", "2"}) {
      require(!rhremote::frame_trace_requested(flag, "client"));
    }
    for (const auto *tag : {"", ".", "..", "../secret", "folder/file", "folder\\file", "has space", "quote\"", "a\nb"}) {
      require(!rhremote::frame_trace_requested("1", tag));
    }
    require(!rhremote::valid_trace_tag(std::string(97, 'a')));
    require(rhremote::valid_trace_tag(std::string(96, 'a')));
    temporary_directory root;
    const auto absent = root.path / "not-created";
    require(!rhremote::frame_trace::create(absent, "../invalid"));
    require(!std::filesystem::exists(absent));
  }

  /** @brief Unknown presentation times remain unknown, including a genuine known zero timestamp. */
  void source_timestamps() {
    const auto before = rhremote::frame_tick_us();
    const auto known = rhremote::stamp_capture(std::chrono::steady_clock::time_point {});
    const auto missing = rhremote::stamp_capture(std::nullopt);
    require(known.id != 0 && missing.id > known.id);
    require(known.source_time_known && known.source_present_us == 0);
    require(!missing.source_time_known && missing.source_present_us == 0);
    require(known.ready_us >= before && missing.ready_us >= known.ready_us);
  }

  /** @brief Slow disk cannot block producers or cancellation, and bounded overflow is counted. */
  void queue_overflow() {
    gated_buffer buffer;
    auto trace = std::make_shared<rhremote::frame_trace>(std::make_unique<std::ostream>(&buffer), "trace-A", "client", rhremote::frame_trace_options {2, 65536});
    buffer.await_writer();
    int accepted = 0;
    for (int frame = 1; frame <= 100; ++frame) {
      rhremote::frame_event row;
      row.frame = frame;
      accepted += trace->emit(row);
    }
    require(accepted == 2 && trace->lost_events() == 98);
    const auto start = std::chrono::steady_clock::now();
    trace->close();
    require(std::chrono::steady_clock::now() - start < 1s);
    require(!trace->wait_closed(1ms));
    buffer.release();
    require(trace->wait_closed(5s));
    const auto text = buffer.str();
    require(text.find("\"lost_events\":98") != std::string::npos);
    require(text.find("\"event\":\"trace_end\"") != std::string::npos);
    require(!trace->emit({}));
  }

  /** @brief A capped file still fits the whole-file bound and contains an explicit footer. */
  void file_cap() {
    gated_buffer buffer;
    auto trace = std::make_shared<rhremote::frame_trace>(std::make_unique<std::ostream>(&buffer), "trace-cap", "client", rhremote::frame_trace_options {64, 4096});
    buffer.await_writer();
    for (int frame = 1; frame <= 32; ++frame) {
      rhremote::frame_event row;
      row.frame = frame;
      require(trace->emit(row));
    }
    trace->close();
    buffer.release();
    require(trace->wait_closed(5s));
    const auto text = buffer.str();
    require(trace->capped() && trace->lost_events() > 0);
    require(text.size() <= 4096);
    require(text.find("\"size_limit_reached\":true") != std::string::npos);
    require(text.find("\"event\":\"trace_end\"") != std::string::npos);
  }

  /** @brief Stream errors and exceptions remain observable without killing the writer thread. */
  void write_failures() {
    for (bool throwing : {false, true}) {
      failing_buffer buffer;
      auto sink = std::make_unique<std::ostream>(&buffer);
      if (throwing) {
        sink->exceptions(std::ios::badbit | std::ios::failbit);
      }
      std::atomic<bool> notified {};
      auto trace = std::make_shared<rhremote::frame_trace>(std::move(sink), "trace-error", "client", rhremote::frame_trace_options {}, [&](std::string_view reason) {
        notified = reason == "write_failed";
      });
      trace->emit({});
      trace->close();
      require(trace->wait_closed(5s));
      require(trace->failed() && notified);
      require(!trace->emit({}));
    }
  }

  /** @brief Two sessions with identical frame numbers have different files and explicit row identities. */
  void session_identity() {
    temporary_directory root;
    auto first = rhremote::frame_trace::create(root.path, "same-client");
    auto second = rhremote::frame_trace::create(root.path, "same-client");
    require(first && second && first->id() != second->id() && first->path() != second->path());
    for (auto trace : {first, second}) {
      rhremote::frame_event row;
      row.stage = rhremote::frame_stage::encode_begin;
      row.frame = 1;
      row.fresh = true;
      row.metadata_known = true;
      row.source = {101, 0, 1, true};
      emit_fixture(trace, row);
      trace->close();
      require(trace->wait_closed(5s));
      const auto text = read_file(trace->path());
      require(text.find("\"trace_id\":\"" + trace->id() + "\"") != std::string::npos);
      require(text.find("\"source_time_known\":true") != std::string::npos);
      require(text.find("\"frame\":1") != std::string::npos);
      require(text.find("\"physical_scanout_measured\":false") != std::string::npos);
    }
  }

  /** @brief Bad output roots and invalid bounds fail before starting a writer or replacing user data. */
  void creation_failures() {
    temporary_directory root;
    const auto occupied = root.path / "existing";
    {
      std::ofstream out(occupied);
      out << "keep";
    }
    require(!rhremote::frame_trace::create(occupied, "client"));
    require(read_file(occupied) == "keep");
    for (auto options : {rhremote::frame_trace_options {0, 4096}, rhremote::frame_trace_options {65537, 4096}, rhremote::frame_trace_options {1, 4095}}) {
      bool rejected = false;
      try {
        rhremote::frame_trace trace(std::make_unique<std::ostringstream>(), "trace", "client", options);
      } catch (const std::invalid_argument &) {
        rejected = true;
      }
      require(rejected);
    }
  }

  /** @brief Delayed and repeated output resolves the original PTS, never the latest submission. */
  void pts_reordering() {
    gated_buffer buffer;
    auto trace = std::make_shared<rhremote::frame_trace>(std::make_unique<std::ostream>(&buffer), "trace-pts", "client");
    buffer.await_writer();
    rhremote::frame_tracker tracker;
    tracker.submit(1, {trace, {101, 10, 20, true}, 0, true, true});
    tracker.submit(2, {trace, {202, 30, 40, true}, 0, false, true});
    const auto second = tracker.lookup(2), first = tracker.lookup(1), same = tracker.lookup(1);
    require(first.source.id == 101 && second.source.id == 202 && same.source.id == 101);
    require(first.known && same.known && second.known);
    require(first.fresh && !second.fresh && first.generation == tracker.generation());
    require(trace->next_packet() != trace->next_packet());
    const auto missing = tracker.lookup(-1);
    require(!missing.known && missing.source.id == 0 && missing.trace == trace);
    trace->close();
    buffer.release();
    require(trace->wait_closed(5s));
    require(buffer.str().find("metadata_missing") != std::string::npos);
  }

  /** @brief An overwritten pending PTS becomes explicitly unknown instead of being bound to a new source. */
  void pts_eviction() {
    gated_buffer buffer;
    auto trace = std::make_shared<rhremote::frame_trace>(std::make_unique<std::ostream>(&buffer), "trace-evict", "client");
    buffer.await_writer();
    rhremote::frame_tracker tracker;
    tracker.submit(1, {trace, {1, 1, 1, true}, 0, true, true});
    tracker.submit(257, {trace, {257, 257, 257, true}, 0, true, true});
    require(!tracker.lookup(1).known);
    require(tracker.lookup(257).source.id == 257);
    trace->close();
    buffer.release();
    require(trace->wait_closed(5s));
    require(buffer.str().find("metadata_evicted") != std::string::npos);
  }

  /** @brief A slow/hung encoder teardown does not retain the trace merely through its PTS cache. */
  void cache_lifetime() {
    gated_buffer buffer;
    auto trace = std::make_shared<rhremote::frame_trace>(std::make_unique<std::ostream>(&buffer), "trace-cache", "client");
    buffer.await_writer();
    rhremote::frame_tracker tracker;
    tracker.submit(1, {trace, {1, 1, 1, true}, 0, true, true});
    std::weak_ptr<rhremote::frame_trace> weak = trace;
    trace->close();
    buffer.release();
    require(trace->wait_closed(5s));
    trace.reset();
    require(weak.expired());
    require(!tracker.lookup(1).trace);
  }

  /** @brief Encoded packet metadata, unlike encoder caches, keeps its session trace alive. */
  void packet_lifetime() {
    gated_buffer buffer;
    auto trace = std::make_shared<rhremote::frame_trace>(std::make_unique<std::ostream>(&buffer), "trace-packet", "client");
    buffer.await_writer();
    rhremote::frame_tracker tracker;
    tracker.submit(1, {trace, {1, 1, 1, true}, 0, true, true});
    auto packet = tracker.lookup(1);
    std::weak_ptr<rhremote::frame_trace> weak = trace;
    trace.reset();
    require(!weak.expired());
    packet.trace->close();
    buffer.release();
    require(packet.trace->wait_closed(5s));
    packet.trace.reset();
    require(weak.expired());
  }

  /** @brief Stable stage names and encoder generations prevent silently merging unrelated work. */
  void generations_and_stages() {
    rhremote::frame_tracker first, second;
    require(first.generation() != second.generation());
    require(rhremote::frame_stage_name(rhremote::frame_stage::send_end) == "send_end");
    require(rhremote::frame_stage_name(static_cast<rhremote::frame_stage>(999)) == "unknown");
    const auto empty = first.lookup(1);
    require(!empty.known && !empty.trace);
  }
}  // namespace

#ifdef RHREMOTE_FRAME_TRACE_STANDALONE
/** @brief Run the same real writer/PTS regressions without the rest of Sunshine. @return Zero on success. */
int main() {
  request_validation();
  source_timestamps();
  queue_overflow();
  file_cap();
  write_failures();
  session_identity();
  creation_failures();
  pts_reordering();
  pts_eviction();
  cache_lifetime();
  packet_lifetime();
  generations_and_stages();
}
#else
TEST(RhRemoteFrameTrace, RequestValidation) {
  request_validation();
}

TEST(RhRemoteFrameTrace, SourceTimestamps) {
  source_timestamps();
}

TEST(RhRemoteFrameTrace, QueueOverflow) {
  queue_overflow();
}

TEST(RhRemoteFrameTrace, FileCap) {
  file_cap();
}

TEST(RhRemoteFrameTrace, WriteFailures) {
  write_failures();
}

TEST(RhRemoteFrameTrace, SessionIdentity) {
  session_identity();
}

TEST(RhRemoteFrameTrace, CreationFailures) {
  creation_failures();
}

TEST(RhRemoteFrameTrace, PtsReordering) {
  pts_reordering();
}

TEST(RhRemoteFrameTrace, PtsEviction) {
  pts_eviction();
}

TEST(RhRemoteFrameTrace, CacheLifetime) {
  cache_lifetime();
}

TEST(RhRemoteFrameTrace, PacketLifetime) {
  packet_lifetime();
}

TEST(RhRemoteFrameTrace, GenerationsAndStages) {
  generations_and_stages();
}
#endif
