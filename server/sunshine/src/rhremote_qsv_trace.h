/**
 * @file src/rhremote_qsv_trace.h
 * @brief Scoped bridge from the pinned QSV module into bounded frame work.
 */
#pragma once

#include "rhremote_encoder_work.h"
#include "rhremote_frame_trace.h"

#if defined(_WIN32) && defined(RHREMOTE_FFMPEG_QSV_TRACE)
  #include "../../ffmpeg/qsv-trace.h"
#endif

namespace rhremote {
  /** @brief Bind only the calling QSV thread; restore callbacks before work expires. */
  class scoped_qsv_trace {
#if defined(_WIN32) && defined(RHREMOTE_FFMPEG_QSV_TRACE)
    rhremote_qsv_sink previous {};  ///< Previous thread-local callbacks.
    bool bound {};  ///< False when tracing is disabled or another backend is selected.

    /** @brief Read the recorder's host clock. @param opaque Unused context. @return Steady microseconds. */
    static std::int64_t tick(void *opaque) noexcept {
      (void) opaque;
      return frame_tick_us();
    }

    /** @brief Append metadata without I/O. @param opaque Work. @param api Call ID. @param begin Start. @param end End. @param result Original status. */
    static void record(void *opaque, std::uint32_t api, std::int64_t begin, std::int64_t end, std::int32_t result) noexcept {
      static_cast<encoder_work *>(opaque)->record({api, begin, end, result});
    }
#endif

  public:
    /** @brief Bind only for an opt-in QSV invocation. @param work Stack-owned buffer. @param enabled Backend/tracing eligibility. */
    scoped_qsv_trace(encoder_work &work, bool enabled) {
#if defined(_WIN32) && defined(RHREMOTE_FFMPEG_QSV_TRACE)
      if (enabled) {
        work.qsv = true;
        previous = rhremote_qsv_set_sink({&work, tick, record});
        bound = true;
      }
#else
      (void) work;
      (void) enabled;
#endif
    }

    /** @brief Restore the outer sink before destroying the current work buffer. */
    ~scoped_qsv_trace() {
#if defined(_WIN32) && defined(RHREMOTE_FFMPEG_QSV_TRACE)
      if (bound) {
        rhremote_qsv_set_sink(previous);
      }
#endif
    }

    /** @brief A binding has exactly one lifetime owner. */
    scoped_qsv_trace(const scoped_qsv_trace &) = delete;
    /** @brief A binding cannot replace another owner's lifetime. */
    scoped_qsv_trace &operator=(const scoped_qsv_trace &) = delete;
  };
}  // namespace rhremote
