# Floating keyboard contrast

The previous Windows overlay applied alpha 166 to the entire window, including
the lettering and edges. Android combined a translucent backing sheet with
translucent keys, making the covered video unnecessarily opaque. Light remote
documents exposed the weak key/label contrast.

Both layouts now use a dark key fill with alpha 80/255, opaque white lettering
with a dark glyph outline, and opposing dark/white key edges. Android has no
backing sheet; the key gaps expose the unmodified remote image. Selected and
pressed keys use a stronger blue fill. Full/simple layout geometry, hit regions,
IME positioning and action routing are unchanged.

Windows opts only the owned keyboard HWND into a software-rendered, per-pixel
layered presenter. This preserves independent fill and glyph opacity. Ordinary
device/settings/control windows keep the existing presentation path. The DIB is
cached by size, alpha is premultiplied BGRA, and presentation happens only on UI
damage. Rendering reads no desktop or video frames. The implementation has a
bounded bitmap size and rejects mismatched buffer lengths before copying.

This is a dual-outline contrast treatment, **not sampled background adaptation**.
It does not choose light/dark themes from video content, add a video-frame loop,
or claim zero rendering overhead. A new DIB/pixel buffer costs memory and UI
repaints cost presentation work. Linux retains its previous window-opacity path;
the Windows per-pixel fix has not been validated on an Ubuntu desktop.

Verification uses production widgets rendered over white, dark and patterned
test backgrounds, checking transparent gaps, low-opacity key fills and clear
letter stems/halos. Native Windows coverage submits independent alpha values to
an unshown private HWND, including clearing a previously opaque pixel. Android
tests cover both keyboard layouts and the existing input/IME/hit-region behavior.
These checks do not substitute for a physical OPPO or live-stream acceptance.
