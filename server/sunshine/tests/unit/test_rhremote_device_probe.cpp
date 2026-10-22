/**
 * @file tests/unit/test_rhremote_device_probe.cpp
 * @brief Exercise output probe device ownership without a GPU or an interactive desktop.
 */
#include "src/rhremote_device_probe.h"

#include <cstdlib>
#include <memory>
#ifndef RHREMOTE_DEVICE_PROBE_STANDALONE
  #include <gtest/gtest.h>
#endif

/**
 * @brief Check reuse, output failure, device creation failure, and adapter isolation.
 * @return EXIT_SUCCESS if the ownership contract holds.
 */
int run_device_probe_tests() {
  std::unique_ptr<int> adapter_a;
  std::unique_ptr<int> adapter_b;
  int creates = 0;
  int probes = 0;
  auto create = [&](auto &device) {
    ++creates;
    device = std::make_unique<int>(creates);
    return true;
  };
  auto unavailable = [&](auto &) {
    ++probes;
    return false;
  };
  auto available = [&](auto &) {
    ++probes;
    return true;
  };
  auto require = [](bool result) {
    if (!result) {
      std::abort();
    }
  };
  require(!rhremote::probe_with_device(adapter_a, create, unavailable));
  require(rhremote::probe_with_device(adapter_a, create, available));
  require(creates == 1 && probes == 2);
  require(rhremote::probe_with_device(adapter_b, create, available));
  require(creates == 2 && probes == 3 && adapter_a.get() != adapter_b.get());
  adapter_a.reset();
  require(!rhremote::probe_with_device(adapter_a, [](auto &) {
    return false;
  },
                                       available));
  require(probes == 3 && !adapter_a);
  require(rhremote::probe_with_device(adapter_a, create, available));
  require(creates == 3 && probes == 4);
  return EXIT_SUCCESS;
}

#ifdef RHREMOTE_DEVICE_PROBE_STANDALONE
/** @brief Run the GPU-independent regression outside the complete server test suite. */
int main() {
  return run_device_probe_tests();
}
#else
/** @brief Exercise the same ownership regression from the complete GoogleTest suite. */
TEST(RhRemoteDeviceProbe, ReuseAndFailureIsolation) {
  EXPECT_EQ(run_device_probe_tests(), EXIT_SUCCESS);
}
#endif
