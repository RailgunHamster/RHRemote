/**
 * @file tests/unit/test_rhremote_encoder_probe.cpp
 * @brief Verify capability reuse boundaries and original-device health ownership.
 */
#include "src/rhremote_encoder_probe.h"

#include <cstdlib>
#include <memory>
#ifndef RHREMOTE_ENCODER_PROBE_STANDALONE
  #include <gtest/gtest.h>
#endif

namespace {
  using namespace rhremote;
  using decision = encoder_probe_decision;
  using health = probe_device_health;
  static_assert(keeps_encoder_probe_health("software"));
  static_assert(keeps_encoder_probe_health("quicksync"));
  static_assert(!keeps_encoder_probe_health(""));
  static_assert(!keeps_encoder_probe_health("mediafoundation"));

  /** @brief Fail a standalone or GoogleTest run on the same invariant. @param value Required condition. */
  void require(bool value) {
    if (!value) {
      std::abort();
    }
  }

  /** @brief Create complete synthetic metadata. @param width Pixel width. @param height Pixel height. @return Fresh environment. */
  encoder_environment environment(std::uint32_t width = 2520, std::uint32_t height = 1680) {
    return {{1, 2, 3, 4, 5}, {{1920, 1080}, {width, height}}, {{10, 20}, {11, 21}}};
  }

  /** @brief Initial and failed observations never authorize reuse. */
  void incomplete_observations() {
    encoder_probe_cache cache;
    require(cache.observe(true, true, environment(), health::healthy) == decision::required);
    require(cache.observe(true, true, {}, health::healthy) == decision::required);
    require(cache.observe(true, true, environment(), health::healthy) == decision::required);
    require(cache.observe(false, true, environment(1920, 1080), health::healthy) == decision::mode_only);
    for (int field = 0; field < 3; ++field) {
      auto invalid = environment();
      if (field == 0) {
        invalid.invariant.clear();
      }
      if (field == 1) {
        invalid.modes.clear();
      }
      if (field == 2) {
        invalid.active_adapters.clear();
      }
      require(cache.observe(true, true, invalid, health::healthy) == decision::required);
      require(cache.observe(true, true, environment(), health::healthy) == decision::required);
    }
  }

  /** @brief Stale factories reuse only explicit software dimensions with healthy original devices. */
  void mode_only_and_repeated_changes() {
    encoder_probe_cache cache;
    require(cache.observe(false, false, environment(), health::unknown) == decision::required);
    for (auto size : {std::pair {1920U, 1080U}, {1680U, 2520U}, {3840U, 2160U}, {2520U, 1680U}}) {
      require(cache.observe(false, true, environment(size.first, size.second), health::healthy) == decision::mode_only);
      require(cache.observe(true, true, environment(size.first, size.second), health::healthy) == decision::current);
    }
    require(cache.observe(false, true, environment(), health::healthy) == decision::required);
  }

  /** @brief Hardware fallback, uncertainty, and TDR never use the software fast path. */
  void uncertain_or_lost_devices() {
    for (auto state : {health::unknown, health::lost}) {
      encoder_probe_cache cache;
      cache.observe(false, false, environment(), health::unknown);
      require(cache.observe(false, true, environment(1920, 1080), state) == decision::required);
    }
    encoder_probe_cache cache;
    cache.observe(false, false, environment(), health::unknown);
    require(cache.observe(false, false, environment(1920, 1080), health::healthy) == decision::required);
    require(cache.observe(true, true, environment(1920, 1080), health::lost) == decision::required);
    require(cache.observe(true, true, environment(1920, 1080), health::unknown) == decision::current);
  }

  /** @brief Every invariant and adapter identity must match, even if the old factory is still current. */
  void invariant_and_adapter_changes() {
    for (std::size_t field = 0; field < environment().invariant.size(); ++field) {
      encoder_probe_cache cache;
      cache.observe(false, false, environment(), health::unknown);
      auto next = environment(1920, 1080);
      ++next.invariant[field];
      require(cache.observe(true, true, next, health::healthy) == decision::required);
    }
    encoder_probe_cache cache;
    cache.observe(false, false, environment(), health::unknown);
    auto next = environment(1920, 1080);
    next.active_adapters[0].second++;
    require(cache.observe(false, true, next, health::healthy) == decision::required);
    next.active_adapters.pop_back();
    require(cache.observe(false, true, next, health::healthy) == decision::required);
  }

  /** @brief The original device is kept, so a later loss cannot be hidden by a newly created device. */
  void health_retains_original_and_replaces_lost_device() {
    encoder_device_health<std::shared_ptr<int>> devices;
    auto valid = [](const auto &device) {
      return *device > 0;
    };
    auto original = std::make_shared<int>(1);
    std::weak_ptr<int> retained = original;
    devices.remember({1, 1}, original, valid);
    devices.remember({1, 1}, std::make_shared<int>(2), valid);
    original.reset();
    require(!retained.expired());
    require(devices.state({{1, 1}}, valid) == health::healthy);
    *retained.lock() = -1;
    require(devices.state({{9, 9}, {1, 1}}, valid) == health::lost);
    devices.remember({1, 1}, std::make_shared<int>(3), valid);
    require(retained.expired());
    require(devices.state({{1, 1}}, valid) == health::healthy);
    require(devices.state({{1, 2}}, valid) == health::unknown);
    devices.remember({1, 2}, {}, valid);
    require(devices.state({{1, 1}, {1, 2}}, valid) == health::unknown);
    require(devices.state({}, valid) == health::unknown);
    devices.retain({{1, 2}});
    require(devices.state({{1, 1}}, valid) == health::unknown);
  }

  /** @brief Repeated adapter churn is bounded and pruning frees room for real current devices. */
  void health_is_bounded() {
    encoder_device_health<std::shared_ptr<int>> devices;
    auto valid = [](const auto &device) {
      return *device > 0;
    };
    for (std::uint32_t i = 0; i < 65; ++i) {
      devices.remember({i, 0}, std::make_shared<int>(1), valid);
    }
    require(devices.state({{63, 0}}, valid) == health::healthy);
    require(devices.state({{64, 0}}, valid) == health::unknown);
    devices.retain({{64, 0}});
    devices.remember({64, 0}, std::make_shared<int>(1), valid);
    require(devices.state({{64, 0}}, valid) == health::healthy);
  }

  /** @brief Observations alone and failed probes never authorize QSV reuse. */
  void qsv_requires_completed_validation() {
    validated_encoder_probe_cache cache;
    require(cache.required(false, environment(), health::unknown));
    require(cache.required(true, environment(), health::healthy));
    require(!cache.completed(false, environment(), health::healthy));
    require(cache.completion_reason() == "probe_failed");
    require(cache.required(true, environment(), health::healthy));
    require(cache.completed(true, environment(), health::healthy));
    require(cache.completion_reason() == "retained");
    require(!cache.required(true, environment(), health::healthy));
    require(!cache.required(true, environment(1920, 1080), health::healthy));
    require(cache.required(false, environment(), health::healthy));
    require(cache.required(true, environment(), health::healthy));
  }

  /** @brief Desktop, driver, selection, options and adapter changes need a new actual probe. */
  void qsv_invariants_and_modes() {
    for (std::size_t field = 0; field < environment().invariant.size(); ++field) {
      validated_encoder_probe_cache cache;
      require(cache.required(false, environment(), health::unknown));
      require(cache.completed(true, environment(), health::healthy));
      auto changed = environment(1920, 1080);
      ++changed.invariant[field];
      require(cache.required(true, changed, health::healthy));
      require(cache.completed(true, changed, health::healthy));
      require(!cache.required(true, changed, health::healthy));
    }
    validated_encoder_probe_cache cache;
    cache.required(false, environment(), health::unknown);
    require(cache.completed(true, environment(), health::healthy));
    auto changed = environment();
    changed.active_adapters[0].second++;
    require(cache.required(true, changed, health::healthy));
    require(cache.completed(true, changed, health::healthy));
    changed.active_adapters.pop_back();
    require(cache.required(true, changed, health::healthy));
    require(cache.completed(true, changed, health::healthy));
    changed.modes.pop_back();
    require(cache.required(true, changed, health::healthy));
  }

  /** @brief A mode or identity race during validation discards its result. */
  void qsv_rejects_probe_races() {
    for (int field = 0; field < 3; ++field) {
      validated_encoder_probe_cache cache;
      cache.required(false, environment(), health::unknown);
      auto changed = environment();
      if (field == 0) {
        ++changed.modes[0].first;
      } else if (field == 1) {
        ++changed.invariant[0];
      } else {
        ++changed.active_adapters[0].first;
      }
      require(!cache.completed(true, changed, health::healthy));
      require(cache.completion_reason() == "environment_changed_during_probe");
      require(cache.required(true, changed, health::healthy));
    }
    validated_encoder_probe_cache cache;
    require(!cache.completed(true, environment(), health::healthy));
    require(cache.completion_reason() == "before_metadata_incomplete");
    require(cache.required(true, environment(), health::healthy));
  }

  /** @brief Unknown metadata or original device loss cannot reuse QSV capabilities. */
  void qsv_requires_complete_healthy_state() {
    for (auto state : {health::unknown, health::lost}) {
      validated_encoder_probe_cache cache;
      cache.required(false, environment(), health::unknown);
      require(!cache.completed(true, environment(), state));
      require(cache.completion_reason() == (state == health::lost ? "original_device_lost" : "original_device_health_unknown"));
      cache.required(false, environment(), health::unknown);
      require(cache.completed(true, environment(), health::healthy));
      require(cache.required(true, environment(), state));
      require(cache.required(true, environment(), health::healthy));
    }
    for (int field = 0; field < 4; ++field) {
      validated_encoder_probe_cache cache;
      cache.required(false, environment(), health::unknown);
      require(cache.completed(true, environment(), health::healthy));
      std::optional<encoder_environment> invalid = environment();
      if (field == 0) {
        invalid.reset();
      } else if (field == 1) {
        invalid->invariant.clear();
      } else if (field == 2) {
        invalid->modes.clear();
      } else {
        invalid->active_adapters.clear();
      }
      require(cache.required(true, invalid, health::healthy));
      require(!cache.completed(true, invalid, health::healthy));
      require(cache.completion_reason() == "before_metadata_incomplete");
      cache.required(false, environment(), health::unknown);
      require(!cache.completed(true, invalid, health::healthy));
      require(cache.completion_reason() == "after_metadata_incomplete");
      require(cache.required(true, environment(), health::healthy));
    }
    validated_encoder_probe_cache cache;
    cache.required(false, environment(), health::unknown);
    require(cache.completed(true, environment(), health::healthy));
    cache.clear();
    require(cache.completion_reason() == "not_validated");
    require(cache.required(true, environment(), health::healthy));
  }

  /** @brief Actual creation-policy registration must provide the original device health needed by a completed QSV probe. */
  void qsv_device_health_registration() {
    for (auto backend : {"quicksync", "software"}) {
      encoder_device_health<std::shared_ptr<int>> devices;
      validated_encoder_probe_cache cache;
      cache.required(false, environment(), health::unknown);
      std::weak_ptr<int> original;
      auto healthy = [](const auto &device) {
        return *device > 0;
      };
      for (const auto &adapter : environment().active_adapters) {
        auto created = std::make_shared<int>(1);
        original = created;
        // The same policy is called by the real D3D creation callback.
        if (keeps_encoder_probe_health(backend)) {
          devices.remember(adapter, created, healthy);
        }
      }
      require(!original.expired());
      require(devices.state(environment().active_adapters, healthy) == health::healthy);
      require(cache.completed(true, environment(), devices.state(environment().active_adapters, healthy)));
      require(!cache.required(true, environment(1920, 1080), devices.state(environment().active_adapters, healthy)));
      *original.lock() = -1;
      require(cache.required(true, environment(), devices.state(environment().active_adapters, healthy)));
    }
    for (auto backend : {"", "auto", "nvenc", "mediafoundation", "quicksync-unknown"}) {
      require(!keeps_encoder_probe_health(backend));
    }
  }
}  // namespace

#ifdef RHREMOTE_ENCODER_PROBE_STANDALONE
/** @brief Run the same fault and ownership cases without a GPU or GoogleTest dependency. @return Zero on success. */
int main() {
  incomplete_observations();
  mode_only_and_repeated_changes();
  uncertain_or_lost_devices();
  invariant_and_adapter_changes();
  health_retains_original_and_replaces_lost_device();
  health_is_bounded();
  qsv_requires_completed_validation();
  qsv_invariants_and_modes();
  qsv_rejects_probe_races();
  qsv_requires_complete_healthy_state();
  qsv_device_health_registration();
  return EXIT_SUCCESS;
}
#else
/** @brief Initial and incomplete metadata cannot authorize reuse. */
TEST(RhRemoteEncoderProbe, IncompleteObservations) {
  incomplete_observations();
}

/** @brief Only dimensions may change in the software fast path. */
TEST(RhRemoteEncoderProbe, ModeOnlyAndRepeatedChanges) {
  mode_only_and_repeated_changes();
}

/** @brief Unknown and lost original devices require re-probing. */
TEST(RhRemoteEncoderProbe, UncertainOrLostDevices) {
  uncertain_or_lost_devices();
}

/** @brief Driver, color, output, desktop and adapter changes require re-probing. */
TEST(RhRemoteEncoderProbe, InvariantsAndAdapterChanges) {
  invariant_and_adapter_changes();
}

/** @brief Original device health cannot be masked by a replacement. */
TEST(RhRemoteEncoderProbe, HealthOwnershipAndReplacement) {
  health_retains_original_and_replaces_lost_device();
}

/** @brief Repeated adapter churn cannot grow retained device references without limit. */
TEST(RhRemoteEncoderProbe, BoundedHealthSentinels) {
  health_is_bounded();
}

/** @brief Reuse requires a successful actual capability probe, not repeated observations. */
TEST(RhRemoteEncoderProbe, QsvCompletedValidation) {
  qsv_requires_completed_validation();
}

/** @brief Only dimensions may differ from the fully validated QSV environment. */
TEST(RhRemoteEncoderProbe, QsvInvariantsAndModes) {
  qsv_invariants_and_modes();
}

/** @brief Probe-time mode, driver or adapter changes invalidate the new baseline. */
TEST(RhRemoteEncoderProbe, QsvProbeRaces) {
  qsv_rejects_probe_races();
}

/** @brief Unknown or lost devices, incomplete snapshots and explicit clearing fail closed. */
TEST(RhRemoteEncoderProbe, QsvCompleteHealthyState) {
  qsv_requires_complete_healthy_state();
}

/** @brief QSV and software creation retain health sentinels; other backends keep their original ownership policy. */
TEST(RhRemoteEncoderProbe, QsvDeviceHealthRegistration) {
  qsv_device_health_registration();
}
#endif
