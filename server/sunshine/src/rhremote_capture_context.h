/**
 * @file src/rhremote_capture_context.h
 * @brief Restore the exact thread identity around user-bound capture operations.
 */
#pragma once

namespace rhremote::capture_context {
  /**
   * @brief Scope an impersonation without changing the server process identity.
   * @tparam Backend Thread-token operations; user() lends a cached token.
   */
  template<class Backend>
  class scope_t {
    Backend &backend;  ///< Platform operations, owned by the current thread.
    typename Backend::token_type previous {};  ///< Owned prior thread token, or an empty token.
    bool changed = false;  ///< Whether this scope successfully changed identity.

  public:
    /** @brief Enter the user context only when the host runs as SYSTEM. @param value Current thread's backend. */
    explicit scope_t(Backend &value):
        backend(value) {
      if (!backend.required()) {
        return;
      }
      previous = backend.previous();
      try {
        backend.impersonate(backend.user());
        changed = true;
      } catch (...) {
        backend.close(previous);
        throw;
      }
    }

    /** @brief Restore an existing thread token, or remove only our impersonation. */
    ~scope_t() {
      if (changed) {
        if (!backend.restore(previous)) {
          backend.fail_closed();
        }
        backend.close(previous);
      }
    }

    scope_t(const scope_t &) = delete;
    scope_t &operator=(const scope_t &) = delete;
  };
}  // namespace rhremote::capture_context
