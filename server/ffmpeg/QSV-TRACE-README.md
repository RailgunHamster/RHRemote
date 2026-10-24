# QSV call observation

This layer recompiles only `qsvenc.o` from the same pinned FFmpeg source and generated ABI configuration.
The preceding QSV device/child-session and MF correction objects are kept byte-identical.
Every unrelated member and its order, including duplicate basenames, remains unchanged.
The 22 required vendor/compiler DWARF storage layouts, including `QSVEncContext`,
are compared before the archive is linked; the preceding 40 device/session checks
remain unchanged. Pointer pointees and calling conventions are not inferred from
these storage models. `AVQSVDeviceContext` has no vendor layout in `qsvenc.o`, which
does not dereference it; both preceding QSV objects continue to check that type.

The actual `EncodeFrameAsync`, `SyncOperation` and original 500-microsecond busy sleep
are observed through a thread-local, opt-in C ABI. Retry conditions, SDK timeout, arguments,
return values and packet ownership are unchanged. Disabled observation does not read a clock.
The host binds the callbacks only during a traced QSV invocation and restores outer callbacks
before its stack-owned work buffer expires. No samples, pointers, pixels or audio are recorded.

CPU call spans include blocking and preemption; they are not GPU execution time.
Each invocation has bounded storage, with discarded-call counts on overflow. Incomplete spans
must not be treated as a complete profile. Output PTS may belong to an earlier input, so these
calls describe the current invocation rather than that output frame's latency.

The boundary test includes the real generated `qsvenc.c` with SDK fixtures. It exercises its
busy/execution retries, sleep, repeated synchronization, packet output and failure path with
tracing enabled and disabled. It also checks thread isolation, nested sinks and incomplete
callbacks. This is a local call-boundary regression; no GPU or actual media is produced.
Real full-codec host/client media acceptance remains required after the exact CI package is verified.
