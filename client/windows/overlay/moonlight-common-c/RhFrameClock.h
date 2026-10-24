/* RHRemote optional frame metadata clock. Does not replace protocol timers. */
#pragma once
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Return microseconds in the recorder's clock domain, or zero if unavailable.
 * Configure only before LiStartConnection or after LiStopConnection has returned.
 * The callback must remain valid until all connection threads have stopped. */
typedef uint64_t (*LI_RH_FRAME_CLOCK)(void);
void LiRhSetFrameClock(LI_RH_FRAME_CLOCK clock);

/* Zero means no measurement; it must never be replaced with a protocol timestamp. */
uint64_t LiRhFrameTimeUs(void);

#ifdef __cplusplus
}
#endif
