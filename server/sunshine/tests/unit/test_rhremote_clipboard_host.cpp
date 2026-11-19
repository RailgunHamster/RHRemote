/**
 * @file tests/unit/test_rhremote_clipboard_host.cpp
 * @brief Real Win32 clipboard tests for the clipboard synchronization host.
 *
 * These tests own the clipboard of whatever window station they run in. They are skipped unless
 * RHREMOTE_CLIPBOARD_ISOLATED=1, which tools/test-clipboard-isolated.ps1 sets after starting the test
 * executable on a private window station whose clipboard is separate from the user's.
 */
#ifdef _WIN32
  #include <gtest/gtest.h>

  #include <chrono>
  #include <cstdlib>
  #include <cstring>
  #include <future>
  #include <string>
  #include <thread>

  #include <windows.h>

  #include "src/platform/windows/clipboard_sync.h"

using namespace std::chrono_literals;
namespace cb = rhremote::clipboard;

namespace {
  bool isolated() {
    const char *value = std::getenv("RHREMOTE_CLIPBOARD_ISOLATED");
    if (!value || std::string(value) != "1") {
      return false;
    }
    // Never touch the signed-in user's clipboard: refuse the interactive window station.
    wchar_t name[256] {};
    DWORD needed = 0;
    return GetUserObjectInformationW(GetProcessWindowStation(), UOI_NAME, name, sizeof(name), &needed) && _wcsicmp(name, L"WinSta0") != 0;
  }

  /** @brief Act as an ordinary desktop application using the clipboard. */
  bool app_set_text(const std::wstring &text) {
    if (!OpenClipboard(nullptr)) {
      return false;
    }
    EmptyClipboard();
    const auto bytes = (text.size() + 1) * sizeof(wchar_t);
    const auto memory = GlobalAlloc(GMEM_MOVEABLE, bytes);
    std::memcpy(GlobalLock(memory), text.c_str(), bytes);
    GlobalUnlock(memory);
    const bool ok = SetClipboardData(CF_UNICODETEXT, memory) != nullptr;
    CloseClipboard();
    return ok;
  }

  std::string app_get(UINT format) {
    for (int attempt = 0; attempt < 50 && !OpenClipboard(nullptr); ++attempt) {
      Sleep(10);
    }
    std::string result;
    if (const auto memory = GetClipboardData(format)) {
      const auto *data = static_cast<const char *>(GlobalLock(memory));
      result.assign(data, GlobalSize(memory));
      GlobalUnlock(memory);
    }
    CloseClipboard();
    return result;
  }

  nlohmann::json poll(cb::broker &broker, const std::string &peer, std::uint64_t cursor, std::chrono::milliseconds wait) {
    std::promise<nlohmann::json> promise;
    auto future = promise.get_future();
    broker.poll(peer, broker.epoch(), cursor, wait, [&promise](nlohmann::json value) {
      promise.set_value(std::move(value));
    });
    return future.get();
  }

  /** @brief Wait for the first event of a type, returning it with the new cursor. */
  std::pair<nlohmann::json, std::uint64_t> next_event(cb::broker &broker, const std::string &peer, std::uint64_t cursor, const std::string &type) {
    const auto deadline = std::chrono::steady_clock::now() + 10s;
    while (std::chrono::steady_clock::now() < deadline) {
      const auto reply = poll(broker, peer, cursor, 1s);
      cursor = reply.value("cursor", cursor);
      for (const auto &event : reply["events"]) {
        if (event["type"] == type) {
          return {event, cursor};
        }
      }
    }
    return {nlohmann::json {}, cursor};
  }

  struct host_fixture {
    cb::broker broker {[](const std::string &) {
                         return true;
                       },
                       [] {
                         return std::uint64_t {1} << 30;
                       }};
    platf::clipboard_sync::host host {broker, [] {
                                        return true;
                                      }};

    host_fixture() {
      broker.set_callbacks(
        [this](std::uint64_t request, std::vector<std::uint8_t> data) {
          host.late_ready(request, std::move(data));
        },
        [this](const std::string &peer) {
          host.peer_gone(peer);
        }
      );
      broker.start();
    }

    ~host_fixture() {
      broker.stop();
      host.stop();
    }
  };
}  // namespace

TEST(RhRemoteClipboardHost, AnnouncesHostCopiesAndServesTheirData) {
  if (!isolated()) {
    GTEST_SKIP() << "Requires a private window station (tools/test-clipboard-isolated.ps1)";
  }
  host_fixture f;
  ASSERT_TRUE(f.host.start());
  const auto cursor = poll(f.broker, "client", 0, 0ms).value("cursor", std::uint64_t {0});

  ASSERT_TRUE(app_set_text(L"\u4F60\u597D clipboard"));
  const auto [event, after] = next_event(f.broker, "client", cursor, "formats");
  ASSERT_FALSE(event.is_null());
  ASSERT_FALSE(event["formats"].empty());
  EXPECT_EQ(event["formats"][0]["id"], CF_UNICODETEXT);

  const auto seq = event["seq"].get<std::uint64_t>();
  const auto read = f.host.read(seq, CF_UNICODETEXT);
  ASSERT_TRUE(read.data) << read.error;
  const std::wstring expected = L"\u4F60\u597D clipboard";
  ASSERT_EQ(read.data->size(), (expected.size() + 1) * sizeof(wchar_t));
  EXPECT_EQ(std::memcmp(read.data->data(), expected.c_str(), read.data->size()), 0);

  EXPECT_EQ(f.host.read(seq + 1000, CF_UNICODETEXT).status, 410);
  // A transfer already reading this snapshot may finish it; once the next copy is announced, the old sequence is gone.
  ASSERT_TRUE(app_set_text(L"newer"));
  const auto [newer, unused] = next_event(f.broker, "client", after, "formats");
  ASSERT_FALSE(newer.is_null());
  EXPECT_NE(newer["seq"].get<std::uint64_t>(), seq);
  EXPECT_EQ(f.host.read(seq, CF_UNICODETEXT).status, 410);
}

TEST(RhRemoteClipboardHost, ClientAnnouncementsRenderOnPasteAndReachOtherClientsOnly) {
  if (!isolated()) {
    GTEST_SKIP() << "Requires a private window station (tools/test-clipboard-isolated.ps1)";
  }
  host_fixture f;
  ASSERT_TRUE(f.host.start());
  auto owner_cursor = poll(f.broker, "owner", 0, 0ms).value("cursor", std::uint64_t {0});
  const auto other_cursor = poll(f.broker, "other", 0, 0ms).value("cursor", std::uint64_t {0});

  const UINT html = RegisterClipboardFormatW(L"HTML Format");
  ASSERT_EQ(f.host.announce("owner", {{CF_UNICODETEXT, {}}, {0xC123, "HTML Format"}}), "");

  // The other client learns about the new content; the owner does not hear its own announcement back.
  const auto [forwarded, unused] = next_event(f.broker, "other", other_cursor, "formats");
  ASSERT_FALSE(forwarded.is_null());
  EXPECT_EQ(forwarded["formats"].size(), 2u);
  EXPECT_TRUE(poll(f.broker, "owner", owner_cursor, 0ms)["events"].empty());

  // The owning client answers render requests the way the Windows client uploads chunks.
  std::thread responder([&] {
    for (int answered = 0; answered < 2;) {
      auto [event, cursor] = next_event(f.broker, "owner", owner_cursor, "render");
      owner_cursor = cursor;
      if (event.is_null()) {
        return;
      }
      const auto request = event["request"].get<std::uint64_t>();
      const std::string payload = event["format"] == CF_UNICODETEXT ? std::string("h\0i\0\0\0", 6) : std::string("<b>hi</b>");
      const auto half = payload.size() / 2;
      EXPECT_EQ(f.broker.accept_chunk("owner", request, 0, payload.size(), std::string_view(payload).substr(0, half)), cb::chunk_result::accepted);
      EXPECT_EQ(f.broker.accept_chunk("owner", request, half, payload.size(), std::string_view(payload).substr(half)), cb::chunk_result::completed);
      ++answered;
    }
  });
  EXPECT_EQ(app_get(CF_UNICODETEXT), std::string("h\0i\0\0\0", 6));
  EXPECT_EQ(app_get(html), "<b>hi</b>");
  responder.join();

  // Rendered data stays available without another network request.
  EXPECT_EQ(app_get(html), "<b>hi</b>");

  // When the owner's stream ends, the host clipboard no longer offers its delayed formats.
  f.host.peer_gone("owner");
  const auto deadline = std::chrono::steady_clock::now() + 5s;
  while (IsClipboardFormatAvailable(CF_UNICODETEXT) && std::chrono::steady_clock::now() < deadline) {
    std::this_thread::sleep_for(20ms);
  }
  EXPECT_FALSE(IsClipboardFormatAvailable(CF_UNICODETEXT));
}

TEST(RhRemoteClipboardHost, FailedRendersLeaveThePasteEmpty) {
  if (!isolated()) {
    GTEST_SKIP() << "Requires a private window station (tools/test-clipboard-isolated.ps1)";
  }
  host_fixture f;
  ASSERT_TRUE(f.host.start());
  auto cursor = poll(f.broker, "owner", 0, 0ms).value("cursor", std::uint64_t {0});
  ASSERT_EQ(f.host.announce("owner", {{CF_UNICODETEXT, {}}}), "");
  std::thread responder([&] {
    const auto [event, unused] = next_event(f.broker, "owner", cursor, "render");
    if (!event.is_null()) {
      f.broker.fail_render("owner", event["request"].get<std::uint64_t>());
    }
  });
  EXPECT_EQ(app_get(CF_UNICODETEXT), "");
  responder.join();
}
#endif
