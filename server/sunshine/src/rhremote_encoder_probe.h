/**
 * @file src/rhremote_encoder_probe.h
 * @brief Conservative software mode reuse and completed, healthy QSV capability evidence.
 */
#pragma once

#include <algorithm>
#include <cstdint>
#include <map>
#include <optional>
#include <string_view>
#include <utility>
#include <vector>

namespace rhremote {
  /**
   * @brief Retain original real D3D devices for backends eligible for validated mode reuse.
   * @param configured_encoder Explicit encoder requested by the host configuration.
   * @return True for software or QSV; automatic selection and other hardware keep their existing ownership policy.
   */
  constexpr bool keeps_encoder_probe_health(std::string_view configured_encoder) {
    return configured_encoder == "software" || configured_encoder == "quicksync";
  }

  /** @brief Locally unique graphics adapter identity, including its high word. */
  using probe_adapter_id = std::pair<std::uint32_t, std::int32_t>;

  /** @brief Fresh environment metadata; only pixel dimensions may differ for mode reuse. */
  struct encoder_environment {
    std::vector<std::uint64_t> invariant;  ///< Length-delimited identities, driver, desktop, geometry and color metadata.
    std::vector<std::pair<std::uint32_t, std::uint32_t>> modes;  ///< Dimensions in the same verified output order.
    std::vector<probe_adapter_id> active_adapters;  ///< Adapters whose previous real D3D devices must remain healthy.
    bool operator==(const encoder_environment &) const = default;
  };

  /** @brief Health of devices retained from actual successful D3D initialization. */
  enum class probe_device_health {
    unknown,
    healthy,
    lost
  };
  /** @brief Reason for requiring or reusing previously validated capabilities. */
  enum class encoder_probe_decision {
    required,
    current,
    mode_only
  };

  /** @brief Preserve a previous complete observation, never a capture object or output index. */
  class encoder_probe_cache {
    std::optional<encoder_environment> previous;  ///< Empty after an incomplete or raced observation.

  public:
    /**
     * @brief Decide whether capabilities need validation and retain this fresh observation.
     * @param factory_current Whether the previous factory remains current.
     * @param explicit_software Whether a previously validated, explicitly selected software encoder is in use.
     * @param fresh Complete, stable metadata, or empty when an API failed or raced.
     * @param health Previous real device health; missing devices cannot prove a mode-only change.
     * @return Reuse reason, or required on first observation, uncertainty, loss or invariant changes.
     */
    encoder_probe_decision observe(bool factory_current, bool explicit_software, std::optional<encoder_environment> fresh, probe_device_health health) {
      auto decision = encoder_probe_decision::required;
      if (fresh && (fresh->invariant.empty() || fresh->modes.empty() || fresh->active_adapters.empty())) {
        fresh.reset();
      }
      if (previous && fresh && health != probe_device_health::lost && previous->invariant == fresh->invariant && previous->active_adapters == fresh->active_adapters) {
        if (factory_current && previous->modes == fresh->modes) {
          decision = encoder_probe_decision::current;
        } else if (explicit_software && health == probe_device_health::healthy && previous->modes != fresh->modes) {
          decision = encoder_probe_decision::mode_only;
        }
      }
      previous = std::move(fresh);
      return decision;
    }
  };

  /**
   * @brief Reuse QSV capabilities only from a completed probe with stable, healthy metadata.
   *
   * Pending observations are never evidence of successful encoding. Dimensions may
   * change, but adapter, driver, selected probe output, color, desktop and options
   * must still match. Capture eligibility and actual encoder creation remain fresh.
   */
  class validated_encoder_probe_cache {
    std::optional<encoder_environment> validated;  ///< Environment of a completed successful capability probe.
    std::optional<encoder_environment> pending;  ///< Exact observation immediately before a required probe.
    std::string_view completion_reason_ {"not_validated"};  ///< Fixed diagnostic identity; never includes configuration values.

    /** @brief Reject incomplete metadata. @param state Candidate observation. @return Whether identities, outputs and adapters are present. */
    static bool complete(const std::optional<encoder_environment> &state) {
      return state && !state->invariant.empty() && !state->modes.empty() && !state->active_adapters.empty();
    }

  public:
    /**
     * @brief Request a probe unless a healthy, previously validated environment still matches.
     * @param eligible Whether the selected encoder is still the explicitly requested, validated QSV encoder.
     * @param fresh Complete, stable current metadata, including the probe configuration.
     * @param health Removal state of the original real devices for all attached adapters.
     * @return True when full capability validation remains necessary.
     */
    bool required(bool eligible, std::optional<encoder_environment> fresh, probe_device_health health) {
      if (!complete(fresh)) {
        clear();
        return true;
      }
      pending = std::move(fresh);
      if (!eligible || health != probe_device_health::healthy) {
        validated.reset();
        return true;
      }
      return !validated || validated->invariant != pending->invariant || validated->active_adapters != pending->active_adapters || validated->modes.size() != pending->modes.size();
    }

    /**
     * @brief Accept a completed probe only if nothing changed during its actual execution.
     * @param successful Whether the entire selected QSV capability probe succeeded.
     * @param fresh Fresh metadata collected after the probe, never the pending snapshot itself.
     * @param health Original device state after the actual probe.
     * @return Whether a validated baseline was retained.
     */
    bool completed(bool successful, std::optional<encoder_environment> fresh, probe_device_health health) {
      if (!successful) {
        completion_reason_ = "probe_failed";
      } else if (!complete(pending)) {
        completion_reason_ = "before_metadata_incomplete";
      } else if (!complete(fresh)) {
        completion_reason_ = "after_metadata_incomplete";
      } else if (pending != fresh) {
        completion_reason_ = "environment_changed_during_probe";
      } else if (health == probe_device_health::lost) {
        completion_reason_ = "original_device_lost";
      } else if (health != probe_device_health::healthy) {
        completion_reason_ = "original_device_health_unknown";
      } else {
        completion_reason_ = "retained";
      }
      if (completion_reason_ == "retained") {
        validated = std::move(fresh);
      } else {
        validated.reset();
      }
      pending.reset();
      return validated.has_value();
    }

    /** @brief Name the last completed validation decision without exposing identities. @return Fixed diagnostic reason. */
    std::string_view completion_reason() const {
      return completion_reason_;
    }

    /** @brief Forget both previous capabilities and unvalidated observations. */
    void clear() {
      validated.reset();
      pending.reset();
      completion_reason_ = "not_validated";
    }
  };

  /**
   * @brief Retain bounded health sentinels without sharing capture contexts or eligibility.
   * @tparam Device Owning, nullable device handle.
   */
  template<class Device>
  class encoder_device_health {
    std::map<probe_adapter_id, Device> devices;  ///< One original healthy device per adapter, at most 64.

  public:
    /**
     * @brief Remember a real created device; preserve an existing healthy sentinel.
     * @param key Exact adapter identity.
     * @param device Owning device reference, or empty if initialization failed.
     * @param healthy Callback checking a device's removal state.
     */
    template<class Healthy>
    void remember(probe_adapter_id key, Device device, Healthy healthy) {
      if (!device) {
        return;
      }
      auto found = devices.find(key);
      if (found != devices.end()) {
        if (!healthy(found->second)) {
          found->second = std::move(device);
        }
      } else if (devices.size() < 64) {
        devices.emplace(key, std::move(device));
      }
    }

    /**
     * @brief Drop sentinels which are no longer needed by the fresh inventory.
     * @param present Adapters whose health sentinels may still be needed.
     */
    void retain(const std::vector<probe_adapter_id> &present) {
      std::erase_if(devices, [&](const auto &entry) {
        return std::find(present.begin(), present.end(), entry.first) == present.end();
      });
    }

    /**
     * @brief Inspect every active adapter, treating a known lost device more strongly than a missing one.
     * @param active Adapters associated with attached outputs.
     * @param healthy Callback checking the original device's removal state.
     * @return Healthy only when all requested original devices remain healthy.
     */
    template<class Healthy>
    probe_device_health state(const std::vector<probe_adapter_id> &active, Healthy healthy) const {
      bool missing = active.empty();
      for (const auto &key : active) {
        const auto found = devices.find(key);
        if (found == devices.end()) {
          missing = true;
        } else if (!healthy(found->second)) {
          return probe_device_health::lost;
        }
      }
      return missing ? probe_device_health::unknown : probe_device_health::healthy;
    }
  };
}  // namespace rhremote
