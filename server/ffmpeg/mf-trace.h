/**
 * @file mf-trace.h
 * @brief Opt-in C ABI for actual MF call spans; no FFmpeg structures are extended.
 */
#ifndef RHREMOTE_MF_TRACE_H
#define RHREMOTE_MF_TRACE_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

/** @brief Fixed API identities; calls belong to the current invocation, not an inferred output PTS. */
enum rhremote_mf_api {
    RHREMOTE_MF_EVENT_WAIT = 1,
    RHREMOTE_MF_PROCESS_INPUT = 2,
    RHREMOTE_MF_PROCESS_OUTPUT = 3
};
/** @brief Caller-owned, nonthrowing callbacks; disabled sinks never read a clock. */
typedef struct rhremote_mf_sink {
    void *opaque;
    int64_t (*tick)(void *opaque);
    void (*record)(void *opaque, uint32_t api, int64_t begin_us, int64_t end_us, int32_t result);
} rhremote_mf_sink;

/**
 * @brief Replace only this thread's sink and return its previous value for scoped restoration.
 * @param sink Caller-owned callbacks, or zero to disable. Incomplete callbacks are disabled.
 * @return Previous sink; the caller must restore it before destroying its callback context.
 */
rhremote_mf_sink rhremote_mf_set_sink(rhremote_mf_sink sink);
#ifdef __cplusplus
}
#endif
#endif
