#pragma once
#include <d3d11.h>
#include <dxgi1_5.h>
#include <wrl/client.h>
#include <mutex>
#include "rhstatistics.h"
#include "rhcapturetools.h"

// A test renderer releases all textures, swapchains and AV contexts before
// handing off its device. The real renderer still creates and validates its own
// codec, resources and swapchain. This slot lasts only for this native process.
namespace RhD3D11Probe {
using Microsoft::WRL::ComPtr;
struct State {
    std::mutex mutex;
    ComPtr<IDXGIFactory5> factory;
    ComPtr<ID3D11Device> device;
    LUID adapter{};
};
inline State& state(){static State value;return value;}
inline bool current(State& value){
    if(!RhCaptureTools::attached()&&value.factory&&value.device&&value.factory->IsCurrent()&&SUCCEEDED(value.device->GetDeviceRemovedReason()))return true;
    value.device.Reset();value.factory.Reset();return false;
}
inline ComPtr<IDXGIFactory5> factory(){
    auto& value=state();std::lock_guard<std::mutex> lock(value.mutex);
    return current(value)?value.factory:ComPtr<IDXGIFactory5>{};
}
inline ComPtr<ID3D11Device> take(const LUID& adapter,IDXGIFactory5* factory){
    auto& value=state();std::lock_guard<std::mutex> lock(value.mutex);
    if(!current(value)||value.factory.Get()!=factory||value.adapter.LowPart!=adapter.LowPart||value.adapter.HighPart!=adapter.HighPart)return {};
    auto device=value.device;value.device.Reset();value.factory.Reset();
    RhStatistics::event("client_d3d11_probe_device_reused");return device;
}
inline void put(IDXGIFactory5* factory,ID3D11Device* device){
    if(RhCaptureTools::attached()||!factory||!device||!factory->IsCurrent()||FAILED(device->GetDeviceRemovedReason()))return;
    ComPtr<IDXGIDevice> dxgi;ComPtr<IDXGIAdapter> adapter;DXGI_ADAPTER_DESC description{};
    if(FAILED(device->QueryInterface(IID_PPV_ARGS(&dxgi)))||FAILED(dxgi->GetAdapter(&adapter))||FAILED(adapter->GetDesc(&description)))return;
    auto& value=state();std::lock_guard<std::mutex> lock(value.mutex);
    value.factory=factory;value.device=device;value.adapter=description.AdapterLuid;
}
}
