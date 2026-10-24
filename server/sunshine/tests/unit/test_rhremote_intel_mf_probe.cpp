/**
 * @file tests/unit/test_rhremote_intel_mf_probe.cpp
 * @brief Keep explicit Intel MF experiments separate from capability advertisement.
 */
#include "src/rhremote_intel_mf_probe.h"

#include <cstdlib>
#include <initializer_list>
#ifndef RHREMOTE_INTEL_MF_STANDALONE
  #include <gtest/gtest.h>
#endif

namespace {
  /** @brief Fail both standalone and GoogleTest executions on a policy violation. @param value Required condition. */
  void require(bool value) {
    if (!value) {
      std::abort();
    }
  }

  /** @brief Only the explicit Intel SDR H.264 candidate may reach real validation. */
  void explicit_candidate() {
    require(rhremote::explicit_intel_mf_probe(0x8086, "h264_mf", true, 0, 0, 0));
    require(!rhremote::explicit_intel_mf_probe(0x8086, "h264_mf", false, 0, 0, 0));
  }

  /** @brief Auto selection, other vendors and differently named backends receive no exception. */
  void unrelated_candidates() {
    for (auto vendor : {0U, 0x10deU, 0x1002U, 0x5143U, 0x4d4f4351U}) {
      require(!rhremote::explicit_intel_mf_probe(vendor, "h264_mf", true, 0, 0, 0));
    }
    for (auto codec : {"h264_qsv", "h264_nvenc", "h264_amf", "libx264", "h264_mf_extra", "H264_MF", ""}) {
      require(!rhremote::explicit_intel_mf_probe(0x8086, codec, true, 0, 0, 0));
    }
  }

  /** @brief No HEVC, AV1, HDR, 4:4:4 or malformed protocol request is advertised by this exception. */
  void unvalidated_formats() {
    for (auto value : {-1, 1, 2, 99}) {
      require(!rhremote::explicit_intel_mf_probe(0x8086, "h264_mf", true, value, 0, 0));
      require(!rhremote::explicit_intel_mf_probe(0x8086, "h264_mf", true, 0, value, 0));
      require(!rhremote::explicit_intel_mf_probe(0x8086, "h264_mf", true, 0, 0, value));
    }
    require(!rhremote::explicit_intel_mf_probe(0x8086, "hevc_mf", true, 1, 0, 0));
    require(!rhremote::explicit_intel_mf_probe(0x8086, "av1_mf", true, 2, 0, 0));
  }
}  // namespace

#ifdef RHREMOTE_INTEL_MF_STANDALONE
/** @brief Exercise the exact probe gate without Windows, a GPU or Media Foundation. */
int main() {
  explicit_candidate();
  unrelated_candidates();
  unvalidated_formats();
}
#else
TEST(RhRemoteIntelMfProbe, ExplicitCandidate) {
  explicit_candidate();
}

TEST(RhRemoteIntelMfProbe, UnrelatedCandidates) {
  unrelated_candidates();
}

TEST(RhRemoteIntelMfProbe, UnvalidatedFormats) {
  unvalidated_formats();
}
#endif
