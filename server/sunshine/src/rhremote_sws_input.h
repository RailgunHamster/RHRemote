/**
 * @file src/rhremote_sws_input.h
 * @brief Reference padded capture storage during synchronous software conversion.
 */
#pragma once

#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <optional>

extern "C" {
#include <libavcodec/defs.h>
#include <libavutil/buffer.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/pixfmt.h>
}

namespace rhremote {
  inline constexpr std::size_t source_frame_padding = 64;  ///< Zero tail reserved for FFmpeg vector reads.
  static_assert(source_frame_padding >= AV_INPUT_BUFFER_PADDING_SIZE);

  /** @brief Check a packed image allocation including its zero tail. @param pitch Bytes per row. @param height Rows. @return Allocation size, or no value on zero/overflow. */
  inline std::optional<std::size_t> padded_source_bytes(std::size_t pitch, std::size_t height) {
    if (!pitch || !height || height > (std::numeric_limits<std::size_t>::max() - source_frame_padding) / pitch) {
      return std::nullopt;
    }
    return pitch * height + source_frame_padding;
  }

  /**
   * @brief Hold an actual image owner in an AVBufferRef, avoiding av_frame_ref's full-image copy.
   * @details Only owned, sufficiently padded BGR0 input is eligible. Other capture backends keep
   * FFmpeg's existing copying path. The input reference is scoped to sws_scale_frame; any internal
   * reference retained on an error also owns the image until the scaling context releases it.
   */
  class sws_source_reference {
  public:
    /** @brief Bind eligible capture memory without changing its pixel data. @param frame Input frame with borrowed data pointers. @param owner Actual shared image owner. @param capacity Allocated bytes including zero tail. */
    sws_source_reference(AVFrame &frame, std::shared_ptr<void> owner, std::size_t capacity):
        frame_(frame) {
      if (!owner || frame.format != AV_PIX_FMT_BGR0 || !capacity) {
        return;
      }
      if (frame.buf[0] || !frame.data[0] || frame.width <= 0 || frame.height <= 0 || frame.linesize[0] <= 0 || static_cast<std::size_t>(frame.width) > static_cast<std::size_t>(frame.linesize[0]) / 4) {
        status_ = AVERROR(EINVAL);
        return;
      }
      const auto required = padded_source_bytes(frame.linesize[0], frame.height);
      if (!required || capacity < *required) {
        return;
      }
      auto lifetime = std::make_unique<std::shared_ptr<void>>(std::move(owner));
      auto ref = av_buffer_create(frame.data[0], capacity, release_owner, lifetime.get(), AV_BUFFER_FLAG_READONLY);
      if (!ref) {
        status_ = AVERROR(ENOMEM);
        return;
      }
      lifetime.release();
      frame_.buf[0] = ref;
      bound_ = true;
    }

    /** @brief Release only the reference installed by this scope. */
    ~sws_source_reference() {
      if (bound_) {
        av_buffer_unref(&frame_.buf[0]);
      }
    }

    sws_source_reference(const sws_source_reference &) = delete;
    sws_source_reference &operator=(const sws_source_reference &) = delete;

    /** @brief Report a failed binding; an ineligible image is a successful fallback. @return Zero or a negative AVERROR. */
    int status() const {
      return status_;
    }

    /** @brief Inspect whether this scope installed a real reference. @return Whether input pixels can be referenced directly. */
    bool bound() const {
      return bound_;
    }

  private:
    /** @brief Drop the image owner when the final FFmpeg reference disappears. @param opaque Shared image owner. @param data Borrowed pixels, released by that owner. */
    static void release_owner(void *opaque, std::uint8_t *data) {
      (void) data;
      delete static_cast<std::shared_ptr<void> *>(opaque);
    }

    AVFrame &frame_;  ///< Input frame outlives this scope.
    int status_ {};  ///< Actual allocation/validation failure, distinct from fallback.
    bool bound_ {};  ///< Whether this scope owns frame.buf[0].
  };
}  // namespace rhremote
