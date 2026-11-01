/**
 * @file src/platform/linux/input/virtualhid.cpp
 * @brief Definitions for libvirtualhid Unix input handling.
 */

// standard includes
#include <memory>
#include <algorithm>
#include <cmath>
#include <utility>

// platform includes
#ifdef SUNSHINE_BUILD_X11
  #include <X11/Xlib.h>
#endif

// local includes
#include "src/platform/virtualhid_input.h"

using namespace std::literals;

namespace platf {
#ifdef SUNSHINE_BUILD_X11
  /**
   * @brief Position the X11 core pointer without mixing absolute and relative uinput axes.
   * @param x Absolute desktop X coordinate after capture-to-desktop mapping.
   * @param y Absolute desktop Y coordinate after capture-to-desktop mapping.
   * @return Whether the X11 pointer request was completed before a following button event.
   */
  bool x11_absolute_mouse(float x, float y) {
    if (!std::isfinite(x) || !std::isfinite(y)) {
      return false;
    }
    // Keep this connection on the input worker. Ubuntu libinput rejects EV_ABS
    // events from libvirtualhid's relative mouse; keyboard, buttons, scrolling
    // and relative movement continue through the unchanged uinput device.
    thread_local std::unique_ptr<Display, decltype(&XCloseDisplay)> display(XOpenDisplay(nullptr), XCloseDisplay);
    if (!display) {
      return false;
    }
    const auto screen = DefaultScreen(display.get());
    const auto width = DisplayWidth(display.get(), screen);
    const auto height = DisplayHeight(display.get(), screen);
    if (width <= 0 || height <= 0) {
      return false;
    }
    const auto target_x = static_cast<int>(std::lround(std::clamp(x, 0.0f, static_cast<float>(width - 1))));
    const auto target_y = static_cast<int>(std::lround(std::clamp(y, 0.0f, static_cast<float>(height - 1))));
    XWarpPointer(display.get(), None, RootWindow(display.get(), screen), 0, 0, 0, 0, target_x, target_y);
    XSync(display.get(), False);
    return true;
  }
#endif

  platform_caps::caps_t get_capabilities() {
    platform_caps::caps_t caps = 0;
    const auto runtime = virtualhid::create_runtime();
    if (!runtime) {
      return caps;
    }

    if (const auto &capabilities = runtime->capabilities(); config::input.native_pen_touch && (capabilities.supports_touchscreen || capabilities.supports_pen_tablet)) {
      caps |= platform_caps::pen_touch;
    }
    if (virtualhid::configured_gamepad_supports_controller_extensions()) {
      caps |= platform_caps::controller_touch;
    }

    return caps;
  }

  std::optional<util::point_t> get_mouse_loc(input_t & /*input*/) {
#ifdef SUNSHINE_BUILD_X11
    auto *display = XOpenDisplay(nullptr);
    if (!display) {
      return std::nullopt;
    }

    const auto root = DefaultRootWindow(display);
    Window root_return {};
    Window child_return {};
    int root_x = 0;
    int root_y = 0;
    int window_x = 0;
    int window_y = 0;
    unsigned int mask = 0;
    const auto queried = XQueryPointer(display, root, &root_return, &child_return, &root_x, &root_y, &window_x, &window_y, &mask);
    XCloseDisplay(display);

    if (!queried) {
      return std::nullopt;
    }

    return util::point_t {
      static_cast<double>(root_x),
      static_cast<double>(root_y)
    };
#else
    return std::nullopt;
#endif
  }

  std::vector<supported_gamepad_t> &supported_gamepads(input_t *input) {
    static std::vector<supported_gamepad_t> gamepads;
    if (!input || !input->get()) {
      gamepads = virtualhid::static_supported_gamepads();
      return gamepads;
    }

    gamepads = virtualhid::supported_gamepads(virtualhid::get_input_context(*input).runtime.get());
    return gamepads;
  }
}  // namespace platf
