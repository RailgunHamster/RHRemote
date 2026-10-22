/**
 * @file src/rhremote_device_probe.h
 * @brief Share a device across output probes belonging to one adapter enumeration.
 */
#pragma once
#include <map>
#include <utility>

namespace rhremote {
  /**
   * @brief Transfer a validated enumeration's graphics devices into capture initialization.
   * @details Instances belong to one capture thread. Taking a device consumes it; a stale
   * factory or a new enumeration discards remaining entries. Output eligibility is not cached.
   * @tparam Key Stable adapter identity.
   * @tparam Device Nullable, owning graphics device type.
   */
  template<class Key, class Device>
  class device_handoff {
    std::map<Key, Device> devices;  ///< Unconsumed devices from the current enumeration.

  public:
    /** @brief Discard an earlier enumeration before probing the current outputs. */
    void clear() {
      devices.clear();
    }

    /** @brief Retain this adapter's device until capture initialization consumes it.
     * @param key Stable adapter identity.
     * @param device Validated device to transfer.
     */
    void store(Key key, Device device) {
      devices.insert_or_assign(std::move(key), std::move(device));
    }

    /** @brief Consume a matching device only while its enumeration factory remains current.
     * @param key Adapter required by the new capture object.
     * @param current Whether DXGI still considers the enumeration current.
     * @return Matching device, or an empty owner after a miss or invalidation.
     */
    Device take(const Key &key, bool current) {
      if (!current) {
        clear();
        return {};
      }
      auto found = devices.find(key);
      if (found == devices.end()) {
        return {};
      }
      auto result = std::move(found->second);
      devices.erase(found);
      return result;
    }
  };

  /**
   * @brief Create one device lazily and retain it even if one output cannot be captured.
   * @details The caller owns the device lifetime and must use a separate instance for each adapter.
   * No device or output eligibility is cached across enumerations or desktop changes.
   * @tparam Device Nullable, owning graphics device type.
   * @tparam Create Device creation callback returning success.
   * @tparam Probe Output validation callback returning capture eligibility.
   * @param device Device belonging to this adapter only.
   * @param create Callback that initializes an empty device.
   * @param probe Callback that validates the current output with the initialized device.
   * @return True if a device was created and the current output can be captured.
   */
  template<class Device, class Create, class Probe>
  bool probe_with_device(Device &device, Create create, Probe probe) {
    if (!device && !create(device)) {
      return false;
    }
    return probe(device);
  }
}  // namespace rhremote
