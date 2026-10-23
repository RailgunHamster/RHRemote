/**
 * @file src/platform/windows/rhremote_encoder_environment.h
 * @brief Read-only graphics identity and health evidence for software capability reuse.
 */
#pragma once

#include "src/rhremote_encoder_probe.h"

#include <bit>
#include <d3d11.h>
#include <dxgi1_6.h>
#include <memory>
#include <mutex>
#include <string>

namespace rhremote::win_probe {
  /** @brief Release one owned COM reference. */
  struct release_com {
    /** @brief Release a non-null interface supplied by a COM owning pointer. @param value Owned interface. */
    template<class T>
    void operator()(T *value) const {
      value->Release();
    }
  };
  /** @brief Local COM ownership without borrowing a capture object. */
  template<class T>
  using com_ptr = std::unique_ptr<T, release_com>;

  /** @brief Restore the caller's DPI context after physical-pixel metadata queries. */
  struct restore_dpi {
    DPI_AWARENESS_CONTEXT previous;  ///< Original calling-thread context; never changes process or display settings.

    /** @brief Restore the original context on every exit path. */
    ~restore_dpi() {
      SetThreadDpiAwarenessContext(previous);
    }
  };

  inline std::mutex health_mutex;  ///< Serializes health references, never held while creating devices.
  inline encoder_device_health<std::shared_ptr<ID3D11Device>> device_health;  ///< Health only, never used to capture or encode.

  /** @brief Inspect removal without creating another device. @param device Original real D3D device. @return Whether it remains healthy. */
  inline bool healthy(const std::shared_ptr<ID3D11Device> &device) {
    return device->GetDeviceRemovedReason() == S_OK;
  }

  /**
   * @brief Keep a health reference from successful real initialization; does not cache eligibility.
   * @param adapter Adapter which actually created the device.
   * @param device Successfully created D3D device.
   */
  inline void remember(IDXGIAdapter1 *adapter, ID3D11Device *device) {
    DXGI_ADAPTER_DESC1 desc {};
    if (!device || FAILED(adapter->GetDesc1(&desc))) {
      return;
    }
    device->AddRef();
    auto owner = std::shared_ptr<ID3D11Device>(device, release_com {});
    std::lock_guard lock(health_mutex);
    device_health.remember({desc.AdapterLuid.LowPart, desc.AdapterLuid.HighPart}, std::move(owner), healthy);
  }

  /**
   * @brief Append a bounded UTF-16 string with its length, avoiding fingerprint ambiguity.
   * @tparam N Buffer capacity including terminator.
   * @param state Environment being collected.
   * @param value Terminated Windows string.
   * @return False when Windows supplied an unterminated string.
   */
  template<std::size_t N>
  bool text(encoder_environment &state, const WCHAR (&value)[N]) {
    const auto end = std::find(value, value + N, L'\0');
    if (end == value + N) {
      return false;
    }
    state.invariant.push_back(end - value);
    state.invariant.insert(state.invariant.end(), value, end);
    return true;
  }

  /**
   * @brief Collect fresh metadata without creating a D3D device or capturing pixels.
   * @param factory Newly created factory, checked again by the caller after collection.
   * @param reason Optional bounded failure stage, without identity or credential contents.
   * @return Complete identities and modes; empty on unknown, unsupported or raced state.
   */
  inline std::optional<encoder_environment> snapshot(IDXGIFactory1 *factory, std::string *reason = nullptr) {
    if (reason) {
      reason->clear();
    }
    auto fail = [&](const char *stage) -> std::optional<encoder_environment> {
      if (reason) {
        *reason = stage;
      }
      return {};
    };
    const auto old_dpi = SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    if (!old_dpi) {
      return fail("physical-pixel-context");
    }
    restore_dpi dpi {old_dpi};
    encoder_environment result;
    auto add = [&](auto value) {
      result.invariant.push_back(static_cast<std::uint64_t>(value));
    };
    auto luid = [&](const LUID &value) {
      add(value.LowPart);
      add(value.HighPart);
    };
    WCHAR desktop_name[256] {};
    DWORD needed = 0;
    auto desktop = OpenInputDesktop(0, FALSE, DESKTOP_READOBJECTS);
    if (!desktop) {
      return fail("input-desktop");
    }
    const bool named = GetUserObjectInformationW(desktop, UOI_NAME, desktop_name, sizeof(desktop_name), &needed);
    CloseDesktop(desktop);
    if (!named || !text(result, desktop_name)) {
      return fail("desktop-name");
    }
    UINT a = 0;
    for (; a < 64; ++a) {
      IDXGIAdapter1 *raw = nullptr;
      const auto status = factory->EnumAdapters1(a, &raw);
      com_ptr<IDXGIAdapter1> adapter(raw);
      if (status == DXGI_ERROR_NOT_FOUND) {
        break;
      }
      DXGI_ADAPTER_DESC1 desc {};
      if (FAILED(status) || !adapter || FAILED(adapter->GetDesc1(&desc))) {
        return fail("adapter-description");
      }
      add(0xADA);
      luid(desc.AdapterLuid);
      add(desc.VendorId);
      add(desc.DeviceId);
      add(desc.SubSysId);
      add(desc.Revision);
      add(desc.Flags);
      if (!text(result, desc.Description)) {
        return fail("adapter-name");
      }
      LARGE_INTEGER version {};
      const auto driver_status = adapter->CheckInterfaceSupport(__uuidof(IDXGIDevice), &version);
      add(driver_status);
      add(SUCCEEDED(driver_status) ? version.QuadPart : 0);
      bool attached = false;
      UINT o = 0;
      for (; o < 64; ++o) {
        IDXGIOutput *output_raw = nullptr;
        const auto output_status = adapter->EnumOutputs(o, &output_raw);
        com_ptr<IDXGIOutput> output(output_raw);
        if (output_status == DXGI_ERROR_NOT_FOUND) {
          break;
        }
        DXGI_OUTPUT_DESC base {};
        if (FAILED(output_status) || !output || FAILED(output->GetDesc(&base))) {
          return fail("output-description");
        }
        add(0x0A7);
        if (!text(result, base.DeviceName)) {
          return fail("output-name");
        }
        add(base.AttachedToDesktop);
        add(base.Rotation);
        add(base.DesktopCoordinates.left);
        add(base.DesktopCoordinates.top);
        if (!base.AttachedToDesktop) {
          continue;
        }
        const auto width = std::int64_t(base.DesktopCoordinates.right) - base.DesktopCoordinates.left;
        const auto height = std::int64_t(base.DesktopCoordinates.bottom) - base.DesktopCoordinates.top;
        if (width <= 0 || height <= 0 || width > UINT32_MAX || height > UINT32_MAX) {
          return fail("output-dimensions");
        }
        result.modes.emplace_back(width, height);
        IDXGIOutput6 *output6_raw = nullptr;
        const auto modern_status = output->QueryInterface(IID_IDXGIOutput6, reinterpret_cast<void **>(&output6_raw));
        com_ptr<IDXGIOutput6> modern(output6_raw);
        DXGI_OUTPUT_DESC1 color {};
        if (FAILED(modern_status) || !modern || FAILED(modern->GetDesc1(&color))) {
          return fail("output-color");
        }
        add(color.BitsPerColor);
        add(color.ColorSpace);
        for (float v : {color.RedPrimary[0], color.RedPrimary[1], color.GreenPrimary[0], color.GreenPrimary[1], color.BluePrimary[0], color.BluePrimary[1], color.WhitePoint[0], color.WhitePoint[1], color.MinLuminance, color.MaxLuminance, color.MaxFullFrameLuminance}) {
          add(std::bit_cast<std::uint32_t>(v));
        }
        attached = true;
      }
      if (o == 64) {
        return fail("output-limit");
      }
      add(0xE0D);
      if (attached) {
        result.active_adapters.emplace_back(desc.AdapterLuid.LowPart, desc.AdapterLuid.HighPart);
      }
    }
    if (a == 0 || a == 64 || result.modes.empty()) {
      return fail("active-adapter-inventory");
    }
    add(0xEAD);
    UINT path_count = 0, mode_count = 0;
    if (GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS, &path_count, &mode_count) != ERROR_SUCCESS || !path_count || path_count > 256 || mode_count > 1024) {
      return fail("display-config-size");
    }
    std::vector<DISPLAYCONFIG_PATH_INFO> paths(path_count);
    std::vector<DISPLAYCONFIG_MODE_INFO> modes(mode_count);
    if (QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS, &path_count, paths.data(), &mode_count, modes.data(), nullptr) != ERROR_SUCCESS) {
      return fail("display-config-query");
    }
    add(path_count);
    for (UINT i = 0; i < path_count; ++i) {
      const auto &path = paths[i];
      DISPLAYCONFIG_SOURCE_DEVICE_NAME source {};
      source.header = {DISPLAYCONFIG_DEVICE_INFO_GET_SOURCE_NAME, sizeof(source), path.sourceInfo.adapterId, path.sourceInfo.id};
      DISPLAYCONFIG_TARGET_DEVICE_NAME target {};
      target.header = {DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_NAME, sizeof(target), path.targetInfo.adapterId, path.targetInfo.id};
      DISPLAYCONFIG_GET_ADVANCED_COLOR_INFO color {};
      color.header = {DISPLAYCONFIG_DEVICE_INFO_GET_ADVANCED_COLOR_INFO, sizeof(color), path.targetInfo.adapterId, path.targetInfo.id};
      if (DisplayConfigGetDeviceInfo(&source.header) != ERROR_SUCCESS || DisplayConfigGetDeviceInfo(&target.header) != ERROR_SUCCESS || DisplayConfigGetDeviceInfo(&color.header) != ERROR_SUCCESS || !text(result, source.viewGdiDeviceName) || !text(result, target.monitorDevicePath)) {
        return fail("display-device-info");
      }
      luid(path.sourceInfo.adapterId);
      add(path.sourceInfo.id);
      luid(path.targetInfo.adapterId);
      add(path.targetInfo.id);
      add(path.targetInfo.targetAvailable);
      add(path.targetInfo.outputTechnology);
      add(path.targetInfo.rotation);
      add(path.targetInfo.scaling);
      add(path.targetInfo.refreshRate.Numerator);
      add(path.targetInfo.refreshRate.Denominator);
      add(color.value);
      add(color.colorEncoding);
      add(color.bitsPerColorChannel);
      const auto index = path.sourceInfo.modeInfoIdx;
      if (index >= mode_count || modes[index].infoType != DISPLAYCONFIG_MODE_INFO_TYPE_SOURCE) {
        return fail("display-source-mode");
      }
      add(modes[index].sourceMode.pixelFormat);
      add(modes[index].sourceMode.position.x);
      add(modes[index].sourceMode.position.y);
    }
    return result;
  }

  /**
   * @brief Inspect sentinels only for the exact currently attached adapters.
   * @param state Fresh complete metadata.
   * @return Healthy, missing, or known lost original device state.
   */
  inline probe_device_health health(const encoder_environment &state) {
    std::lock_guard lock(health_mutex);
    device_health.retain(state.active_adapters);
    return device_health.state(state.active_adapters, healthy);
  }
}  // namespace rhremote::win_probe
