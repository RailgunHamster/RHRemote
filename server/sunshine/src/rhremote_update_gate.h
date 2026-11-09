/**
 * @file src/rhremote_update_gate.h
 * @brief Atomically hold new launches while observing every already admitted request.
 */
#pragma once
#include <algorithm>
#include <chrono>
#include <memory>
#include <mutex>
#include <optional>

namespace rhremote {
  /** @brief Admission accounting shared with leases that may outlive an HTTP callback. */
  class update_gate {
    using clock = std::chrono::steady_clock;  ///< Monotonic activity clock.

    /** @brief State survives until the last admitted request retires. */
    struct state_t {
      std::mutex mutex;  ///< Orders protected-file reads against admission/snapshot.
      std::size_t preparing = 0;  ///< New and legacy launch requests, including queued ones.
      std::optional<clock::time_point> last;  ///< Last accepted launch/resume.
    };

    std::shared_ptr<state_t> state = std::make_shared<state_t>();  ///< Owned shared state.

  public:
    /** @brief One admitted HTTP preparation, never an input or frame lock. */
    class lease_t {
      friend class update_gate;
      std::shared_ptr<state_t> state;  ///< Owner's shared accounting.
      bool counted = false;  ///< Publication completed successfully.

      /** @brief Allocate before changing counters. @param value Shared state. */
      explicit lease_t(std::shared_ptr<state_t> value):
          state(std::move(value)) {}

    public:
      /** @brief Release admission only after its queued/running callback retires. */
      ~lease_t() {
        if (counted) {
          std::lock_guard lock(state->mutex);
          --state->preparing;
        }
      }
    };

    /** @brief Update readiness without credentials or window metadata. */
    struct snapshot_t {
      bool held;  ///< New launches are currently held.
      std::size_t preparing;  ///< Already admitted legacy or modern launches.
      long long quiet_ms;  ///< Time since admission; startup is already quiet.
    };

    /** @brief Admit while reading the current protected hold file under this lock. @param held Protected hold reader. @param now Monotonic time. @return Owned lease, or empty during maintenance. */
    template<class Held>
    std::shared_ptr<lease_t> begin(Held held, clock::time_point now = clock::now()) {
      auto lease = std::shared_ptr<lease_t>(new lease_t(state));
      std::lock_guard lock(state->mutex);
      if (held()) {
        return {};
      }
      ++state->preparing;
      state->last = now;
      lease->counted = true;
      return lease;
    }

    /** @brief Observe accounting after the same ordered protected hold read. @param held Protected hold reader. @param now Monotonic time. @return Admission state and quiet duration. */
    template<class Held>
    snapshot_t snapshot(Held held, clock::time_point now = clock::now()) {
      std::lock_guard lock(state->mutex);
      const auto quiet = state->last ? std::max(0LL, static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(now - *state->last).count())) : 600000LL;
      return {held(), state->preparing, quiet};
    }
  };
}  // namespace rhremote
