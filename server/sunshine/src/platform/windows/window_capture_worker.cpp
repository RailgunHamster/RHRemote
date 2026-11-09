/**
 * @file src/platform/windows/window_capture_worker.cpp
 * @brief Keep WGC activation in the interactive user's process; share GPU frames with SYSTEM.
 */
#include "window_capture_worker.h"

#include "capture_context.h"
#include "src/logging.h"
#include "src/rhremote_window_worker.h"

#include <array>
#include <charconv>
#include <d3d11_1.h>
#include <dxgi1_2.h>
#include <limits>
#include <roapi.h>
#include <string>
#include <userenv.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Metadata.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>
#define ____FIReference_1_boolean_INTERFACE_DEFINED__
#include <Windows.Graphics.Capture.Interop.h>

/** @brief Wrap a DXGI device for WGC. @param device DXGI device. @param result WinRT wrapper. @return HRESULT. */
extern "C" HRESULT WINAPI CreateDirect3D11DeviceFromDXGIDevice(IDXGIDevice *device, IInspectable **result);

namespace platf::window_capture {
  /**
   * @brief DXGI surface interop implemented by Windows, never by this translation unit.
   * External linkage is required: GCC can devirtualize an anonymous-namespace
   * abstract interface to a pure-virtual call because no local implementation exists.
   */
  struct surface_access_t: IUnknown {
    /** @brief Get a native surface. @param id Requested interface. @param result Native result. @return HRESULT. */
    virtual HRESULT STDMETHODCALLTYPE GetInterface(REFIID id, void **result) = 0;
  };

  namespace {
    namespace wire = rhremote::window_worker;
    using clock = std::chrono::steady_clock;  ///< All timeout accounting is monotonic.

    /** @brief Own one kernel handle, including inherited child handles. */
    struct handle_t {
      HANDLE value = nullptr;  ///< No handles are exposed to remote clients.
      handle_t() = default;

      explicit handle_t(HANDLE h):
          value(h) {}

      handle_t(const handle_t &) = delete;

      /** @brief Close exactly the owned handle. */
      ~handle_t() {
        reset();
      }

      /** @brief Replace an owned handle. @param h Replacement or null. */
      void reset(HANDLE h = nullptr) {
        if (value && value != INVALID_HANDLE_VALUE) {
          CloseHandle(value);
        }
        value = h;
      }
    };

    /** @brief Turn a Windows API failure into a bounded HRESULT. @param ok API result. */
    void check(BOOL ok) {
      if (!ok) {
        winrt::throw_hresult(HRESULT_FROM_WIN32(GetLastError()));
      }
    }

    /** @brief Check a complete write on a private pipe. @tparam T Fixed message. @param pipe Owned pipe. @param value Message. */
    template<class T>
    void send(HANDLE pipe, const T &value) {
      DWORD written = 0;
      check(WriteFile(pipe, &value, sizeof(value), &written, nullptr));
      if (written != sizeof(value)) {
        winrt::throw_hresult(E_FAIL);
      }
    }

    /** @brief Read one complete fixed-size message. @tparam T Fixed message. @param pipe Owned pipe. @return Message. */
    template<class T>
    T receive(HANDLE pipe) {
      T value {};
      DWORD read = 0;
      check(ReadFile(pipe, &value, sizeof(value), &read, nullptr));
      if (read != sizeof(value)) {
        winrt::throw_hresult(E_FAIL);
      }
      return value;
    }

    /** @brief Pack a GPU LUID without aliasing. @param value Native LUID. @return Fixed-width identity. */
    std::uint64_t pack(LUID value) {
      return (std::uint64_t(std::uint32_t(value.HighPart)) << 32) | value.LowPart;
    }

    /** @brief Check the HWND lifetime marker before every published frame. @param request Immutable target identity. @return Original window still exists and is not minimized. */
    bool live(const wire::request &request) {
      const auto window = reinterpret_cast<HWND>(static_cast<std::uintptr_t>(request.window));
      DWORD process = 0;
      return IsWindow(window) && !IsIconic(window) && GetWindowThreadProcessId(window, &process) && process == request.process &&
             reinterpret_cast<std::uintptr_t>(GetPropW(window, reinterpret_cast<const wchar_t *>(request.property.data()))) == request.marker;
    }

    constexpr GUID surface_access_id {0xA9B3D012, 0x3DF2, 0x4EE3, {0xB8, 0xD1, 0x86, 0x95, 0xF4, 0x57, 0xD3, 0xC1}};  ///< IDirect3DDxgiInterfaceAccess.

    /** @brief Balance a successful worker COM initialization after all capture objects retire. */
    struct apartment_t {
      apartment_t() {
        winrt::check_hresult(RoInitialize(RO_INIT_MULTITHREADED));
      }

      ~apartment_t() {
        RoUninitialize();
      }
    };

    /** @brief Run only as an ordinary user, through four inherited unnamed handles. @param input Parent requests. @param output Worker results. @param notify Wake parent. @param wake Parent commands/GPU release. @return Process status. */
    int run_worker(HANDLE input, HANDLE output, HANDLE notify, HANDLE wake) {
      std::uint32_t stage = 1;
      try {
        if (capture_context::is_system_process()) {
          winrt::throw_hresult(E_ACCESSDENIED);
        }
        if (GetFileType(input) != FILE_TYPE_PIPE || GetFileType(output) != FILE_TYPE_PIPE) {
          winrt::throw_hresult(E_INVALIDARG);
        }
        const auto request = receive<wire::request>(input);
        if (!wire::valid(request) || !live(request)) {
          winrt::throw_hresult(E_INVALIDARG);
        }
        apartment_t apartment;
        if (!winrt::Windows::Graphics::Capture::GraphicsCaptureSession::IsSupported()) {
          winrt::throw_hresult(HRESULT_FROM_WIN32(ERROR_NOT_SUPPORTED));
        }
        stage = 2;
        winrt::com_ptr<IDXGIFactory1> factory;
        winrt::check_hresult(CreateDXGIFactory1(IID_IDXGIFactory1, factory.put_void()));
        winrt::com_ptr<IDXGIAdapter1> adapter;
        for (UINT i = 0;; ++i) {
          winrt::com_ptr<IDXGIAdapter1> current;
          if (factory->EnumAdapters1(i, current.put()) == DXGI_ERROR_NOT_FOUND) {
            break;
          }
          DXGI_ADAPTER_DESC1 desc {};
          winrt::check_hresult(current->GetDesc1(&desc));
          if (pack(desc.AdapterLuid) == request.adapter) {
            adapter = current;
            break;
          }
        }
        if (!adapter) {
          winrt::throw_hresult(DXGI_ERROR_NOT_FOUND);
        }
        winrt::com_ptr<ID3D11Device> device;
        winrt::com_ptr<ID3D11DeviceContext> context;
        winrt::check_hresult(D3D11CreateDevice(adapter.get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr, D3D11_CREATE_DEVICE_BGRA_SUPPORT, nullptr, 0, D3D11_SDK_VERSION, device.put(), nullptr, context.put()));
        winrt::com_ptr<IDXGIDevice> dxgi;
        winrt::check_hresult(device->QueryInterface(IID_IDXGIDevice, dxgi.put_void()));
        winrt::com_ptr<IInspectable> wrapped;
        winrt::check_hresult(CreateDirect3D11DeviceFromDXGIDevice(dxgi.get(), wrapped.put()));
        const auto graphics = wrapped.as<winrt::Windows::Graphics::DirectX::Direct3D11::IDirect3DDevice>();
        stage = 3;
        const auto interop = winrt::get_activation_factory<winrt::Windows::Graphics::Capture::GraphicsCaptureItem, IGraphicsCaptureItemInterop>();
        winrt::Windows::Graphics::Capture::GraphicsCaptureItem item {nullptr};
        winrt::check_hresult(interop->CreateForWindow(reinterpret_cast<HWND>(static_cast<std::uintptr_t>(request.window)), winrt::guid_of<winrt::Windows::Graphics::Capture::IGraphicsCaptureItem>(), winrt::put_abi(item)));
        const auto size = item.Size();
        if (!wire::geometry(size.Width, size.Height, request.format) || !live(request)) {
          winrt::throw_hresult(E_INVALIDARG);
        }
        stage = 4;
        D3D11_TEXTURE2D_DESC description {};
        description.Width = size.Width;
        description.Height = size.Height;
        description.MipLevels = 1;
        description.ArraySize = 1;
        description.Format = static_cast<DXGI_FORMAT>(request.format);
        description.SampleDesc.Count = 1;
        description.Usage = D3D11_USAGE_DEFAULT;
        description.BindFlags = D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_RENDER_TARGET;
        description.MiscFlags = D3D11_RESOURCE_MISC_SHARED_NTHANDLE | D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX;
        winrt::com_ptr<ID3D11Texture2D> texture;
        winrt::check_hresult(device->CreateTexture2D(&description, nullptr, texture.put()));
        winrt::com_ptr<IDXGIResource1> resource;
        winrt::check_hresult(texture->QueryInterface(IID_IDXGIResource1, resource.put_void()));
        handle_t shared;
        winrt::check_hresult(resource->CreateSharedHandle(nullptr, DXGI_SHARED_RESOURCE_READ | DXGI_SHARED_RESOURCE_WRITE, nullptr, &shared.value));
        winrt::com_ptr<IDXGIKeyedMutex> mutex;
        winrt::check_hresult(texture->QueryInterface(IID_IDXGIKeyedMutex, mutex.put_void()));
        const auto arrived = std::make_shared<handle_t>(CreateEventW(nullptr, FALSE, FALSE, nullptr));
        check(arrived->value != nullptr);
        auto pool = winrt::Windows::Graphics::Capture::Direct3D11CaptureFramePool::CreateFreeThreaded(graphics, static_cast<winrt::Windows::Graphics::DirectX::DirectXPixelFormat>(request.format), 2, size);
        auto session = pool.CreateCaptureSession(item);
        const auto event = pool.FrameArrived([signal = arrived](auto &&, auto &&) {
          SetEvent(signal->value);
        });

        struct capture_guard {
          winrt::Windows::Graphics::Capture::GraphicsCaptureSession session;  ///< Retire before the event signal is released.
          winrt::Windows::Graphics::Capture::Direct3D11CaptureFramePool pool;  ///< Owned frame pool.
          winrt::event_token event;  ///< Only this subscription.

          ~capture_guard() {
            try {
              session.Close();
            } catch (...) {}
            try {
              pool.FrameArrived(event);
              pool.Close();
            } catch (...) {}
          }
        } capture {session, pool, event};

        try {
          session.IncludeSecondaryWindows(false);
        } catch (const winrt::hresult_error &) {}
        try {
          session.IsBorderRequired(false);
        } catch (const winrt::hresult_error &) {}
        try {
          session.MinUpdateInterval(std::chrono::duration<std::int64_t, std::ratio<1, 10000000>>(10000000 / request.fps));
        } catch (const winrt::hresult_error &) {}
        stage = 5;
        session.StartCapture();
        wire::response ready {};
        ready.type = wire::kind::ready;
        ready.width = size.Width;
        ready.height = size.Height;
        ready.format = request.format;
        ready.handle = reinterpret_cast<std::uintptr_t>(shared.value);
        send(output, ready);
        check(SetEvent(notify));
        const HANDLE waits[] {arrived->value, wake};
        winrt::Windows::Graphics::Capture::Direct3D11CaptureFrame pending {nullptr};
        std::uint64_t sequence = 0;
        bool stopped = false;
        stage = 6;
        while (!stopped) {
          const auto signal = WaitForMultipleObjects(2, waits, FALSE, 200);
          if (signal == WAIT_FAILED) {
            winrt::throw_last_error();
          }
          DWORD available = 0;
          if (!PeekNamedPipe(input, nullptr, 0, nullptr, &available, nullptr)) {
            break;
          }
          while (available >= sizeof(wire::control)) {
            const auto command = receive<wire::control>(input);
            if (!wire::valid(command)) {
              winrt::throw_hresult(E_INVALIDARG);
            }
            if (command.action == wire::command::stop) {
              stopped = true;
              break;
            }
            session.IsCursorCaptureEnabled(command.value != 0);
            check(PeekNamedPipe(input, nullptr, 0, nullptr, &available, nullptr));
          }
          if (stopped) {
            break;
          }
          if (!live(request)) {
            wire::response closed {};
            closed.type = wire::kind::closed;
            closed.status = HRESULT_FROM_WIN32(ERROR_INVALID_WINDOW_HANDLE);
            send(output, closed);
            SetEvent(notify);
            break;
          }
          if (signal == WAIT_OBJECT_0) {
            for (;;) {
              auto frame = pool.TryGetNextFrame();
              if (!frame) {
                break;
              }
              if (pending) {
                pending.Close();
              }
              pending = std::move(frame);
            }
          }
          if (!pending) {
            continue;
          }
          const auto content = pending.ContentSize();
          if (content.Width <= 0 || content.Height <= 0) {
            pending.Close();
            pending = nullptr;
            continue;
          }
          if (content.Width != size.Width || content.Height != size.Height) {
            wire::response resized {};
            resized.type = wire::kind::resized;
            resized.width = content.Width;
            resized.height = content.Height;
            resized.format = request.format;
            send(output, resized);
            SetEvent(notify);
            break;
          }
          const auto acquired = mutex->AcquireSync(0, 0);
          if (acquired == WAIT_TIMEOUT) {
            continue;
          }
          if (acquired != S_OK) {
            winrt::throw_hresult(E_FAIL);
          }
          try {
            winrt::com_ptr<surface_access_t> access;
            const auto surface = pending.Surface().as<IUnknown>();
            winrt::check_hresult(surface->QueryInterface(surface_access_id, access.put_void()));
            winrt::com_ptr<ID3D11Texture2D> source;
            winrt::check_hresult(access->GetInterface(IID_ID3D11Texture2D, source.put_void()));
            D3D11_TEXTURE2D_DESC actual {};
            source->GetDesc(&actual);
            if (actual.Width != description.Width || actual.Height != description.Height || actual.Format != description.Format) {
              winrt::throw_hresult(E_INVALIDARG);
            }
            context->CopyResource(texture.get(), source.get());
            context->Flush();
            winrt::check_hresult(mutex->ReleaseSync(1));
          } catch (...) {
            mutex->ReleaseSync(0);
            throw;
          }
          wire::response frame {};
          frame.type = wire::kind::frame;
          frame.width = size.Width;
          frame.height = size.Height;
          frame.format = request.format;
          frame.sequence = ++sequence;
          frame.timestamp = pending.SystemRelativeTime().count();
          pending.Close();
          pending = nullptr;
          send(output, frame);
          check(SetEvent(notify));
        }
        if (pending) {
          pending.Close();
        }
        return 0;
      } catch (const winrt::hresult_error &error) {
        try {
          wire::response reply {};
          reply.type = wire::kind::error;
          reply.status = error.code().value;
          reply.stage = stage;
          send(output, reply);
          SetEvent(notify);
        } catch (...) {}
      } catch (...) {
        try {
          wire::response reply {};
          reply.type = wire::kind::error;
          reply.status = E_FAIL;
          reply.stage = stage;
          send(output, reply);
          SetEvent(notify);
        } catch (...) {}
      }
      return 1;
    }
  }  // namespace

  /** @brief Parent-owned user process, bounded pipes and one keyed shared texture. */
  struct broker_t::impl_t {
    handle_t process, job, input, output, notify, wake;  ///< Only this owned worker can be retired.
    target_ptr target;  ///< Original authenticated selector lifetime.
    winrt::com_ptr<ID3D11DeviceContext> context;  ///< Flush copies before releasing GPU ownership.
    winrt::com_ptr<ID3D11Texture2D> texture;  ///< Opened through a handle duplicated from this child only.
    winrt::com_ptr<IDXGIKeyedMutex> mutex;  ///< One outstanding frame at a time, no frame queue growth.
    int width = 0, height = 0;  ///< Acknowledged content geometry.
    DXGI_FORMAT format = DXGI_FORMAT_UNKNOWN;  ///< Validated format.
    bool held = false;  ///< Current frame owns keyed mutex 1.
    std::optional<bool> cursor;  ///< Send cursor commands only on change.
    std::uint64_t sequence = 0;  ///< Reject stale/reordered frame reports.
    std::optional<wire::response> pending_reply;  ///< Preserve a report if GPU ownership is briefly unavailable.

    /** @brief Gracefully stop this child, then close the kill-on-close job as a bounded fallback. */
    ~impl_t() {
      release();
      if (input.value) {
        try {
          wire::control stop {};
          send(input.value, stop);
          SetEvent(wake.value);
        } catch (...) {}
      }
      input.reset();
      if (process.value && WaitForSingleObject(process.value, 1000) != WAIT_OBJECT_0) {
        job.reset();
        WaitForSingleObject(process.value, 3000);
      }
    }

    /** @brief Release only an acquired shared frame. @return Whether GPU release succeeded. */
    bool release() {
      if (!held) {
        return true;
      }
      context->Flush();
      const auto status = mutex->ReleaseSync(0);
      held = false;
      SetEvent(wake.value);
      return status == S_OK;
    }

    /** @brief Read a fixed reply with process-death and timeout boundaries. @param timeout Maximum wait. @return Validated response or no response before timeout. */
    std::optional<wire::response> read(std::chrono::milliseconds timeout) {
      const auto deadline = clock::now() + timeout;
      for (;;) {
        DWORD available = 0;
        check(PeekNamedPipe(output.value, nullptr, 0, nullptr, &available, nullptr));
        if (available >= sizeof(wire::response)) {
          const auto reply = receive<wire::response>(output.value);
          if (!wire::valid(reply)) {
            winrt::throw_hresult(E_INVALIDARG);
          }
          return reply;
        }
        const auto left = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - clock::now()).count();
        if (left <= 0) {
          return {};
        }
        const HANDLE waits[] {notify.value, process.value};
        const auto waited = WaitForMultipleObjects(2, waits, FALSE, static_cast<DWORD>(left));
        if (waited == WAIT_TIMEOUT) {
          return {};
        }
        if (waited == WAIT_FAILED) {
          winrt::throw_last_error();
        }
        if (waited == WAIT_OBJECT_0 + 1) {
          check(PeekNamedPipe(output.value, nullptr, 0, nullptr, &available, nullptr));
          if (available < sizeof(wire::response)) {
            winrt::throw_hresult(E_FAIL);
          }
        }
      }
    }
  };

  broker_t::broker_t(std::unique_ptr<impl_t> value):
      impl(std::move(value)) {}

  broker_t::~broker_t() = default;

  std::shared_ptr<broker_t> broker_t::start(ID3D11Device *device, target_ptr target, DXGI_FORMAT format, int fps) {
    const auto began = clock::now();
    try {
      if (!capture_context::is_system_process() || !target || !target->alive() || !device) {
        winrt::throw_hresult(E_INVALIDARG);
      }
      auto state = std::make_unique<impl_t>();
      state->target = target;
      device->GetImmediateContext(state->context.put());
      winrt::com_ptr<IDXGIDevice> dxgi;
      winrt::check_hresult(device->QueryInterface(IID_IDXGIDevice, dxgi.put_void()));
      winrt::com_ptr<IDXGIAdapter> adapter;
      winrt::check_hresult(dxgi->GetAdapter(adapter.put()));
      DXGI_ADAPTER_DESC desc {};
      winrt::check_hresult(adapter->GetDesc(&desc));
      wire::request request {};
      request.window = reinterpret_cast<std::uintptr_t>(target->window);
      request.marker = target->marker;
      request.process = target->process;
      request.adapter = pack(desc.AdapterLuid);
      request.format = format;
      request.fps = fps;
      const auto &property = target->marker_property();
      if (property.size() >= request.property.size()) {
        winrt::throw_hresult(E_INVALIDARG);
      }
      std::copy(property.begin(), property.end(), request.property.begin());
      if (!wire::valid(request)) {
        winrt::throw_hresult(E_INVALIDARG);
      }
      SECURITY_ATTRIBUTES inherit {sizeof(inherit), nullptr, TRUE};
      handle_t read_input, write_output;
      check(CreatePipe(&read_input.value, &state->input.value, &inherit, 4096));
      check(SetHandleInformation(state->input.value, HANDLE_FLAG_INHERIT, 0));
      check(CreatePipe(&state->output.value, &write_output.value, &inherit, 4096));
      check(SetHandleInformation(state->output.value, HANDLE_FLAG_INHERIT, 0));
      state->notify.value = CreateEventW(&inherit, FALSE, FALSE, nullptr);
      check(state->notify.value != nullptr);
      state->wake.value = CreateEventW(&inherit, FALSE, FALSE, nullptr);
      check(state->wake.value != nullptr);
      state->job.value = CreateJobObjectW(nullptr, nullptr);
      check(state->job.value != nullptr);
      JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits {};
      limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
      check(SetInformationJobObject(state->job.value, JobObjectExtendedLimitInformation, &limits, sizeof(limits)));
      SIZE_T bytes = 0;
      InitializeProcThreadAttributeList(nullptr, 1, 0, &bytes);
      std::vector<std::uintptr_t> storage((bytes + sizeof(std::uintptr_t) - 1) / sizeof(std::uintptr_t));
      auto attributes = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(storage.data());
      check(InitializeProcThreadAttributeList(attributes, 1, 0, &bytes));

      struct attributes_guard {
        LPPROC_THREAD_ATTRIBUTE_LIST value;

        ~attributes_guard() {
          DeleteProcThreadAttributeList(value);
        }
      } attribute_guard {attributes};

      HANDLE inherited[] {read_input.value, write_output.value, state->notify.value, state->wake.value};
      check(UpdateProcThreadAttribute(attributes, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, inherited, sizeof(inherited), nullptr, nullptr));
      wchar_t executable[32768] {};
      check(GetModuleFileNameW(nullptr, executable, std::size(executable)) != 0);
      std::wstring command = L"\"" + std::wstring(executable) + L"\" --rh-window-worker";
      for (const auto handle : inherited) {
        command += L" " + std::to_wstring(reinterpret_cast<std::uintptr_t>(handle));
      }
      const auto user = capture_context::backend().user();
      LPVOID environment = nullptr;
      check(CreateEnvironmentBlock(&environment, user, FALSE));

      struct environment_guard {
        LPVOID value;

        ~environment_guard() {
          DestroyEnvironmentBlock(value);
        }
      } environment_guard {environment};

      STARTUPINFOEXW startup {};
      startup.StartupInfo.cb = sizeof(startup);
      startup.StartupInfo.lpDesktop = const_cast<wchar_t *>(L"winsta0\\default");
      startup.lpAttributeList = attributes;
      PROCESS_INFORMATION child {};
      check(CreateProcessAsUserW(user, executable, command.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW | CREATE_UNICODE_ENVIRONMENT | EXTENDED_STARTUPINFO_PRESENT | CREATE_SUSPENDED, environment, nullptr, &startup.StartupInfo, &child));
      state->process.value = child.hProcess;
      handle_t thread(child.hThread);
      if (!AssignProcessToJobObject(state->job.value, state->process.value)) {
        const auto failure = GetLastError();
        TerminateProcess(state->process.value, 1);
        winrt::throw_hresult(HRESULT_FROM_WIN32(failure));
      }
      check(ResumeThread(thread.value) != static_cast<DWORD>(-1));
      read_input.reset();
      write_output.reset();
      send(state->input.value, request);
      const auto reply = state->read(std::chrono::seconds(5));
      if (!reply || reply->type != wire::kind::ready) {
        if (reply) {
          BOOST_LOG(error) << "RHREMOTE_WINDOW_WORKER initialization stage=" << reply->stage << " code=" << reply->status;
        }
        winrt::throw_hresult(E_FAIL);
      }
      handle_t shared;
      check(DuplicateHandle(state->process.value, reinterpret_cast<HANDLE>(static_cast<std::uintptr_t>(reply->handle)), GetCurrentProcess(), &shared.value, 0, FALSE, DUPLICATE_SAME_ACCESS));
      winrt::com_ptr<ID3D11Device1> device1;
      winrt::check_hresult(device->QueryInterface(IID_ID3D11Device1, device1.put_void()));
      winrt::check_hresult(device1->OpenSharedResource1(shared.value, IID_ID3D11Texture2D, state->texture.put_void()));
      D3D11_TEXTURE2D_DESC actual {};
      state->texture->GetDesc(&actual);
      if (actual.Width != reply->width || actual.Height != reply->height || actual.Format != format || reply->format != static_cast<unsigned>(format) || actual.MipLevels != 1 || actual.ArraySize != 1 || actual.SampleDesc.Count != 1 || !(actual.MiscFlags & D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX)) {
        winrt::throw_hresult(E_INVALIDARG);
      }
      winrt::check_hresult(state->texture->QueryInterface(IID_IDXGIKeyedMutex, state->mutex.put_void()));
      state->width = reply->width;
      state->height = reply->height;
      state->format = format;
      target->width = state->width;
      target->height = state->height;
      BOOST_LOG(info) << "RHREMOTE_WINDOW_WORKER ready elapsed_us=" << std::chrono::duration_cast<std::chrono::microseconds>(clock::now() - began).count() << " width=" << state->width << " height=" << state->height;
      return std::shared_ptr<broker_t>(new broker_t(std::move(state)));
    } catch (const winrt::hresult_error &failure) {
      BOOST_LOG(error) << "RHREMOTE_WINDOW_WORKER failed code=" << failure.code().value;
    } catch (...) {
      BOOST_LOG(error) << "RHREMOTE_WINDOW_WORKER initialization failed";
    }
    return {};
  }

  std::pair<int, int> broker_t::size() const {
    return {impl->width, impl->height};
  }

  capture_e broker_t::next(std::chrono::milliseconds timeout, ID3D11Texture2D **out, std::uint64_t &time) {
    *out = nullptr;
    try {
      if (!impl->release() || impl->target->state() != "ready") {
        return capture_e::error;
      }
      const auto reply = impl->pending_reply ? impl->pending_reply : impl->read(timeout);
      if (!reply) {
        return capture_e::timeout;
      }
      if (reply->type == wire::kind::resized) {
        impl->target->width = reply->width;
        impl->target->height = reply->height;
        return capture_e::reinit;
      }
      if (reply->type != wire::kind::frame || reply->width != static_cast<unsigned>(impl->width) || reply->height != static_cast<unsigned>(impl->height) || reply->format != static_cast<unsigned>(impl->format) || reply->sequence <= impl->sequence) {
        BOOST_LOG(error) << "RHREMOTE_WINDOW_WORKER frame rejected type=" << static_cast<unsigned>(reply->type) << " code=" << reply->status << " stage=" << reply->stage;
        return capture_e::error;
      }
      impl->pending_reply = reply;
      const auto acquired = impl->mutex->AcquireSync(1, static_cast<DWORD>(std::max<std::int64_t>(1, timeout.count())));
      if (acquired == WAIT_TIMEOUT) {
        return capture_e::timeout;
      }
      if (acquired != S_OK) {
        BOOST_LOG(error) << "RHREMOTE_WINDOW_WORKER ownership failed code=" << acquired;
        return capture_e::error;
      }
      impl->held = true;
      impl->pending_reply.reset();
      impl->sequence = reply->sequence;
      time = reply->timestamp;
      *out = impl->texture.get();
      (*out)->AddRef();
      return capture_e::ok;
    } catch (const winrt::hresult_error &failure) {
      BOOST_LOG(error) << "RHREMOTE_WINDOW_WORKER frame failed code=" << failure.code().value;
      return capture_e::error;
    } catch (...) {
      BOOST_LOG(error) << "RHREMOTE_WINDOW_WORKER frame failed";
      return capture_e::error;
    }
  }

  capture_e broker_t::release() {
    return impl->release() ? capture_e::ok : capture_e::error;
  }

  int broker_t::cursor(bool visible) {
    if (impl->cursor == visible) {
      return 0;
    }
    try {
      wire::control control {};
      control.action = wire::command::cursor;
      control.value = visible ? 1 : 0;
      send(impl->input.value, control);
      check(SetEvent(impl->wake.value));
      impl->cursor = visible;
      return 0;
    } catch (...) {
      return -1;
    }
  }

  std::optional<int> worker_arguments(int argc, char **argv) {
    if (argc < 2 || std::string_view(argv[1]) != "--rh-window-worker") {
      return {};
    }
    if (argc != 6) {
      return 2;
    }
    std::array<handle_t, 4> handles;
    for (unsigned i = 0; i < 4; ++i) {
      std::uintptr_t value = 0;
      const std::string_view text(argv[i + 2]);
      const auto parsed = std::from_chars(text.data(), text.data() + text.size(), value);
      if (parsed.ec != std::errc {} || parsed.ptr != text.data() + text.size() || !value || value == std::numeric_limits<std::uintptr_t>::max()) {
        return 2;
      }
      handles[i].value = reinterpret_cast<HANDLE>(value);
    }
    return run_worker(handles[0].value, handles[1].value, handles[2].value, handles[3].value);
  }
}  // namespace platf::window_capture
