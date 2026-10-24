/**
 * @file qsv-trace.h
 * @brief Opt-in C ABI for actual QSV calls; no FFmpeg or SDK structures are extended.
 */
#ifndef RHREMOTE_QSV_TRACE_H
#define RHREMOTE_QSV_TRACE_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

/** @brief Fixed call identities; statuses include normal SDK flow control. */
enum rhremote_qsv_api {
    RHREMOTE_QSV_ENCODE_ASYNC = 11,
    RHREMOTE_QSV_SYNC_OPERATION = 12,
    RHREMOTE_QSV_BUSY_SLEEP = 13
};
/** @brief Caller-owned, nonthrowing callbacks; disabled sinks never read a clock. */
typedef struct rhremote_qsv_sink {
    void *opaque;
    int64_t (*tick)(void *opaque);
    void (*record)(void *opaque, uint32_t api, int64_t begin_us, int64_t end_us, int32_t result);
} rhremote_qsv_sink;

/**
 * @brief Replace this thread's sink, returning the previous callbacks for restoration.
 * @param sink Caller-owned callbacks, or zero to disable; incomplete sinks are disabled.
 * @return Previous sink, which must be restored before destroying its context.
 */
rhremote_qsv_sink rhremote_qsv_set_sink(rhremote_qsv_sink sink);
#ifdef __cplusplus
}
#endif
#endif
