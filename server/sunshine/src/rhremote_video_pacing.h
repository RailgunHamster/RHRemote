/**
 * @file src/rhremote_video_pacing.h
 * @brief Bound static-content refresh work to the client-requested frame rate.
 */
#pragma once

#include <algorithm>
#include <cmath>

namespace rhremote {
  /**
   * @brief Keep a host-wide refresh floor from exceeding a lower-rate client request.
   * @param configured_target Host minimum refresh target; zero selects half the requested rate.
   * @param requested_fps Client-requested frame rate.
   * @return Positive bounded refresh target. This does not measure unique pictures or scanout.
   */
  inline double minimum_refresh_target(double configured_target, int requested_fps) {
    const double ceiling = std::max(1, requested_fps);
    const double target = std::isfinite(configured_target) && configured_target > 0 ? configured_target : ceiling / 2.0;
    return std::min(target, ceiling);
  }
}  // namespace rhremote
