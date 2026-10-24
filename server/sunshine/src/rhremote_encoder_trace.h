/**
 * @file src/rhremote_encoder_trace.h
 * @brief Preserve encoder initialization results while measuring actual API calls.
 */
#pragma once

#include "rhremote_trace.h"

#include <utility>

namespace rhremote {
  /**
   * @brief Measure one initialization call, including its error or exception path.
   * @tparam Function Callable type, forwarded without copying.
   * @param name Stable diagnostic stage name; never includes user data.
   * @param function Operation to invoke exactly once.
   * @return The original value, reference or void result without conversion.
   */
  template<typename Function>
  decltype(auto) trace_encoder_call(const char *name, Function &&function) {
    scope operation(name);
    return std::forward<Function>(function)();
  }
}  // namespace rhremote
