# RHRemote local patch

This is the published `i-slint-backend-winit` 1.18.1 crate, with its original license files and Cargo metadata retained.

The upstream repaint modification is Slint PR [#13568](https://github.com/slint-ui/slint/pull/13568), commit `5203fd9`: `winit: repaint what Windows invalidated` (merged 2026-09-28). It reads the Windows update rectangle before painting and passes that region to Slint's dirty-region tracker. Internal paints without a Windows update rectangle retain partial rendering.

Patch source: `https://github.com/slint-ui/slint/pull/13568.diff`.
SHA-256 is recorded in `design/WINDOWS-REPAINT-20261027.md`.

The workspace uses `[patch.crates-io]` to keep these changes reproducible without modifying the Cargo registry cache. On a future Slint upgrade, check whether the selected release contains the repaint fix. Do not reapply that fix to a release that already contains it.

RHRemote also adds `renderer/layered.rs` and a small opt-in branch in `renderer/sw.rs`. Only a Windows HWND carrying the `RHRemote.KeyboardPerPixel` property uses this path. It presents the existing software-rendered premultiplied BGRA buffer through `UpdateLayeredWindow`, preserving opaque lettering over translucent key surfaces. Ordinary windows retain the upstream softbuffer path. There is no desktop capture or remote-video readback; the bitmap is cached by size and updated only when Slint reports UI damage. The private unshown-window regression verifies the alpha values accepted by Windows. This local addition must be retained or replaced with equivalent per-pixel software presentation when removing the vendor override.
