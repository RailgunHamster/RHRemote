/**
 * @file tests/unit/test_rhremote_encoder_trace.cpp
 * @brief Exercise real initialization trace logging without a GPU or codec fixture.
 */
#include "src/rhremote_encoder_trace.h"

#include <fstream>
#include <gtest/gtest.h>
#include <memory>
#include <regex>
#include <stdexcept>

namespace {
  /**
   * @brief Check that the real logger closed exactly one scope with the same ID.
   * @param name Unique stage name produced by this test.
   */
  void expect_closed_stage(const std::string &name) {
    logging::log_flush();
    std::ifstream input("test_sunshine.log");
    ASSERT_TRUE(input.is_open());
    const std::regex pattern("RHREMOTE_PHASE (begin|end) id=([0-9]+) name=" + name + " tick_us=([0-9]+)(?: elapsed_us=([0-9]+))?");
    std::string begin_id;
    long long start = 0;
    int begins = 0;
    int ends = 0;
    for (std::string line; std::getline(input, line);) {
      std::smatch match;
      if (!std::regex_search(line, match, pattern)) {
        continue;
      }
      if (match[1] == "begin") {
        ++begins;
        begin_id = match[2];
        start = std::stoll(match[3]);
      } else {
        ++ends;
        EXPECT_EQ(match[2].str(), begin_id);
        EXPECT_GE(std::stoll(match[3]), start);
        ASSERT_TRUE(match[4].matched);
        EXPECT_GE(std::stoll(match[4]), std::stoll(match[3]) - start);
      }
    }
    EXPECT_EQ(begins, 1);
    EXPECT_EQ(ends, 1);
  }
}  // namespace

/** @brief Error codes and references pass through unchanged and run only once. */
TEST(RhRemoteEncoderTrace, PreservesStatusAndReference) {
  int calls = 0;
  EXPECT_EQ(rhremote::trace_encoder_call("rh_test_encoder_error", [&] {
              ++calls;
              return -117;
            }),
            -117);
  EXPECT_EQ(calls, 1);
  int value = 23;
  auto &reference = rhremote::trace_encoder_call("rh_test_encoder_reference", [&]() -> int & {
    return value;
  });
  EXPECT_EQ(&reference, &value);
  reference = 29;
  EXPECT_EQ(value, 29);
  expect_closed_stage("rh_test_encoder_error");
  expect_closed_stage("rh_test_encoder_reference");
}

/** @brief The wrapper does not copy a callable or its move-only return value. */
TEST(RhRemoteEncoderTrace, MoveOnlyCallableAndResult) {
  auto value = std::make_unique<int>(41);
  const auto *original = value.get();
  auto result = rhremote::trace_encoder_call("rh_test_encoder_move", [owned = std::move(value)]() mutable {
    return std::move(owned);
  });
  EXPECT_EQ(result.get(), original);
  EXPECT_EQ(*result, 41);
  expect_closed_stage("rh_test_encoder_move");
}

/** @brief Void calls and exception unwinding both close their actual scopes. */
TEST(RhRemoteEncoderTrace, VoidAndNestedExceptionScopes) {
  int calls = 0;
  rhremote::trace_encoder_call("rh_test_encoder_void", [&] {
    ++calls;
  });
  EXPECT_EQ(calls, 1);
  try {
    rhremote::trace_encoder_call("rh_test_encoder_outer", [] {
      rhremote::trace_encoder_call("rh_test_encoder_inner", [] {
        throw std::runtime_error("original encoder failure");
      });
    });
    FAIL() << "Original failure was swallowed";
  } catch (const std::runtime_error &error) {
    EXPECT_STREQ(error.what(), "original encoder failure");
  }
  expect_closed_stage("rh_test_encoder_void");
  expect_closed_stage("rh_test_encoder_inner");
  expect_closed_stage("rh_test_encoder_outer");
}
