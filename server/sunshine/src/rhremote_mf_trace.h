/**
 * @file src/rhremote_mf_trace.h
 * @brief Scoped bridge from the pinned MF module into one frame's bounded work profile.
 */
#pragma once

#include "rhremote_encoder_work.h"
#include "rhremote_frame_trace.h"

#if defined(_WIN32) && defined(RHREMOTE_FFMPEG_MF_TRACE)
  #include "../../ffmpeg/mf-trace.h"
#endif

namespace rhremote {
  /** @brief Bind only the calling encoder thread; restoration covers normal and exceptional exits. */
  class scoped_mf_trace {
#if defined(_WIN32) && defined(RHREMOTE_FFMPEG_MF_TRACE)
    rhremote_mf_sink previous {};  ///< Restored before the stack-owned frame work expires.
    bool bound {};  ///< False when tracing is off or this is another backend.

    /** @brief Read the recorder's actual host clock. @param opaque Unused caller context. @return Steady microseconds. */
    static std::int64_t tick(void *opaque) noexcept {
      (void) opaque;
      return frame_tick_us();
    }

    /** @brief Append measured metadata without logging or retaining the encoder. @param opaque Current work. @param api API ID. @param begin Start. @param end End. @param result Original HRESULT. */
    static void record(void *opaque, std::uint32_t api, std::int64_t begin, std::int64_t end, std::int32_t result) noexcept {
      static_cast<encoder_work *>(opaque)->record({api, begin, end, result});
    }
#endif

  public:
    /** @brief Bind callbacks only for an opt-in MF invocation. @param work Stack-owned work buffer. @param enabled Actual tracing/backend eligibility. */
    scoped_mf_trace(encoder_work &work, bool enabled) {
#if defined(_WIN32) && defined(RHREMOTE_FFMPEG_MF_TRACE)
      if (enabled) {
        work.mf = true;
        previous = rhremote_mf_set_sink({&work, tick, record});
        bound = true;
      }
#else
      (void) work;
      (void) enabled;
#endif
    }

    /** @brief Restore any outer sink before the work buffer leaves scope. */
    ~scoped_mf_trace() {
#if defined(_WIN32) && defined(RHREMOTE_FFMPEG_MF_TRACE)
      if (bound) {
        rhremote_mf_set_sink(previous);
      }
#endif
    }

    /** @brief A sink binding has exactly one lifetime owner. */
    scoped_mf_trace(const scoped_mf_trace &) = delete;
    /** @brief A sink binding cannot replace another owner. */
    scoped_mf_trace &operator=(const scoped_mf_trace &) = delete;
  };
}  // namespace rhremote
