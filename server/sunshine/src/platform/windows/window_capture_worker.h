/**
 * @file src/platform/windows/window_capture_worker.h
 * @brief User-process WGC with inherited private IPC and shared GPU textures.
 */
#pragma once
#include "src/platform/common.h"
#include "window_capture.h"

#include <chrono>
#include <cstdint>
#include <d3d11.h>
#include <memory>
#include <optional>
#include <utility>

namespace platf::window_capture {
  /** @brief Own exactly one selected-window user process and its GPU transport. */
  class broker_t {
    struct impl_t;  ///< Private Windows handles and D3D resources.
    std::unique_ptr<impl_t> impl;  ///< Pimpl permits use from the display interface.
    /** @brief Construct only after a validated startup. @param value Owned implementation. */
    explicit broker_t(std::unique_ptr<impl_t> value);

  public:
    /** @brief Retire only this worker; never stop a service or other application. */
    ~broker_t();
    /** @brief Spawn the logged-on user's worker on the exact device/target. @param device Capture GPU. @param target Selected lifetime. @param format Required DXGI format. @param fps Frame rate. @return Ready broker or no broker on explicit failure. */
    static std::shared_ptr<broker_t> start(ID3D11Device *device, target_ptr target, DXGI_FORMAT format, int fps);
    /** @brief Geometry acknowledged by the worker. @return Physical content dimensions. */
    std::pair<int, int> size() const;
    /** @brief Acquire one protected shared GPU frame. @param timeout Maximum wait. @param out Retained texture. @param time System-relative frame time. @return Capture state; never a desktop fallback. */
    capture_e next(std::chrono::milliseconds timeout, ID3D11Texture2D **out, std::uint64_t &time);
    /** @brief Release the keyed GPU ownership after consumer commands were queued. @return Capture state. */
    capture_e release();
    /** @brief Change cursor state through the bounded private pipe. @param visible Cursor inclusion. @return Zero on success. */
    int cursor(bool visible);
  };

  /** @brief Handle only the private worker command before normal Sunshine startup. @param argc Argument count. @param argv Arguments. @return Worker exit code when selected, otherwise no value. */
  std::optional<int> worker_arguments(int argc, char **argv);
}  // namespace platf::window_capture
