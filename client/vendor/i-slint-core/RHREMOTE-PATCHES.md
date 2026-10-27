# RHRemote local patch

This directory is the published `i-slint-core` 1.18.1 crate. Original Cargo metadata, source headers and licensing are retained. Its `.cargo_vcs_info.json` identifies Slint source commit `372cf0ee5577c3dfec309a45e7b778ba4e81b734`, `internal/core`. The Cargo registry cache itself is not modified.

RHRemote commit `497ed751bfc31b216c8c3be17763e85cb7d89acf` imports the unmodified published baseline separately from the project correction, so future imports and local differences remain reviewable.

The only upstream source modification is in `item_tree.rs`: `ItemRc::is_visible_or_clipped_by_flickable()` and its small `clip_overlaps()` helper are backported from [Slint commit cffe9dc85733c901b39b9523c2e701e83ac57b29](https://github.com/slint-ui/slint/blob/cffe9dc85733c901b39b9523c2e701e83ac57b29/internal/core/item_tree.rs). That commit identifies the inspected tree, not a claim that its commit introduced the fix. The source file SHA-256 is `CFCB7AD9190EA561803902AE1BFFA36DA00C05A27A56CE719B01FB69B8D83BBF`.

The 1.18.1 code intersects a LineEdit's own clip with outer clips before deciding which ancestor hides it. An offscreen LineEdit therefore looks hidden by its internal clip and is skipped by Tab before the Flickable can reveal it. The backport distinguishes an ancestor's own rectangle from the accumulated clip, while retaining genuine hidden/ordinary-clip exclusions. It does not change the existing visible-item fast path, layout, rendering, fonts, widget implementations or dependency features.

The original upstream issue and earlier fixes are [#10321](https://github.com/slint-ui/slint/issues/10321), [#10780](https://github.com/slint-ui/slint/pull/10780) and [#11494](https://github.com/slint-ui/slint/pull/11494). The earlier #11494 implementation is already in the published crate and is not the new backport.

The workspace `[patch.crates-io]` keeps this correction reproducible. When upgrading Slint, check the selected release's nested-clip traversal and run the offscreen input, hidden/ordinary-clip, complete Tab traversal and existing UI regressions. If the release contains this correction, remove this override and directory together; do not apply it twice. The independent Windows repaint override remains separate.
