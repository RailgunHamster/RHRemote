# Fixed FFmpeg MF low-latency argument

The pinned FFmpeg encoder uses VT_UI4 for CODECAPI_AVLowLatencyMode. On the tested
Arc B580 Intel MFT, SetValue returns S_OK but GetValue remains VT_BOOL false.
Canonical VT_BOOL true is accepted and survives output-type configuration.
Microsoft documents VT_BOOL for encoders:
https://learn.microsoft.com/en-us/windows/win32/medfound/codecapi-avlowlatencymode

This module recompiles `libavcodec/mfenc.c` from the exact FFmpeg source revision
in UPSTREAM.json using that source's headers and the original package's generated
config.h and avconfig.h. Source headers take priority so private relative includes
cannot accidentally resolve to another FFmpeg component's internal.h. Only the low-latency argument changes to
`FF_VAL_VT_BOOL(VARIANT_TRUE)`. The compiler emits mfenc.o; the archiver replaces
that one member in a separate build-directory copy of libavcodec.a. Every other
archive member is verified byte-identical. No executable byte patching occurs.
The original vendor package is never modified. Source/config/library/member
hash mismatches stop the build, rather than silently mixing private FFmpeg ABIs.

The original FFmpeg and LizardByte build-deps pins remain unchanged. A future
upstream update requires reviewing these explicit pins and removing this module
if the fix is already upstream. Sunshine has one small include in its dependency
loader; the correction and provenance stay outside its imported subtree.
The full source archive is identified by URL and SHA-256 in UPSTREAM.json;
FFmpeg's original LGPL notices and the project's combined GPL licensing apply.

`test_rhremote_mf_variant` calls the actual patched encoder function and checks
the variant at the CodecAPI boundary. It also verifies that ordinary contexts
do not set the low-latency property. The destination CodecAPI is a test fixture;
`test_rhremote_mf_original_variant` includes the original pinned source and must
fail the identical assertion with exit code 3; other failures are not accepted.
this test does not establish hardware performance. Real before/after media,
startup, recovery and configuration-preserving deployment remain required.
