/**
 * @file tests/unit/test_rhremote_video_pacing.cpp
 * @brief Regress per-client refresh bounds independently of capture hardware.
 */
#include "src/rhremote_video_pacing.h"

#include <cstdlib>
#include <limits>
#ifndef RHREMOTE_VIDEO_PACING_STANDALONE
  #include <gtest/gtest.h>
#endif

namespace {
  /** @brief Enforce the policy in standalone and GoogleTest runs. @param value Required invariant. */
  void require(bool value) {
    if (!value) {
      std::abort();
    }
  }

  /** @brief Preserve configured floors within each client's requested range. */
  void preserve_lower_floors() {
    require(rhremote::minimum_refresh_target(0, 30) == 15);
    require(rhremote::minimum_refresh_target(0, 60) == 30);
    require(rhremote::minimum_refresh_target(0, 59) == 29.5);
    require(rhremote::minimum_refresh_target(20, 30) == 20);
    require(rhremote::minimum_refresh_target(60, 60) == 60);
    require(rhremote::minimum_refresh_target(60, 120) == 60);
  }

  /** @brief A global 60 FPS refresh floor must not create 60 FPS work for a 30 FPS client. */
  void clamp_excessive_floors() {
    for (int requested : {24, 30, 60, 120}) {
      require(rhremote::minimum_refresh_target(1000, requested) == requested);
    }
    require(rhremote::minimum_refresh_target(60, 30) == 30);
    require(rhremote::minimum_refresh_target(60, 24) == 24);
  }

  /** @brief Invalid or unbounded configuration cannot cause zero/NaN frame waits. */
  void bound_invalid_inputs() {
    for (double configured : {-1., std::numeric_limits<double>::quiet_NaN(), std::numeric_limits<double>::infinity()}) {
      require(rhremote::minimum_refresh_target(configured, 30) == 15);
    }
    require(rhremote::minimum_refresh_target(60, 0) == 1);
    require(rhremote::minimum_refresh_target(0, -1) == .5);
  }
}  // namespace

#ifdef RHREMOTE_VIDEO_PACING_STANDALONE
int main() {
  preserve_lower_floors();
  clamp_excessive_floors();
  bound_invalid_inputs();
}
#else
TEST(RhRemoteVideoPacing, PreserveLowerFloors) {
  preserve_lower_floors();
}

TEST(RhRemoteVideoPacing, ClampExcessiveFloors) {
  clamp_excessive_floors();
}

TEST(RhRemoteVideoPacing, BoundInvalidInputs) {
  bound_invalid_inputs();
}
#endif
