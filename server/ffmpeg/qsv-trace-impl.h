/**
 * @file qsv-trace-impl.h
 * @brief Observe original SDK calls once, without changing arguments, results or retry policy.
 * @details Included only by pinned qsvenc.c after the original SDK and time headers.
 */
#include "qsv-trace.h"

static _Thread_local rhremote_qsv_sink rhremote_qsv_current_sink;

rhremote_qsv_sink rhremote_qsv_set_sink(rhremote_qsv_sink sink) {
    rhremote_qsv_sink previous = rhremote_qsv_current_sink;
    if (!sink.tick || !sink.record)
        sink = (rhremote_qsv_sink){0};
    rhremote_qsv_current_sink = sink;
    return previous;
}

/** @brief Record one executed call using the caller's monotonic clock and original status. */
static void rhremote_qsv_record(rhremote_qsv_sink sink, uint32_t api, int64_t begin, int32_t result) {
    if (sink.tick)
        sink.record(sink.opaque, api, begin, sink.tick(sink.opaque), result);
}

/** @brief Measure actual EncodeFrameAsync calls, including each busy or execution retry. */
static mfxStatus rhremote_qsv_encode_async(mfxSession session, mfxEncodeCtrl *control,
        mfxFrameSurface1 *surface, mfxBitstream *bits, mfxSyncPoint *sync) {
    rhremote_qsv_sink sink = rhremote_qsv_current_sink;
    int64_t begin = sink.tick ? sink.tick(sink.opaque) : 0;
    mfxStatus result = MFXVideoENCODE_EncodeFrameAsync(session, control, surface, bits, sync);
    rhremote_qsv_record(sink, RHREMOTE_QSV_ENCODE_ASYNC, begin, result);
    return result;
}

/** @brief Measure actual SyncOperation calls with the unchanged SDK timeout. */
static mfxStatus rhremote_qsv_sync_operation(mfxSession session, mfxSyncPoint sync, mfxU32 wait) {
    rhremote_qsv_sink sink = rhremote_qsv_current_sink;
    int64_t begin = sink.tick ? sink.tick(sink.opaque) : 0;
    mfxStatus result = MFXVideoCORE_SyncOperation(session, sync, wait);
    rhremote_qsv_record(sink, RHREMOTE_QSV_SYNC_OPERATION, begin, result);
    return result;
}

/** @brief Measure the original busy sleep, including scheduler overshoot. */
static int rhremote_qsv_busy_sleep(unsigned usec) {
    rhremote_qsv_sink sink = rhremote_qsv_current_sink;
    int64_t begin = sink.tick ? sink.tick(sink.opaque) : 0;
    int result = av_usleep(usec);
    rhremote_qsv_record(sink, RHREMOTE_QSV_BUSY_SLEEP, begin, result);
    return result;
}

/* Wrappers above call the original SDK. Only subsequent encoder sites change. */
#undef MFXVideoENCODE_EncodeFrameAsync
#define MFXVideoENCODE_EncodeFrameAsync rhremote_qsv_encode_async
#undef MFXVideoCORE_SyncOperation
#define MFXVideoCORE_SyncOperation rhremote_qsv_sync_operation
