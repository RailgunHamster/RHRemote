#include "client/windows/overlay/app/gui/rhd3d11probe.h"
#include <cstdlib>
int main(){
    auto check=[](bool value){if(!value)std::abort();};
    Microsoft::WRL::ComPtr<IDXGIFactory5> factory,otherFactory;
    Microsoft::WRL::ComPtr<ID3D11Device> device;
    check(SUCCEEDED(CreateDXGIFactory1(IID_PPV_ARGS(&factory))));
    check(SUCCEEDED(CreateDXGIFactory1(IID_PPV_ARGS(&otherFactory))));
    check(SUCCEEDED(D3D11CreateDevice(nullptr,D3D_DRIVER_TYPE_WARP,nullptr,0,nullptr,0,D3D11_SDK_VERSION,&device,nullptr,nullptr)));
    Microsoft::WRL::ComPtr<IDXGIDevice> dxgi;Microsoft::WRL::ComPtr<IDXGIAdapter> adapter;DXGI_ADAPTER_DESC description{};
    check(SUCCEEDED(device.As(&dxgi))&&SUCCEEDED(dxgi->GetAdapter(&adapter))&&SUCCEEDED(adapter->GetDesc(&description)));
    check(!RhD3D11Probe::factory());
    QJsonObject layer{{"description","Debugging capture layer for RenderDoc"},{"disable_environment",QJsonObject{{"DISABLE_VULKAN_EXAMPLE_CAPTURE","1"},{"PATH","untrusted"},{"DISABLE_VULKAN_BAD",""}}}};
    const auto filtered=RhCaptureTools::optOut(QJsonObject{{"layer",layer}});
    check(filtered.size()==1&&filtered.value("DISABLE_VULKAN_EXAMPLE_CAPTURE")=="1");
    layer["description"]="Unrelated display layer";
    check(RhCaptureTools::optOut(QJsonObject{{"layer",layer}}).isEmpty());
    RhD3D11Probe::put(factory.Get(),device.Get());
    check(RhD3D11Probe::factory().Get()==factory.Get());
    auto wrong=description.AdapterLuid;++wrong.LowPart;
    check(!RhD3D11Probe::take(wrong,factory.Get()));
    check(!RhD3D11Probe::take(description.AdapterLuid,otherFactory.Get()));
    auto consumed=RhD3D11Probe::take(description.AdapterLuid,factory.Get());
    check(consumed.Get()==device.Get());
    check(!RhD3D11Probe::factory()&&!RhD3D11Probe::take(description.AdapterLuid,factory.Get()));
    RhD3D11Probe::put(nullptr,device.Get());
    check(!RhD3D11Probe::factory());
    RhD3D11Probe::put(factory.Get(),nullptr);
    check(!RhD3D11Probe::factory());
    RhD3D11Probe::put(factory.Get(),device.Get());
    {auto& state=RhD3D11Probe::state();std::lock_guard<std::mutex> lock(state.mutex);state.factory.Reset();}
    check(!RhD3D11Probe::factory());
    HMODULE capture=LoadLibraryW(L"test-rhremote-capture-api.dll");
    check(capture&&RhCaptureTools::attached());
    RhD3D11Probe::put(factory.Get(),device.Get());
    check(!RhD3D11Probe::factory());
    FreeLibrary(capture);
}
