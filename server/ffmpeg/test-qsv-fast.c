/* Execute the actual generated FFmpeg entry points with SDK and COM fixtures.
 * No display, GPU, video, audio, production service or network is used. */
#define COBJMACROS
#include <d3d11.h>
#include <dxgi1_2.h>
#include <mfxvideo.h>
#include <mfxdispatcher.h>
#include <stdio.h>
#include <string.h>

enum fault_kind {
    RH_QSV_TEST_NONE, RH_QSV_TEST_REMOVED, RH_QSV_TEST_NON_INTEL, RH_QSV_TEST_FACTORY_FAIL, RH_QSV_TEST_DUPLICATE, RH_QSV_TEST_INVENTORY_MOVED,
    RH_QSV_TEST_LOAD_FAIL, RH_QSV_TEST_CONFIG_FAIL, RH_QSV_TEST_FILTER_FAIL, RH_QSV_TEST_CREATE_FAIL, RH_QSV_TEST_CREATE_PARTIAL,
    RH_QSV_TEST_WRONG_IMPL, RH_QSV_TEST_LOW_VERSION, RH_QSV_TEST_SET_FAIL, RH_QSV_TEST_GET_MISMATCH, RH_QSV_TEST_GET_FAIL, RH_QSV_TEST_JOIN_FAIL,
    RH_QSV_TEST_ALL_CREATE_FAIL, RH_QSV_TEST_ENUM_FAIL, RH_QSV_TEST_STALE_FACTORY, RH_QSV_TEST_UNBOUNDED_INVENTORY
};
static enum fault_kind fault;
static unsigned references, factories, loads, unloads, creates, closes, enums, sets, joins, filters;
static int loader_storage[8];
static mfxU32 requested_version;
typedef struct session_fixture { mfxHDL handle; int closed; } session_fixture;
static session_fixture sessions[16];
static const mfxIMPL implementation = MFX_IMPL_HARDWARE | MFX_IMPL_VIA_D3D11;
static mfxVersion api_version = {.Major = 2, .Minor = 16};
static ID3D11Device device;
static IDXGIDevice dxgi_device;
static IDXGIAdapter1 wanted_adapter, entries[2];
static IDXGIFactory1 factory;

static ULONG STDMETHODCALLTYPE device_release(ID3D11Device *self) { (void)self; return --references; }
static HRESULT STDMETHODCALLTYPE device_reason(ID3D11Device *self) {
    (void)self; return fault == RH_QSV_TEST_REMOVED ? DXGI_ERROR_DEVICE_REMOVED : S_OK;
}
static HRESULT STDMETHODCALLTYPE device_interface(ID3D11Device *self, REFIID iid, void **out) {
    (void)self; (void)iid; *out = &dxgi_device; references++; return S_OK;
}
static ULONG STDMETHODCALLTYPE dxgi_release(IDXGIDevice *self) { (void)self; return --references; }
static HRESULT STDMETHODCALLTYPE dxgi_adapter(IDXGIDevice *self, IDXGIAdapter **out) {
    (void)self; *out = (IDXGIAdapter *)&wanted_adapter; references++; return S_OK;
}
static ULONG STDMETHODCALLTYPE adapter_release(IDXGIAdapter1 *self) { (void)self; return --references; }
static HRESULT STDMETHODCALLTYPE adapter_desc(IDXGIAdapter1 *self, DXGI_ADAPTER_DESC *out) {
    (void)self; memset(out, 0, sizeof(*out)); out->VendorId = fault == RH_QSV_TEST_NON_INTEL ? 0x10de : 0x8086;
    out->DeviceId = 0xe20b; out->AdapterLuid.LowPart = 42; return S_OK;
}
static HRESULT STDMETHODCALLTYPE adapter_desc1(IDXGIAdapter1 *self, DXGI_ADAPTER_DESC1 *out) {
    unsigned index = self == &entries[1] ? 1 : 0;
    unsigned target = fault == RH_QSV_TEST_INVENTORY_MOVED && factories > 1 ? 1 : 0;
    memset(out, 0, sizeof(*out)); out->VendorId = 0x8086; out->DeviceId = 0xe20b;
    out->AdapterLuid.LowPart = index == target || fault == RH_QSV_TEST_DUPLICATE ? 42 : 99;
    return S_OK;
}
static ULONG STDMETHODCALLTYPE factory_release(IDXGIFactory1 *self) { (void)self; return --references; }
static BOOL STDMETHODCALLTYPE factory_current(IDXGIFactory1 *self) { (void)self; return fault != RH_QSV_TEST_STALE_FACTORY; }
static HRESULT STDMETHODCALLTYPE factory_adapter(IDXGIFactory1 *self, UINT i, IDXGIAdapter1 **out) {
    (void)self;
    if (i >= 2 && fault != RH_QSV_TEST_UNBOUNDED_INVENTORY) { *out = NULL; return DXGI_ERROR_NOT_FOUND; }
    *out = &entries[i % 2]; references++; return S_OK;
}
static HRESULT WINAPI fixture_factory(REFIID iid, void **out) {
    (void)iid; factories++;
    if (fault == RH_QSV_TEST_FACTORY_FAIL) { *out = NULL; return E_FAIL; }
    *out = &factory; references++; return S_OK;
}
static ID3D11DeviceVtbl device_methods = {.QueryInterface = device_interface, .Release = device_release,
                                         .GetDeviceRemovedReason = device_reason};
static IDXGIDeviceVtbl dxgi_methods = {.Release = dxgi_release, .GetAdapter = dxgi_adapter};
static IDXGIAdapter1Vtbl adapter_methods = {.Release = adapter_release, .GetDesc = adapter_desc, .GetDesc1 = adapter_desc1};
static IDXGIFactory1Vtbl factory_methods = {.Release = factory_release, .EnumAdapters1 = factory_adapter, .IsCurrent = factory_current};

static mfxLoader MFX_CDECL fixture_load(void) {
    loads++; return fault == RH_QSV_TEST_LOAD_FAIL ? NULL : (mfxLoader)&loader_storage[loads];
}
static void MFX_CDECL fixture_unload(mfxLoader loader) { if (loader) unloads++; }
static mfxConfig MFX_CDECL fixture_config(mfxLoader loader) { return fault == RH_QSV_TEST_CONFIG_FAIL ? NULL : (mfxConfig)loader; }
static mfxStatus MFX_CDECL fixture_filter(mfxConfig config, const mfxU8 *name, mfxVariant value) {
    unsigned bit = 0;
    filters++;
    if (fault == RH_QSV_TEST_FILTER_FAIL) return MFX_ERR_UNSUPPORTED;
    if (!strcmp((const char *)name, "mfxImplDescription.ImplName")) {
        if (value.Type != MFX_VARIANT_TYPE_PTR || strcmp(value.Data.Ptr, "mfx-gen")) return MFX_ERR_INVALID_VIDEO_PARAM;
        bit = 32;
    } else if (!strcmp((const char *)name, "DXGIAdapterIndex")) {
        if (value.Type != MFX_VARIANT_TYPE_U32 || value.Data.U32 != 0) return MFX_ERR_INVALID_VIDEO_PARAM;
        bit = 8;
    } else if (!strcmp((const char *)name, "mfxImplDescription.Impl")) {
        if (value.Type != MFX_VARIANT_TYPE_U32 || value.Data.U32 != MFX_IMPL_TYPE_HARDWARE) return MFX_ERR_INVALID_VIDEO_PARAM;
        bit = 1;
    } else if (!strcmp((const char *)name, "mfxImplDescription.ApiVersion.Version")) {
        if (value.Type != MFX_VARIANT_TYPE_U32 || value.Data.U32 != requested_version) return MFX_ERR_INVALID_VIDEO_PARAM;
        bit = 2;
    } else if (!strcmp((const char *)name, "mfxImplDescription.VendorID")) {
        if (value.Type != MFX_VARIANT_TYPE_U32 || value.Data.U32 != 0x8086) return MFX_ERR_INVALID_VIDEO_PARAM;
        bit = 4;
    } else if (!strcmp((const char *)name, "mfxImplDescription.AccelerationMode")) {
        if (value.Type != MFX_VARIANT_TYPE_U32 || value.Data.U32 != MFX_ACCEL_MODE_VIA_D3D11) return MFX_ERR_INVALID_VIDEO_PARAM;
        bit = 16;
    } else if (!strcmp((const char *)name, "mfxExtendedDeviceId.DeviceID")) {
        if (value.Type != MFX_VARIANT_TYPE_U16 || value.Data.U16 != 0xe20b) return MFX_ERR_INVALID_VIDEO_PARAM;
        bit = 64;
    } else if (!strcmp((const char *)name, "mfxExtendedDeviceId.DeviceLUID")) {
        if (value.Type != MFX_VARIANT_TYPE_PTR || !value.Data.Ptr ||
            ((LUID *)value.Data.Ptr)->LowPart != 42 || ((LUID *)value.Data.Ptr)->HighPart) return MFX_ERR_INVALID_VIDEO_PARAM;
        bit = 128;
    } else if (!strcmp((const char *)name, "mfxExtendedDeviceId.LUIDDeviceNodeMask")) {
        if (value.Type != MFX_VARIANT_TYPE_U32 || value.Data.U32 != 1) return MFX_ERR_INVALID_VIDEO_PARAM;
        bit = 256;
    } else return MFX_ERR_UNSUPPORTED;
    *(int *)config |= bit;
    return MFX_ERR_NONE;
}
static mfxStatus MFX_CDECL fixture_create(mfxLoader loader, mfxU32 index, mfxSession *out) {
    creates++;
    if (!enums && *(int *)loader != 63) { *out = NULL; return MFX_ERR_UNSUPPORTED; }
    if (index || fault == RH_QSV_TEST_ALL_CREATE_FAIL || (fault == RH_QSV_TEST_CREATE_FAIL && creates == 1)) { *out = NULL; return MFX_ERR_NOT_FOUND; }
    *out = (mfxSession)&sessions[creates];
    return fault == RH_QSV_TEST_CREATE_PARTIAL && creates == 1 ? MFX_ERR_DEVICE_FAILED : MFX_ERR_NONE;
}
static mfxStatus MFX_CDECL fixture_close(mfxSession session) {
    session_fixture *value = (session_fixture *)session;
    if (!value || value->closed) return MFX_ERR_INVALID_HANDLE;
    value->closed = 1; closes++; return MFX_ERR_NONE;
}
static mfxStatus MFX_CDECL fixture_impl(mfxSession session, mfxIMPL *out) {
    *out = fault == RH_QSV_TEST_WRONG_IMPL && session == (mfxSession)&sessions[1] ? MFX_IMPL_SOFTWARE : implementation;
    return MFX_ERR_NONE;
}
static mfxStatus MFX_CDECL fixture_version(mfxSession session, mfxVersion *out) {
    *out = api_version;
    if (fault == RH_QSV_TEST_LOW_VERSION && session == (mfxSession)&sessions[1]) { out->Major = 1; out->Minor = 2; }
    return MFX_ERR_NONE;
}
static mfxStatus MFX_CDECL fixture_set(mfxSession session, mfxHandleType type, mfxHDL handle) {
    (void)type; sets++;
    if (fault == RH_QSV_TEST_SET_FAIL && session == (mfxSession)&sessions[1]) return MFX_ERR_INVALID_HANDLE;
    ((session_fixture *)session)->handle = handle; return MFX_ERR_NONE;
}
static mfxStatus MFX_CDECL fixture_get(mfxSession session, mfxHandleType type, mfxHDL *out) {
    (void)type;
    if (fault == RH_QSV_TEST_GET_FAIL && session == (mfxSession)&sessions[1]) return MFX_ERR_INVALID_HANDLE;
    *out = ((session_fixture *)session)->handle;
    if (fault == RH_QSV_TEST_GET_MISMATCH && session == (mfxSession)&sessions[1]) *out = &dxgi_device;
    return *out ? MFX_ERR_NONE : MFX_ERR_NOT_INITIALIZED;
}
static mfxStatus MFX_CDECL fixture_join(mfxSession parent, mfxSession child) {
    joins++;
    if (parent != (mfxSession)&sessions[0] || !child || (fault == RH_QSV_TEST_JOIN_FAIL && joins == 1)) return MFX_ERR_INVALID_HANDLE;
    return MFX_ERR_NONE;
}
static mfxStatus MFX_CDECL fixture_enum(mfxLoader loader, mfxU32 index, mfxImplCapsDeliveryFormat format, mfxHDL *out) {
    static mfxImplDescription description;
    (void)loader; (void)format; enums++;
    if (index || fault == RH_QSV_TEST_ENUM_FAIL) return MFX_ERR_NOT_FOUND;
    memset(&description, 0, sizeof(description)); description.Impl = MFX_IMPL_TYPE_HARDWARE;
    description.VendorID = 0x8086; description.ApiVersion = api_version; *out = &description; return MFX_ERR_NONE;
}
static mfxStatus MFX_CDECL fixture_description(mfxLoader loader, mfxHDL desc) { (void)loader; (void)desc; return MFX_ERR_NONE; }

#define CreateDXGIFactory1 fixture_factory
#define MFXLoad fixture_load
#define MFXUnload fixture_unload
#define MFXCreateConfig fixture_config
#define MFXSetConfigFilterProperty fixture_filter
#define MFXCreateSession fixture_create
#define MFXClose fixture_close
#define MFXQueryIMPL fixture_impl
#define MFXQueryVersion fixture_version
#define MFXVideoCORE_SetHandle fixture_set
#define MFXVideoCORE_GetHandle fixture_get
#define MFXJoinSession fixture_join
#define MFXEnumImplementations fixture_enum
#define MFXDispReleaseImplDescription fixture_description

#if RHREMOTE_QSV_DEVICE
#include "hwcontext_qsv.c"
#else
#include "qsv.c"
#endif

static void reset(enum fault_kind value) {
    fault = value; references = factories = loads = unloads = creates = closes = enums = sets = joins = filters = 0;
    memset(sessions, 0, sizeof(sessions)); sessions[0].handle = &device;
    memset(loader_storage, 0, sizeof(loader_storage)); loader_storage[0] = 63;
    requested_version = api_version.Version;
    device.lpVtbl = &device_methods; dxgi_device.lpVtbl = &dxgi_methods;
    wanted_adapter.lpVtbl = &adapter_methods; entries[0].lpVtbl = entries[1].lpVtbl = &adapter_methods;
    factory.lpVtbl = &factory_methods;
}

static int actual_entry(mfxSession *out) {
#if RHREMOTE_QSV_DEVICE
    requested_version = (1u << 16) | 3u;
    AVD3D11VADeviceContext d3d = {.device = &device};
    AVHWDeviceContext child = {.type = AV_HWDEVICE_TYPE_D3D11VA, .hwctx = &d3d};
    QSVDeviceContext qsv = {0};
    AVHWDeviceContext context = {.type = AV_HWDEVICE_TYPE_QSV, .hwctx = &qsv};
    int ret = qsv_device_derive_from_child(&context, MFX_IMPL_HARDWARE, &child, 0);
    *out = qsv.p.session;
    return ret;
#else
    AVQSVDeviceContext qsv = {.session = (mfxSession)&sessions[0], .loader = &loader_storage[0]};
    AVHWDeviceContext context = {.hwctx = &qsv};
    AVBufferRef buffer = {.data = (uint8_t *)&context};
    AVCodecContext codec = {0};
    return ff_qsv_init_session_device(&codec, out, &buffer, NULL, 0);
#endif
}

#define CHECK(condition, code) do { if (!(condition)) { fprintf(stderr, "QSV actual-source regression at line %d\n", __LINE__); return code; } } while (0)

int main(void) {
    mfxSession session = NULL;
    reset(RH_QSV_TEST_NONE);
    CHECK(actual_entry(&session) == 0 && session && !enums && creates == 1 && sets == 1 && !references, 3);
#if RHREMOTE_QSV_DEVICE
    CHECK(loads == 1 && filters == 6 && !joins, 4);
#else
    CHECK(!loads && !unloads && joins == 1, 4);
#endif
#ifndef RHREMOTE_QSV_TEST_ORIGINAL
    /* Every optional-path failure frees its own resources and exposes no result. */
    const enum fault_kind rejects[] = {RH_QSV_TEST_REMOVED, RH_QSV_TEST_NON_INTEL, RH_QSV_TEST_FACTORY_FAIL, RH_QSV_TEST_DUPLICATE, RH_QSV_TEST_INVENTORY_MOVED,
        RH_QSV_TEST_LOAD_FAIL, RH_QSV_TEST_CONFIG_FAIL, RH_QSV_TEST_FILTER_FAIL, RH_QSV_TEST_CREATE_FAIL, RH_QSV_TEST_CREATE_PARTIAL, RH_QSV_TEST_WRONG_IMPL, RH_QSV_TEST_LOW_VERSION,
        RH_QSV_TEST_SET_FAIL, RH_QSV_TEST_GET_MISMATCH, RH_QSV_TEST_GET_FAIL, RH_QSV_TEST_STALE_FACTORY, RH_QSV_TEST_UNBOUNDED_INVENTORY};
    for (unsigned i = 0; i < sizeof(rejects) / sizeof(rejects[0]); i++) {
        mfxLoader loader = (mfxLoader)1;
        session = (mfxSession)1;
        reset(rejects[i]);
        CHECK(rhremote_qsv_new_session(&device, &api_version, &loader, &session) < 0, 5);
        CHECK(!loader && !session && !references && !enums, 6);
        CHECK(unloads == (loads && fault != RH_QSV_TEST_LOAD_FAIL ? 1 : 0), 7);
        for (unsigned n = 1; n <= creates; n++)
            CHECK(sessions[n].closed || fault == RH_QSV_TEST_CREATE_FAIL, 8);
    }
    /* The real FFmpeg entry must continue normal discovery after rejection. */
    const enum fault_kind fallbacks[] = {RH_QSV_TEST_CREATE_FAIL, RH_QSV_TEST_CREATE_PARTIAL, RH_QSV_TEST_WRONG_IMPL, RH_QSV_TEST_LOW_VERSION, RH_QSV_TEST_SET_FAIL, RH_QSV_TEST_GET_FAIL, RH_QSV_TEST_GET_MISMATCH};
    for (unsigned i = 0; i < sizeof(fallbacks) / sizeof(fallbacks[0]); i++) {
        reset(fallbacks[i]); session = NULL;
        CHECK(actual_entry(&session) == 0 && session && enums && creates == 2 && !references, 9);
#if !RHREMOTE_QSV_DEVICE
        CHECK(!unloads, 10); /* The parent loader is borrowed even after failure. */
#endif
    }
#if !RHREMOTE_QSV_DEVICE
    reset(RH_QSV_TEST_JOIN_FAIL); session = NULL;
    CHECK(actual_entry(&session) == 0 && session && enums && creates == 2 && joins == 2 && closes == 1 && !unloads && !references, 11);
#endif
    reset(RH_QSV_TEST_ALL_CREATE_FAIL); session = NULL;
    CHECK(actual_entry(&session) < 0 && !session && enums && !references, 12);
    reset(RH_QSV_TEST_NONE);
    CHECK(rhremote_qsv_set_handle((mfxSession)&sessions[0], MFX_HANDLE_D3D11_DEVICE, &device) == MFX_ERR_NONE && !sets, 13);
    sessions[0].handle = &dxgi_device;
    CHECK(rhremote_qsv_set_handle((mfxSession)&sessions[0], MFX_HANDLE_D3D11_DEVICE, &device) == MFX_ERR_NONE && sets == 1, 14);
    reset(RH_QSV_TEST_WRONG_IMPL); sessions[1].handle = &device;
    CHECK(rhremote_qsv_set_handle((mfxSession)&sessions[1], MFX_HANDLE_D3D11_DEVICE, &device) == MFX_ERR_NONE && sets == 1, 15);
#endif
    printf("PASS: actual pinned QSV %s fast path and fallback preserve exact device binding\n", RHREMOTE_QSV_DEVICE ? "device" : "child");
    return 0;
}
