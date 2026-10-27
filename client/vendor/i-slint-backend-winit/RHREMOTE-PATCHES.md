# RHRemote local patch

This is the published `i-slint-backend-winit` 1.18.1 crate, with its original license files and Cargo metadata retained.

The only upstream source modification is Slint PR [#13568](https://github.com/slint-ui/slint/pull/13568), commit `5203fd9`: `winit: repaint what Windows invalidated` (merged 2026-09-28). It reads the Windows update rectangle before painting and passes that region to Slint's dirty-region tracker. Internal paints without a Windows update rectangle retain partial rendering.

Patch source: `https://github.com/slint-ui/slint/pull/13568.diff`.
SHA-256 is recorded in `design/WINDOWS-REPAINT-20261027.md`.

The workspace uses `[patch.crates-io]` to keep this change reproducible without modifying the Cargo registry cache. On a future Slint upgrade, check whether the selected release contains this fix; then remove this override and this directory together after the Windows restore/DPI regression passes. Do not reapply the patch to a release that already contains it.
