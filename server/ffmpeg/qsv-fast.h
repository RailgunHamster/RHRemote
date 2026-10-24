/**
 * @file qsv-fast.h
 * @brief Optional oneVPL D3D11 initialization without full capability enumeration.
 * @details Included by the two pinned FFmpeg QSV translation units. No FFmpeg
 * public/private structure layout is changed; rejected attempts leave the
 * original initialization path responsible for compatibility and diagnostics.
 */
#ifndef RHREMOTE_FFMPEG_QSV_FAST_H
#define RHREMOTE_FFMPEG_QSV_FAST_H

#if defined(_WIN32) && CONFIG_D3D11VA && QSV_ONEVPL
#ifndef COBJMACROS
#define COBJMACROS
#endif
#include <d3d11.h>
#include <dxgi1_2.h>

/**
 * @brief Resolve a healthy Intel D3D11 device to one current DXGI adapter index.
 * @param handle The caller's actual device, never a vendor-only first match.
 * @param index Receives the uniquely matching index only on success.
 * @return Zero on an exact match; a negative value rejects the optional path.
 */
static int rhremote_qsv_device_index(mfxHDL handle, mfxU32 *index)
{
    ID3D11Device *device = handle;
    IDXGIDevice *dxgi = NULL;
    IDXGIAdapter *adapter = NULL;
    IDXGIFactory1 *factory = NULL;
    DXGI_ADAPTER_DESC wanted;
    mfxU32 candidate = 0, matches = 0;
    HRESULT hr;
    int ret = AVERROR_UNKNOWN;

    if (!device || !index || FAILED(ID3D11Device_GetDeviceRemovedReason(device)))
        return ret;
    hr = ID3D11Device_QueryInterface(device, &IID_IDXGIDevice, (void **)&dxgi);
    if (FAILED(hr) || !dxgi)
        goto done;
    hr = IDXGIDevice_GetAdapter(dxgi, &adapter);
    if (FAILED(hr) || !adapter || FAILED(IDXGIAdapter_GetDesc(adapter, &wanted)) ||
        wanted.VendorId != 0x8086)
        goto done;
    hr = CreateDXGIFactory1(&IID_IDXGIFactory1, (void **)&factory);
    if (FAILED(hr) || !factory)
        goto done;
    for (mfxU32 i = 0; i < 128; i++) {
        IDXGIAdapter1 *entry = NULL;
        DXGI_ADAPTER_DESC1 description;
        hr = IDXGIFactory1_EnumAdapters1(factory, i, &entry);
        if (hr == DXGI_ERROR_NOT_FOUND)
            break;
        if (FAILED(hr) || !entry) {
            if (entry)
                IDXGIAdapter1_Release(entry);
            goto done;
        }
        hr = IDXGIAdapter1_GetDesc1(entry, &description);
        IDXGIAdapter1_Release(entry);
        if (FAILED(hr))
            goto done;
        if (description.AdapterLuid.LowPart == wanted.AdapterLuid.LowPart &&
            description.AdapterLuid.HighPart == wanted.AdapterLuid.HighPart) {
            if (description.VendorId != wanted.VendorId ||
                description.DeviceId != wanted.DeviceId ||
                (description.Flags & DXGI_ADAPTER_FLAG_SOFTWARE))
                goto done;
            candidate = i;
            matches++;
        }
        /* Exhausting the bound is not proof that the inventory was complete. */
        if (i == 127)
            goto done;
    }
    if (matches == 1 && IDXGIFactory1_IsCurrent(factory) &&
        SUCCEEDED(ID3D11Device_GetDeviceRemovedReason(device))) {
        *index = candidate;
        ret = 0;
    }
done:
    if (factory)
        IDXGIFactory1_Release(factory);
    if (adapter)
        IDXGIAdapter_Release(adapter);
    if (dxgi)
        IDXGIDevice_Release(dxgi);
    return ret;
}

/** @brief Require a hardware D3D11 implementation rather than accepting AUTO or software. */
static int rhremote_qsv_hardware_d3d11(mfxIMPL impl)
{
    mfxIMPL base = MFX_IMPL_BASETYPE(impl);
    return (impl & 0x0f00) == MFX_IMPL_VIA_D3D11 &&
           (base == MFX_IMPL_HARDWARE || base == MFX_IMPL_HARDWARE2 ||
            base == MFX_IMPL_HARDWARE3 || base == MFX_IMPL_HARDWARE4);
}

/**
 * @brief Try index zero of an already filtered loader and validate its real device.
 * @param loader Borrowed loader; never unloaded or reconfigured here.
 * @param handle The exact caller-owned D3D11 device to bind.
 * @param version Minimum version, or exact version when expected_impl is nonzero.
 * @param expected_impl Parent implementation for a child, zero for a new device.
 * @param expected_index Optional adapter index used to configure a new loader.
 * @param result Receives a validated session; remains null after any failure.
 * @return Zero on success; the caller must use its original path after rejection.
 */
static int rhremote_qsv_try_session(mfxLoader loader, mfxHDL handle,
                                    const mfxVersion *version, mfxIMPL expected_impl,
                                    const mfxU32 *expected_index,
                                    mfxSession *result)
{
    mfxSession session = NULL;
    mfxVersion actual = {0};
    mfxIMPL impl = 0;
    mfxHDL bound = NULL;
    mfxU32 before, after;
    mfxStatus status;

    *result = NULL;
    if (!loader || !version || rhremote_qsv_device_index(handle, &before) ||
        (expected_index && before != *expected_index))
        return AVERROR_UNKNOWN;
    status = MFXCreateSession(loader, 0, &session);
    if (status != MFX_ERR_NONE || !session)
        goto fail;
    if (MFXQueryIMPL(session, &impl) != MFX_ERR_NONE ||
        MFXQueryVersion(session, &actual) != MFX_ERR_NONE ||
        !rhremote_qsv_hardware_d3d11(impl) ||
        (expected_impl ? (impl != expected_impl || actual.Version != version->Version)
                       : actual.Version < version->Version))
        goto fail;
    if (MFXVideoCORE_SetHandle(session, MFX_HANDLE_D3D11_DEVICE, handle) != MFX_ERR_NONE ||
        MFXVideoCORE_GetHandle(session, MFX_HANDLE_D3D11_DEVICE, &bound) != MFX_ERR_NONE ||
        bound != handle || rhremote_qsv_device_index(handle, &after) || after != before)
        goto fail;
    *result = session;
    return 0;
fail:
    if (session)
        MFXClose(session);
    return AVERROR_UNKNOWN;
}

/**
 * @brief Build a minimal Intel loader eligible for the dispatcher's fast path.
 * @param handle Actual healthy D3D11 device.
 * @param version Required API version.
 * @param result_loader Receives a loader only after session validation succeeds.
 * @param result_session Receives its validated, device-bound session.
 * @return Zero on success; no partially created session or loader escapes failure.
 */
static int rhremote_qsv_new_session(mfxHDL handle, const mfxVersion *version,
                                    mfxLoader *result_loader, mfxSession *result_session)
{
    mfxLoader loader = NULL;
    mfxConfig config;
    mfxVariant value = {0};
    mfxU32 index;
    static const char *names[] = {
        "mfxImplDescription.Impl", "mfxImplDescription.ApiVersion.Version",
        "mfxImplDescription.VendorID", "DXGIAdapterIndex",
        "mfxImplDescription.AccelerationMode"
    };
    mfxU32 values[5];

    *result_loader = NULL;
    *result_session = NULL;
    if (!version || rhremote_qsv_device_index(handle, &index))
        return AVERROR_UNKNOWN;
    loader = MFXLoad();
    if (!loader)
        return AVERROR_UNKNOWN;
    config = MFXCreateConfig(loader);
    if (!config)
        goto fail;
    values[0] = MFX_IMPL_TYPE_HARDWARE;
    values[1] = version->Version;
    values[2] = 0x8086;
    values[3] = index;
    values[4] = MFX_ACCEL_MODE_VIA_D3D11;
    for (unsigned i = 0; i < 5; i++) {
        value.Type = MFX_VARIANT_TYPE_U32;
        value.Data.U32 = values[i];
        if (MFXSetConfigFilterProperty(config, (const mfxU8 *)names[i], value) != MFX_ERR_NONE)
            goto fail;
    }
    value.Type = MFX_VARIANT_TYPE_PTR;
    value.Data.Ptr = (void *)"mfx-gen";
    if (MFXSetConfigFilterProperty(config, (const mfxU8 *)"mfxImplDescription.ImplName", value) != MFX_ERR_NONE ||
        rhremote_qsv_try_session(loader, handle, version, 0, &index, result_session))
        goto fail;
    *result_loader = loader;
    return 0;
fail:
    MFXUnload(loader);
    return AVERROR_UNKNOWN;
}

/**
 * @brief Preserve an already validated identical D3D11 binding without setting it twice.
 * @details All other handles and failed/mismatched queries call the original API.
 */
static mfxStatus rhremote_qsv_set_handle(mfxSession session, mfxHandleType type, mfxHDL handle)
{
    mfxHDL bound = NULL;
    mfxIMPL impl = 0;
    if (type == MFX_HANDLE_D3D11_DEVICE && handle &&
        MFXQueryIMPL(session, &impl) == MFX_ERR_NONE && rhremote_qsv_hardware_d3d11(impl) &&
        MFXVideoCORE_GetHandle(session, type, &bound) == MFX_ERR_NONE && bound == handle &&
        SUCCEEDED(ID3D11Device_GetDeviceRemovedReason((ID3D11Device *)handle)))
        return MFX_ERR_NONE;
    return MFXVideoCORE_SetHandle(session, type, handle);
}
#else
#define rhremote_qsv_set_handle MFXVideoCORE_SetHandle
#endif
#endif
