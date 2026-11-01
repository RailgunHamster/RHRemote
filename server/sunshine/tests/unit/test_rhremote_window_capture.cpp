/**
 * @file tests/unit/test_rhremote_window_capture.cpp
 * @brief Window source names are opaque and cannot degrade into desktop selection.
 */
#include "src/rhremote_window_capture.h"

#include <gtest/gtest.h>
#include <limits>
#ifdef _WIN32
  #include "src/platform/windows/window_capture.h"
#endif

TEST(WindowCaptureSource, AcceptsTheCompleteOpaqueToken) {
  EXPECT_EQ(rhremote::window_source_token("rhrwindow:0123456789abcdef0123456789abcdef"), "0123456789abcdef0123456789abcdef");
}

TEST(WindowPointerGeometry, TracksCurrentWindowBoundsAndVirtualDesktopOrigin) {
  rhremote::window_pointer_mapper pointer;
  const auto point = pointer.map(0.5f, 0.5f, {100, 200, 801, 601}, -1920, 0);
  ASSERT_TRUE(point);
  EXPECT_FLOAT_EQ(point->first, 2420);
  EXPECT_FLOAT_EQ(point->second, 500);
  const auto moved = pointer.map(0.5f, 0.5f, {300, 400, 801, 601}, 0, -1080);
  ASSERT_TRUE(moved);
  EXPECT_FLOAT_EQ(moved->first, 700);
  EXPECT_FLOAT_EQ(moved->second, 1780);
}

TEST(WindowPointerGeometry, AWindowMoveCannotFeedItsOwnTranslationBackIntoTheDrag) {
  rhremote::window_pointer_mapper pointer;
  pointer.update_button(1, true, {100, 200, 801, 601});
  const auto point = pointer.map(0.6f, 0.5f, {300, 400, 801, 601}, 0, 0);
  ASSERT_TRUE(point);
  EXPECT_FLOAT_EQ(point->first, 580);
  EXPECT_FLOAT_EQ(point->second, 500);
  pointer.update_button(3, true, {300, 400, 801, 601});
  pointer.update_button(1, false, {});
  EXPECT_FLOAT_EQ(pointer.map(0.5f, 0.5f, {500, 600, 801, 601}, 0, 0)->first, 500);
  pointer.update_button(3, false, {});
  EXPECT_FLOAT_EQ(pointer.map(0.5f, 0.5f, {500, 600, 801, 601}, 0, 0)->first, 900);
}

TEST(WindowPointerGeometry, RejectsInvalidGeometryAndClearsAnchorsOnRetirement) {
  rhremote::window_pointer_mapper pointer;
  EXPECT_FALSE(pointer.map(0, 0, {}, 0, 0));
  EXPECT_FALSE(pointer.map(std::numeric_limits<float>::infinity(), 0, {0, 0, 100, 100}, 0, 0));
  const auto clipped = pointer.map(-1, 2, {10, 20, 100, 100}, 0, 0);
  ASSERT_TRUE(clipped);
  EXPECT_FLOAT_EQ(clipped->first, 10);
  EXPECT_FLOAT_EQ(clipped->second, 119);
  pointer.update_button(0, true, {10, 20, 100, 100});
  pointer.update_button(9, true, {10, 20, 100, 100});
  pointer.update_button(1, true, {10, 20, 100, 100});
  pointer.reset();
  EXPECT_FLOAT_EQ(pointer.map(0, 0, {50, 60, 100, 100}, 0, 0)->first, 50);
}

TEST(WindowCaptureSource, MalformedWindowRequestsStillProhibitDesktopFallback) {
  for (const auto source : {"rhrwindow:", "rhrwindow:0x1234", "rhrwindow:0123456789abcdef0123456789abcde", "rhrwindow:0123456789abcdef0123456789abcdef0", "rhrwindow:0123456789ABCDEF0123456789abcdef", "rhrwindow:0123456789abcdef0123456789abcdeg"}) {
    EXPECT_TRUE(rhremote::is_window_source(source));
    EXPECT_FALSE(rhremote::window_source_token(source));
  }
}

TEST(WindowCaptureSource, DoesNotMistakeDesktopNamesForWindows) {
  for (const auto source : {"", R"(\\.\DISPLAY1)", "window:0123456789abcdef0123456789abcdef", "RHRWINDOW:0123456789abcdef0123456789abcdef"}) {
    EXPECT_FALSE(rhremote::is_window_source(source));
    EXPECT_FALSE(rhremote::window_source_token(source));
  }
}

#ifdef _WIN32
TEST(WindowCaptureRegistry, SelectorsAreBoundToTheVerifiedPeer) {
  const auto source = platf::window_capture::testing::advertise("verified-peer-a");
  const auto target = platf::window_capture::resolve(source, "verified-peer-a");
  ASSERT_TRUE(target);
  EXPECT_FALSE(target->alive());
  EXPECT_FALSE(target->present());
  EXPECT_FALSE(target->focus());
  EXPECT_FALSE(platf::window_capture::resolve(source, "verified-peer-b"));
  EXPECT_FALSE(platf::window_capture::resolve(source, ""));
  EXPECT_FALSE(platf::window_capture::active(source));
  platf::window_capture::testing::expire_now(source);
  EXPECT_FALSE(platf::window_capture::resolve(source, "verified-peer-a"));
  EXPECT_FALSE(platf::window_capture::acquire(source));
}

TEST(WindowCaptureRegistry, ActiveCaptureSurvivesIdleExpiryAndReleaseAllowsRecovery) {
  const auto source = platf::window_capture::testing::advertise("verified-peer");
  {
    const auto capture = platf::window_capture::acquire(source);
    ASSERT_TRUE(capture);
    EXPECT_EQ(capture->captures.load(), 1);
    EXPECT_EQ(platf::window_capture::active(source).get(), capture.get());
    platf::window_capture::testing::expire_now(source);
    EXPECT_EQ(platf::window_capture::resolve(source, "verified-peer").get(), capture.get());
    platf::window_capture::testing::expire_now(source);
  }
  EXPECT_FALSE(platf::window_capture::active(source));
  EXPECT_TRUE(platf::window_capture::resolve(source, "verified-peer"));
  platf::window_capture::testing::expire_now(source);
  EXPECT_FALSE(platf::window_capture::acquire(source));
}
#endif
