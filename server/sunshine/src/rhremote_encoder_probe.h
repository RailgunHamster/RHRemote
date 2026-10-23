/**
 * @file src/rhremote_encoder_probe.h
 * @brief Conservative capability reuse after a verified software capture mode change.
 */
#pragma once

#include <algorithm>
#include <cstdint>
#include <map>
#include <optional>
#include <utility>
#include <vector>

namespace rhremote {
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
