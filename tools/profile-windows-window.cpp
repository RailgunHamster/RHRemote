// Hidden-window startup diagnostic. Does not record pixels, sound or input.
#define SDL_MAIN_HANDLED
#include "client/windows/overlay/app/gui/rhcapturetools.h"
#include "client/windows/overlay/app/gui/rhwindowbridge.h"
#include <QCoreApplication>
#include <SDL.h>
#include <SDL_vulkan.h>
#include <chrono>
#include <iostream>

static quint64 cpuTime() {
    FILETIME created{}, exited{}, kernel{}, user{};
    GetThreadTimes(GetCurrentThread(), &created, &exited, &kernel, &user);
    return (quint64(kernel.dwHighDateTime) << 32 | kernel.dwLowDateTime) +
           (quint64(user.dwHighDateTime) << 32 | user.dwLowDateTime);
}
template<class Operation> static void measure(const char* name, Operation operation) {
    const auto cpu = cpuTime();
    const auto start = std::chrono::steady_clock::now();
    const bool ok = operation();
    const auto milliseconds = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    std::cout << QJsonDocument(QJsonObject{{"name", name}, {"ok", ok}, {"elapsedMs", milliseconds},
        {"threadCpuMs", double(cpuTime() - cpu) / 10000.0}}).toJson(QJsonDocument::Compact).constData() << std::endl;
}
static bool window(Uint32 flags) {
    auto value = SDL_CreateWindow("RHRemote hidden diagnostic", 80, 80, 1180, 780,
        SDL_WINDOW_HIDDEN | SDL_WINDOW_ALLOW_HIGHDPI | SDL_WINDOW_RESIZABLE | flags);
    if (!value) return false;
    SDL_DestroyWindow(value);
    return true;
}
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    qputenv("RHREMOTE_CONTROL_PORT", "diagnostic");
    RhCaptureTools::isolate();
    SDL_SetMainReady();
    measure("sdl_video_init", [] { return SDL_InitSubSystem(SDL_INIT_VIDEO) == 0; });
    measure("win32_hidden", [] {
        auto value = CreateWindowExW(0, L"STATIC", L"RHRemote hidden diagnostic", WS_POPUP,
            80, 80, 1180, 780, nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
        if (!value) return false;
        DestroyWindow(value); return true;
    });
    for (int i = 0; i < 3; ++i) {
        measure("sdl_hidden_plain", [] { return window(0); });
        measure("sdl_hidden_vulkan", [] { return window(SDL_WINDOW_VULKAN); });
    }
    const bool loaded = SDL_Vulkan_LoadLibrary(nullptr) == 0;
    if (loaded) {
        for (int i = 0; i < 3; ++i) measure("sdl_hidden_vulkan_loader_retained", [] { return window(SDL_WINDOW_VULKAN); });
        SDL_Vulkan_UnloadLibrary();
    }
    {
        RhWindowBridge bridge;
        bridge.retain(nullptr);
        auto probe=SDL_CreateWindow("RHRemote hidden probe",80,80,1180,780,SDL_WINDOW_HIDDEN|SDL_WINDOW_VULKAN);
        if(probe){
            bridge.retain(probe);SDL_DestroyWindow(probe);
            measure("sdl_hidden_vulkan_connection_bridge",[]{return window(SDL_WINDOW_VULKAN);});
        }
        bridge.release();bridge.release();
    }
    SDL_QuitSubSystem(SDL_INIT_VIDEO);
}
