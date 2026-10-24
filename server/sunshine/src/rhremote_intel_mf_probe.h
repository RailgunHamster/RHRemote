/**
 * @file src/rhremote_intel_mf_probe.h
 * @brief Permit an explicitly selected Intel MF candidate to reach real validation.
 */
#pragma once

#include <cstdint>
#include <string_view>

namespace rhremote {
  /**
   * @brief Authorize probing, without claiming that a hardware encoder exists or works.
   * @param vendor DXGI vendor of the actual capture adapter.
   * @param codec FFmpeg encoder name being validated.
   * @param explicitly_selected Whether server configuration requests mediafoundation.
   * @param video_format Protocol codec selector, where zero is H.264.
   * @param dynamic_range Nonzero when HDR is requested.
   * @param chroma_sampling Nonzero when 4:4:4 is requested.
   * @return True only for explicit Intel H.264 SDR 4:2:0 candidates; normal codec,
   * device, first-IDR and bitstream validation must still succeed afterwards.
   */
  inline bool explicit_intel_mf_probe(std::uint32_t vendor, std::string_view codec, bool explicitly_selected, int video_format, int dynamic_range, int chroma_sampling) {
    return vendor == 0x8086 && explicitly_selected && codec == "h264_mf" &&
           video_format == 0 && dynamic_range == 0 && chroma_sampling == 0;
  }
}  // namespace rhremote
