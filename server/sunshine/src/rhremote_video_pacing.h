/**
 * @file src/rhremote_video_pacing.h
 * @brief Bound static-content refresh work to the client-requested frame rate.
 */
#pragma once

#include <algorithm>
#include <chrono>
#include <cmath>
#include <optional>
#include <stdexcept>

namespace rhremote {
  /**
   * @brief Keep a host-wide refresh floor from exceeding a lower-rate client request.
   * @param configured_target Host minimum refresh target; zero selects half the requested rate.
   * @param requested_fps Client-requested frame rate.
   * @return Positive bounded refresh target. This does not measure unique pictures or scanout.
   */
  inline double minimum_refresh_target(double configured_target, int requested_fps) {
    const double ceiling = std::max(1, requested_fps);
    const double target = std::isfinite(configured_target) && configured_target > 0 && std::isfinite(1000.0 / configured_target) ? configured_target : ceiling / 2.0;
    return std::min(target, ceiling);
  }

  /** @brief Budget the whole idle-frame interval, including encoding and other loop work. */
  class refresh_budget {
  public:
    using clock = std::chrono::steady_clock;  ///< Monotonic submission clock.
    using duration = std::chrono::duration<double, std::milli>;  ///< Fractional frame-wait precision.

    /** @brief Set the idle refresh interval. @param target Positive finite target from minimum_refresh_target(). */
    explicit refresh_budget(double target):
        period_(checked_period(target)) {
    }

    /** @brief Record an actual encode call, without accumulating missed deadlines. @param now Encode submission time. */
    void submitted(clock::time_point now) {
      last_submission_ = now;
    }

    /** @brief Get only the unused interval; captured images may still wake the queue immediately. @param now Current time. @return Nonnegative wait no longer than one interval. */
    duration remaining(clock::time_point now) const {
      if (!last_submission_) {
        return period_;
      }
      return std::clamp(period_ - duration(now - *last_submission_), duration::zero(), period_);
    }

  private:
    /** @brief Reject invalid targets before constructing a wait. @param target Requested refresh target. @return Interval in milliseconds. */
    static duration checked_period(double target) {
      if (!std::isfinite(target) || target <= 0) {
        throw std::invalid_argument("Refresh target must be positive and finite");
      }
      const auto milliseconds = 1000.0 / target;
      if (!std::isfinite(milliseconds)) {
        throw std::invalid_argument("Refresh interval must be finite");
      }
      return duration {milliseconds};
    }

    duration period_;  ///< Full requested interval, not a delay added after every encode.
    std::optional<clock::time_point> last_submission_;  ///< Most recent actual submission, if any.
  };
}  // namespace rhremote
