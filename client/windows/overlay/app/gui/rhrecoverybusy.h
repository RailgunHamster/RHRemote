#pragma once

namespace RhRecoveryBusy {
// A busy response may be transient while the host retires an interrupted
// session. This never arms recovery for a connection without a first frame.
inline bool retry(int status, int recoveryAttempt, bool closing) {
    return (status == 409 || status == 410) && recoveryAttempt >= 1 && recoveryAttempt <= 3 && !closing;
}
}
