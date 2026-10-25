/**
 * @file tests/unit/test_rhremote_sws_input.cpp
 * @brief Exercise real FFmpeg references, owner lifetime and pixel-identical scaling.
 */
#include "src/rhremote_sws_input.h"

#include <algorithm>
#include <climits>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

extern "C" {
#include <libavutil/imgutils.h>
#include <libavutil/mem.h>
#include <libavutil/opt.h>
#include <libavutil/pixdesc.h>
#include <libswscale/swscale.h>
}
#ifndef RHREMOTE_SWS_INPUT_STANDALONE
  #include "src/video.h"

  #include <gtest/gtest.h>
  #ifdef _WIN32
    #include "src/platform/windows/display.h"
  #endif
#endif

namespace {
  /** @brief Require the same invariant in standalone and GoogleTest runs. @param value Required condition. */
  void require(bool value) {
    if (!value) {
      std::abort();
    }
  }

  /** @brief Free an actual FFmpeg frame. @param frame Frame to release. */
  void free_frame(AVFrame *frame) {
    av_frame_free(&frame);
  }

  using frame_ptr = std::unique_ptr<AVFrame, decltype(&free_frame)>;  ///< Actual frame owner.

  /** @brief Allocate frame metadata. @param format Pixel format. @param width Pixel width. @param height Pixel height. @return Owned empty frame. */
  frame_ptr frame(AVPixelFormat format, int width, int height) {
    frame_ptr result(av_frame_alloc(), free_frame);
    require(bool(result));
    result->format = format;
    result->width = width;
    result->height = height;
    return result;
  }

  /** @brief Make a nonuniform padded BGR0 input. @param input Empty metadata. @param seed Changing pixel pattern. @return Actual storage owner. */
  std::shared_ptr<std::vector<std::uint8_t>> pixels(AVFrame &input, unsigned seed = 0) {
    input.linesize[0] = input.width * 4;
    auto capacity = rhremote::padded_source_bytes(input.linesize[0], input.height);
    require(capacity.has_value());
    auto result = std::make_shared<std::vector<std::uint8_t>>(*capacity, 0);
    input.data[0] = result->data();
    for (std::size_t i = 0; i < *capacity - rhremote::source_frame_padding; ++i) {
      (*result)[i] = (i * 37 + i / 13 + seed * 19) & 255;
    }
    return result;
  }

  /** @brief Capacity checks reject zeros and overflow without allocating. */
  void capacity_bounds() {
    require(!rhremote::padded_source_bytes(0, 1080));
    require(!rhremote::padded_source_bytes(7680, 0));
    require(!rhremote::padded_source_bytes(std::numeric_limits<std::size_t>::max(), 1));
    require(!rhremote::padded_source_bytes(2, std::numeric_limits<std::size_t>::max()));
    require(rhremote::padded_source_bytes(7680, 1080) == 8294464);
    require(rhremote::padded_source_bytes(1, std::numeric_limits<std::size_t>::max() - 64) == std::numeric_limits<std::size_t>::max());
  }

  /** @brief Legacy non-refcounted input copies, while the new path references the same pixels. */
  void real_reference_identity() {
    auto input = frame(AV_PIX_FMT_BGR0, 67, 49);
    auto storage = pixels(*input);
    auto copied = frame(AV_PIX_FMT_NONE, 0, 0);
    require(av_frame_ref(copied.get(), input.get()) == 0);
    require(copied->data[0] != input->data[0]);
    require(std::memcmp(copied->data[0], input->data[0], input->width * 4) == 0);
    auto referenced = frame(AV_PIX_FMT_NONE, 0, 0);
    {
      rhremote::sws_source_reference reference(*input, storage, storage->size());
      require(reference.status() == 0 && reference.bound());
      require(av_frame_ref(referenced.get(), input.get()) == 0);
      require(referenced->data[0] == input->data[0]);
      require(av_buffer_is_writable(input->buf[0]) == 0);
    }
    require(input->buf[0] == nullptr);
    require(referenced->data[0] == storage->data());
  }

  /** @brief An internal reference, including one retained on failure, owns the captured image. */
  void retained_owner_lifetime() {
    auto input = frame(AV_PIX_FMT_BGR0, 64, 48);
    auto storage = pixels(*input);
    std::weak_ptr<std::vector<std::uint8_t>> weak = storage;
    auto retained = frame(AV_PIX_FMT_NONE, 0, 0);
    {
      rhremote::sws_source_reference reference(*input, storage, storage->size());
      require(reference.bound());
      storage.reset();
      require(!weak.expired());
      require(av_frame_ref(retained.get(), input.get()) == 0);
    }
    require(!weak.expired() && input->buf[0] == nullptr);
    retained.reset();
    require(weak.expired());
  }

  /** @brief Missing ownership, unknown/short padding and other formats preserve copying fallback. */
  void copying_fallbacks() {
    auto input = frame(AV_PIX_FMT_BGR0, 64, 48);
    auto storage = pixels(*input);
    {
      rhremote::sws_source_reference reference(*input, {}, storage->size());
      require(reference.status() == 0 && !reference.bound() && !input->buf[0]);
    }
    for (auto capacity : {std::size_t {0}, storage->size() - 1}) {
      rhremote::sws_source_reference reference(*input, storage, capacity);
      require(reference.status() == 0 && !reference.bound() && !input->buf[0]);
    }
    input->format = AV_PIX_FMT_NV12;
    rhremote::sws_source_reference reference(*input, storage, storage->size());
    require(reference.status() == 0 && !reference.bound() && !input->buf[0]);
  }

  /** @brief Invalid eligible views fail without removing someone else's existing reference. */
  void invalid_views() {
    for (int condition = 0; condition < 6; ++condition) {
      auto input = frame(AV_PIX_FMT_BGR0, 64, 48);
      auto storage = pixels(*input);
      if (condition == 0) {
        input->data[0] = nullptr;
      }
      if (condition == 1) {
        input->width = 0;
      }
      if (condition == 2) {
        input->height = 0;
      }
      if (condition == 3) {
        input->linesize[0] = -256;
      }
      if (condition == 4) {
        input->linesize[0] = 255;
      }
      if (condition == 5) {
        input->buf[0] = av_buffer_alloc(16);
      }
      auto previous = input->buf[0];
      {
        rhremote::sws_source_reference reference(*input, storage, storage->size());
        require(reference.status() == AVERROR(EINVAL) && !reference.bound());
      }
      require(input->buf[0] == previous);
    }
  }

  /** @brief A real AVBuffer allocation failure releases the temporary owner and leaves metadata clean. */
  void allocation_failure() {
    auto input = frame(AV_PIX_FMT_BGR0, 64, 48);
    auto storage = pixels(*input);
    const auto count = storage.use_count();
    av_max_alloc(1);
    {
      rhremote::sws_source_reference reference(*input, storage, storage->size());
      av_max_alloc(INT_MAX);
      require(reference.status() == AVERROR(ENOMEM) && !reference.bound() && !input->buf[0]);
    }
    require(storage.use_count() == count);
  }

  /** @brief Create the same actual legacy scaler as production. @param input Source metadata. @param output Destination metadata. @param threads Actual slice thread count. @return Initialized scaler. */
  std::unique_ptr<SwsContext, decltype(&sws_freeContext)> scaler(const AVFrame &input, const AVFrame &output, int threads) {
    std::unique_ptr<SwsContext, decltype(&sws_freeContext)> result(sws_alloc_context(), sws_freeContext);
    require(bool(result));
    for (auto value : {std::pair {"srcw", input.width}, {"srch", input.height}, {"src_format", input.format}, {"dstw", output.width}, {"dsth", output.height}, {"dst_format", output.format}, {"sws_flags", SWS_LANCZOS | SWS_ACCURATE_RND}, {"threads", threads}}) {
      require(av_opt_set_int(result.get(), value.first, value.second, 0) == 0);
    }
    require(sws_init_context(result.get(), nullptr, nullptr) >= 0);
    return result;
  }

  /** @brief Unchanged filters produce byte-identical active pixels with and without the extra copy. @param threads Slice threads. @param scale Whether to scale to a different size. @param format Actual encoder pixel format. */
  void identical_scaling(int threads, bool scale, AVPixelFormat format) {
    auto input = frame(AV_PIX_FMT_BGR0, 67, 49);
    auto expected = frame(format, scale ? 126 : 67, scale ? 70 : 49);
    auto actual = frame(format, expected->width, expected->height);
    require(av_frame_get_buffer(expected.get(), 32) == 0 && av_frame_get_buffer(actual.get(), 32) == 0);
    auto baseline = scaler(*input, *expected, threads);
    auto optimized = scaler(*input, *actual, threads);
    for (unsigned seed = 0; seed < 3; ++seed) {
      auto storage = pixels(*input, seed);
      require(sws_scale_frame(baseline.get(), expected.get(), input.get()) >= 0);
      {
        rhremote::sws_source_reference reference(*input, storage, storage->size());
        require(reference.bound());
        require(sws_scale_frame(optimized.get(), actual.get(), input.get()) >= 0);
      }
      require(input->buf[0] == nullptr && storage.use_count() == 1);
      const auto descriptor = av_pix_fmt_desc_get(format);
      require(descriptor != nullptr);
      for (int plane = 0; plane < 3; ++plane) {
        const auto width = AV_CEIL_RSHIFT(actual->width, plane ? descriptor->log2_chroma_w : 0);
        const auto height = AV_CEIL_RSHIFT(actual->height, plane ? descriptor->log2_chroma_h : 0);
        for (int row = 0; row < height; ++row) {
          require(std::memcmp(expected->data[plane] + row * expected->linesize[plane], actual->data[plane] + row * actual->linesize[plane], width) == 0);
        }
      }
    }
  }
}  // namespace

#ifdef RHREMOTE_SWS_INPUT_STANDALONE
/** @brief Exercise the same actual FFmpeg cases without the full GPU-enabled executable. @return Successful test status. */
int main() {
  capacity_bounds();
  real_reference_identity();
  retained_owner_lifetime();
  copying_fallbacks();
  invalid_views();
  allocation_failure();
  for (int threads : {1, 2, 4}) {
    for (bool scale : {false, true}) {
      for (auto format : {AV_PIX_FMT_YUV420P, AV_PIX_FMT_YUV444P}) {
        identical_scaling(threads, scale, format);
      }
    }
  }
  std::puts("PASS:6 ownership/error cases;12 real swscale cases x3 changing frames, identical active pixels");
}
#else
TEST(RhRemoteSwsInput, CapacityBounds) {
  capacity_bounds();
}

TEST(RhRemoteSwsInput, RealReferenceIdentity) {
  real_reference_identity();
}

TEST(RhRemoteSwsInput, RetainedOwnerLifetime) {
  retained_owner_lifetime();
}

TEST(RhRemoteSwsInput, CopyingFallbacks) {
  copying_fallbacks();
}

TEST(RhRemoteSwsInput, InvalidViews) {
  invalid_views();
}

TEST(RhRemoteSwsInput, AllocationFailure) {
  allocation_failure();
}

TEST(RhRemoteSwsInput, Identical420SingleThread) {
  identical_scaling(1, false, AV_PIX_FMT_YUV420P);
}

TEST(RhRemoteSwsInput, Identical420TwoThreads) {
  identical_scaling(2, false, AV_PIX_FMT_YUV420P);
}

TEST(RhRemoteSwsInput, Identical420FourThreads) {
  identical_scaling(4, false, AV_PIX_FMT_YUV420P);
}

TEST(RhRemoteSwsInput, Identical444SingleThread) {
  identical_scaling(1, false, AV_PIX_FMT_YUV444P);
}

TEST(RhRemoteSwsInput, Identical444TwoThreads) {
  identical_scaling(2, false, AV_PIX_FMT_YUV444P);
}

TEST(RhRemoteSwsInput, Identical444FourThreads) {
  identical_scaling(4, false, AV_PIX_FMT_YUV444P);
}

TEST(RhRemoteSwsInput, IdenticalScaled420SingleThread) {
  identical_scaling(1, true, AV_PIX_FMT_YUV420P);
}

TEST(RhRemoteSwsInput, IdenticalScaled420FourThreads) {
  identical_scaling(4, true, AV_PIX_FMT_YUV420P);
}

TEST(RhRemoteSwsInput, IdenticalScaled444SingleThread) {
  identical_scaling(1, true, AV_PIX_FMT_YUV444P);
}

TEST(RhRemoteSwsInput, IdenticalScaled444TwoThreads) {
  identical_scaling(2, true, AV_PIX_FMT_YUV444P);
}

TEST(RhRemoteSwsInput, IdenticalScaled420TwoThreads) {
  identical_scaling(2, true, AV_PIX_FMT_YUV420P);
}

TEST(RhRemoteSwsInput, IdenticalScaled444FourThreads) {
  identical_scaling(4, true, AV_PIX_FMT_YUV444P);
}

  #ifdef _WIN32
/** @brief Real RAM storage remains padded across pitch changes, without initializing GPU capture. */
TEST(RhRemoteSwsInput, WindowsCaptureStorageAndPitchChange) {
  platf::dxgi::display_ddup_ram_t capture {};
  capture.width = 67;
  capture.height = 49;
  capture.capture_format = DXGI_FORMAT_B8G8R8A8_UNORM;
  capture.img_info.RowPitch = 320;
  auto image = capture.alloc_img();
  ASSERT_NE(image, nullptr);
  ASSERT_EQ(capture.complete_img(image.get(), false), 0);
  EXPECT_EQ(image->data_capacity, 320 * 49 + rhremote::source_frame_padding);
  auto original = image->data;
  ASSERT_EQ(capture.complete_img(image.get(), false), 0);
  EXPECT_EQ(image->data, original);
  capture.img_info.RowPitch = 640;
  ASSERT_EQ(capture.complete_img(image.get(), false), 0);
  EXPECT_EQ(image->row_pitch, 640);
  EXPECT_EQ(image->data_capacity, 640 * 49 + rhremote::source_frame_padding);
  EXPECT_TRUE(std::all_of(image->data + image->data_capacity - rhremote::source_frame_padding, image->data + image->data_capacity, [](auto x) {
    return x == 0;
  }));
  auto dummy = capture.alloc_img();
  ASSERT_EQ(capture.dummy_img(dummy.get()), 0);
  EXPECT_EQ(dummy->data_capacity, 67 * 4 * 49 + rhremote::source_frame_padding);
  EXPECT_TRUE(std::all_of(dummy->data, dummy->data + dummy->data_capacity, [](auto x) {
    return x == 0;
  }));
  capture.height = 0;
  auto invalid = capture.alloc_img();
  EXPECT_EQ(capture.complete_img(invalid.get(), false), -1);
  EXPECT_EQ(invalid->data, nullptr);
}

/** @brief Production conversion from an actual capture buffer matches the ownerless copying fallback. */
TEST(RhRemoteSwsInput, ProductionConvertKeepsPixelsAndReleasesImage) {
  platf::dxgi::display_ddup_ram_t capture {};
  capture.width = 67;
  capture.height = 49;
  capture.capture_format = DXGI_FORMAT_B8G8R8A8_UNORM;
  capture.img_info.RowPitch = 320;
  auto image = capture.alloc_img();
  ASSERT_EQ(capture.complete_img(image.get(), false), 0);
  for (std::size_t i = 0; i < image->data_capacity - rhremote::source_frame_padding; ++i) {
    image->data[i] = (i * 37 + i / 13) & 255;
  }
  platf::img_t ownerless;
  ownerless.data = image->data;
  ownerless.width = image->width;
  ownerless.height = image->height;
  ownerless.pixel_pitch = image->pixel_pitch;
  ownerless.row_pitch = image->row_pitch;
  video::avcodec_software_encode_device_t optimized, legacy;
  auto actual = frame(AV_PIX_FMT_YUV420P, 126, 70);
  auto expected = frame(AV_PIX_FMT_YUV420P, 126, 70);
  auto actual_ptr = actual.release(), expected_ptr = expected.release();
  ASSERT_EQ(optimized.set_frame(actual_ptr, nullptr), 0);
  ASSERT_EQ(legacy.set_frame(expected_ptr, nullptr), 0);
  ASSERT_EQ(optimized.init(67, 49, actual_ptr, AV_PIX_FMT_YUV420P, false), 0);
  ASSERT_EQ(legacy.init(67, 49, expected_ptr, AV_PIX_FMT_YUV420P, false), 0);
  ASSERT_EQ(optimized.convert(*image), 0);
  ASSERT_EQ(legacy.convert(ownerless), 0);
  for (int plane = 0; plane < 3; ++plane) {
    const auto width = AV_CEIL_RSHIFT(actual_ptr->width, plane ? 1 : 0);
    const auto height = AV_CEIL_RSHIFT(actual_ptr->height, plane ? 1 : 0);
    for (int row = 0; row < height; ++row) {
      EXPECT_EQ(std::memcmp(actual_ptr->data[plane] + row * actual_ptr->linesize[plane], expected_ptr->data[plane] + row * expected_ptr->linesize[plane], width), 0);
    }
  }
  EXPECT_EQ(image.use_count(), 1);
}
  #endif
#endif
