/**
 * @file tests/unit/test_rhremote_gpu_priority.cpp
 * @brief Verify requested and observed GPU scheduling classes remain distinct.
 */
#include "src/rhremote_gpu_priority.h"

#include <gtest/gtest.h>

TEST(RhRemoteGpuPriority, SuccessPreservesOriginalRequestAndActualClass) {
  int set_calls = 0, get_calls = 0;
  const auto observed = rhremote::observe_gpu_priority(5, [&](int requested) {
    ++set_calls;
    EXPECT_EQ(requested, 5);
    return 0;
  },
                                                       [&](int &actual) {
                                                         ++get_calls;
                                                         actual = 5;
                                                         return 0;
                                                       },
                                                       true);
  EXPECT_TRUE(observed.set_succeeded());
  EXPECT_EQ(set_calls, 1);
  EXPECT_EQ(get_calls, 1);
  EXPECT_EQ(rhremote::gpu_priority_log_fields(observed), "requested=5 set_status=0 query_status=0 actual=5");
}

TEST(RhRemoteGpuPriority, SetterFailureDoesNotBecomeSuccessfulRealtime) {
  const auto observed = rhremote::observe_gpu_priority(5, [](int) {
    return std::int32_t {-1073741790};
  },
                                                       [](int &actual) {
                                                         actual = 2;
                                                         return 0;
                                                       },
                                                       true);
  EXPECT_FALSE(observed.set_succeeded());
  EXPECT_EQ(observed.set_status, -1073741790);
  ASSERT_TRUE(observed.actual);
  EXPECT_EQ(*observed.actual, 2);
  EXPECT_EQ(rhremote::gpu_priority_log_fields(observed), "requested=5 set_status=-1073741790 query_status=0 actual=2");
}

TEST(RhRemoteGpuPriority, FailedReadbackCannotInventIdlePriority) {
  const auto observed = rhremote::observe_gpu_priority(5, [](int) {
    return 0;
  },
                                                       [](int &actual) {
                                                         actual = 0;
                                                         return std::int32_t {-1073741790};
                                                       },
                                                       true);
  EXPECT_TRUE(observed.set_succeeded());
  EXPECT_FALSE(observed.actual);
  EXPECT_EQ(rhremote::gpu_priority_log_fields(observed), "requested=5 set_status=0 query_status=-1073741790 actual=unknown");
}

TEST(RhRemoteGpuPriority, UnavailableGetterIsNotCalledOrReportedAsZero) {
  int calls = 0;
  const auto observed = rhremote::observe_gpu_priority(5, [](int) {
    return 0;
  },
                                                       [&](int &) {
                                                         ++calls;
                                                         return 0;
                                                       },
                                                       false);
  EXPECT_EQ(calls, 0);
  EXPECT_FALSE(observed.query_status);
  EXPECT_FALSE(observed.actual);
  EXPECT_EQ(rhremote::gpu_priority_log_fields(observed), "requested=5 set_status=0 query_status=unavailable actual=unknown");
}

TEST(RhRemoteGpuPriority, SuccessfulReadbackMayDifferFromRequestIncludingZero) {
  const auto observed = rhremote::observe_gpu_priority(5, [](int) {
    return 0;
  },
                                                       [](int &actual) {
                                                         actual = 0;
                                                         return 0;
                                                       },
                                                       true);
  ASSERT_TRUE(observed.actual);
  EXPECT_EQ(*observed.actual, 0);
  EXPECT_EQ(rhremote::gpu_priority_log_fields(observed), "requested=5 set_status=0 query_status=0 actual=0");
}

TEST(RhRemoteGpuPriority, NativeInformationalSuccessIsNotFailure) {
  const auto observed = rhremote::observe_gpu_priority(4, [](int) {
    return 1;
  },
                                                       [](int &actual) {
                                                         actual = 3;
                                                         return 1;
                                                       },
                                                       true);
  EXPECT_TRUE(observed.set_succeeded());
  EXPECT_EQ(rhremote::gpu_priority_log_fields(observed), "requested=4 set_status=1 query_status=1 actual=3");
}
