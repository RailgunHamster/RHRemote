/**
 * @file tests/unit/test_rhremote_update_gate.cpp
 * @brief Maintenance cannot race an already admitted legacy or modern launch.
 */
#include "src/rhremote_server_update.h"

#include <future>
#include <gtest/gtest.h>
#include <thread>

TEST(ServerUpdateGate, HoldsNewRequestsWithoutRetiringAnExistingOne) {
  rhremote::update_gate gate;
  bool held = false;
  auto existing = gate.begin([&] {
    return held;
  });
  ASSERT_TRUE(existing);
  held = true;
  EXPECT_FALSE(gate.begin([&] {
    return held;
  }));
  EXPECT_EQ(gate.snapshot([&] {
                  return held;
                })
              .preparing,
            1);
  existing.reset();
  EXPECT_EQ(gate.snapshot([&] {
                  return held;
                })
              .preparing,
            0);
  held = false;
  EXPECT_TRUE(gate.begin([&] {
    return held;
  }));
}

TEST(ServerUpdateGate, QueuedRequestOwnershipSurvivesTheHttpStack) {
  rhremote::update_gate gate;
  auto lease = gate.begin([] {
    return false;
  });
  auto queued = lease;
  lease.reset();
  EXPECT_EQ(gate.snapshot([] {
                  return true;
                })
              .preparing,
            1);
  queued.reset();
  EXPECT_EQ(gate.snapshot([] {
                  return true;
                })
              .preparing,
            0);
}

TEST(ServerUpdateGate, AnAcknowledgedHoldCannotBeOvertakenByConcurrentAdmission) {
  rhremote::update_gate gate;
  bool held = false;
  std::promise<void> reading, release;
  auto permit = release.get_future();
  auto first = std::async(std::launch::async, [&] {
    return gate.begin([&] {
      reading.set_value();
      permit.wait();
      return held;
    });
  });
  reading.get_future().wait();
  auto snapshot = std::async(std::launch::async, [&] {
    return gate.snapshot([&] {
      held = true;
      return true;
    });
  });
  release.set_value();
  auto owned = first.get();
  const auto result = snapshot.get();
  EXPECT_TRUE(result.held);
  EXPECT_EQ(result.preparing, 1);
  EXPECT_FALSE(gate.begin([&] {
    return held;
  }));
}

TEST(ServerUpdateGate, QuietTimeUsesMonotonicAdmissionRatherThanAppState) {
  rhremote::update_gate gate;
  const auto now = std::chrono::steady_clock::now();
  EXPECT_GE(gate.snapshot([] {
                  return false;
                },
                          now)
              .quiet_ms,
            120000);
  auto request = gate.begin([] {
    return false;
  },
                            now);
  request.reset();
  EXPECT_EQ(gate.snapshot([] {
                  return false;
                },
                          now + std::chrono::seconds(120))
              .quiet_ms,
            120000);
  EXPECT_EQ(gate.snapshot([] {
                  return false;
                },
                          now - std::chrono::seconds(1))
              .quiet_ms,
            0);
}

TEST(ServerUpdateGate, InvalidHoldStateFailsClosedAndExpiredStateReleasesAdmission) {
  const nlohmann::json valid = {{"schema", "rhremote.update-hold/v1"}, {"transactionId", std::string(32, 'a')}, {"expiresAt", 110}};
  EXPECT_TRUE(rhremote::server_update::active_hold(valid, 100));
  EXPECT_FALSE(rhremote::server_update::active_hold(valid, 110));
  EXPECT_TRUE(rhremote::server_update::active_hold(nlohmann::json::object(), 100));
  auto bad = valid;
  bad["transactionId"] = "../a";
  EXPECT_TRUE(rhremote::server_update::active_hold(bad, 100));
}

TEST(ServerUpdateGate, AdministratorSettingsCannotIntroduceArbitrarySourcesOrInvalidIntervals) {
  const nlohmann::json valid = {{"schemaVersion", 1}, {"enabled", true}, {"preferredSource", "cnb"}, {"checkIntervalMinutes", 15}};
  EXPECT_EQ(rhremote::server_update::settings(valid), valid);
  auto changed = valid;
  changed["preferredSource"] = "https://example.org";
  EXPECT_THROW(rhremote::server_update::settings(changed), std::exception);
  changed = valid;
  changed["checkIntervalMinutes"] = 0;
  EXPECT_THROW(rhremote::server_update::settings(changed), std::exception);
  changed = valid;
  changed["installPath"] = "C:/Windows";
  EXPECT_THROW(rhremote::server_update::settings(changed), std::exception);
}
