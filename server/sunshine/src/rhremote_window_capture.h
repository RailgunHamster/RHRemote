/**
 * @file src/rhremote_window_capture.h
 * @brief Opaque application-window source names shared by launch and capture.
 */
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <utility>

namespace rhremote {
  /** @brief Physical bounds of one captured window. */
  struct window_bounds {
    int left {};  ///< Physical desktop origin.
    int top {};  ///< Physical desktop origin.
    int width {};  ///< Content extent.
    int height {};  ///< Content extent.
  };

  /** @brief Preserve a drag's original desktop plane while its own window moves. */
  class window_pointer_mapper {
    std::uint32_t held {};  ///< Buttons held by this session.
    std::optional<window_bounds> origin;  ///< Frozen plane for a native window move or resize.

  public:
    /** @brief Drop all ephemeral pointer state at session retirement. */
    void reset() {
      held = 0;
      origin.reset();
    }

    /** @brief Track one button without recapturing a moving window. @param button One-based protocol button. @param down Whether pressed. @param bounds Current physical bounds. */
    void update_button(unsigned button, bool down, window_bounds bounds) {
      if (button < 1 || button > 8) {
        return;
      }
      const auto bit = std::uint32_t {1} << (button - 1);
      if (down) {
        if (!held && bounds.width > 0 && bounds.height > 0) {
          origin = bounds;
        }
        held |= bit;
      } else {
        held &= ~bit;
        if (!held) {
          origin.reset();
        }
      }
    }

    /** @brief Map into the current virtual desktop, anchoring drags to avoid feedback. @param x Normalized content coordinate. @param y Normalized content coordinate. @param current Current window bounds. @param desktop_left Current virtual desktop origin. @param desktop_top Current virtual desktop origin. @return Physical virtual-desktop coordinates, or no point for invalid geometry. */
    std::optional<std::pair<float, float>> map(float x, float y, window_bounds current, int desktop_left, int desktop_top) const {
      const auto bounds = origin.value_or(current);
      if (!std::isfinite(x) || !std::isfinite(y) || bounds.width <= 0 || bounds.height <= 0) {
        return std::nullopt;
      }
      return std::pair {
        bounds.left - desktop_left + std::clamp(x, 0.0f, 1.0f) * (bounds.width - 1),
        bounds.top - desktop_top + std::clamp(y, 0.0f, 1.0f) * (bounds.height - 1)
      };
    }
  };

  /** @brief Prefix marking a window request, including malformed requests that must never fall back to a display. */
  inline constexpr std::string_view window_source_prefix = "rhrwindow:";

  /** @brief Recognize a request for a window backend. @param source Requested source. @return Whether desktop fallback must be prohibited. */
  inline bool is_window_source(std::string_view source) {
    return source.starts_with(window_source_prefix);
  }

  /** @brief Decode an opaque random token, never a native HWND. @param source Requested window source. @return The token only when the entire source is canonical. */
  inline std::optional<std::string> window_source_token(std::string_view source) {
    if (!is_window_source(source)) {
      return std::nullopt;
    }
    const auto token = source.substr(window_source_prefix.size());
    if (token.size() != 32 || !std::all_of(token.begin(), token.end(), [](char c) {
          return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
        })) {
      return std::nullopt;
    }
    return std::string(token);
  }
}  // namespace rhremote
