/**
 * @file src/rhremote_gpu_priority.h
 * @brief Keep requested GPU priority separate from observed native API results.
 */
#pragma once

#include <cstdint>
#include <optional>
#include <sstream>
#include <string>

namespace rhremote {
  /** @brief Results from one process-priority request and optional readback. */
  struct gpu_priority_observation {
    int requested {};  ///< Requested native scheduling class, not an observed value.
    std::int32_t set_status {};  ///< Original signed NTSTATUS from the setter.
    std::optional<std::int32_t> query_status;  ///< Unavailable when the getter is absent.
    std::optional<int> actual;  ///< Known only after a successful getter, including legitimate class zero.

    /** @brief Report native setter success without claiming that readback matches. @return True for nonnegative NTSTATUS. */
    bool set_succeeded() const noexcept {
      return set_status >= 0;
    }
  };

  /**
   * @brief Call the original setter once and preserve a separate, optional readback.
   * @tparam Set Native setter callback taking the requested class.
   * @tparam Get Native getter callback writing the current class.
   * @param requested Original scheduling class request.
   * @param set Original setter; its result is not replaced by the getter result.
   * @param get Getter called once only when available, even if setting failed.
   * @param getter_available Whether the native getter was resolved.
   * @return Original results; failed or unavailable reads never become class zero.
   */
  template<class Set, class Get>
  gpu_priority_observation observe_gpu_priority(int requested, Set set, Get get, bool getter_available) {
    gpu_priority_observation result;
    result.requested = requested;
    result.set_status = static_cast<std::int32_t>(set(requested));
    if (getter_available) {
      int current {};
      result.query_status = static_cast<std::int32_t>(get(current));
      if (*result.query_status >= 0) {
        result.actual = current;
      }
    }
    return result;
  }

  /** @brief Format diagnostic fields without inferring actual priority from the request. @param result Observed results. @return Stable log fields with explicit unknown/unavailable values. */
  inline std::string gpu_priority_log_fields(const gpu_priority_observation &result) {
    std::ostringstream text;
    text << "requested=" << result.requested << " set_status=" << result.set_status << " query_status=";
    if (result.query_status) {
      text << *result.query_status;
    } else {
      text << "unavailable";
    }
    text << " actual=";
    if (result.actual) {
      text << *result.actual;
    } else {
      text << "unknown";
    }
    return text.str();
  }
}  // namespace rhremote
