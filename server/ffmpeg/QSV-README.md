# Pinned QSV initialization candidate

This module rebuilds the exact `libavutil/hwcontext_qsv.c` and `libavcodec/qsv.c`
recorded in QSV-UPSTREAM.json, using the same original generated FFmpeg ABI
configuration as the MF correction. The vendor archives are never overwritten.
The final codec archive preserves the verified MF object, changes only `qsv.o`,
and verifies all other 212 members against the original bytes and order. The
utility archive changes only `hwcontext_qsv.o`, preserving its other 112 members.
Per-library provenance records the compiler objects and final archive hashes.

Before re-archiving, the build compares the original and compiled COFF objects'
actual DWARF storage layouts for 21 codec and 19 utility types. Sizes, member
offsets, nested arrays/scalar encodings and atomic wrappers must match. Missing
or unsupported debug layouts fail the build; the two objects retain `-g` even
in Release builds. This checks storage compatibility, not pointer pointee types
or calling conventions. Actual entry-point tests and real media remain needed.
Both ABI proofs are included in the package and tied to the compiled object hash.

The optional Windows oneVPL D3D11 path maps the actual healthy Intel device LUID
to a unique current DXGI adapter index. A new loader uses ImplName `mfx-gen`,
hardware type, required API version, Intel vendor, that index, and D3D11 mode.
It calls CreateSession directly instead of requesting all implementation caps.
Both implementation/version and the actual bound D3D11 handle are checked;
adapter movement and device loss reject the attempt. A child session uses the
parent's loader, implementation, version and device, and must join successfully.
Rejected attempts release only newly created resources and use the original
FFmpeg discovery path. Other platforms retain the original calls.

An already verified identical handle is not set a second time. A failed or
different GetHandle result still calls the original SetHandle API. No public or
private FFmpeg structure layout changes. Helpers live outside the Sunshine
imported subtree, with exact patch anchors and input hashes to make upstream
updates explicit. Original FFmpeg LGPL notices and combined project licensing
continue to apply.

Eight separate zero-sample Intel VPL 2.17 controls identified a fast dispatcher
branch on winserver; they do not prove this candidate's production performance.
Actual translation-unit regression tests, full builds, artifact verification,
real media, initialization and recovery tests remain required before deployment
or a performance claim. See design/QSV-INITIALIZATION-20261024.md.
