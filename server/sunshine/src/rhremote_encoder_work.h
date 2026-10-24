/**
 * @file src/rhremote_encoder_work.h
 * @brief Bounded per-invocation encoder API metadata, without samples or producer-side I/O.
 */
#pragma once

#include <array>
#include <cstdint>
#include <string_view>

namespace rhremote {
  /** @brief One actually executed backend API call in the host frame clock. */
  struct encoder_api_span {
    std::uint32_t api {};  ///< Fixed MF API identity, not an input/output PTS.
    std::int64_t begin_us {}, end_us {};  ///< Real CPU call boundaries, including blocking.
    std::int32_t result {};  ///< Original signed HRESULT, including failed calls.
  };

  /** @brief Backend work performed during one encode invocation; delayed output may have another PTS. */
  struct encoder_work {
    bool mf {};  ///< True only when actual MF API instrumentation was bound for this invocation.
    std::uint32_t count {}, discarded {};  ///< Stored calls and explicit bounded-capacity overflow.
    std::array<encoder_api_span, 16> calls {};  ///< Fixed storage; no allocation on the encoder thread.

    /** @brief Append one actual call, reporting overflow rather than inventing complete data. @param call Measured call. */
    void record(encoder_api_span call) noexcept {
      if (count < calls.size()) {
        calls[count++] = call;
      } else if (discarded != UINT32_MAX) {
        ++discarded;
      }
    }
  };

  /** @brief Name a fixed MF API identity. @param api Recorded identity. @return Stable name, or unknown. */
  inline std::string_view encoder_api_name(std::uint32_t api) {
    switch (api) {
      case 1:
        return "mf_get_event";
      case 2:
        return "mf_process_input";
      case 3:
        return "mf_process_output";
      default:
        return "unknown";
    }
  }
}  // namespace rhremote
