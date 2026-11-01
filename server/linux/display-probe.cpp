/** @file display-probe.cpp
 * @brief Enumerate X11 outputs in the same XRandR order used by Sunshine's X11
 * capture backend.
 */
#include <X11/Xlib.h>
#include <X11/extensions/Xrandr.h>
#include <cstdio>
#include <cstdlib>
#include <string>

/** @brief Validate a wire-compatible output name. @param info Output metadata.
 * @return Whether the name can form a canonical X11 selector. */
static bool valid_name(const XRROutputInfo *info) {
  if (!info->name || info->nameLen < 1 || info->nameLen > 128)
    return false;
  for (int i = 0; i < info->nameLen; ++i) {
    const char c = info->name[i];
    if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
          (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' ||
          c == ':'))
      return false;
  }
  return true;
}

/** @brief Write a fresh, bounded JSON display inventory. @return Zero on a
 * complete snapshot; a nonzero status otherwise. */
int main() {
  Display *display = XOpenDisplay(nullptr);
  if (!display)
    return 2;
  const Window root = DefaultRootWindow(display);
  XRRScreenResources *resources = XRRGetScreenResources(display, root);
  if (!resources || resources->noutput < 0 || resources->noutput > 128) {
    if (resources)
      XRRFreeScreenResources(resources);
    XCloseDisplay(display);
    return 3;
  }
  const RROutput primary = XRRGetOutputPrimary(display, root);
  const char *headless = std::getenv("RHREMOTE_HEADLESS");
  const bool owned = headless && std::string(headless) == "1";
  const char *owned_json = owned ? "true" : "false";
  std::printf("{\"schema\":\"rhremote.displays/"
              "v1\",\"captureBackend\":\"x11\",\"displays\":[");
  bool first = true;
  unsigned index = 0;
  for (int i = 0; i < resources->noutput; ++i) {
    XRROutputInfo *info =
        XRRGetOutputInfo(display, resources, resources->outputs[i]);
    if (!info)
      continue;
    ++index;
    const bool valid = valid_name(info);
    const std::string name =
        valid ? std::string(info->name, info->nameLen)
              : "unsupported-" + std::to_string(resources->outputs[i]);
    XRRCrtcInfo *crtc =
        info->crtc ? XRRGetCrtcInfo(display, resources, info->crtc) : nullptr;
    const bool available = valid && info->connection == RR_Connected && crtc &&
                           crtc->width && crtc->height;
    unsigned long rate = 0;
    if (crtc) {
      for (int m = 0; m < resources->nmode; ++m) {
        const auto &mode = resources->modes[m];
        if (mode.id == crtc->mode && mode.hTotal && mode.vTotal)
          rate = static_cast<unsigned long>(
              1000.0 * mode.dotClock / mode.hTotal / mode.vTotal + 0.5);
      }
    }
    if (!first)
      std::putchar(',');
    first = false;
    std::printf(
        "{\"id\":\"x11:%s\",\"name\":\"%s\",\"gdi\":\"rhrx11:%s\","
        "\"captureIndex\":%u,\"available\":%s,\"virtual\":%s,\"owned\":%s,"
        "\"primary\":%s,\"width\":%u,\"height\":%u,\"x\":%d,\"y\":%d,"
        "\"rotation\":%u,\"refreshNumerator\":%lu,\"refreshDenominator\":1000}",
        name.c_str(), name.c_str(), name.c_str(), index,
        available ? "true" : "false", owned_json, owned_json,
        resources->outputs[i] == primary ? "true" : "false",
        crtc ? crtc->width : 0, crtc ? crtc->height : 0, crtc ? crtc->x : 0,
        crtc ? crtc->y : 0, crtc ? unsigned(crtc->rotation) : 0, rate);
    if (crtc)
      XRRFreeCrtcInfo(crtc);
    XRRFreeOutputInfo(info);
  }
  std::printf("],\"capabilities\":{\"window\":false,\"virtual\":%s}}\n",
              owned_json);
  XRRFreeScreenResources(resources);
  XCloseDisplay(display);
  return 0;
}
