/**
 * @file tests/unit/test_rhremote_pending_launch.cpp
 * @brief Exercise real request ownership, expiry, cancellation and handoff races without a GPU.
 */
#include "src/rhremote_pending_launch.h"

#include <barrier>
#include <cstdlib>
#include <iostream>
#include <source_location>
#include <thread>
#ifndef RHREMOTE_PENDING_LAUNCH_STANDALONE
  #include <gtest/gtest.h>
#endif

namespace {
  /** @brief Minimal upstream session shape used by the actual registry. */
  struct fake_session {
    std::uint32_t id;  ///< Server-assigned session identifier.
    std::string client_cert;  ///< Authenticated certificate identity.
  };

  using registry = rhremote::pending_launches<fake_session>;  ///< Production registry with fake session data only.
  using result = rhremote::launch_result;  ///< Non-sensitive lifecycle result.
  using namespace std::chrono_literals;
  const auto origin = registry::clock::time_point {1s};  ///< Deterministic monotonic origin.

  /** @brief Preserve checks in release builds and report the failing line. @param value Required invariant. @param location Caller location. */
  void require(bool value, std::source_location location = std::source_location::current()) {
    if (!value) {
      std::cerr << location.file_name() << ':' << location.line() << '\n';
      std::abort();
    }
  }

  /** @brief Generate distinct valid fixture credentials. @param c Hexadecimal fixture digit. @return Non-production fixture credential. */
  std::string key(char c) {
    return std::string(64, c);
  }

  /** @brief Create fake session data. @param id Server identifier. @param owner Canonical certificate fixture. @return Shared session. */
  auto session(std::uint32_t id, std::string owner = "owner-A") {
    return std::make_shared<fake_session>(fake_session {id, std::move(owner)});
  }

  /** @brief A secret with the wrong authenticated identity cannot cancel another client. */
  void validate_and_bind_owner() {
    registry slots;
    for (const auto &bad : {std::string {}, key('A'), key('g'), std::string(63, 'a'), key('a') + "&next=1"}) {
      require(slots.begin(bad, "owner-A", origin).result == result::invalid);
      require(slots.cancel(bad, "owner-A", origin) == result::invalid);
    }
    require(slots.begin(key('a'), "", origin).result == result::invalid);
    require(slots.cancel(key('a'), "", origin) == result::invalid);
    require(slots.begin(key('a'), std::string(65537, 'x'), origin).result == result::invalid);
    const auto a = slots.begin(key('a'), "owner-A", origin);
    require(a.result == result::accepted && slots.preparing(a.ticket, origin));
    require(slots.begin(key('a'), "owner-B", origin).result == result::forbidden);
    require(slots.begin(key('a'), "owner-A", origin).result == result::duplicate);
    require(slots.cancel(key('a'), "owner-B", origin) == result::forbidden);
    require(slots.preparing(a.ticket, origin));
    require(!slots.publish(session(1, "owner-B"), a.ticket, 10s, origin));
    require(!slots.publish({}, a.ticket, 10s, origin));
    require(!slots.publish(session(0), a.ticket, 10s, origin));
    require(!slots.publish(session(1), a.ticket, 0s, origin));
    require(slots.publish(session(1), a.ticket, 10s, origin));
    require(slots.cancel(key('a'), "owner-B", origin) == result::forbidden);
    require(slots.pending(origin)->id == 1);
  }

  /** @brief Both cancel-before-register and cancel-during-preparation prevent late publication. */
  void cancel_before_publication() {
    registry slots;
    require(slots.cancel(key('a'), "owner-A", origin) == result::cancelled);
    require(slots.begin(key('a'), "owner-A", origin).result == result::cancelled);
    require(slots.begin(key('a'), "owner-B", origin).result == result::forbidden);
    const auto b = slots.begin(key('b'), "owner-A", origin);
    require(b.result == result::accepted);
    require(slots.cancel(key('b'), "owner-A", origin) == result::cancelled);
    require(!slots.preparing(b.ticket, origin));
    require(!slots.publish(session(2), b.ticket, 10s, origin));
    slots.finish_preparing(b.ticket, origin);
    require(slots.cancel(key('b'), "owner-A", origin) == result::cancelled);
    require(!slots.pending(origin));
  }

  /** @brief An accepted HTTP request can be removed before RTSP, allowing immediate retry. */
  void cancel_published_and_retry() {
    registry slots;
    const auto a = slots.begin(key('a'), "owner-A", origin);
    const auto old = session(1);
    require(slots.publish(old, a.ticket, 10s, origin));
    require(slots.pending(origin) == old);
    require(slots.cancel(key('a'), "owner-A", origin + 1ms) == result::cancelled);
    require(!slots.pending(origin + 1ms));
    require(!slots.claim(*old, origin + 1ms));
    const auto b = slots.begin(key('b'), "owner-A", origin + 2ms);
    const auto next = session(2);
    require(slots.publish(next, b.ticket, 10s, origin + 2ms));
    require(slots.cancel(key('a'), "owner-A", origin + 3ms) == result::cancelled);
    require(!slots.clear(1, origin + 3ms));
    require(!slots.claim(*old, origin + 3ms));
    require(slots.pending(origin + 3ms) == next);
    require(slots.claim(*next, origin + 3ms));
  }

  /** @brief Handoff wins once; a late request cancellation cannot stop an allocated stream. */
  void handoff_is_irrevocable_for_request_cancel() {
    registry slots;
    const auto a = slots.begin(key('a'), "owner-A", origin);
    const auto value = session(1);
    require(slots.publish(value, a.ticket, 10s, origin));
    require(slots.claim(*value, origin + 1ms));
    require(!slots.claim(*value, origin + 2ms));
    require(slots.cancel(key('a'), "owner-A", origin + 3ms) == result::handed_off);
    require(slots.pending(origin + 3ms) == value);
    require(slots.clear(1, origin + 4ms));
    require(slots.cancel(key('a'), "owner-A", origin + 5ms) == result::finished);
    require(!slots.pending(origin + 5ms));
    const auto failed = slots.begin(key('b'), "owner-A", origin + 6ms);
    slots.finish_preparing(failed.ticket, origin + 7ms);
    require(!slots.preparing(failed.ticket, origin + 8ms));
    require(slots.cancel(key('b'), "owner-A", origin + 8ms) == result::finished);
    slots.finish_preparing({}, origin + 8ms);
  }

  /** @brief Expiry is evaluated atomically; there is no old timer callback able to remove a new slot. */
  void expire_and_replace() {
    registry slots;
    const auto a = slots.begin(key('a'), "owner-A", origin);
    auto old = session(1);
    require(slots.publish(old, a.ticket, 10s, origin));
    require(!slots.pending(origin + 10s));
    require(slots.cancel(key('a'), "owner-A", origin + 10s) == result::expired);
    const auto b = slots.begin(key('b'), "owner-B", origin + 10s);
    const auto next = session(2, "owner-B");
    require(slots.publish(next, b.ticket, 10s, origin + 10s));
    require(!slots.clear(1, origin + 11s));
    require(!slots.claim(*old, origin + 11s));
    require(slots.pending(origin + 11s) == next);
    // Even reuse of the 32-bit upstream ID does not admit a stale socket pointer.
    old->id = next->id;
    require(!slots.claim(*old, origin + 11s));
  }

  /** @brief Bound history, prune it, and reject a stale preparation handle after record replacement. */
  void bounded_history_and_stale_handles() {
    registry slots {2, 1s};
    const auto a = slots.begin(key('a'), "owner-A", origin);
    require(slots.cancel(key('b'), "owner-B", origin) == result::cancelled);
    require(slots.begin(key('c'), "owner-C", origin).result == result::capacity);
    require(slots.cancel(key('c'), "owner-C", origin) == result::capacity);
    const auto replacement = slots.begin(key('a'), "owner-A", origin + 2s);
    require(replacement.result == result::accepted);
    require(!slots.preparing(a.ticket, origin + 2s));
    slots.finish_preparing(a.ticket, origin + 2s);
    require(slots.preparing(replacement.ticket, origin + 2s));
    require(!slots.publish(session(1), a.ticket, 10s, origin + 2s));
    require(slots.publish(session(2), replacement.ticket, 10s, origin + 2s));
    require(slots.pending(origin + 4s)->id == 2);
    registry other;
    require(!other.preparing(replacement.ticket, origin + 4s));
    require(!other.publish(session(9), replacement.ticket, 10s, origin + 4s));
    registry zero {0};
    require(zero.begin(key('a'), "owner-A", origin).result == result::capacity);
  }

  /** @brief A credential cancellation never removes a legacy client's unrelated slot. */
  void preserve_legacy_clients() {
    registry slots;
    const auto legacy = session(1);
    require(slots.preparing({}, origin));
    require(slots.publish(legacy, {}, 10s, origin));
    const auto a = slots.begin(key('a'), "owner-A", origin);
    require(!slots.publish(session(2), a.ticket, 10s, origin));
    require(slots.cancel(key('a'), "owner-A", origin) == result::cancelled);
    require(slots.pending(origin) == legacy);
    require(slots.claim(*legacy, origin));
    require(slots.clear(1, origin));
    require(!slots.clear(1, origin));
  }

  /** @brief Revocation and explicit shutdown prevent delayed publication without touching another owner. */
  void revoke_and_shutdown() {
    registry slots;
    const auto a = slots.begin(key('a'), "owner-A", origin);
    const auto b = slots.begin(key('b'), "owner-B", origin);
    require(slots.publish(session(1), a.ticket, 10s, origin));
    slots.revoke("owner-A", origin);
    require(!slots.pending(origin));
    require(!slots.preparing(a.ticket, origin));
    require(slots.preparing(b.ticket, origin));
    require(slots.publish(session(2, "owner-B"), b.ticket, 10s, origin));
    slots.reset(false, origin);
    require(!slots.pending(origin));
    require(slots.begin(key('c'), "owner-C", origin).result == result::accepted);
    require(slots.publish(session(3), {}, 10s, origin));
    slots.revoke("owner-A", origin);
    require(!slots.pending(origin));
    slots.reset(true, origin);
    require(!slots.preparing({}, origin));
    require(!slots.publish(session(4), {}, 10s, origin));
    require(slots.begin(key('d'), "owner-D", origin).result == result::closed);
    require(slots.cancel(key('d'), "owner-D", origin) == result::closed);
  }

  /** @brief Actual concurrent cancellation and ANNOUNCE have exactly one ownership winner. */
  void race_cancel_and_claim() {
    for (int iteration = 0; iteration < 200; ++iteration) {
      registry slots;
      const auto a = slots.begin(key('a'), "owner-A", origin);
      const auto value = session(1);
      require(slots.publish(value, a.ticket, 10s, origin));
      std::barrier start {3};
      bool claimed = false;
      result cancelled = result::invalid;
      std::thread claimant {[&] {
        start.arrive_and_wait();
        claimed = slots.claim(*value, origin);
      }};
      std::thread canceller {[&] {
        start.arrive_and_wait();
        cancelled = slots.cancel(key('a'), "owner-A", origin);
      }};
      start.arrive_and_wait();
      claimant.join();
      canceller.join();
      require(claimed ? cancelled == result::handed_off : cancelled == result::cancelled);
      require(bool(slots.pending(origin)) == claimed);
    }
  }

  /** @brief Cancellation racing HTTP publication cannot leave a cancelled slot available to RTSP. */
  void race_cancel_and_publish() {
    for (int iteration = 0; iteration < 200; ++iteration) {
      registry slots;
      const auto a = slots.begin(key('a'), "owner-A", origin);
      std::barrier start {3};
      std::thread publisher {[&] {
        start.arrive_and_wait();
        slots.publish(session(1), a.ticket, 10s, origin);
      }};
      std::thread canceller {[&] {
        start.arrive_and_wait();
        require(slots.cancel(key('a'), "owner-A", origin) == result::cancelled);
      }};
      start.arrive_and_wait();
      publisher.join();
      canceller.join();
      require(!slots.pending(origin));
      require(!slots.preparing(a.ticket, origin));
    }
  }
}  // namespace

#ifdef RHREMOTE_PENDING_LAUNCH_STANDALONE
int main() {
  validate_and_bind_owner();
  cancel_before_publication();
  cancel_published_and_retry();
  handoff_is_irrevocable_for_request_cancel();
  expire_and_replace();
  bounded_history_and_stale_handles();
  preserve_legacy_clients();
  revoke_and_shutdown();
  race_cancel_and_claim();
  race_cancel_and_publish();
}
#else
TEST(RhRemotePendingLaunch, ValidateAndBindOwner) {
  validate_and_bind_owner();
}

TEST(RhRemotePendingLaunch, CancelBeforePublication) {
  cancel_before_publication();
}

TEST(RhRemotePendingLaunch, CancelPublishedAndRetry) {
  cancel_published_and_retry();
}

TEST(RhRemotePendingLaunch, HandoffIsIrrevocable) {
  handoff_is_irrevocable_for_request_cancel();
}

TEST(RhRemotePendingLaunch, ExpireAndReplace) {
  expire_and_replace();
}

TEST(RhRemotePendingLaunch, BoundHistoryAndStaleHandles) {
  bounded_history_and_stale_handles();
}

TEST(RhRemotePendingLaunch, PreserveLegacyClients) {
  preserve_legacy_clients();
}

TEST(RhRemotePendingLaunch, RevokeAndShutdown) {
  revoke_and_shutdown();
}

TEST(RhRemotePendingLaunch, RaceCancelAndClaim) {
  race_cancel_and_claim();
}

TEST(RhRemotePendingLaunch, RaceCancelAndPublish) {
  race_cancel_and_publish();
}
#endif
