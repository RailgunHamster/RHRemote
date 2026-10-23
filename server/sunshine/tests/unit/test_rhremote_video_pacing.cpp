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
    for (double configured : {-1., std::numeric_limits<double>::quiet_NaN(), std::numeric_limits<double>::infinity(), std::numeric_limits<double>::denorm_min()}) {
      require(rhremote::minimum_refresh_target(configured, 30) == 15);
    }
    require(rhremote::minimum_refresh_target(60, 0) == 1);
    require(rhremote::minimum_refresh_target(0, -1) == .5);
  }

  /** @brief Nine milliseconds of encoding must consume part of a 30 FPS interval. */
  void include_encode_cost() {
    using budget = rhremote::refresh_budget;
    using namespace std::chrono_literals;
    budget pacing {30};
    const auto origin = budget::clock::time_point {1s};
    const double interval = 1000.0 / 30;
    require(std::abs(pacing.remaining(origin).count() - interval) < 1e-6);
    pacing.submitted(origin);
    require(std::abs(pacing.remaining(origin + 9ms).count() - (interval - 9)) < 1e-6);
    require(pacing.remaining(origin + 40ms) == budget::duration::zero());
    require(std::abs(pacing.remaining(origin - 1ms).count() - interval) < 1e-6);
  }

  /** @brief Variable encoder cost must not lengthen every idle refresh period. */
  void keep_idle_cadence() {
    using budget = rhremote::refresh_budget;
    using namespace std::chrono_literals;
    budget pacing {30};
    auto submitted = budget::clock::time_point {1s};
    const auto start = submitted;
    for (int frame = 0; frame < 300; ++frame) {
      pacing.submitted(submitted);
      const auto work = std::chrono::milliseconds {5 + frame % 20};
      const auto wait = pacing.remaining(submitted + work);
      require(std::abs((budget::duration(work) + wait).count() - 1000.0 / 30) < 1e-6);
      submitted += std::chrono::duration_cast<budget::clock::duration>(budget::duration(work) + wait);
    }
    require(std::abs(budget::duration(submitted - start).count() - 10000) < .001);
  }

  /** @brief An overrun admits one immediate frame, then resets the budget instead of bursting catch-up work. */
  void overrun_does_not_accumulate() {
    using budget = rhremote::refresh_budget;
    using namespace std::chrono_literals;
    budget pacing {60};
    const auto first = budget::clock::time_point {1s};
    pacing.submitted(first);
    require(pacing.remaining(first + 100ms) == budget::duration::zero());
    pacing.submitted(first + 100ms);
    require(std::abs(pacing.remaining(first + 101ms).count() - (1000.0 / 60 - 1)) < 1e-6);
  }

  /** @brief A bad target cannot silently create an infinite wait or a busy loop. */
  void reject_invalid_budgets() {
    for (double rate : {0., -1., std::numeric_limits<double>::quiet_NaN(), std::numeric_limits<double>::infinity(), std::numeric_limits<double>::denorm_min()}) {
      bool rejected = false;
      try {
        rhremote::refresh_budget pacing {rate};
      } catch (const std::invalid_argument &) {
        rejected = true;
      }
      require(rejected);
    }
  }
}  // namespace

#ifdef RHREMOTE_VIDEO_PACING_STANDALONE
int main() {
  preserve_lower_floors();
  clamp_excessive_floors();
  bound_invalid_inputs();
  include_encode_cost();
  keep_idle_cadence();
  overrun_does_not_accumulate();
  reject_invalid_budgets();
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

TEST(RhRemoteVideoPacing, IncludeEncodeCost) {
  include_encode_cost();
}

TEST(RhRemoteVideoPacing, KeepIdleCadence) {
  keep_idle_cadence();
}

TEST(RhRemoteVideoPacing, OverrunDoesNotAccumulate) {
  overrun_does_not_accumulate();
}

TEST(RhRemoteVideoPacing, RejectInvalidBudgets) {
  reject_invalid_budgets();
}
#endif
