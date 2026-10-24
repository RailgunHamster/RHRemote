/**
 * @file src/rhremote_capture_work.h
 * @brief Fixed-size, opt-in capture timings without pixels, allocations or producer-side I/O.
 */
#pragma once

#include <array>
#include <chrono>
#include <cstdint>
#include <string_view>

namespace rhremote {
  /** @brief Actual CPU-side steps in the Desktop Duplication RAM capture path. */
  enum class capture_work_step : std::uint8_t {
    acquire,  ///< Release the previous duplication frame and acquire the next one.
    prepare,  ///< Inspect the surface/cursor and submit the GPU copy; not GPU completion.
    image_pool,  ///< Wait for a reusable CPU image from the capture pool.
    map,  ///< Map the staging texture, including any wait for the GPU readback.
    image_setup,  ///< Allocate/prepare the CPU image after its format is known.
    cpu_copy,  ///< Copy the mapped pixels into the CPU image.
    unmap,  ///< Release the staging texture mapping.
    cursor,  ///< Composite the cursor only when requested and visible.
    count  ///< Number of fixed timing slots; never serialized as a step.
  };

  /** @brief Return a stable diagnostic step name. @param step Fixed timing slot. @return Name or unknown for an invalid slot. */
  inline std::string_view capture_work_step_name(capture_work_step step) {
    constexpr std::array names {"acquire", "prepare", "image_pool", "map", "image_setup", "cpu_copy", "unmap", "cursor"};
    const auto index = static_cast<std::size_t>(step);
    return index < names.size() ? names[index] : "unknown";
  }

  /** @brief Read the same steady-clock epoch used by frame metadata. @return Monotonic microseconds. */
  inline std::int64_t capture_work_tick_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
  }

  /** @brief One actual call span; an unexecuted step is explicitly absent. */
  struct capture_work_span {
    std::int64_t begin_us {}, end_us {};  ///< Actual CPU call boundaries in the host steady clock.
    bool recorded {};  ///< False for an unsupported or unexecuted step, not a measured zero.
  };

  /** @brief One successful capture's fixed-size work profile, copied with its original image. */
  struct capture_work {
    bool dxgi_ram {};  ///< True only for an enabled and instrumented RAM capture.
    std::int64_t begin_us {}, end_us {};  ///< Whole snapshot call boundaries, excluding the later delivery callback.
    std::array<capture_work_span, static_cast<std::size_t>(capture_work_step::count)> steps {};  ///< No dynamically allocated timing data.

    /** @brief Start a profile only when an active session requested frame statistics. @param enabled Current capture-thread opt-in. @return Empty or active profile. */
    static capture_work start(bool enabled) {
      capture_work work;
      work.dxgi_ram = enabled;
      if (enabled) {
        work.begin_us = capture_work_tick_us();
      }
      return work;
    }

    /** @brief End a successful snapshot; disabled captures do not read the clock. */
    void finish() {
      if (dxgi_ram) {
        end_us = capture_work_tick_us();
      }
    }
  };

  /** @brief Measure a stack-owned call span; disabled capture never reads the clock. */
  class capture_work_scope {
    capture_work_span *span_ {};  ///< Only the caller-owned profile, never the image or a trace writer.

  public:
    /** @brief Start one actual step. @param work Current snapshot profile. @param step Fixed timing slot. */
    capture_work_scope(capture_work &work, capture_work_step step) {
      const auto index = static_cast<std::size_t>(step);
      if (work.dxgi_ram && index < work.steps.size()) {
        span_ = &work.steps[index];
        span_->begin_us = capture_work_tick_us();
      }
    }

    /** @brief Finish the same span without allocating, blocking or writing metadata. */
    ~capture_work_scope() {
      if (span_) {
        span_->end_us = capture_work_tick_us();
        span_->recorded = true;
      }
    }

    /** @brief A measured scope has exactly one lifetime owner. */
    capture_work_scope(const capture_work_scope &) = delete;
    /** @brief A measured scope cannot replace another owner. */
    capture_work_scope &operator=(const capture_work_scope &) = delete;
  };
}  // namespace rhremote
