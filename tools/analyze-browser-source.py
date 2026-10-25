"""Validate a real-movie source's recorded compositor callbacks and quality counters.

This is a source-input diagnostic. Browser composition is not physical scanout,
and processingDuration is not a timed hardware decoder API call.
"""
import argparse
import hashlib
import json
import math
from collections import Counter
from pathlib import Path


def number(value, name):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f"Missing or non-finite {name}")
    return value


def distribution(values):
    if not values:
        return {"count": 0, "mean": None, "p50": None, "p95": None, "p99": None, "max": None}
    ordered = sorted(values)
    return {"count": len(values), "mean": sum(values) / len(values),
            **{f"p{p}": ordered[max(0, math.ceil(len(values) * p / 100) - 1)] for p in (50, 95, 99)},
            "max": ordered[-1]}


def analyze(case, records, source_fps=60):
    before = case.get("before", case.get("sourceBefore"))
    after = case.get("after", case.get("sourceAfter"))
    if not before or not after or before.get("producer") != "browser_movie" or after.get("producer") != "browser_movie":
        raise ValueError("Actual browser source endpoints required")
    begin, end = before["browser"], after["browser"]
    left = number(begin.get("nowMs"), "begin browser time")
    right = number(end.get("nowMs"), "end browser time")
    seconds = (right - left) / 1000
    if seconds <= 0 or before.get("source_file_fps") != source_fps or after.get("source_file_fps") != source_fps:
        raise ValueError("Actual source rate or interval differs")
    packets, frames, window_packets = [], [], []
    previous_qpc = 0
    for i, record in enumerate(records, 1):
        packet = json.loads(record["json"])
        if record.get("sequence") != i or packet.get("seq") != i or packet.get("schema") != "rhremote.browser-source/v1":
            raise ValueError("Missing, reordered or wrong-schema source packet")
        qpc = number(record.get("receivedQpc"), "transport receipt QPC")
        if qpc < previous_qpc:
            raise ValueError("Transport receipt clock regressed")
        previous_qpc = qpc
        batch = packet.get("frames")
        if not isinstance(batch, list):
            raise ValueError("Source frame batch is absent")
        frames.extend(batch)
        if packet.get("callbackCount") != len(frames):
            raise ValueError("Raw callback batches do not account for the callback counter")
        packets.append(packet)
        if left <= number(packet.get("nowMs"), "packet clock") <= right:
            window_packets.append(packet)
    if not packets or not packets[-1].get("ended") or packets[-1].get("error"):
        raise ValueError("Source transport did not close successfully")
    indexed = {p["seq"]: p for p in packets}
    for endpoint in (begin, end):
        raw = indexed.get(endpoint.get("seq"))
        if not raw or any(raw.get(k) != endpoint.get(k) for k in ("nowMs", "currentTime", "callbackCount", "lastFrame", "quality")):
            raise ValueError("Case endpoint does not match an actual recorded source packet")
    for p in window_packets:
        if (p.get("phase") != "playing" or p.get("paused") or p.get("seeking") or p.get("visible") != "visible"
                or p.get("muted") is not True or p.get("volume") != 0 or p.get("playbackRate") != 1
                or p.get("width") != begin.get("width") or p.get("height") != begin.get("height")
                or number(p.get("innerWidth"), "viewport width") * number(p.get("devicePixelRatio"), "pixel scale") != p["width"]
                or number(p.get("innerHeight"), "viewport height") * p["devicePixelRatio"] != p["height"]):
            raise ValueError("Window source stopped, changed viewport, lost visibility or violated mute/rate")
    selected = []
    for frame in frames:
        now = number(frame.get("nowMs"), "callback clock")
        if left < now <= right:
            for k in ("presentationTime", "expectedDisplayTime", "mediaTime", "presentedFrames", "width", "height"):
                number(frame.get(k), k)
            if frame["width"] != begin["width"] or frame["height"] != begin["height"]:
                raise ValueError("Actual composition frame dimensions changed")
            selected.append(frame)
    if len(selected) < 2:
        raise ValueError("Insufficient recorded composition callbacks")
    for a, b in zip(selected, selected[1:]):
        if (b["nowMs"] <= a["nowMs"] or b["mediaTime"] <= a["mediaTime"]
                or b["presentedFrames"] <= a["presentedFrames"]
                or b["presentationTime"] < a["presentationTime"]):
            raise ValueError("Source frame identity or composition clock duplicated/regressed")
    def delta(path):
        a, b = begin, end
        for key in path:
            a, b = a.get(key), b.get(key)
            if a is None or b is None:
                raise ValueError("Required source counter is unknown: " + ".".join(path))
        value = number(b, str(path)) - number(a, str(path))
        if value < 0:
            raise ValueError("Source counter regressed: " + ".".join(path))
        return value
    drops = delta(("quality", "droppedVideoFrames"))
    total = delta(("quality", "totalVideoFrames"))
    submitted = delta(("lastFrame", "presentedFrames"))
    callbacks = delta(("callbackCount",))
    rate = (number(end.get("currentTime"), "end media clock") - number(begin.get("currentTime"), "begin media clock")) / seconds
    drop_fraction = drops / (seconds * source_fps)
    submitted_fps = submitted / seconds
    processing = [number(f["processingDuration"], "processing duration") * 1000 for f in selected if f.get("processingDuration") is not None]
    if any(v < 0 for v in processing):
        raise ValueError("Negative frame processing duration")
    compositor_gaps = [b["presentationTime"] - a["presentationTime"] for a, b in zip(selected, selected[1:])]
    callback_gaps = [b["nowMs"] - a["nowMs"] for a, b in zip(selected, selected[1:])]
    counter_skips = [b["presentedFrames"] - a["presentedFrames"] - 1 for a, b in zip(selected, selected[1:])]
    clock_drop_pass = .98 <= rate <= 1.02 and drop_fraction <= .01
    # A successfully closed raw browser transport does not prove its owner
    # remained alive throughout the client measurement. Preserve the diagnostic
    # counters, but reject a failed/ended controller or a shorter source scope.
    lifecycle_pass = all(endpoint.get("ended") is False
                         and not endpoint.get("error")
                         and endpoint.get("phase") == "playing"
                         for endpoint in (before, after))
    measurement_seconds = None
    measurement_covered = None
    if "measurementSeconds" in case:
        measurement_seconds = number(case["measurementSeconds"], "client measurement duration")
        if measurement_seconds <= 0:
            raise ValueError("Client measurement duration must be positive")
        measurement_covered = seconds >= measurement_seconds
    cpu_percent = None
    if before.get("cpu_seconds") is not None and after.get("cpu_seconds") is not None:
        cpu_span = number(after.get("cpu_sample_elapsed_s"), "end CPU sample clock") - number(before.get("cpu_sample_elapsed_s"), "begin CPU sample clock")
        cpu_delta = number(after["cpu_seconds"], "end job CPU") - number(before["cpu_seconds"], "begin job CPU")
        if cpu_span <= 0 or cpu_delta < 0:
            raise ValueError("Job CPU sample interval/counter is invalid")
        cpu_percent = 100 * cpu_delta / cpu_span
    return {"scope": {"seconds": seconds, "beginMs": left, "endMs": right, "sourceFps": source_fps},
            "rawTransportComplete": True, "rawCallbackBatchesComplete": True, "packets": len(packets),
            "allCallbacks": len(frames), "windowCallbacks": len(selected), "clockRate": rate,
            "droppedFramesDelta": drops, "droppedFraction": drop_fraction, "qualityTotalFramesDelta": total,
            "compositorSubmittedFramesDelta": submitted, "compositorSubmittedFps": submitted_fps,
            "callbackCountDelta": callbacks, "callbackFps": callbacks / seconds,
            "missedCallbacksInsideWindow": sum(counter_skips), "compositorCounterStepHistogram": dict(Counter(s + 1 for s in counter_skips)),
            "callbackGapMs": distribution(callback_gaps), "observedCompositionSubmissionGapMs": distribution(compositor_gaps),
            "decodeReadyDurationMs": distribution(processing), "unknownProcessingDurationCount": len(selected) - len(processing),
            "callbackMinusExpectedDisplayMs": distribution([f["nowMs"] - f["expectedDisplayTime"] for f in selected]),
            "originalClockDropGatePass": clock_drop_pass,
            "sourceControllerHealthyAtEndpoints": lifecycle_pass,
            "clientMeasurementSeconds": measurement_seconds,
            "sourceScopeCoversClientMeasurement": measurement_covered,
            "sourceComparisonEligible": lifecycle_pass and measurement_covered is not False
                                        and clock_drop_pass and source_fps * .99 <= submitted_fps <= source_fps * 1.01,
            "remotePerformanceAccepted": False,
            "processCpuPercentOfOneCore": cpu_percent,
            "limits": ["Composition counters are not physical scanout, optical latency or an end-to-end remote performance acceptance",
                       "processingDuration measures encoded-packet to decoded-frame-ready including possible conversion/staging, not a decoder API call",
                       "Processing durations exist only for reported presented frames; missing/dropped frames cannot be cleared by fast surviving-frame samples",
                       "Composition gaps sampled through callbacks may combine submissions when callbacks are missed; counter skips are reported separately",
                       "Missing process CPU and optional processing durations remain unknown, never zero",
                       "Source counters covering only part of a client measurement cannot validate that entire measurement; absent client duration stays unknown",
                       "Before/after browser clocks are matched to raw source packets; no sub-millisecond browser-to-host QPC alignment is claimed"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("case", type=Path)
    parser.add_argument("raw", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--source-fps", type=int, default=60)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError("Immutable analysis output already exists")
    case = json.loads(args.case.read_text(encoding="utf-8-sig"))
    records = [json.loads(s) for s in args.raw.read_text(encoding="utf-8-sig").splitlines()]
    result = analyze(case, records, args.source_fps)
    result["rawSha256"] = hashlib.sha256(args.raw.read_bytes()).hexdigest().upper()
    result["case"] = str(args.case)
    result["raw"] = str(args.raw)
    args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({k: result[k] for k in ("rawTransportComplete", "scope", "sourceComparisonEligible", "compositorSubmittedFps", "callbackFps", "droppedFraction", "observedCompositionSubmissionGapMs", "decodeReadyDurationMs", "unknownProcessingDurationCount")}, indent=2))


if __name__ == "__main__":
    main()
