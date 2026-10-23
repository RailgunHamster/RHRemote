/**
 * @file src/rhremote_initial_display.h
 * @brief Validate per-launch capture selection without changing global display configuration.
 */
#pragma once

#include <algorithm>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace rhremote {
  /** @brief Result of admitting a paired client's initial-display request. */
  enum class initial_display_admission {
    legacy,  ///< No explicit target: retain upstream display selection.
    selected,  ///< A valid target can initialize a new capture pipeline.
    invalid,  ///< Malformed or unsupported explicit target.
    busy,  ///< A pending launch or shared capture prevents independent selection.
  };

  /** @brief Check a canonical Windows GDI source name. @param name Requested source. @return Whether it fits CCHDEVICENAME and canonical syntax. */
  inline bool valid_initial_display(std::string_view name) {
    constexpr std::string_view prefix = R"(\\.\DISPLAY)";
    if (!name.starts_with(prefix) || name.size() <= prefix.size() || name.size() > 31 || name[prefix.size()] == '0') {
      return false;
    }
    return std::all_of(name.begin() + prefix.size(), name.end(), [](char c) {
      return c >= '0' && c <= '9';
    });
  }

  /** @brief Admit a launch while the HTTP launch mutex is held. @param name Optional requested source. @param active Number of active sessions. @param pending Whether another RTSP launch is pending. @param supported Whether this platform supports GDI targets. @return Admission decision. */
  inline initial_display_admission admit_initial_display(std::string_view name, int active, bool pending, bool supported) {
    if (pending) {
      return initial_display_admission::busy;
    }
    if (name.empty()) {
      return initial_display_admission::legacy;
    }
    if (!supported || !valid_initial_display(name)) {
      return initial_display_admission::invalid;
    }
    return active == 0 ? initial_display_admission::selected : initial_display_admission::busy;
  }

  /** @brief Resolve only a unique target in a fresh capture-backend enumeration. @param name Explicit source name. @param fresh_names Current backend-eligible sources. @return Exact index, or no value; never substitutes a default or stale source. */
  inline std::optional<int> initial_display_index(std::string_view name, const std::vector<std::string> &fresh_names) {
    if (!valid_initial_display(name)) {
      return std::nullopt;
    }
    std::optional<int> selected;
    for (std::size_t i = 0; i < fresh_names.size(); ++i) {
      if (fresh_names[i] == name) {
        if (selected) {
          return std::nullopt;
        }
        selected = static_cast<int>(i);
      }
    }
    return selected;
  }
}  // namespace rhremote
