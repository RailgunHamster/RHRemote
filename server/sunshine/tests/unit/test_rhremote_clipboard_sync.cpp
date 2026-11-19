/** @file tests/unit/test_rhremote_clipboard_sync.cpp @brief Clipboard synchronization broker and format policy tests. */
#include <gtest/gtest.h>

#include <atomic>
#include <future>
#include <string>
#include <thread>

#include "src/rhremote_clipboard.h"

using namespace std::chrono_literals;
namespace cb = rhremote::clipboard;

namespace {
  /** @brief Captures one long-poll answer. */
  struct captured_poll {
    std::promise<nlohmann::json> promise;
    std::future<nlohmann::json> future = promise.get_future();

    cb::broker::responder responder() {
      return [this](nlohmann::json value) {
        promise.set_value(std::move(value));
      };
    }

    bool ready() {
      return future.wait_for(0s) == std::future_status::ready;
    }
  };

  /** @brief Broker with controllable liveness and memory budget. */
  struct fixture {
    std::atomic<bool> alive {true};
    std::atomic<std::uint64_t> budget {std::uint64_t {1} << 30};
    cb::broker broker {[this](const std::string &) {
                         return alive.load();
                       },
                       [this] {
                         return budget.load();
                       }};

    /** @brief Register a peer by polling once without waiting. */
    nlohmann::json touch(const std::string &peer, std::uint64_t cursor = 0) {
      captured_poll poll;
      broker.poll(peer, broker.epoch(), cursor, 0ms, poll.responder());
      return poll.future.get();
    }
  };
}  // namespace

TEST(RhRemoteClipboardSync, SelectsTransferableFormatsInSourceOrder) {
  const std::vector<cb::format_t> available {
    {49161, "HTML Format"},
    {cb::cf_unicodetext, {}},
    {cb::cf_text, {}},
    {cb::cf_oemtext, {}},
    {cb::cf_locale, {}},
    {2, {}},  // CF_BITMAP
    {15, {}},  // CF_HDROP (files are a later phase)
    {49300, "Ole Private Data"},
    {49301, "Rich Text Format"},
    {cb::cf_dibv5, {}},
    {cb::cf_dib, {}},
    {0x0200, {}},  // private range
  };
  const std::vector<cb::format_t> expected {
    {49161, "HTML Format"},
    {cb::cf_unicodetext, {}},
    {cb::cf_locale, {}},
    {49301, "Rich Text Format"},
    {cb::cf_dibv5, {}},
  };
  EXPECT_EQ(cb::select(available), expected);
}

TEST(RhRemoteClipboardSync, KeepsLegacyTextAndDibWhenNoRicherFormatExists) {
  const std::vector<cb::format_t> available {{cb::cf_text, {}}, {cb::cf_dib, {}}};
  EXPECT_EQ(cb::select(available), available);
}

TEST(RhRemoteClipboardSync, CapsAnnouncedFormats) {
  std::vector<cb::format_t> available;
  for (std::uint32_t i = 0; i < 100; ++i) {
    available.push_back({0xC000 + i, "Custom " + std::to_string(i)});
  }
  EXPECT_EQ(cb::select(available).size(), cb::max_formats);
}

TEST(RhRemoteClipboardSync, ParsesOnlyWellFormedTransferableAnnouncements) {
  const auto good = cb::parse_formats(nlohmann::json::parse(R"([{"id":13},{"id":49161,"name":"HTML Format"}])"));
  ASSERT_TRUE(good);
  EXPECT_EQ(good->size(), 2u);
  EXPECT_EQ((*good)[1].name, "HTML Format");

  for (const auto *bad : {
         R"({"id":13})",
         R"([{"id":0}])",
         R"([{"id":70000}])",
         R"([{"id":-1}])",
         R"([{"id":"13"}])",
         R"([{"id":2}])",
         R"([{"id":13,"name":"x"}])",
         R"([{"id":49161}])",
         R"([{"id":49161,"name":"Ole Private Data"}])",
         R"([{"id":49161,"name":7}])",
       }) {
    EXPECT_FALSE(cb::parse_formats(nlohmann::json::parse(bad))) << bad;
  }
  EXPECT_FALSE(cb::parse_formats(nlohmann::json::parse(R"([{"id":49161,"name":")" + std::string(300, 'a') + R"("}])")));
}

TEST(RhRemoteClipboardSync, LongPollIsAnsweredByTheNextAnnouncementAndSkipsTheOrigin) {
  fixture f;
  f.touch("a");
  f.touch("b");
  captured_poll a, b;
  f.broker.poll("a", f.broker.epoch(), 0, 10s, a.responder());
  f.broker.poll("b", f.broker.epoch(), 0, 10s, b.responder());
  EXPECT_FALSE(a.ready());

  f.broker.publish_formats(7, {{cb::cf_unicodetext, {}}}, "b");
  ASSERT_TRUE(a.ready());
  EXPECT_FALSE(b.ready());
  const auto reply = a.future.get();
  EXPECT_EQ(reply["epoch"], f.broker.epoch());
  ASSERT_EQ(reply["events"].size(), 1u);
  EXPECT_EQ(reply["events"][0]["type"], "formats");
  EXPECT_EQ(reply["events"][0]["seq"], 7);
  EXPECT_EQ(reply["events"][0]["formats"][0]["id"], cb::cf_unicodetext);
  f.broker.stop();
  EXPECT_TRUE(b.ready());
}

TEST(RhRemoteClipboardSync, OnlyTheNewestAnnouncementIsQueued) {
  fixture f;
  f.touch("a");
  f.broker.publish_formats(1, {{cb::cf_unicodetext, {}}});
  f.broker.publish_formats(2, {{cb::cf_dibv5, {}}});
  const auto reply = f.touch("a");
  ASSERT_EQ(reply["events"].size(), 1u);
  EXPECT_EQ(reply["events"][0]["seq"], 2);

  const auto cursor = reply["cursor"].get<std::uint64_t>();
  EXPECT_TRUE(f.touch("a", cursor)["events"].empty());
}

TEST(RhRemoteClipboardSync, AnotherEpochRestartsTheCursor) {
  fixture f;
  f.touch("a");
  f.broker.publish_formats(1, {{cb::cf_unicodetext, {}}});
  captured_poll poll;
  f.broker.poll("a", f.broker.epoch() + 1, 999, 0ms, poll.responder());
  EXPECT_EQ(poll.future.get()["events"].size(), 1u);
}

TEST(RhRemoteClipboardSync, ANewPollReplacesTheOldWaiter) {
  fixture f;
  f.touch("a");
  captured_poll first, second;
  f.broker.poll("a", f.broker.epoch(), 0, 10s, first.responder());
  f.broker.poll("a", f.broker.epoch(), 0, 10s, second.responder());
  ASSERT_TRUE(first.ready());
  EXPECT_TRUE(first.future.get()["events"].empty());
  EXPECT_FALSE(second.ready());
  f.broker.stop();  // Answer the held poll while its capture is still alive.
  EXPECT_TRUE(second.ready());
}

TEST(RhRemoteClipboardSync, RendersChunksFromTheOwningPeer) {
  fixture f;
  f.touch("a");
  f.touch("b");
  const auto request = f.broker.begin_render("a", 13);
  ASSERT_NE(request, 0u);
  const auto events = f.touch("a")["events"];
  ASSERT_EQ(events.size(), 1u);
  EXPECT_EQ(events[0]["type"], "render");
  EXPECT_EQ(events[0]["request"], request);
  EXPECT_EQ(events[0]["format"], 13);

  EXPECT_EQ(f.broker.accept_chunk("b", request, 0, 6, "abc"), cb::chunk_result::invalid);
  EXPECT_EQ(f.broker.accept_chunk("a", request, 0, 6, "abc"), cb::chunk_result::accepted);
  EXPECT_EQ(f.broker.accept_chunk("a", request, 1, 6, "def"), cb::chunk_result::invalid);
  EXPECT_EQ(f.broker.accept_chunk("a", request, 3, 7, "def"), cb::chunk_result::invalid);

  auto waiter = std::async(std::launch::async, [&] {
    std::vector<std::uint8_t> data;
    const auto result = f.broker.wait_render(request, cb::broker::clock::now() + 5s, data);
    return std::pair {result, std::string(data.begin(), data.end())};
  });
  EXPECT_EQ(f.broker.accept_chunk("a", request, 3, 6, "def"), cb::chunk_result::completed);
  const auto [result, text] = waiter.get();
  EXPECT_EQ(result, cb::wait_result::completed);
  EXPECT_EQ(text, "abcdef");
  EXPECT_EQ(f.broker.accept_chunk("a", request, 6, 6, ""), cb::chunk_result::cancelled);
}

TEST(RhRemoteClipboardSync, EmptyFormatsCompleteWithOneEmptyChunk) {
  fixture f;
  f.touch("a");
  const auto request = f.broker.begin_render("a", 13);
  EXPECT_EQ(f.broker.accept_chunk("a", request, 0, 0, ""), cb::chunk_result::completed);
  std::vector<std::uint8_t> data {1};
  EXPECT_EQ(f.broker.wait_render(request, cb::broker::clock::now() + 1s, data), cb::wait_result::completed);
  EXPECT_TRUE(data.empty());
}

TEST(RhRemoteClipboardSync, RejectsTransfersBeyondTheMemoryBudget) {
  fixture f;
  f.budget = 10;
  f.touch("a");
  const auto request = f.broker.begin_render("a", 13);
  EXPECT_EQ(f.broker.accept_chunk("a", request, 0, 11, "x"), cb::chunk_result::too_large);
  std::vector<std::uint8_t> data;
  EXPECT_EQ(f.broker.wait_render(request, cb::broker::clock::now() + 1s, data), cb::wait_result::failed);
  EXPECT_EQ(f.broker.begin_render("unknown", 13), 0u);
}

TEST(RhRemoteClipboardSync, PeerFailureEndsTheWait) {
  fixture f;
  f.touch("a");
  const auto request = f.broker.begin_render("a", 13);
  f.broker.fail_render("b", request);
  f.broker.fail_render("a", request);
  std::vector<std::uint8_t> data;
  EXPECT_EQ(f.broker.wait_render(request, cb::broker::clock::now() + 1s, data), cb::wait_result::failed);
}

TEST(RhRemoteClipboardSync, SlowTransfersContinueAfterTheBudgetAndDeliverLate) {
  fixture f;
  std::promise<std::pair<std::uint64_t, std::string>> late;
  f.broker.set_callbacks(
    [&](std::uint64_t request, std::vector<std::uint8_t> data) {
      late.set_value({request, std::string(data.begin(), data.end())});
    },
    {}
  );
  f.touch("a");
  const auto request = f.broker.begin_render("a", 13);
  EXPECT_EQ(f.broker.accept_chunk("a", request, 0, 4, "ab"), cb::chunk_result::accepted);
  std::vector<std::uint8_t> data;
  EXPECT_EQ(f.broker.wait_render(request, cb::broker::clock::now() + 20ms, data), cb::wait_result::pending);
  EXPECT_EQ(f.broker.accept_chunk("a", request, 2, 4, "cd"), cb::chunk_result::completed);
  const auto delivered = late.get_future().get();
  EXPECT_EQ(delivered.first, request);
  EXPECT_EQ(delivered.second, "abcd");
}

TEST(RhRemoteClipboardSync, CancellingNotifiesTheUploader) {
  fixture f;
  f.touch("a");
  const auto request = f.broker.begin_render("a", 13);
  const auto cursor = f.touch("a")["cursor"].get<std::uint64_t>();
  f.broker.cancel_render(request);
  const auto events = f.touch("a", cursor)["events"];
  ASSERT_EQ(events.size(), 1u);
  EXPECT_EQ(events[0]["type"], "cancel");
  EXPECT_EQ(events[0]["request"], request);
  EXPECT_EQ(f.broker.accept_chunk("a", request, 0, 1, "x"), cb::chunk_result::cancelled);
}

TEST(RhRemoteClipboardSync, MaintenanceExpiresPollsIdleTransfersAndEndedStreams) {
  fixture f;
  std::vector<std::string> gone;
  f.broker.set_callbacks({}, [&](const std::string &peer) {
    gone.push_back(peer);
  });
  f.touch("a");
  captured_poll poll;
  f.broker.poll("a", f.broker.epoch(), 0, 1s, poll.responder());
  const auto request = f.broker.begin_render("a", 13);
  ASSERT_TRUE(poll.ready());  // The render event answered the held poll.
  const auto cursor = poll.future.get()["cursor"].get<std::uint64_t>();

  captured_poll held;
  f.broker.poll("a", f.broker.epoch(), cursor, 1s, held.responder());
  f.broker.tick(cb::broker::clock::now() + 2s, false);
  EXPECT_TRUE(held.ready());

  f.broker.tick(cb::broker::clock::now() + cb::transfer_idle + 1s, false);
  std::vector<std::uint8_t> data;
  EXPECT_EQ(f.broker.wait_render(request, cb::broker::clock::now() + 1s, data), cb::wait_result::failed);
  const auto events = f.touch("a", cursor)["events"];
  ASSERT_FALSE(events.empty());
  EXPECT_EQ(events.back()["type"], "cancel");

  f.alive = false;
  f.broker.tick(cb::broker::clock::now(), true);
  EXPECT_EQ(gone, std::vector<std::string> {"a"});
  EXPECT_EQ(f.broker.begin_render("a", 13), 0u);
}

TEST(RhRemoteClipboardSync, SnapshotCacheMatchesSequenceAndFormat) {
  cb::snapshot_cache cache;
  auto data = std::make_shared<const std::vector<std::uint8_t>>(std::vector<std::uint8_t> {1, 2, 3});
  cache.store(5, 13, data);
  EXPECT_EQ(cache.find(5, 13), data);
  EXPECT_FALSE(cache.find(6, 13));
  EXPECT_FALSE(cache.find(5, 1));
  cache.clear();
  EXPECT_FALSE(cache.find(5, 13));
}
