/**
 * @file tests/unit/test_rhremote_capture_context.cpp
 * @brief Capture cannot leave an HTTP/input thread impersonating another user.
 */
#include "src/rhremote_capture_context.h"

#include <gtest/gtest.h>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
  /** @brief Thread-token fixture that never changes OS identity. */
  struct backend_t {
    using token_type = int;  ///< Opaque fixture token.
    bool system = true, reject_user = false, reject_impersonation = false, reject_restore = false, failed_closed = false;  ///< Failure controls.
    int original = 0, current = 0;  ///< Incoming and effective thread identity.
    std::vector<int> closed;  ///< Owned previous tokens released by the scope.

    /** @brief Whether the fixture represents SYSTEM. @return Required scope state. */
    bool required() {
      return system;
    }

    /** @brief Return an owned snapshot of the incoming thread. @return Previous identity. */
    int previous() {
      return original;
    }

    /** @brief Lend a user token, or fail before identity changes. @return User token. */
    int user() {
      if (reject_user) {
        throw std::runtime_error("no user");
      }
      return 42;
    }

    /** @brief Apply the user token. @param value Borrowed user token. */
    void impersonate(int value) {
      if (reject_impersonation) {
        throw std::runtime_error("rejected");
      }
      current = value;
    }

    /** @brief Restore the exact original thread. @param value Original token. @return Whether restoration succeeded. */
    bool restore(int value) {
      if (reject_restore) {
        return false;
      }
      current = value;
      return true;
    }

    /** @brief Record owned-token closure. @param value Original token. */
    void close(int value) {
      closed.push_back(value);
    }

    /** @brief Observe the fail-closed branch without terminating the test. */
    void fail_closed() {
      failed_closed = true;
    }
  };
}  // namespace

TEST(CaptureUserContext, UserProcessesDoNotRequestOrReplaceAToken) {
  backend_t backend;
  backend.system = false;
  backend.reject_user = true;
  backend.current = 7;
  {
    rhremote::capture_context::scope_t scope(backend);
    EXPECT_EQ(backend.current, 7);
  }
  EXPECT_EQ(backend.current, 7);
  EXPECT_TRUE(backend.closed.empty());
}

TEST(CaptureUserContext, SystemThreadsReturnToTheirExactIncomingIdentity) {
  for (int original : {0, 11}) {
    backend_t backend;
    backend.original = backend.current = original;
    {
      rhremote::capture_context::scope_t scope(backend);
      EXPECT_EQ(backend.current, 42);
    }
    EXPECT_EQ(backend.current, original);
    EXPECT_EQ(backend.closed, std::vector<int> {original});
  }
}

TEST(CaptureUserContext, FailedUserLookupOrImpersonationClosesThePriorToken) {
  for (bool lookup : {false, true}) {
    backend_t backend;
    backend.original = backend.current = 11;
    backend.reject_user = lookup;
    backend.reject_impersonation = !lookup;
    EXPECT_THROW({ rhremote::capture_context::scope_t scope(backend); }, std::runtime_error);
    EXPECT_EQ(backend.current, 11);
    EXPECT_EQ(backend.closed, std::vector<int> {11});
  }
}

TEST(CaptureUserContext, ExceptionsCannotLeakTheUserIdentityIntoOtherWork) {
  backend_t backend;
  EXPECT_THROW({ rhremote::capture_context::scope_t scope(backend); throw std::runtime_error("capture failed"); }, std::runtime_error);
  EXPECT_EQ(backend.current, 0);
  EXPECT_EQ(backend.closed, std::vector<int> {0});
}

TEST(CaptureUserContext, RestorationFailureCannotSilentlyReuseTheWorkerThread) {
  backend_t backend;
  backend.reject_restore = true;
  { rhremote::capture_context::scope_t scope(backend); }
  EXPECT_TRUE(backend.failed_closed);
  EXPECT_EQ(backend.closed, std::vector<int> {0});
}
