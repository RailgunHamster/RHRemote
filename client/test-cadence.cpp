#include "StableCadence.h"
#include <cassert>
#include <iostream>
#include <vector>

int main() {
    StableCadence policy;
    assert(!policy.configure(60, 60));
    assert(!policy.configure(144, 30));
    assert(!policy.configure(60, 0));
    assert(policy.configure(60, 30));
    assert(!policy.shouldRender(0));
    assert(!policy.shouldRender(1));
    assert(policy.shouldRender(2));
    assert(!policy.shouldRender(1));
    assert(policy.shouldRender(1));
    assert(!policy.shouldRender(0));
    assert(!policy.shouldRender(0)); // real underflow is preserved
    assert(policy.shouldRender(1)); // recover immediately; do not amplify the stall
    assert(!policy.shouldRender(2));

    // Realistic arrival jitter crosses one refresh boundary, but the buffered
    // steady stream must retain two-refresh presentation intervals.
    for (unsigned reserve = 1; reserve <= 2; ++reserve) {
    policy.configure(60, 30, reserve);
    unsigned queued = 0;
    unsigned nextFrame = 0;
    std::vector<unsigned> rendered;
    for (unsigned tick = 0; tick < 1200; ++tick) {
        while (nextFrame * 2 + (nextFrame % 3 == 0 ? 1 : 0) <= tick) {
            ++queued;
            ++nextFrame;
        }
        if (policy.shouldRender(queued)) {
            assert(queued > 0);
            --queued;
            rendered.push_back(tick);
        }
        assert(queued <= reserve + 1);
    }
    for (unsigned i = 1; i < rendered.size(); ++i) assert(rendered[i] - rendered[i-1] == 2);
    assert(rendered.size() >= 596);
    }
    for (unsigned reserve = 1; reserve <= 2; ++reserve) {
        policy.configure(60, 30, reserve);
        assert(!policy.shouldRender(reserve, true)); // startup reserve still required
        assert(policy.shouldRender(reserve + 1));
        assert(policy.shouldRender(reserve + 1, true)); // late wake avoids extra idle VBlank
        assert(!policy.shouldRender(reserve + 1)); // no catch-up burst on next wake
        assert(policy.shouldRender(reserve + 1));
        assert(!policy.shouldRender(0, true)); // true starvation cannot be hidden
        assert(policy.shouldRender(1)); // normal first-frame recovery still works
        assert(!policy.shouldRender(1));
    }
    std::cout << "PASS: matched cadence, bounded reserve, jitter, real underflow, late-wake recovery without replay.\n";
}
