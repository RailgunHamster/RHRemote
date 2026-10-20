#pragma once

#include <cstdint>

// Optional cadence for an integer refresh/stream ratio. Keep one frame in reserve
// before starting so small arrival-time variations do not move each frame between
// adjacent VBlanks. Call only from the VSync thread, with the frame queue locked.
// This never creates or repeats a frame and cannot hide a real queue underflow.
class StableCadence {
public:
    StableCadence() : m_step(0), m_reserve(1), m_tick(0), m_next(0), m_started(false) {}

    bool configure(int displayFps, int streamFps, unsigned reserve = 1) {
        m_step = reserve >= 1 && reserve <= 2 && displayFps > streamFps && streamFps > 0 && displayFps % streamFps == 0
                ? static_cast<unsigned>(displayFps / streamFps) : 0;
        m_tick = m_next = 0;
        m_reserve = reserve;
        m_started = false;
        return enabled();
    }

    bool enabled() const { return m_step != 0; }
    unsigned reserve() const { return m_reserve; }

    bool shouldRender(unsigned queuedFrames, bool lateWake = false) {
        const std::uint64_t tick = m_tick++;
        if (!enabled()) return false;
        if (!m_started) {
            if (queuedFrames < m_reserve + 1) return false;
            m_started = true;
        } else if (tick < m_next && !lateWake) {
            return false;
        }
        if (queuedFrames == 0) {
            // A real underflow remains visible. Resume with the first arriving frame,
            // rather than extending the stall while rebuilding the startup reserve.
            return false;
        }
        // A late wake may represent missed native VBlanks. Render at most one
        // queued frame now, then resume the usual spacing; never replay a burst.
        m_next = tick + m_step;
        return true;
    }

private:
    unsigned m_step;
    unsigned m_reserve;
    std::uint64_t m_tick;
    std::uint64_t m_next;
    bool m_started;
};
