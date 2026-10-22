/**
 * @file src/rhremote_device_probe.h
 * @brief Share a device across output probes belonging to one adapter enumeration.
 */
#pragma once

namespace rhremote {
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
