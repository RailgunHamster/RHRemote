/**
 * @file src/platform/windows/window_capture.h
 * @brief Peer-bound window identities for Windows Graphics Capture.
 */
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>
#include <Windows.h>
#include <winrt/windows.graphics.capture.h>

namespace platf::window_capture {
  /** @brief A capture item tied to one native window lifetime. */
  struct target_t: std::enable_shared_from_this<target_t> {
    HWND window {};  ///< Original window; never exported as a client selector.
    DWORD process {};  ///< Process that owned the original window.
    std::uintptr_t marker {};  ///< Window property erased automatically on native destruction.
    winrt::Windows::Graphics::Capture::GraphicsCaptureItem item {nullptr};  ///< Stable native item, never recreated from a stale HWND.
    winrt::event_token closed_event {};  ///< Subscription to native item closure.
    std::atomic<bool> closed {false};  ///< Closure observed by the free-threaded callback.
    std::atomic<unsigned> captures {0};  ///< Active capture leases, excluded from idle expiry.
    std::atomic<int> width {0};  ///< Most recent nonempty content width.
    std::atomic<int> height {0};  ///< Most recent nonempty content height.
    std::mutex item_mutex;  ///< Serialize lazy direct-user WGC item creation.

    /** @brief Remove only this target's private property and event subscription. */
    ~target_t();
    /** @brief Validate the native lifetime marker and owning process. @return Whether the original window still exists. */
    bool alive() const;
    /** @brief Describe window availability without substituting another source. @return ready, minimized, closed, or unavailable. */
    std::string state() const;
    /** @brief Query physical frame bounds. @return Current bounds, or an empty rectangle if unavailable. */
    RECT bounds() const;
    /** @brief Name of the process-private native-lifetime property. @return Stable property name. */
    const std::wstring &marker_property() const;
    /** @brief Create WGC only for the selected window in a user process. @return The original capture item; SYSTEM callers must use the worker. */
    winrt::Windows::Graphics::Capture::GraphicsCaptureItem capture_item();
    /** @brief Find the device used to initialize the capture D3D adapter. @return GDI monitor name, or an empty name on failure. */
    std::wstring monitor_name() const;
    /** @brief Focus only the selected window before injecting input. @return Whether the selected root currently owns foreground input. */
    bool focus() const;
    /** @brief Raise only the selected window without synthetic input or shared thread queues. @return Whether the native window accepted the request. */
    bool present() const;
  };

  /** @brief Shared native identity; capture leases use aliasing ownership. */
  using target_ptr = std::shared_ptr<target_t>;

  /** @brief A client-visible window row without raw handles or process paths. */
  struct entry_t {
    std::string id;  ///< Opaque peer-bound source name, empty when capture is unavailable.
    std::string title;  ///< Current native caption.
    std::string application;  ///< Executable leaf name only.
    std::string state;  ///< Explicit availability for the picker.
    int width {};  ///< Current physical frame width.
    int height {};  ///< Current physical frame height.
  };

  /** @brief Check native application capture support. @return Whether a capture item can be created on this system. */
  bool supported();
  /** @brief Check whether the OS can capture owned secondary windows. @return Whether IncludeSecondaryWindows is available. */
  bool secondary_windows_supported();
  /** @brief List visible application windows for an authenticated certificate. @param certificate Canonical certificate from the verified TLS socket. @return Bounded window rows with peer-bound tokens. */
  std::vector<entry_t> enumerate(const std::string &certificate);
  /** @brief Validate a source against its authenticated owner. @param source Opaque source name. @param certificate Verified requesting certificate. @return The exact native target, or no target. */
  target_ptr resolve(std::string_view source, const std::string &certificate);
  /** @brief Acquire a capture lease after launch authorization. @param source Previously authorized opaque source. @return A native target whose lease protects it from idle expiry. */
  target_ptr acquire(std::string_view source);
  /** @brief Look up an active source for input mapping. @param source Capture source from this session's input port. @return The exact target only while a capture lease exists. */
  target_ptr active(std::string_view source);
#ifdef SUNSHINE_TESTS
  namespace testing {
    /** @brief Advertise an inert native-free test identity. @param certificate Test peer name. @return Opaque selector exercising the real registry. */
    std::string advertise(const std::string &certificate);
    /** @brief Expire one test selector without sleeping. @param source Opaque selector created by advertise. */
    void expire_now(std::string_view source);
    /** @brief Build only metadata for an owned native test window. @param window Test-owned HWND. @return Lazy target without a WGC item. */
    target_ptr metadata(HWND window);
  }  // namespace testing
#endif
}  // namespace platf::window_capture
