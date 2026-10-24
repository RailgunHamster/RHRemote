/* Exercise the actual generated translation unit and its original COM callers.
 * Fixtures own all interfaces. No GPU, input sample contents, audio or sockets. */
#include "mfenc.c"
#include <stdio.h>

static unsigned clock_calls, record_calls, get_calls, input_calls, output_calls;
static uint32_t last_api;
static int32_t last_result;
static HRESULT event_result = S_OK;
static int failed;
static void *expected_opaque;
static MFT_OUTPUT_DATA_BUFFER *expected_buffers;
static DWORD *expected_status;

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "MF call regression line %d\n", __LINE__); failed = 1; } } while (0)

static ULONG STDMETHODCALLTYPE event_release(IMFMediaEvent *self) { (void)self; return 1; }
static HRESULT STDMETHODCALLTYPE event_type(IMFMediaEvent *self, MediaEventType *type) {
    (void)self;
    *type = ff_METransformNeedInput;
    return S_OK;
}
static IMFMediaEventVtbl event_methods = {.Release = event_release, .GetType = event_type};
static IMFMediaEvent fixture_event = {.lpVtbl = &event_methods};
static HRESULT STDMETHODCALLTYPE get_event(IMFMediaEventGenerator *self, DWORD flags, IMFMediaEvent **event) {
    (void)self;
    CHECK(flags == 0);
    ++get_calls;
    *event = SUCCEEDED(event_result) ? &fixture_event : NULL;
    return event_result;
}
static IMFMediaEventGeneratorVtbl generator_methods = {.GetEvent = get_event};
static IMFMediaEventGenerator generator = {.lpVtbl = &generator_methods};
static HRESULT STDMETHODCALLTYPE process_input(IMFTransform *self, DWORD stream, IMFSample *sample, DWORD flags) {
    (void)self;
    CHECK(stream == 17 && sample == (IMFSample *)(uintptr_t)0x1234 && flags == 9);
    ++input_calls;
    return E_ABORT;
}
static HRESULT STDMETHODCALLTYPE process_output(IMFTransform *self, DWORD flags, DWORD count, MFT_OUTPUT_DATA_BUFFER *buffers, DWORD *status) {
    (void)self;
    CHECK(flags == 5 && count == 1 && buffers == expected_buffers && status == expected_status);
    ++output_calls;
    *status = 37;
    return MF_E_TRANSFORM_NEED_MORE_INPUT;
}
static IMFTransformVtbl transform_methods = {.ProcessInput = process_input, .ProcessOutput = process_output};
static IMFTransform transform = {.lpVtbl = &transform_methods};
static int64_t tick(void *opaque) {
    CHECK(opaque == expected_opaque);
    return 100 + ++clock_calls;
}
static void record(void *opaque, uint32_t api, int64_t begin, int64_t end, int32_t result) {
    CHECK(opaque == expected_opaque && end == begin + 1);
    ++record_calls;
    last_api = api;
    last_result = result;
}
static DWORD WINAPI isolated_thread(void *unused) {
    (void)unused;
    rhremote_mf_sink old = rhremote_mf_set_sink((rhremote_mf_sink){0});
    CHECK(old.opaque == NULL && old.tick == NULL && old.record == NULL);
    return 0;
}

int main(void) {
    AVCodecContext context = {0};
    MFContext mf = {0};
    context.priv_data = &mf;
    mf.async_events = &generator;
    CHECK(mf_wait_events(&context) == 0 && mf.async_need_input == 1 && get_calls == 1);
    CHECK(clock_calls == 0 && record_calls == 0);
    int outer_context, inner_context;
    expected_opaque = &outer_context;
    rhremote_mf_sink active = {expected_opaque, tick, record};
    rhremote_mf_sink initial = rhremote_mf_set_sink(active);
    CHECK(initial.tick == NULL);
    mf.async_need_input = 0;
    CHECK(mf_wait_events(&context) == 0 && get_calls == 2);
    CHECK(record_calls == 1 && last_api == RHREMOTE_MF_EVENT_WAIT && last_result == S_OK);
    mf.async_need_input = 0;
    event_result = E_ABORT;
    CHECK(mf_wait_events(&context) == AVERROR_EXTERNAL && get_calls == 3);
    CHECK(record_calls == 2 && last_result == (int32_t)E_ABORT);
    CHECK(IMFTransform_ProcessInput(&transform, 17, (IMFSample *)(uintptr_t)0x1234, 9) == E_ABORT);
    CHECK(input_calls == 1 && record_calls == 3 && last_api == RHREMOTE_MF_PROCESS_INPUT && last_result == (int32_t)E_ABORT);
    MFT_OUTPUT_DATA_BUFFER buffers = {0};
    DWORD status = 0;
    expected_buffers = &buffers;
    expected_status = &status;
    CHECK(IMFTransform_ProcessOutput(&transform, 5, 1, &buffers, &status) == MF_E_TRANSFORM_NEED_MORE_INPUT);
    CHECK(output_calls == 1 && status == 37 && record_calls == 4 && last_api == RHREMOTE_MF_PROCESS_OUTPUT);
    CHECK(last_result == (int32_t)MF_E_TRANSFORM_NEED_MORE_INPUT);
    rhremote_mf_sink nested = {&inner_context, tick, record};
    rhremote_mf_sink previous = rhremote_mf_set_sink(nested);
    CHECK(previous.opaque == &outer_context);
    expected_opaque = &inner_context;
    CHECK(IMFTransform_ProcessInput(&transform, 17, (IMFSample *)(uintptr_t)0x1234, 9) == E_ABORT);
    CHECK(record_calls == 5);
    rhremote_mf_set_sink(previous);
    expected_opaque = &outer_context;
    HANDLE thread = CreateThread(NULL, 0, isolated_thread, NULL, 0, NULL);
    CHECK(thread && WaitForSingleObject(thread, 10000) == WAIT_OBJECT_0);
    if (thread) CloseHandle(thread);
    CHECK(IMFTransform_ProcessInput(&transform, 17, (IMFSample *)(uintptr_t)0x1234, 9) == E_ABORT);
    CHECK(record_calls == 6);
    rhremote_mf_sink incomplete = {&outer_context, tick, NULL};
    previous = rhremote_mf_set_sink(incomplete);
    CHECK(previous.opaque == &outer_context);
    unsigned clocks_before = clock_calls;
    CHECK(IMFTransform_ProcessInput(&transform, 17, (IMFSample *)(uintptr_t)0x1234, 9) == E_ABORT);
    CHECK(record_calls == 6 && clock_calls == clocks_before);
    rhremote_mf_set_sink(initial);
    if (failed) return 1;
    puts("PASS: actual MF calls preserve results, arguments, events and thread-local opt-in spans");
    return 0;
}
