/**
 * @file tests/unit/test_rhremote_initial_display.cpp
 * @brief Regress per-launch target validation, concurrency admission and fresh source selection.
 */
#include "src/rhremote_initial_display.h"

#include <cstdlib>
#ifndef RHREMOTE_INITIAL_DISPLAY_STANDALONE
  #include <gtest/gtest.h>
#endif

namespace {
  /** @brief Fail both standalone and GoogleTest on a violated invariant. @param condition Required condition. */
  void require(bool condition) {
    if (!condition) {
      std::abort();
    }
  }

  /** @brief Reject query injection, truncation, noncanonical and unsupported source names. */
  void validate_names() {
    for (const auto *name : {R"(\\.\DISPLAY1)", R"(\\.\DISPLAY25)", R"(\\.\DISPLAY12345678901234567890)"}) {
      require(rhremote::valid_initial_display(name));
    }
    for (const auto *name : {"", "1", R"(\\.\DISPLAY)", R"(\\.\DISPLAY0)", R"(\\.\DISPLAY01)", R"(\\.\display1)", R"(\\.\DISPLAY1&mode=1)", R"(\\.\DISPLAY-1)", R"(\\.\DISPLAY123456789012345678901)"}) {
      require(!rhremote::valid_initial_display(name));
    }
    require(!rhremote::valid_initial_display(std::string(R"(\\.\DISPLAY1)") + '\0' + "2"));
  }

  /** @brief Legacy joins remain possible; explicit requests cannot silently share another target. */
  void admit_requests() {
    using admission = rhremote::initial_display_admission;
    for (int active : {0, 1, 2}) {
      for (bool supported : {false, true}) {
        require(rhremote::admit_initial_display("", active, false, supported) == admission::legacy);
        require(rhremote::admit_initial_display("", active, true, supported) == admission::busy);
        require(rhremote::admit_initial_display(R"(\\.\DISPLAY2)", active, true, supported) == admission::busy);
        require(rhremote::admit_initial_display("malformed", active, false, supported) == admission::invalid);
        require(rhremote::admit_initial_display(R"(\\.\DISPLAY2)", active, false, supported) == (!supported ? admission::invalid : active ? admission::busy :
                                                                                                                                            admission::selected));
      }
    }
  }

  /** @brief Reordering uses fresh indices; absent or ambiguous targets never become the primary. */
  void select_fresh_source() {
    const std::string physical = R"(\\.\DISPLAY1)", target = R"(\\.\DISPLAY25)";
    require(rhremote::initial_display_index(target, {physical, target}) == 1);
    require(rhremote::initial_display_index(target, {target, physical}) == 0);
    require(!rhremote::initial_display_index(target, {}));
    require(!rhremote::initial_display_index(target, {physical}));
    require(!rhremote::initial_display_index(target, {target, target}));
    require(!rhremote::initial_display_index("", {""}));
    require(!rhremote::initial_display_index("bad", {"bad"}));
  }
}  // namespace

#ifdef RHREMOTE_INITIAL_DISPLAY_STANDALONE
int main() {
  validate_names();
  admit_requests();
  select_fresh_source();
}
#else
TEST(RhRemoteInitialDisplay, ValidateNames) {
  validate_names();
}

TEST(RhRemoteInitialDisplay, AdmitRequests) {
  admit_requests();
}

TEST(RhRemoteInitialDisplay, SelectFreshSource) {
  select_fresh_source();
}
#endif
