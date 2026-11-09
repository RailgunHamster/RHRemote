/**
 * @file src/platform/windows/window_capture.cpp
 * @brief Enumerate and lease single-window capture items without stale HWND reuse.
 */
#include "window_capture.h"

#include "capture_context.h"

#include <chrono>
#include <cstring>
#include <dwmapi.h>
#include <mutex>
#include <roapi.h>
#include <unordered_map>
#include <winrt/windows.foundation.metadata.h>

// Match the interop header workaround used by the existing WGC backend.
#define ____FIReference_1_boolean_INTERFACE_DEFINED__
#include "src/crypto.h"
#include "src/rhremote_window_capture.h"

#include <Windows.Graphics.Capture.Interop.h>

namespace platf::window_capture {
  namespace {
    /** @brief Ensure WinRT exists on the calling HTTP or capture thread. */
    struct apartment_t {
      HRESULT status = RoInitialize(RO_INIT_MULTITHREADED);  ///< Initialization owned by this thread.

      /** @brief Balance only a successful initialization. */
      ~apartment_t() {
        if (SUCCEEDED(status)) {
          RoUninitialize();
        }
      }
    };

    /** @brief Initialize this thread's apartment. */
    void apartment() {
      thread_local apartment_t instance;
      if (FAILED(instance.status) && instance.status != RPC_E_CHANGED_MODE) {
        winrt::throw_hresult(instance.status);
      }
    }

    /** @brief Generate an opaque random token. @return Exactly 32 lowercase hex characters. */
    std::string nonce() {
      constexpr char hex[] = "0123456789abcdef";
      std::string output;
      for (unsigned char value : crypto::rand(16)) {
        output += hex[value >> 4];
        output += hex[value & 15];
      }
      return output;
    }

    /** @brief Private property namespace belonging to this server process. @return The property name. */
    const std::wstring &marker_name() {
      static const auto name = std::wstring(L"RHRemote.WindowCapture.") + winrt::to_hstring(nonce()).c_str();
      return name;
    }

    /** @brief An idle-expiring, certificate-owned source. */
    struct entry_lease_t {
      std::string certificate;  ///< Exact canonical TLS peer.
      target_ptr target;  ///< Native item retained while advertised.
      std::chrono::steady_clock::time_point expires;  ///< Idle lease deadline.
    };

    /** @brief Registry lock is never held while creating a WinRT item. */
    struct registry_t {
      std::mutex mutex;  ///< Protects maps and admission budgets.
      std::unordered_map<std::string, entry_lease_t> entries;  ///< Opaque source lookup.
      std::unordered_map<HWND, std::weak_ptr<target_t>> windows;  ///< Reuse only an original, live native identity.
    };

    /** @brief Access process-local source leases. @return Registry instance. */
    registry_t &registry() {
      (void) marker_name();
      static registry_t value;
      return value;
    }

    /** @brief Drop expired idle references outside the registry lock. */
    void expire() {
      std::vector<target_ptr> retired;
      auto &catalog = registry();
      {
        std::lock_guard guard(catalog.mutex);
        const auto now = std::chrono::steady_clock::now();
        for (auto it = catalog.entries.begin(); it != catalog.entries.end();) {
          if (it->second.expires < now && it->second.target->captures.load() == 0) {
            retired.push_back(std::move(it->second.target));
            it = catalog.entries.erase(it);
          } else {
            ++it;
          }
        }
        for (auto it = catalog.windows.begin(); it != catalog.windows.end();) {
          if (it->second.expired()) {
            it = catalog.windows.erase(it);
          } else {
            ++it;
          }
        }
      }
    }

    /** @brief Bind an item to a native lifetime, not only its numeric handle. @param window Native candidate. @return A stable capture identity. */
    target_ptr make_target(HWND window) {
      static std::mutex creation_mutex;
      std::lock_guard creation_guard(creation_mutex);
      auto &catalog = registry();
      {
        std::lock_guard guard(catalog.mutex);
        auto existing = catalog.windows[window].lock();
        if (existing && existing->alive()) {
          return existing;
        }
      }
      auto target = std::make_shared<target_t>();
      target->window = window;
      GetWindowThreadProcessId(window, &target->process);
      const auto bytes = crypto::rand(sizeof(target->marker));
      std::memcpy(&target->marker, bytes.data(), sizeof(target->marker));
      if (target->marker == 0) {
        target->marker = 1;
      }
      if (!SetPropW(window, marker_name().c_str(), reinterpret_cast<HANDLE>(target->marker))) {
        return {};
      }
      apartment();
      auto factory = winrt::get_activation_factory<winrt::Windows::Graphics::Capture::GraphicsCaptureItem, IGraphicsCaptureItemInterop>();
      winrt::check_hresult(factory->CreateForWindow(window, winrt::guid_of<winrt::Windows::Graphics::Capture::IGraphicsCaptureItem>(), winrt::put_abi(target->item)));
      auto size = target->item.Size();
      target->width = size.Width;
      target->height = size.Height;
      std::weak_ptr<target_t> weak = target;
      target->closed_event = target->item.Closed([weak](auto &&, auto &&) {
        if (auto live = weak.lock()) {
          live->closed = true;
        }
      });
      if (!target->alive()) {
        return {};
      }
      std::lock_guard guard(catalog.mutex);
      catalog.windows[window] = target;
      return target;
    }

    /** @brief Retain an active capture independently from registry expiry. */
    struct capture_lease_t {
      target_ptr target;  ///< Native item owning the active-count reference.

      /** @brief Increment the active count. @param value Exact leased identity. */
      explicit capture_lease_t(target_ptr value):
          target(std::move(value)) {
        ++target->captures;
      }

      /** @brief Release the capture count. */
      ~capture_lease_t() {
        auto &catalog = registry();
        std::lock_guard guard(catalog.mutex);
        for (auto &[id, entry] : catalog.entries) {
          if (entry.target == target) {
            entry.expires = std::chrono::steady_clock::now() + std::chrono::minutes(2);
          }
        }
        --target->captures;
      }
    };
  }  // namespace

  target_t::~target_t() {
    if (item && closed_event.value) {
      try {
        capture_context::scope_t user;
        item.Closed(closed_event);
      } catch (...) {}
    }
    if (window && marker && GetPropW(window, marker_name().c_str()) == reinterpret_cast<HANDLE>(marker)) {
      RemovePropW(window, marker_name().c_str());
    }
  }

  bool target_t::alive() const {
    DWORD current {};
    return !closed.load() && IsWindow(window) && GetWindowThreadProcessId(window, &current) && current == process && GetPropW(window, marker_name().c_str()) == reinterpret_cast<HANDLE>(marker);
  }

  RECT target_t::bounds() const {
    RECT value {};
    if (alive() && FAILED(DwmGetWindowAttribute(window, DWMWA_EXTENDED_FRAME_BOUNDS, &value, sizeof(value)))) {
      GetWindowRect(window, &value);
    }
    return value;
  }

  std::string target_t::state() const {
    if (!alive()) {
      return "closed";
    }
    if (IsIconic(window)) {
      return "minimized";
    }
    const auto rect = bounds();
    return rect.right > rect.left && rect.bottom > rect.top ? "ready" : "unavailable";
  }

  std::wstring target_t::monitor_name() const {
    MONITORINFOEXW info {};
    info.cbSize = sizeof(info);
    if (!alive() || !GetMonitorInfoW(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST), &info)) {
      return {};
    }
    return info.szDevice;
  }

  bool target_t::focus() const {
    if (state() != "ready") {
      return false;
    }
    if (GetAncestor(GetForegroundWindow(), GA_ROOT) != window) {
      SetForegroundWindow(window);
    }
    return alive() && GetAncestor(GetForegroundWindow(), GA_ROOT) == window;
  }

  bool target_t::present() const {
    if (state() != "ready") {
      return false;
    }
    // Keep thread queues separate: attaching them can reset held key states and
    // tie connection admission to an application's responsiveness. The client's
    // first real click activates this exact window through the normal HID path.
    return SetWindowPos(window, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_ASYNCWINDOWPOS) && alive();
  }

  bool supported() {
    try {
      capture_context::scope_t user;
      apartment();
      return winrt::Windows::Graphics::Capture::GraphicsCaptureSession::IsSupported();
    } catch (...) {
      return false;
    }
  }

  bool secondary_windows_supported() {
    try {
      capture_context::scope_t user;
      apartment();
      return winrt::Windows::Foundation::Metadata::ApiInformation::IsPropertyPresent(L"Windows.Graphics.Capture.GraphicsCaptureSession", L"IncludeSecondaryWindows");
    } catch (...) {
      return false;
    }
  }

  std::vector<entry_t> enumerate(const std::string &certificate) {
    std::vector<entry_t> rows;
    if (certificate.empty() || !supported()) {
      return rows;
    }
    capture_context::scope_t user;
    expire();
    std::vector<HWND> handles;
    EnumWindows([](HWND window, LPARAM context) -> BOOL {
      auto &values = *reinterpret_cast<std::vector<HWND> *>(context);
      DWORD pid {};
      GetWindowThreadProcessId(window, &pid);
      DWORD cloaked {};
      DwmGetWindowAttribute(window, DWMWA_CLOAKED, &cloaked, sizeof(cloaked));
      if (values.size() < 256 && IsWindowVisible(window) && !cloaked && pid != GetCurrentProcessId() && window != GetShellWindow() && window != GetDesktopWindow()) {
        values.push_back(window);
      }
      return TRUE;
    },
                reinterpret_cast<LPARAM>(&handles));
    for (const auto window : handles) {
      if (rows.size() >= 128) {
        break;
      }
      wchar_t title[512] {};
      if (GetWindowTextW(window, title, 512) <= 0) {
        continue;
      }
      entry_t row;
      row.title = winrt::to_string(title);
      try {
        auto target = make_target(window);
        if (!target) {
          row.state = "permission_denied";
          rows.push_back(std::move(row));
          continue;
        }
        row.state = target->state();
        row.title = winrt::to_string(target->item.DisplayName());
        const auto size = target->item.Size();
        row.width = size.Width;
        row.height = size.Height;
        HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, target->process);
        if (process) {
          wchar_t path[4096] {};
          DWORD count = 4096;
          if (QueryFullProcessImageNameW(process, 0, path, &count)) {
            const auto leaf = std::wstring_view(path, count).find_last_of(L"\\/");
            row.application = winrt::to_string(std::wstring_view(path, count).substr(leaf == std::wstring_view::npos ? 0 : leaf + 1));
          }
          CloseHandle(process);
        }
        auto &catalog = registry();
        std::lock_guard guard(catalog.mutex);
        for (auto &[token, lease] : catalog.entries) {
          if (lease.certificate == certificate && lease.target == target) {
            row.id = std::string(rhremote::window_source_prefix) + token;
            lease.expires = std::chrono::steady_clock::now() + std::chrono::minutes(2);
            break;
          }
        }
        if (row.id.empty() && catalog.entries.size() < 4096 && rows.size() < 128) {
          auto token = nonce();
          catalog.entries.emplace(token, entry_lease_t {certificate, target, std::chrono::steady_clock::now() + std::chrono::minutes(2)});
          row.id = std::string(rhremote::window_source_prefix) + token;
        }
        if (row.id.empty()) {
          row.state = "capacity";
        }
      } catch (...) {
        row.state = "unavailable";
      }
      rows.push_back(std::move(row));
      if (rows.size() >= 128) {
        break;
      }
    }
    return rows;
  }

  target_ptr resolve(std::string_view source, const std::string &certificate) {
    const auto token = rhremote::window_source_token(source);
    if (!token || certificate.empty()) {
      return {};
    }
    expire();
    auto &catalog = registry();
    std::lock_guard guard(catalog.mutex);
    const auto found = catalog.entries.find(*token);
    if (found == catalog.entries.end() || found->second.certificate != certificate) {
      return {};
    }
    found->second.expires = std::chrono::steady_clock::now() + std::chrono::minutes(2);
    return found->second.target;
  }

  target_ptr acquire(std::string_view source) {
    const auto token = rhremote::window_source_token(source);
    if (!token) {
      return {};
    }
    expire();
    auto &catalog = registry();
    std::lock_guard guard(catalog.mutex);
    const auto found = catalog.entries.find(*token);
    if (found == catalog.entries.end()) {
      return {};
    }
    auto lease = std::make_shared<capture_lease_t>(found->second.target);
    return target_ptr(lease, lease->target.get());
  }

  target_ptr active(std::string_view source) {
    const auto token = rhremote::window_source_token(source);
    if (!token) {
      return {};
    }
    auto &catalog = registry();
    std::lock_guard guard(catalog.mutex);
    const auto found = catalog.entries.find(*token);
    return found != catalog.entries.end() && found->second.target->captures.load() > 0 ? found->second.target : target_ptr {};
  }
#ifdef SUNSHINE_TESTS
  namespace testing {
    std::string advertise(const std::string &certificate) {
      const auto token = nonce();
      auto &catalog = registry();
      std::lock_guard guard(catalog.mutex);
      catalog.entries.emplace(token, entry_lease_t {certificate, std::make_shared<target_t>(), std::chrono::steady_clock::now() + std::chrono::minutes(2)});
      return std::string(rhremote::window_source_prefix) + token;
    }

    void expire_now(std::string_view source) {
      const auto token = rhremote::window_source_token(source);
      if (!token) {
        return;
      }
      auto &catalog = registry();
      std::lock_guard guard(catalog.mutex);
      const auto found = catalog.entries.find(*token);
      if (found != catalog.entries.end()) {
        found->second.expires = std::chrono::steady_clock::now() - std::chrono::seconds(1);
      }
    }
  }  // namespace testing
#endif
}  // namespace platf::window_capture
