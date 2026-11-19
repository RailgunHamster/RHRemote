/**
 * @file src/platform/windows/clipboard_sync.cpp
 * @brief Windows clipboard backend: hidden owner window, format announcements and delayed rendering.
 */
// standard includes
#include <array>
#include <condition_variable>
#include <cstring>
#include <future>
#include <map>
#include <mutex>
#include <thread>

// platform includes
#include <windows.h>

// local includes
#include "clipboard_sync.h"
#include "clipboard_text.h"
#include "src/logging.h"
#include "utf_utils.h"

namespace platf::clipboard_sync {
  namespace cb = rhremote::clipboard;

  namespace {
    constexpr UINT msg_announce = WM_APP + 0x51;  ///< lParam: owned announce_request.
    constexpr UINT msg_late = WM_APP + 0x52;  ///< wParam: broker request id with stored late data.
    constexpr UINT msg_peer_gone = WM_APP + 0x53;  ///< lParam: owned peer string.
    constexpr UINT msg_quit = WM_APP + 0x54;
    constexpr UINT_PTR publish_timer = 1;
    constexpr wchar_t window_class[] = L"RHRemoteClipboardSync";

    /** @brief Open the clipboard, retrying briefly while another application holds it. */
    bool open_clipboard(HWND owner) {
      for (int attempt = 0; attempt < 20; ++attempt) {
        if (OpenClipboard(owner)) {
          return true;
        }
        Sleep(15);
      }
      return false;
    }

    /** @brief Copy bytes into a movable global allocation owned by the clipboard after SetClipboardData. */
    HGLOBAL to_global(const std::vector<std::uint8_t> &data) {
      const auto memory = GlobalAlloc(GMEM_MOVEABLE, std::max<SIZE_T>(data.size(), 1));
      if (!memory) {
        return nullptr;
      }
      if (auto *target = GlobalLock(memory)) {
        if (!data.empty()) {
          std::memcpy(target, data.data(), data.size());
        }
        GlobalUnlock(memory);
        return memory;
      }
      GlobalFree(memory);
      return nullptr;
    }

    /** @brief Read the registered name of a format. */
    std::string format_name(UINT format) {
      if (format < cb::cf_registered_first) {
        return {};
      }
      std::array<wchar_t, cb::max_format_name + 1> name {};
      const auto length = GetClipboardFormatNameW(format, name.data(), static_cast<int>(name.size()));
      return length > 0 ? utf_utils::to_utf8(std::wstring(name.data(), length)) : std::string {};
    }
  }  // namespace

  struct host::impl {
    /** @brief Cross-thread announcement handed to the clipboard thread. */
    struct announce_request {
      std::string peer;
      std::vector<cb::format_t> formats;
      std::promise<std::string> result;
    };

    /** @brief Delayed render that outlived the pasting application's budget. */
    struct late_render {
      UINT format = 0;
      std::uint64_t generation = 0;
    };

    impl(cb::broker &broker, std::function<bool()> desktop):
        broker(broker),
        desktop_available(desktop ? std::move(desktop) : std::function<bool()>(&platf::clipboard::desktop_available)) {}

    cb::broker &broker;
    std::function<bool()> desktop_available;  ///< Interactive-desktop check; replaceable for isolated tests.
    cb::snapshot_cache cache;

    std::mutex mutex;  ///< Guards thread startup, hwnd publication and late data.
    std::condition_variable started_cv;
    std::thread thread;
    bool started = false;
    bool failed = false;
    HWND hwnd = nullptr;
    std::map<std::uint64_t, std::vector<std::uint8_t>> late_data;

    // Clipboard-thread state.
    std::string owner_peer;  ///< Client whose clipboard the host currently mirrors.
    std::map<UINT, std::uint32_t> mapping;  ///< Host-local delayed format to client-local format.
    std::uint64_t generation = 0;  ///< Changes whenever ownership changes.
    std::map<std::uint64_t, late_render> late;  ///< Requests that may still deliver.
    DWORD last_published = 0;
    bool publish_pending = false;

    static LRESULT CALLBACK window_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
      if (message == WM_NCCREATE) {
        const auto *create = reinterpret_cast<CREATESTRUCTW *>(lparam);
        SetWindowLongPtrW(window, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(create->lpCreateParams));
      }
      auto *self = reinterpret_cast<impl *>(GetWindowLongPtrW(window, GWLP_USERDATA));
      if (self) {
        return self->handle(window, message, wparam, lparam);
      }
      return DefWindowProcW(window, message, wparam, lparam);
    }

    void run() {
      WNDCLASSEXW wc {sizeof(wc)};
      wc.lpfnWndProc = &impl::window_proc;
      wc.hInstance = GetModuleHandleW(nullptr);
      wc.lpszClassName = window_class;
      if (!RegisterClassExW(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
        finish_start(nullptr);
        return;
      }
      const auto window = CreateWindowExW(0, window_class, L"", 0, 0, 0, 0, 0, HWND_MESSAGE, nullptr, wc.hInstance, this);
      if (!window) {
        finish_start(nullptr);
        return;
      }
      // The engine may run with a higher integrity than desktop applications; allow their clipboard notifications.
      for (const auto message : {WM_RENDERFORMAT, WM_RENDERALLFORMATS, WM_DESTROYCLIPBOARD, WM_CLIPBOARDUPDATE}) {
        ChangeWindowMessageFilterEx(window, message, MSGFLT_ALLOW, nullptr);
      }
      if (!AddClipboardFormatListener(window)) {
        BOOST_LOG(warning) << "RHREMOTE_CLIPBOARD listener unavailable";
      }
      SetTimer(window, publish_timer, 1000, nullptr);
      last_published = GetClipboardSequenceNumber();
      finish_start(window);

      MSG message;
      while (GetMessageW(&message, nullptr, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
      }
      KillTimer(window, publish_timer);
      RemoveClipboardFormatListener(window);
      cancel_late();
      DestroyWindow(window);
    }

    void finish_start(HWND window) {
      {
        std::lock_guard lock {mutex};
        hwnd = window;
        started = window != nullptr;
        failed = window == nullptr;
      }
      started_cv.notify_all();
    }

    LRESULT handle(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
      switch (message) {
        case WM_CLIPBOARDUPDATE:
          if (GetClipboardOwner() != window) {
            publish_pending = true;
            publish(window);
          }
          return 0;
        case WM_TIMER:
          if (wparam == publish_timer && publish_pending) {
            publish(window);
          }
          return 0;
        case WM_RENDERFORMAT:
          render(static_cast<UINT>(wparam));
          return 0;
        case WM_RENDERALLFORMATS:
          // Shutting down: never block engine exit on network transfers; unrendered formats disappear.
          return 0;
        case WM_DESTROYCLIPBOARD:
          release_ownership();
          return 0;
        case msg_announce:
          {
            std::unique_ptr<announce_request> request {reinterpret_cast<announce_request *>(lparam)};
            request->result.set_value(take_ownership(window, request->peer, request->formats));
            return 0;
          }
        case msg_late:
          deliver_late(window, static_cast<std::uint64_t>(wparam));
          return 0;
        case msg_peer_gone:
          {
            std::unique_ptr<std::string> peer {reinterpret_cast<std::string *>(lparam)};
            if (!owner_peer.empty() && owner_peer == *peer && GetClipboardOwner() == window && open_clipboard(window)) {
              EmptyClipboard();
              CloseClipboard();
            }
            if (owner_peer == *peer) {
              release_ownership();
            }
            return 0;
          }
        case msg_quit:
          PostQuitMessage(0);
          return 0;
        default:
          return DefWindowProcW(window, message, wparam, lparam);
      }
    }

    /** @brief Announce the current host clipboard when the interactive desktop is visible. */
    void publish(HWND window) {
      if (!desktop_available()) {
        return;  // Locked or secure desktop: retry from the timer after unlock.
      }
      const auto seq = GetClipboardSequenceNumber();
      if (seq == last_published) {
        publish_pending = false;
        return;
      }
      if (!open_clipboard(window)) {
        return;  // Busy: the timer retries.
      }
      std::vector<cb::format_t> available;
      for (UINT format = EnumClipboardFormats(0); format; format = EnumClipboardFormats(format)) {
        available.push_back({format, format_name(format)});
      }
      CloseClipboard();
      last_published = seq;
      publish_pending = false;
      cache.clear();
      if (auto selected = cb::select(available); !selected.empty()) {
        broker.publish_formats(seq, selected);  // An empty clipboard never wipes the clients' clipboards.
      }
    }

    /** @brief Empty the host clipboard and register delayed formats for a client. */
    std::string take_ownership(HWND window, const std::string &peer, const std::vector<cb::format_t> &formats) {
      if (!open_clipboard(window)) {
        return "Remote clipboard is in use; try again";
      }
      if (!EmptyClipboard()) {  // Notifies the previous owner, possibly this window, through WM_DESTROYCLIPBOARD.
        CloseClipboard();
        return "Remote clipboard could not be replaced";
      }
      if (!owner_peer.empty()) {
        release_ownership();
      }
      owner_peer = peer;
      std::vector<cb::format_t> mirrored;
      for (const auto &format : formats) {
        const UINT local = format.name.empty() ? format.id : RegisterClipboardFormatW(utf_utils::from_utf8(format.name).c_str());
        if (!local || mapping.contains(local)) {
          continue;
        }
        // Delayed rendering returns NULL on success, so success is distinguished through the last error.
        SetLastError(ERROR_SUCCESS);
        if (SetClipboardData(local, nullptr) || GetLastError() == ERROR_SUCCESS) {
          mapping[local] = format.id;
          mirrored.push_back({local, format.name});
        }
      }
      CloseClipboard();
      cache.clear();
      // Another connected client can read this content through the host, which renders it from the owner.
      broker.publish_formats(GetClipboardSequenceNumber(), mirrored, peer);
      return {};
    }

    /** @brief Forget the mirrored client clipboard and stop pending deliveries. */
    void release_ownership() {
      ++generation;
      owner_peer.clear();
      mapping.clear();
      cancel_late();
    }

    void cancel_late() {
      for (const auto &[request, pending] : late) {
        broker.cancel_render(request);
      }
      late.clear();
      std::lock_guard lock {mutex};
      late_data.clear();
    }

    /** @brief Answer WM_RENDERFORMAT from the owning client within the pasting application's budget. */
    void render(UINT format) {
      const auto found = mapping.find(format);
      if (found == mapping.end() || owner_peer.empty()) {
        return;
      }
      const auto request = broker.begin_render(owner_peer, found->second);
      if (!request) {
        return;
      }
      std::vector<std::uint8_t> data;
      switch (broker.wait_render(request, cb::broker::clock::now() + cb::render_budget, data)) {
        case cb::wait_result::completed:
          if (const auto memory = to_global(data); memory && !SetClipboardData(format, memory)) {
            GlobalFree(memory);
          }
          break;
        case cb::wait_result::pending:
          // This paste fails; the transfer continues and the data is placed when complete.
          late[request] = {format, generation};
          BOOST_LOG(info) << "RHREMOTE_CLIPBOARD render continues in background request=" << request;
          break;
        case cb::wait_result::failed:
          break;
      }
    }

    /** @brief Place a late render if the host clipboard still mirrors the same client content. */
    void deliver_late(HWND window, std::uint64_t request) {
      std::vector<std::uint8_t> data;
      {
        std::lock_guard lock {mutex};
        const auto stored = late_data.find(request);
        if (stored == late_data.end()) {
          return;
        }
        data = std::move(stored->second);
        late_data.erase(stored);
      }
      const auto pending = late.find(request);
      if (pending == late.end()) {
        return;
      }
      const auto target = pending->second;
      late.erase(pending);
      if (target.generation != generation || GetClipboardOwner() != window || !open_clipboard(window)) {
        return;
      }
      if (const auto memory = to_global(data); memory && !SetClipboardData(target.format, memory)) {
        GlobalFree(memory);
      }
      CloseClipboard();
      BOOST_LOG(info) << "RHREMOTE_CLIPBOARD late render placed request=" << request << " bytes=" << data.size();
    }

    bool post(UINT message, WPARAM wparam, LPARAM lparam) {
      std::lock_guard lock {mutex};
      return hwnd && PostMessageW(hwnd, message, wparam, lparam);
    }
  };

  host::host(cb::broker &broker, std::function<bool()> desktop_available):
      impl_(std::make_unique<impl>(broker, std::move(desktop_available))) {}

  host::~host() {
    stop();
  }

  bool host::start() {
    std::unique_lock lock {impl_->mutex};
    if (!impl_->thread.joinable()) {
      impl_->thread = std::thread([this] {
        impl_->run();
      });
    }
    impl_->started_cv.wait_for(lock, std::chrono::seconds(5), [this] {
      return impl_->started || impl_->failed;
    });
    return impl_->started;
  }

  void host::stop() {
    if (!impl_ || !impl_->thread.joinable()) {
      return;
    }
    impl_->post(msg_quit, 0, 0);
    impl_->thread.join();
    std::lock_guard lock {impl_->mutex};
    impl_->hwnd = nullptr;
    impl_->started = false;
  }

  std::string host::announce(const std::string &peer, const std::vector<cb::format_t> &formats) {
    if (!start()) {
      return "Remote clipboard is unavailable";
    }
    auto request = std::make_unique<impl::announce_request>();
    request->peer = peer;
    request->formats = formats;
    auto result = request->result.get_future();
    if (!impl_->post(msg_announce, 0, reinterpret_cast<LPARAM>(request.get()))) {
      return "Remote clipboard is unavailable";
    }
    request.release();  // Owned by the clipboard thread from here.
    if (result.wait_for(std::chrono::seconds(5)) != std::future_status::ready) {
      return "Remote clipboard did not respond";
    }
    return result.get();
  }

  read_result host::read(std::uint64_t seq, std::uint32_t format) {
    if (!impl_->desktop_available()) {
      return {nullptr, "Unlock the remote desktop to copy its clipboard", 409};
    }
    if (auto cached = impl_->cache.find(seq, format)) {
      return {std::move(cached)};
    }
    if (GetClipboardSequenceNumber() != seq) {
      return {nullptr, "Remote clipboard changed", 410};
    }
    if (!open_clipboard(nullptr)) {
      return {nullptr, "Remote clipboard is in use; try again", 503};
    }
    std::shared_ptr<std::vector<std::uint8_t>> data;
    read_result failure;
    if (const auto memory = GetClipboardData(format); !memory) {
      failure = {nullptr, "Format is no longer on the remote clipboard", 404};
    } else if (const auto size = GlobalSize(memory); size > cb::max_total) {
      failure = {nullptr, "Remote clipboard data is too large", 413};
    } else if (const auto *source = static_cast<const std::uint8_t *>(GlobalLock(memory)); !source && size) {
      failure = {nullptr, "Remote clipboard data is unavailable", 404};
    } else {
      try {
        data = std::make_shared<std::vector<std::uint8_t>>(source, source + size);
      } catch (const std::bad_alloc &) {
        failure = {nullptr, "Remote host is out of memory", 507};
      }
      if (source) {
        GlobalUnlock(memory);
      }
    }
    CloseClipboard();
    if (!data) {
      return failure;
    }
    if (GetClipboardSequenceNumber() != seq) {
      return {nullptr, "Remote clipboard changed", 410};
    }
    impl_->cache.store(seq, format, data);
    return {std::move(data)};
  }

  void host::peer_gone(const std::string &peer) {
    auto owned = std::make_unique<std::string>(peer);
    if (impl_->post(msg_peer_gone, 0, reinterpret_cast<LPARAM>(owned.get()))) {
      owned.release();
    }
  }

  void host::late_ready(std::uint64_t request, std::vector<std::uint8_t> data) {
    {
      std::lock_guard lock {impl_->mutex};
      impl_->late_data[request] = std::move(data);
    }
    impl_->post(msg_late, static_cast<WPARAM>(request), 0);
  }

  std::uint64_t host::memory_budget() {
    MEMORYSTATUSEX status {sizeof(status)};
    if (!GlobalMemoryStatusEx(&status)) {
      return 256ull << 20;
    }
    return std::min<std::uint64_t>(status.ullAvailPhys / 2, cb::max_total);
  }
}  // namespace platf::clipboard_sync
