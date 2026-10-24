/* RHRemote optional frame metadata clock. Set only while the connection is stopped. */
#include "RhFrameClock.h"

static LI_RH_FRAME_CLOCK frameClock;

void LiRhSetFrameClock(LI_RH_FRAME_CLOCK clock) {
    frameClock = clock;
}

uint64_t LiRhFrameTimeUs(void) {
    return frameClock ? frameClock() : 0;
}
