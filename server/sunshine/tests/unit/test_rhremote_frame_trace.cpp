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

  /** @brief Disabled capture leaves every step unknown; enabled scopes retain actual steady-clock boundaries. */
  void capture_work_scopes() {
    auto disabled = rhremote::capture_work::start(false);
    {
      rhremote::capture_work_scope scope(disabled, rhremote::capture_work_step::map);
    }
    disabled.finish();
    require(!disabled.dxgi_ram && disabled.begin_us == 0 && disabled.end_us == 0);
    for (const auto &step : disabled.steps) {
      require(!step.recorded && step.begin_us == 0 && step.end_us == 0);
    }
    auto active = rhremote::capture_work::start(true);
    {
      rhremote::capture_work_scope scope(active, rhremote::capture_work_step::acquire);
    }
    {
      rhremote::capture_work_scope invalid(active, rhremote::capture_work_step::count);
    }
    active.finish();
    const auto &acquire = active.steps[0];
    require(active.dxgi_ram && active.begin_us <= acquire.begin_us && acquire.begin_us <= acquire.end_us && acquire.end_us <= active.end_us);
    require(acquire.recorded && !active.steps[static_cast<std::size_t>(rhremote::capture_work_step::map)].recorded);
    require(rhremote::capture_work_step_name(rhremote::capture_work_step::map) == "map");
    require(rhremote::capture_work_step_name(rhremote::capture_work_step::count) == "unknown");
  }

  /** @brief Queue copies keep one image's timings isolated, and only capture events serialize them. */
  void capture_work_serialization() {
    std::stringbuf buffer;
    auto trace = std::make_shared<rhremote::frame_trace>(std::make_unique<std::ostream>(&buffer), "capture-work", "client");
    rhremote::frame_context context;
    context.trace = trace;
    context.generation = 99;
    context.source = {123, 0, 1000, false};
    context.fresh = true;
    context.known = true;
    rhremote::capture_work work;
    work.dxgi_ram = true;
    work.begin_us = 100;
    work.end_us = 900;
    work.steps[0] = {110, 210, true};
    work.steps[1] = {220, 300, true};
    work.steps[2] = {310, 330, true};
    work.steps[3] = {340, 740, true};
    work.steps[4] = {745, 750, true};
    work.steps[5] = {760, 820, true};
    work.steps[6] = {830, 840, true};
    auto row = rhremote::capture_row(context, work);
    require(row.frame == -1 && row.generation == 0 && row.source.id == 123 && row.capture.steps[3].end_us == 740);
    work.steps[3].end_us = 999999;
    emit_fixture(trace, row);
    // Mutating the producer's POD after emit cannot alter the ring's copy.
    row.capture.steps[3].end_us = 888888;
    emit_fixture(trace, rhremote::frame_row(context, rhremote::frame_stage::encode_begin, 1));
    emit_fixture(trace, rhremote::capture_row(context, {}));
    trace->close();
    require(trace->wait_closed(5s));
    const auto output = buffer.str();
    require(output.find("\"capture_work_schema\":\"rhremote.capture-work/v1\"") != std::string::npos);
    require(output.find("\"map\":{\"begin_us\":340,\"end_us\":740}") != std::string::npos);
    require(output.find("\"cursor\":null") != std::string::npos);
    require(output.find("\"capture_work\":null") != std::string::npos);
    require(output.find("999999") == std::string::npos && output.find("888888") == std::string::npos);
    const auto encoded = output.find("\"event\":\"encode_begin\"");
    require(encoded != std::string::npos);
    require(output.substr(encoded, output.find('\n', encoded) - encoded).find("capture_work") == std::string::npos);
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

  /** @brief Concurrent encoder/capture/sender producers must not lose records merely because a writer is copying a batch. */
  void concurrent_producers() {
    std::stringbuf buffer;
    auto trace = std::make_shared<rhremote::frame_trace>(std::make_unique<std::ostream>(&buffer), "concurrent", "client", rhremote::frame_trace_options {8192, 16 * 1024 * 1024});
    std::atomic<bool> start {};
    std::atomic<unsigned> waiting {}, accepted {};
    std::vector<std::thread> producers;
    constexpr unsigned count = 4, each = 1000;
    for (unsigned producer = 0; producer < count; ++producer) {
      producers.emplace_back([&, producer] {
        ++waiting;
        while (!start) {
          std::this_thread::yield();
        }
        for (unsigned index = 0; index < each; ++index) {
          rhremote::frame_event row;
          row.frame = producer * each + index + 1;
          accepted += trace->emit(row);
        }
      });
    }
    while (waiting != count) {
      std::this_thread::yield();
    }
    start = true;
    for (auto &producer : producers) {
      producer.join();
    }
    trace->close();
    require(trace->wait_closed(5s));
    require(accepted == count * each && trace->lost_events() == 0);
    std::vector<bool> seen(count * each);
    unsigned rows = 0;
    std::istringstream input(buffer.str());
    for (std::string line; std::getline(input, line);) {
      const auto marker = line.find("\"frame\":");
      if (marker != std::string::npos) {
        const auto id = std::stoul(line.substr(marker + 8));
        require(id >= 1 && id <= seen.size() && !seen[id - 1]);
        seen[id - 1] = true;
        ++rows;
      }
    }
    require(rows == accepted);
  }

  /** @brief Close racing producers drains every accepted record before writing the footer. */
  void concurrent_close() {
    std::stringbuf buffer;
    auto trace = std::make_shared<rhremote::frame_trace>(std::make_unique<std::ostream>(&buffer), "closing", "client", rhremote::frame_trace_options {8192, 16 * 1024 * 1024});
    std::atomic<unsigned> accepted {};
    std::vector<std::thread> producers;
    for (unsigned producer = 0; producer < 4; ++producer) {
      producers.emplace_back([&, producer] {
        for (unsigned index = 0; index < 1000; ++index) {
          rhremote::frame_event row;
          row.frame = producer * 1000 + index + 1;
          if (!trace->emit(row)) {
            break;
          }
          ++accepted;
        }
      });
    }
    const auto deadline = std::chrono::steady_clock::now() + 5s;
    while (accepted < 4) {
      require(std::chrono::steady_clock::now() < deadline);
      std::this_thread::yield();
    }
    trace->close();
    for (auto &producer : producers) {
      producer.join();
    }
    require(trace->wait_closed(5s));
    unsigned rows = 0;
    std::istringstream input(buffer.str());
    for (std::string line; std::getline(input, line);) {
      rows += line.find("\"frame\":") != std::string::npos;
    }
    require(rows == accepted && trace->lost_events() == 0);
    require(buffer.str().ends_with("\"write_failed\":false}\n"));
  }

  /** @brief Capacity one remains a real one-slot queue across many consumer wraps. */
  void single_slot_wrap() {
    std::stringbuf buffer;
    auto trace = std::make_shared<rhremote::frame_trace>(std::make_unique<std::ostream>(&buffer), "one-slot", "client", rhremote::frame_trace_options {1, 1024 * 1024});
    const auto deadline = std::chrono::steady_clock::now() + 5s;
    for (unsigned id = 1; id <= 200;) {
      rhremote::frame_event row;
      row.frame = id;
      if (trace->emit(row)) {
        ++id;
      } else {
        require(std::chrono::steady_clock::now() < deadline);
        std::this_thread::yield();
      }
    }
    trace->close();
    require(trace->wait_closed(5s));
    unsigned rows = 0;
    std::istringstream input(buffer.str());
    for (std::string line; std::getline(input, line);) {
      const auto marker = line.find("\"frame\":");
      if (marker != std::string::npos) {
        require(std::stoul(line.substr(marker + 8)) == ++rows);
      }
    }
    require(rows == 200);
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
    tracker.converted(2, {trace, {2, 2, 2, true}, 0, true, true});
    std::weak_ptr<rhremote::frame_trace> weak = trace;
    trace->close();
    buffer.release();
    require(trace->wait_closed(5s));
    trace.reset();
    require(weak.expired());
    require(!tracker.lookup(1).trace);
  }

  /** @brief Encoder release explicitly resolves a successful conversion that was never submitted. */
  void converted_release() {
    std::stringbuf buffer;
    auto trace = std::make_shared<rhremote::frame_trace>(std::make_unique<std::ostream>(&buffer), "converted-release", "client");
    {
      rhremote::frame_tracker tracker;
      tracker.converted(-1, {trace, {}, 0, false, true});
      tracker.converted(1, {});
      tracker.converted(17, {trace, {71, 100, 200, true}, 0, true, true});
    }
    trace->close();
    require(trace->wait_closed(5s));
    const auto text = buffer.str();
    require(text.find("\"event\":\"input_abandoned\"") != std::string::npos);
    require(text.find("\"frame\":17") != std::string::npos);
    require(text.find("\"source\":71") != std::string::npos);
    require(text.find("\"result\":2") != std::string::npos);
    require(trace->lost_events() == 0);
  }

  /** @brief Priming conversion can be replaced for one PTS; submission clears it while a new PTS resolves the old candidate. */
  void converted_submission_and_supersession() {
    std::stringbuf buffer;
    std::stringbuf other_buffer;
    auto trace = std::make_shared<rhremote::frame_trace>(std::make_unique<std::ostream>(&buffer), "converted-submit", "client");
    auto other = std::make_shared<rhremote::frame_trace>(std::make_unique<std::ostream>(&other_buffer), "converted-other", "client-other");
    {
      rhremote::frame_tracker tracker;
      tracker.converted(1, {trace, {}, 0, false, true});
      tracker.converted(1, {trace, {81, 10, 20, true}, 0, true, true});
      tracker.submit(1, {trace, {81, 10, 20, true}, 0, true, true});
      require(tracker.lookup(1).source.id == 81);
      tracker.converted(2, {trace, {82, 20, 30, true}, 0, true, true});
      tracker.converted(3, {trace, {83, 30, 40, true}, 0, true, true});
      tracker.submit(4, {trace, {84, 40, 50, true}, 0, true, true});
      require(tracker.lookup(4).source.id == 84);
      tracker.converted(5, {trace, {85, 50, 60, true}, 0, true, true});
      tracker.converted(5, {other, {86, 60, 70, true}, 0, true, true});
      tracker.submit(5, {other, {86, 60, 70, true}, 0, true, true});
      require(tracker.lookup(5).source.id == 86 && tracker.lookup(5).trace == other);
    }
    trace->close();
    other->close();
    require(trace->wait_closed(5s));
    require(other->wait_closed(5s));
    const auto text = buffer.str();
    require(text.find("\"frame\":1,") == std::string::npos);
    require(text.find("\"frame\":2,") != std::string::npos);
    require(text.find("\"frame\":3,") != std::string::npos);
    require(text.find("\"result\":2") == std::string::npos);
    unsigned abandoned = 0;
    std::istringstream input(text);
    for (std::string line; std::getline(input, line);) {
      abandoned += line.find("\"event\":\"input_abandoned\"") != std::string::npos;
    }
    require(abandoned == 3 && trace->lost_events() == 0);
    require(other_buffer.str().find("\"event\":\"input_abandoned\"") == std::string::npos);
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
  capture_work_scopes();
  capture_work_serialization();
  queue_overflow();
  concurrent_producers();
  concurrent_close();
  single_slot_wrap();
  file_cap();
  write_failures();
  session_identity();
  creation_failures();
  pts_reordering();
  pts_eviction();
  cache_lifetime();
  converted_release();
  converted_submission_and_supersession();
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

TEST(RhRemoteFrameTrace, CaptureWorkScopes) {
  capture_work_scopes();
}

TEST(RhRemoteFrameTrace, CaptureWorkSerialization) {
  capture_work_serialization();
}

TEST(RhRemoteFrameTrace, QueueOverflow) {
  queue_overflow();
}

TEST(RhRemoteFrameTrace, ConcurrentProducers) {
  concurrent_producers();
}

TEST(RhRemoteFrameTrace, ConcurrentClose) {
  concurrent_close();
}

TEST(RhRemoteFrameTrace, SingleSlotWrap) {
  single_slot_wrap();
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

TEST(RhRemoteFrameTrace, ConvertedRelease) {
  converted_release();
}

TEST(RhRemoteFrameTrace, ConvertedSubmissionAndSupersession) {
  converted_submission_and_supersession();
}

TEST(RhRemoteFrameTrace, PacketLifetime) {
  packet_lifetime();
}

TEST(RhRemoteFrameTrace, GenerationsAndStages) {
  generations_and_stages();
}
#endif
