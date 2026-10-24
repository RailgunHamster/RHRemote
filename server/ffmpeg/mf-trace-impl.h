/**
 * @file mf-trace-impl.h
 * @brief Wrap the original COM calls once, preserving arguments, return values and ownership.
 * @details Included only by the fixed mfenc.c after the original MF interface headers.
 */
#include "mf-trace.h"

static _Thread_local rhremote_mf_sink rhremote_mf_current_sink;

rhremote_mf_sink rhremote_mf_set_sink(rhremote_mf_sink sink) {
    rhremote_mf_sink previous = rhremote_mf_current_sink;
    if (!sink.tick || !sink.record)
        sink = (rhremote_mf_sink){0};
    rhremote_mf_current_sink = sink;
    return previous;
}

/** @brief Complete one actually executed COM call using the caller's monotonic clock. */
static void rhremote_mf_record(rhremote_mf_sink sink, uint32_t api, int64_t begin, HRESULT result) {
    if (sink.tick)
        sink.record(sink.opaque, api, begin, sink.tick(sink.opaque), (int32_t)result);
}

/** @brief Measure GetEvent itself, including blocking; the returned event is still owned by FFmpeg. */
static HRESULT rhremote_mf_get_event(IMFMediaEventGenerator *generator, DWORD flags, IMFMediaEvent **event) {
    rhremote_mf_sink sink = rhremote_mf_current_sink;
    int64_t begin = sink.tick ? sink.tick(sink.opaque) : 0;
    HRESULT result = IMFMediaEventGenerator_GetEvent(generator, flags, event);
    rhremote_mf_record(sink, RHREMOTE_MF_EVENT_WAIT, begin, result);
    return result;
}

/** @brief Measure one ProcessInput call without inspecting or changing its sample. */
static HRESULT rhremote_mf_process_input(IMFTransform *transform, DWORD stream, IMFSample *sample, DWORD flags) {
    rhremote_mf_sink sink = rhremote_mf_current_sink;
    int64_t begin = sink.tick ? sink.tick(sink.opaque) : 0;
    HRESULT result = IMFTransform_ProcessInput(transform, stream, sample, flags);
    rhremote_mf_record(sink, RHREMOTE_MF_PROCESS_INPUT, begin, result);
    return result;
}

/** @brief Measure one ProcessOutput call without changing output buffers or status. */
static HRESULT rhremote_mf_process_output(IMFTransform *transform, DWORD flags, DWORD count, MFT_OUTPUT_DATA_BUFFER *buffers, DWORD *status) {
    rhremote_mf_sink sink = rhremote_mf_current_sink;
    int64_t begin = sink.tick ? sink.tick(sink.opaque) : 0;
    HRESULT result = IMFTransform_ProcessOutput(transform, flags, count, buffers, status);
    rhremote_mf_record(sink, RHREMOTE_MF_PROCESS_OUTPUT, begin, result);
    return result;
}

/* Wrappers above use the original macros. Only subsequent encoder calls are redirected. */
#undef IMFMediaEventGenerator_GetEvent
#define IMFMediaEventGenerator_GetEvent rhremote_mf_get_event
#undef IMFTransform_ProcessInput
#define IMFTransform_ProcessInput rhremote_mf_process_input
#undef IMFTransform_ProcessOutput
#define IMFTransform_ProcessOutput rhremote_mf_process_output
