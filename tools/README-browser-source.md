# Real-movie source diagnostics

`browser-movie-source.ps1` plays a hash-checked, existing movie in a separate Edge
kiosk on one explicitly named active display. It is a benchmark source, not a
replacement streaming client. Use an active console task, as with the existing
movie source; QueryDisplayConfig is not available from an arbitrary SSH desktop.

Required parameters are `MediaManifest`, `ExpectedMonitorDevicePath` and an
existing private `OutputDirectory`. The manifest contains `movie`,
`movieSha256` and ffprobe `metadata.streams` fields for width, height and
`avg_frame_rate`. The selected display and requested `SourceFps` must match.
The source seeks to 30 seconds in the actual movie and plays at normal speed.
`Seconds` is bounded to 30–180. No synthetic clip or frame interpolation is used.

The script needs the companion `.cs`, `.html` and `displayconfig.ps1` beside it,
a 64-bit Windows host and an existing Edge installation. It never changes a
display mode, URL ACL, installed browser configuration or streaming service.
The page is muted before assigning its media URL.

The browser starts suspended, joins a private kill-on-close job, and then resumes.
Its fresh profile is neither synchronized nor reused. Window placement applies
only to job-owned windows. Explicit stop, failure and owner-process exit terminate
only that job. Profile deletion verifies the exact fresh directory and rejects
reparse points. The source also honors the existing `stop-motion` marker.

`motion-status.json` contains phase, actual source counters and cleanup status.
The loopback-only capability URL is not exposed by status. Raw JSONL contains
each original page packet with receipt QPC and all reported frame metadata.
It records `requestVideoFrameCallback` composition counters/timestamps and
`getVideoPlaybackQuality` drop counters. `play()` success is distinct from the
first composition callback. Browser counters do not prove physical scanout.

`DisableAcceleratedVideoDecode` requests a browser decoder policy for a contrast;
`EnableMediaLog` writes a private diagnostic log outside the disposable profile.
A requested flag is not proof of the selected decoder. Missing decoder identity,
CPU accounting and optional frame processing durations remain unknown.

`analyze-browser-source.py CASE RAW --output NEW_JSON` matches before/after
endpoints to actual packets. It rejects missing batches, counter discontinuities,
duplicate media timestamps and invalid viewport/mute/rate state. It independently
recomputes the source clock/drop gate and compositor submission rate; it never
accepts remote performance on the basis of source counters. Processing duration
also covers possible queuing/conversion and excludes unreported dropped frames.

Run the following contracts before deployment:

```powershell
./tools/test-browser-movie-source.ps1
powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File ./tools/test-browser-movie-source.ps1
python tools/test-analyze-browser-source.py
node tools/test-browser-movie-page.cjs
```

The PowerShell contract exercises the real loopback server and both explicit and
owner-failure cleanup of a real descendant tree. It does not launch a browser or
change the desktop. Frame fixtures in the analyzer tests are metadata only.
