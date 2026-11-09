/**
 * @file src/platform/windows/capture_context.h
 * @brief Activate Windows Graphics Capture as the logged-on user in a SYSTEM host.
 */
#pragma once

#include "src/rhremote_capture_context.h"

#include <condition_variable>
#include <exception>
#include <mutex>
#include <objbase.h>
#include <semaphore>
#include <thread>
#include <vector>
#include <Windows.h>
#include <winrt/base.h>
#include <wtsapi32.h>

namespace platf::capture_context {
  /** @brief Keep COM security alive without changing the main thread's apartment model. */
  class process_security_t {
    std::mutex mutex;  ///< Protects the shutdown wait.
    std::condition_variable stopped;  ///< Zero-CPU wait until server retirement.
    std::binary_semaphore ready {0};  ///< Publishes initialization before other activations.
    HRESULT result = CO_E_NOTINITIALIZED;  ///< Initial security configuration result.
    std::jthread apartment;  ///< Last member joins before synchronization state is destroyed.

  public:
    /** @brief Initialize security on one retained MTA before any other COM activation. */
    process_security_t():
        apartment([this](std::stop_token stop) {
          const HRESULT initialized = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
          result = SUCCEEDED(initialized) ? CoInitializeSecurity(nullptr, -1, nullptr, nullptr, RPC_C_AUTHN_LEVEL_DEFAULT, RPC_C_IMP_LEVEL_IMPERSONATE, nullptr, EOAC_DYNAMIC_CLOAKING, nullptr) : initialized;
          ready.release();
          {
            std::unique_lock lock(mutex);
            stopped.wait(lock, [&] {
              return stop.stop_requested();
            });
          }
          if (SUCCEEDED(initialized)) {
            CoUninitialize();
          }
        }) {
      ready.acquire();
    }

    /** @brief Release COM only after the server's HTTP and capture threads have retired. */
    ~process_security_t() {
      {
        std::lock_guard lock(mutex);
        apartment.request_stop();
      }
      stopped.notify_all();
    }

    /** @brief Report whether dynamic cloaking was installed. @return Initial COM security status. */
    HRESULT status() const {
      return result;
    }
  };

  /** @brief Close a thread-owned cached user token at thread retirement. */
  struct token_t {
    HANDLE value = nullptr;  ///< Token is never exported through an API or selector.

    /** @brief Close only a valid owned token. */
    ~token_t() {
      if (value) {
        CloseHandle(value);
      }
    }
  };

  /** @brief Token operations for a single HTTP or capture thread. */
  class backend_t {
    token_t cached_user;  ///< One user logon token per thread; supervisor restarts on session change.

  public:
    using token_type = HANDLE;  ///< Native token type used by the restoration scope.
    unsigned scopes = 0;  ///< Nested capture calls reuse the already scoped identity.

    /** @brief Test the process token without depending on existing thread impersonation. @return True for SYSTEM hosts. */
    bool required() const {
      if (scopes) {
        return false;
      }
      static const bool system = [] {
        token_t process;
        if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &process.value)) {
          winrt::throw_hresult(HRESULT_FROM_WIN32(GetLastError()));
        }
        DWORD size = 0;
        GetTokenInformation(process.value, TokenUser, nullptr, 0, &size);
        std::vector<unsigned char> storage(size);
        if (!size || !GetTokenInformation(process.value, TokenUser, storage.data(), size, &size)) {
          winrt::throw_hresult(HRESULT_FROM_WIN32(GetLastError()));
        }
        return IsWellKnownSid(reinterpret_cast<TOKEN_USER *>(storage.data())->User.Sid, WinLocalSystemSid) != FALSE;
      }();
      return system;
    }

    /** @brief Capture an existing thread identity for exact restoration. @return Owned token, or null when unimpersonated. */
    token_type previous() const {
      HANDLE value = nullptr;
      if (!OpenThreadToken(GetCurrentThread(), TOKEN_QUERY | TOKEN_IMPERSONATE, TRUE, &value) && GetLastError() != ERROR_NO_TOKEN) {
        winrt::throw_hresult(HRESULT_FROM_WIN32(GetLastError()));
      }
      return value;
    }

    /** @brief Lend the user token belonging to this engine's interactive session. @return Thread-owned cached primary token. */
    token_type user() {
      if (!cached_user.value) {
        DWORD session = 0;
        if (!ProcessIdToSessionId(GetCurrentProcessId(), &session) || !WTSQueryUserToken(session, &cached_user.value)) {
          winrt::throw_hresult(HRESULT_FROM_WIN32(GetLastError()));
        }
      }
      return cached_user.value;
    }

    /** @brief Apply a borrowed primary user token only on this thread. @param value Logged-on user's token. */
    void impersonate(token_type value) const {
      if (!ImpersonateLoggedOnUser(value)) {
        winrt::throw_hresult(HRESULT_FROM_WIN32(GetLastError()));
      }
    }

    /** @brief Restore the caller's identity. @param value Previous thread token or null. @return Whether restoration succeeded. */
    bool restore(token_type value) const noexcept {
      return value ? SetThreadToken(nullptr, value) != FALSE : RevertToSelf() != FALSE;
    }

    /** @brief Release an owned prior token. @param value Prior token, possibly null. */
    void close(token_type value) const noexcept {
      if (value) {
        CloseHandle(value);
      }
    }

    /** @brief Never reuse an HTTP or input thread with an unknown security identity. */
    [[noreturn]] void fail_closed() const noexcept {
      RaiseFailFastException(nullptr, nullptr, 0);
      std::terminate();
    }
  };

  /** @brief Get the current thread's capture token cache. @return Thread-owned backend. */
  inline backend_t &backend() {
    thread_local backend_t value;
    return value;
  }

  /** @brief User context limited to a stack-local capture operation. */
  struct scope_t: rhremote::capture_context::scope_t<backend_t> {
    /** @brief Preserve SYSTEM for every operation outside this scope. */
    scope_t():
        rhremote::capture_context::scope_t<backend_t>(platf::capture_context::backend()) {
      ++platf::capture_context::backend().scopes;
    }

    /** @brief Leave nesting before the outer restoration scope restores identity. */
    ~scope_t() {
      --platf::capture_context::backend().scopes;
    }
  };
}  // namespace platf::capture_context
