/**
 * @file src/rhremote_window_worker.h
 * @brief Fixed private-pipe messages for a user-owned window capture worker.
 */
#pragma once
#include <algorithm>
#include <array>
#include <cstdint>

namespace rhremote::window_worker {
  constexpr std::uint32_t magic = 0x52485743;  ///< RHWC, never a network protocol.
  constexpr std::uint32_t version = 1;  ///< Independent from release versions.
  constexpr std::uint32_t bgra = 87, rgba16 = 10;  ///< Supported DXGI texture formats.
  /** @brief Commands accepted only from the parent's inherited pipe. */
  enum class command : std::uint32_t {
    stop = 1,
    cursor = 2
  };
  /** @brief Worker results never contain titles, credentials or pixel payloads. */
  enum class kind : std::uint32_t {
    ready = 1,
    frame = 2,
    resized = 3,
    closed = 4,
    error = 5
  };

  /** @brief One selected native lifetime, transmitted only through inherited handles. */
  struct request {
    std::uint32_t tag = magic, protocol = version;  ///< Framing and compatibility.
    std::uint64_t window = 0, marker = 0, adapter = 0;  ///< Exact target identity and GPU LUID.
    std::uint32_t process = 0, format = bgra, fps = 30, reserved = 0;  ///< Bounded capture settings.
    std::array<char16_t, 128> property {};  ///< Private lifetime-property name, NUL-terminated.
  };

  /** @brief Small, bounded parent-to-worker command. */
  struct control {
    std::uint32_t tag = magic, protocol = version;  ///< Framing and compatibility.
    command action = command::stop;  ///< No arbitrary process/file actions.
    std::uint32_t value = 0;  ///< Boolean cursor state only.
  };

  /** @brief A shared GPU texture and frame timing, never copied image bytes. */
  struct response {
    std::uint32_t tag = magic, protocol = version;  ///< Framing and compatibility.
    kind type = kind::error;  ///< Discriminates payload validation.
    std::int32_t status = 0;  ///< HRESULT for a failed worker operation.
    std::uint32_t width = 0, height = 0, format = bgra, stage = 0;  ///< Geometry and failure stage.
    std::uint64_t handle = 0, sequence = 0;  ///< Child-owned shared handle; monotonic frame ordinal.
    std::int64_t timestamp = 0;  ///< SystemRelativeTime in 100-nanosecond units.
  };

  /** @brief Bound every shared texture before allocation/open. @param width Width. @param height Height. @param format DXGI enum value. @return Valid bounded capture geometry. */
  inline bool geometry(std::uint32_t width, std::uint32_t height, std::uint32_t format) {
    return width > 0 && height > 0 && width <= 16384 && height <= 16384 && std::uint64_t(width) * height <= 67108864 && (format == bgra || format == rgba16);
  }

  /** @brief Validate a private request without reading any native handles. @param value Parent-owned request. @return Whether its structure is supported. */
  inline bool valid(const request &value) {
    constexpr char16_t prefix[] = u"RHRemote.WindowCapture.";
    constexpr auto length = std::size(prefix) - 1;
    return value.tag == magic && value.protocol == version && value.window && value.marker && value.process && value.fps > 0 && value.fps <= 500 && !value.reserved &&
           (value.format == bgra || value.format == rgba16) && std::equal(prefix, prefix + length, value.property.begin()) &&
           std::all_of(value.property.begin() + length, value.property.begin() + length + 32, [](char16_t v) {
             return (v >= u'0' && v <= u'9') || (v >= u'a' && v <= u'f');
           }) &&
           std::all_of(value.property.begin() + length + 32, value.property.end(), [](char16_t v) {
             return v == 0;
           });
  }

  /** @brief Reject ambiguous commands. @param value Parent control. @return Whether the command is valid. */
  inline bool valid(const control &value) {
    return value.tag == magic && value.protocol == version && ((value.action == command::stop && value.value == 0) || (value.action == command::cursor && value.value <= 1));
  }

  /** @brief Validate a worker reply before trusting dimensions or handles. @param value Worker message. @return Whether its framing/payload is supported. */
  inline bool valid(const response &value) {
    if (value.tag != magic || value.protocol != version) {
      return false;
    }
    if (value.type == kind::error || value.type == kind::closed) {
      return value.status < 0 && !value.handle;
    }
    if (value.status || !geometry(value.width, value.height, value.format)) {
      return false;
    }
    if (value.type == kind::ready) {
      return value.handle && !value.sequence;
    }
    if (value.type == kind::resized) {
      return !value.handle;
    }
    return value.type == kind::frame && !value.handle && value.sequence && value.timestamp > 0;
  }
}  // namespace rhremote::window_worker
