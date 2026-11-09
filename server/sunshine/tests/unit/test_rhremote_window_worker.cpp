/**
 * @file tests/unit/test_rhremote_window_worker.cpp
 * @brief Private worker transport cannot select another HWND or import unchecked GPU resources.
 */
#include "src/rhremote_window_worker.h"

#include <gtest/gtest.h>
#ifdef _WIN32
  #include "src/logging.h"
  #include "src/platform/windows/window_capture_worker.h"

  #include <chrono>
  #include <d3d11.h>
  #include <dxgi1_2.h>
  #include <fstream>
  #include <nlohmann/json.hpp>
  #include <winrt/base.h>
#endif

namespace {
  /** @brief Complete inert request without touching native handles. @return Protocol fixture. */
  rhremote::window_worker::request request() {
    rhremote::window_worker::request value {};
    value.window = 1;
    value.marker = 2;
    value.process = 3;
    constexpr auto property = u"RHRemote.WindowCapture.0123456789abcdef0123456789abcdef";
    std::copy(property, property + std::char_traits<char16_t>::length(property), value.property.begin());
    return value;
  }
}  // namespace

TEST(WindowWorkerProtocol, RejectsInvalidNativeIdentityAndPropertyNamespaces) {
  const auto valid = request();
  EXPECT_TRUE(rhremote::window_worker::valid(valid));
  for (int field = 0; field < 7; ++field) {
    auto bad = valid;
    switch (field) {
      case 0:
        bad.window = 0;
        break;
      case 1:
        bad.process = 0;
        break;
      case 2:
        bad.marker = 0;
        break;
      case 3:
        bad.protocol = 2;
        break;
      case 4:
        bad.property[0] = u'X';
        break;
      case 5:
        bad.property.back() = u'a';
        break;
      default:
        bad.property[25] = u'/';
        break;
    }
    EXPECT_FALSE(rhremote::window_worker::valid(bad));
  }
}

TEST(WindowWorkerProtocol, RestrictsTextureFormatsAllocationBudgetsAndCommands) {
  using namespace rhremote::window_worker;
  EXPECT_TRUE(geometry(1920, 1080, bgra));
  EXPECT_TRUE(geometry(10200, 2160, rgba16));
  EXPECT_FALSE(geometry(0, 1080, bgra));
  EXPECT_FALSE(geometry(16384, 16384, bgra));
  EXPECT_FALSE(geometry(100, 100, 28));
  control value {};
  EXPECT_TRUE(valid(value));
  value.action = command::cursor;
  value.value = 1;
  EXPECT_TRUE(valid(value));
  value.value = 2;
  EXPECT_FALSE(valid(value));
  value.value = 0;
  value.action = static_cast<command>(99);
  EXPECT_FALSE(valid(value));
}

TEST(WindowWorkerProtocol, ValidatesEveryReplyBeforeImportingHandlesOrFrames) {
  using namespace rhremote::window_worker;
  response value {};
  value.type = kind::ready;
  value.width = 1920;
  value.height = 1080;
  value.handle = 10;
  EXPECT_TRUE(valid(value));
  value.handle = 0;
  EXPECT_FALSE(valid(value));
  value.type = kind::frame;
  value.sequence = 1;
  value.timestamp = 1;
  EXPECT_TRUE(valid(value));
  value.handle = 10;
  EXPECT_FALSE(valid(value));
  value.handle = 0;
  value.timestamp = 0;
  EXPECT_FALSE(valid(value));
  value.type = kind::error;
  value.status = -1;
  EXPECT_TRUE(valid(value));
  value.status = 0;
  EXPECT_FALSE(valid(value));
  value.status = -1;
  value.protocol = 2;
  EXPECT_FALSE(valid(value));
}

#ifdef _WIN32
namespace rhremote::window_testing {
  /** @brief Bound a capture check to a synthetic fixture, not another desktop application. @param argc Arguments. @param argv Arguments. @return Result only for this private test mode. */
  std::optional<int> arguments(int argc, char **argv) {
    if (argc < 2 || std::string_view(argv[1]) != "--rh-window-integration") {
      return {};
    }
    if (argc != 5) {
      return 2;
    }
    nlohmann::json result {{"success", false}, {"syntheticFixtureOnly", true}, {"pixelsExported", false}};
    auto log = logging::init(2, "window-worker-integration.log");
    try {
      const auto window = reinterpret_cast<HWND>(static_cast<std::uintptr_t>(std::stoull(argv[2])));
      const auto expected = std::stoul(argv[3]);
      DWORD process = 0;
      wchar_t name[128] {};
      GetWindowThreadProcessId(window, &process);
      GetClassNameW(window, name, std::size(name));
      if (process != expected || std::wstring_view(name) != L"RHRemoteWindowWorkerFixture") {
        throw std::runtime_error("Expected owned synthetic fixture");
      }
      auto target = platf::window_capture::testing::metadata(window);
      if (!target || !target->alive() || target->item) {
        throw std::runtime_error("Metadata unexpectedly activated WGC");
      }
      const auto probe_start = std::chrono::steady_clock::now();
      result["supportedInSystem"] = platf::window_capture::supported();
      result["supportCheckMs"] = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - probe_start).count();
      if (!result["supportedInSystem"].get<bool>()) {
        throw std::runtime_error("SYSTEM metadata capability is unavailable");
      }
      winrt::com_ptr<IDXGIFactory1> factory;
      winrt::check_hresult(CreateDXGIFactory1(IID_IDXGIFactory1, factory.put_void()));
      winrt::com_ptr<IDXGIAdapter1> adapter;
      winrt::check_hresult(factory->EnumAdapters1(0, adapter.put()));
      winrt::com_ptr<ID3D11Device> device;
      winrt::com_ptr<ID3D11DeviceContext> context;
      winrt::check_hresult(D3D11CreateDevice(adapter.get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr, 0, nullptr, 0, D3D11_SDK_VERSION, device.put(), nullptr, context.put()));
      const auto start = std::chrono::steady_clock::now();
      auto broker = platf::window_capture::broker_t::start(device.get(), target, DXGI_FORMAT_B8G8R8A8_UNORM, 30);
      if (!broker) {
        throw std::runtime_error("Broker startup failed");
      }
      const auto first_size = broker->size();
      result["initialWidth"] = first_size.first;
      result["initialHeight"] = first_size.second;
      unsigned frames = 0;
      bool color = false;
      std::uint64_t previous = 0;
      for (unsigned attempt = 0; attempt < 30 && frames < 8; ++attempt) {
        winrt::com_ptr<ID3D11Texture2D> frame;
        std::uint64_t timestamp = 0;
        const auto captured = broker->next(std::chrono::milliseconds(300), frame.put(), timestamp);
        if (captured == platf::capture_e::timeout) {
          continue;
        }
        if (captured != platf::capture_e::ok || timestamp < previous) {
          throw std::runtime_error("Frame transport or ordering failed");
        }
        previous = timestamp;
        if (!frames) {
          result["firstFrameMs"] = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
          D3D11_TEXTURE2D_DESC description {};
          description.Width = 1;
          description.Height = 1;
          description.MipLevels = 1;
          description.ArraySize = 1;
          description.SampleDesc.Count = 1;
          description.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
          description.Usage = D3D11_USAGE_STAGING;
          description.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
          winrt::com_ptr<ID3D11Texture2D> sample;
          winrt::check_hresult(device->CreateTexture2D(&description, nullptr, sample.put()));
          const D3D11_BOX source {32, 32, 0, 33, 33, 1};
          context->CopySubresourceRegion(sample.get(), 0, 0, 0, 0, frame.get(), 0, &source);
          D3D11_MAPPED_SUBRESOURCE mapped {};
          winrt::check_hresult(context->Map(sample.get(), 0, D3D11_MAP_READ, 0, &mapped));
          const auto pixel = static_cast<const unsigned char *>(mapped.pData);
          color = std::abs(int(pixel[2]) - 0x12) <= 6 && std::abs(int(pixel[1]) - 0x34) <= 6 && std::abs(int(pixel[0]) - 0x56) <= 6;
          context->Unmap(sample.get(), 0);
          // Force producer backpressure; a pending latest frame must survive it.
          Sleep(100);
        }
        if (broker->release() != platf::capture_e::ok) {
          throw std::runtime_error("GPU ownership release failed");
        }
        ++frames;
      }
      result["frames"] = frames;
      result["fixtureColorMatches"] = color;
      if (frames != 8 || !color || broker->cursor(false) != 0 || broker->cursor(true) != 0) {
        throw std::runtime_error("Frame, fixture color or cursor check failed");
      }
      PostMessageW(window, WM_APP + 1, 0, 0);
      bool resized = false;
      for (unsigned attempt = 0; attempt < 20 && !resized; ++attempt) {
        winrt::com_ptr<ID3D11Texture2D> frame;
        std::uint64_t timestamp = 0;
        const auto captured = broker->next(std::chrono::milliseconds(200), frame.put(), timestamp);
        resized = captured == platf::capture_e::reinit;
        if (captured == platf::capture_e::error) {
          throw std::runtime_error("Resize failed instead of requesting reinitialization");
        }
        broker->release();
      }
      result["resizeRequestsReinit"] = resized;
      if (!resized) {
        throw std::runtime_error("Resize was not observed");
      }
      broker.reset();
      broker = platf::window_capture::broker_t::start(device.get(), target, DXGI_FORMAT_B8G8R8A8_UNORM, 30);
      if (!broker || broker->size() != std::pair(800, 450)) {
        throw std::runtime_error("Resized capture geometry was not re-created");
      }
      result["resizeRecreated"] = true;
      PostMessageW(window, WM_APP + 2, 0, 0);
      bool closed = false;
      for (unsigned attempt = 0; attempt < 20 && !closed; ++attempt) {
        winrt::com_ptr<ID3D11Texture2D> frame;
        std::uint64_t timestamp = 0;
        const auto captured = broker->next(std::chrono::milliseconds(100), frame.put(), timestamp);
        closed = captured == platf::capture_e::error;
        broker->release();
      }
      result["closedWithoutDesktopFallback"] = closed;
      broker.reset();
      target.reset();
      result["success"] = closed;
    } catch (const std::exception &failure) {
      result["error"] = failure.what();
    }
    std::ofstream output {std::filesystem::path(argv[4])};
    output << result.dump(2);
    return result.value("success", false) ? 0 : 1;
  }
}  // namespace rhremote::window_testing
#endif
