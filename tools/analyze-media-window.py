"""Compare one measured video window by frame identity, using separate host/client clocks.

This never repairs a whole-trace anomaly or estimates absolute network latency.
The scope is explicit: client assembled frame numbers in the recorded interval,
and the unambiguous host submissions spanning those numbers in the same session.
"""
import argparse
import collections
import importlib.util
import json
import math
from pathlib import Path


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


host = module("media_host", "analyze-host-frames.py")
client = module("media_client", "analyze-statistics.py")


def cadence(stamps):
    # Separate frames can share a microsecond; do not silently drop either one.
    stamps = sorted(stamps)
    gaps = [(b - a) / 1000 for a, b in zip(stamps, stamps[1:])]
    return {"count": len(stamps), "fps": (len(stamps) - 1) * 1e6 / (stamps[-1] - stamps[0])
            if len(stamps) > 1 and stamps[-1] > stamps[0] else None,
            "gap_ms": host.distribution(gaps), "over_50ms": sum(v > 50 for v in gaps),
            "over_100ms": sum(v > 100 for v in gaps)}


def analyze(case, events, host_rows, parse_issues=None):
    lo, hi = case["measurementStartUs"], case["measurementEndUs"]
    if any(type(v) not in (int, float) or not math.isfinite(v) for v in (lo, hi)) or hi <= lo:
        raise ValueError("Invalid client measurement interval")
    global_host, _, _, _ = host.analyze(host_rows, parse_issues)
    direct_receive=client.direct_frame_clock(events)
    # Frame-local startup/shutdown defects may be outside this named window.
    # File integrity and identity defects cannot be hidden by selecting a subset.
    integrity_names = {"mixed_trace_identity", "invalid_event_fields", "unknown_event",
                       "missing_frame_identity", "missing_packet_identity", "unexpected_packet_identity",
                       "missing_footer", "duplicate_footer", "events_after_footer", "invalid_footer",
                       "footer_precedes_events", "recorder_lost_events", "recorder_size_limit",
                       "recorder_write_failed", "conflicting_capture_identity", "missing_capture_identity",
                       "packet_reused_across_frames", *(parse_issues or {})}
    integrity_names.add("invalid_capture_work")
    integrity_names.update(("invalid_encoder_work", "encoder_work_overflow"))
    integrity = {k: v for k, v in global_host["issues"].items() if k in integrity_names}
    if any(k in integrity for k in ("mixed_trace_identity", "invalid_event_fields", "unknown_event",
                                   "missing_frame_identity", "missing_packet_identity", "unexpected_packet_identity")):
        raise ValueError("Host event identity/schema is invalid: " + json.dumps(integrity, sort_keys=True))
    by_frame = {}
    for row in events:
        if row.get("frame", -1) >= 1:
            frame = by_frame.setdefault(int(row["frame"]), {"events": {}, "fields": {}})
            frame["events"].setdefault(row["event"], row["ts_us"])
            if row["event"] == "frame_assembled":
                frame["fields"] = row.get("fields", {})
    received = {n for n, f in by_frame.items() if lo <= f["events"].get("frame_assembled", float("inf")) <= hi}
    if not received:
        raise ValueError("No assembled client frames in the requested window")
    first, last = min(received), max(received)
    submissions = collections.defaultdict(set)
    for row in host_rows:
        if row.get("event") == "encode_begin" and first <= row["frame"] <= last:
            submissions[row["frame"]].add(row["generation"])
    ambiguous = [n for n, generations in submissions.items() if len(generations) != 1]
    missing = sorted(received - set(submissions))
    if ambiguous or missing:
        raise ValueError("Frame identity cannot be aligned unambiguously: ambiguous=" + str(ambiguous[:20]) + "; unmatched=" + str(missing[:20]))
    keys = {(next(iter(generations)), n) for n, generations in submissions.items()}
    selected = [r for r in host_rows if (r.get("generation"), r.get("frame")) in keys]
    sources = {r["source"] for r in selected if r.get("source")}
    captures = [r for r in host_rows if r.get("event") == "capture_ready" and r["source"] in sources]
    # Retain the original header/footer and loss flags. This is a named subset,
    # never a replacement raw file and never a claim that its whole file is fixed.
    subset = [host_rows[0], *captures, *selected, *[r for r in host_rows if r.get("event") == "trace_end"]]
    scoped, frames, packets, _ = host.analyze(subset, parse_issues)
    seconds = (hi - lo) / 1e6
    packet_sets = collections.defaultdict(list)
    for packet in packets:
        packet_sets[(packet["generation"], packet["frame"])].append(packet)
    client_cadence = {}
    for name in ("packet_first", "frame_assembled", "decode_output", "render_submit", "render_return", "surface_render_callback"):
        client_cadence[name] = cadence(f["events"][name] for f in by_frame.values()
                                         if name in f["events"] and lo <= f["events"][name] <= hi)
    metrics = {}
    for name, (begin, end) in client.PAIRS.items():
        values = [(f["events"][end] - f["events"][begin]) / 1000 for n, f in by_frame.items()
                  if n in received and begin in f["events"] and end in f["events"] and f["events"][end] >= f["events"][begin]]
        metrics[name] = host.distribution(values)
    start_host = min(r["at_us"] for r in selected if r["event"] == "encode_begin")
    end_host = max((r["at_us"] for r in selected if r["event"] == "send_end"), default=start_host)
    captures_inside = [r for r in host_rows if r.get("event") == "capture_ready" and start_host <= r["at_us"] <= end_host]
    sender_cadence = {}
    for key, field in (("output", "output_us"), ("send_begin", "send_begin_us"), ("send_return", "send_end_us")):
        boundary = max if key == "send_return" else min
        sender_cadence[key] = cadence(boundary(p[field] for p in group if field in p) for group in packet_sets.values() if any(field in p for p in group))
    drops = collections.Counter(r.get("fields", {}).get("reason", "unknown") for r in events
                                if r.get("event") == "frame_drop" and lo <= r["ts_us"] <= hi)
    host_encoded = sum(p.get("encoded_bytes", 0) for p in packets)
    host_payload = sum(p.get("udp_payload_bytes", 0) for p in packets)
    source_before, source_after = case.get("sourceBefore", {}), case.get("sourceAfter", {})
    source_seconds = source_after.get("elapsed_s", 0) - source_before.get("elapsed_s", 0)
    source = {"physical_paint_measured": False, "file_fps": source_before.get("source_file_fps"),
              "client_window_seconds": seconds, "controller_healthy_at_endpoints": None,
              "scope_covers_client_window": None}
    if source_before and source_after:
        source["controller_healthy_at_endpoints"] = all(
            endpoint.get("phase") == "playing" and endpoint.get("ended") is False and not endpoint.get("error")
            for endpoint in (source_before, source_after))
    if source_seconds > 0:
        source["scope_covers_client_window"] = source_seconds >= seconds
    if source_seconds > 0 and source_before.get("media_time_s") is not None and source_after.get("media_time_s") is not None:
        source.update(reading_interval_s=source_seconds,
                      playback_clock_rate=(source_after["media_time_s"] - source_before["media_time_s"]) / source_seconds,
                      player_dropped_frames_delta=source_after["player_dropped_frames"] - source_before["player_dropped_frames"])
    # File completeness is independent of whether the source was a valid load.
    # Recompute this from the observations, never trust a caller's PASS/eligible flag.
    source["comparison_eligible"] = None
    if (source.get("reading_interval_s", 0) > 0 and type(source.get("file_fps")) in (int, float) and
            source["file_fps"] > 0 and "player_dropped_frames_delta" in source):
        dropped = source["player_dropped_frames_delta"]
        fraction = dropped / (source["reading_interval_s"] * source["file_fps"])
        source["player_dropped_fraction"] = fraction
        source["comparison_eligible"] = (source["controller_healthy_at_endpoints"] is True and
                                         source["scope_covers_client_window"] is True and
                                         .98 <= source["playback_clock_rate"] <= 1.02 and
                                         dropped >= 0 and fraction <= .01)
    source["comparison_criteria"] = "Playing controller endpoints, actual source scope covering the full client window, playback clock within2% of real time and player drops<=1%; neither proves unique pictures or physical paint"
    return {"schema": "rhremote.media-window/v1", "client_id": global_host["client_id"], "trace_id": global_host["trace_id"],
            "scope": {"client_start_us": lo, "client_end_us": hi, "seconds": seconds, "first_frame": first, "last_frame": last,
                      "selection": "Client assembly timestamps select ordinal boundaries; host submissions use that inclusive frame-number range in the same trace. Source captures for metrics are referenced inputs.",
                      "host_clock_not_subtracted_from_client": True},
            "whole_host_trace_complete": global_host["complete"], "whole_host_trace_issues": global_host["issues"],
            "window_host_complete": scoped["complete"] and not integrity, "window_host_issues": scoped["issues"],
            "whole_file_integrity_issues": integrity,
            "host_submissions": scoped["frame_count"], "host_packets": scoped["packet_count"], "client_assembled_frames": len(received),
            "host_frame_numbers_absent_from_client_window": sorted(set(submissions) - received),
            "host_metrics_ms": scoped["metrics_ms"], "host_sender_cadence": sender_cadence,
            "host_capture_callbacks_inside_send_span": cadence(r["at_us"] for r in captures_inside),
            "host_fresh_submissions": scoped["fresh_submissions"], "host_repeat_submissions": scoped["repeat_or_priming_submissions"],
            "client_cadence": client_cadence, "client_metrics_ms": metrics, "client_frame_drops": dict(drops),
            "host_encoded_mbps": host_encoded * 8 / seconds / 1e6, "host_udp_payload_mbps": host_payload * 8 / seconds / 1e6,
            "udp_payload_to_encoded_ratio": host_payload / host_encoded if host_encoded else None,
            "client_encoded_payload_mbps": sum(by_frame[n]["fields"].get("bytes", 0) for n in received) * 8 / seconds / 1e6,
            "source": source, "measurement": {"absolute_network_latency_measured": False, "physical_scanout_measured": False,
            "unique_picture_fps_measured": False, "send_endpoint": "send_call_return",
            "receive_assembly_high_resolution_proven": direct_receive,
            "receive_assembly_timing": "Direct recorder-domain microsecond timestamps at UDP receive return and decode-unit readiness. These are software call boundaries, not NIC receipt or scanout." if direct_receive else "Legacy native packet_first/frame_assembled timestamps are millisecond-clock backprojections, or the direct clock contract is incomplete. Reassembly and pre-decoder queue metrics retain those estimates; they are not exact receipt or waiting spans. Direct decode/render call events and host capture work are separate measurements.",
            "byte_rates": "Both payload rates use the client interval and aligned ordinal range, not link throughput. UDP payload includes FEC and packetization; no IP/TCP/TLS/iroh wire overhead is counted.",
            "missing_frames": "An absent frame number alone does not locate loss or prove a particular network queue.",
            "scope": "A complete selected window does not make a whole trace complete; unselected startup/shutdown anomalies remain reported."}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("case", type=Path)
    parser.add_argument("--host", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    case = json.loads(args.case.read_text(encoding="utf-8-sig"))
    if case["result"] != "PASS":
        raise ValueError("Media capture failed; it cannot be presented as a completed window")
    root = Path(case["trace"])
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8-sig"))
    if manifest.get("schema") != "rhremote.statistics/v1":
        raise ValueError("Invalid client run")
    events, health = [], []
    if not manifest.get("files"):
        raise ValueError("Client run has no recorder files")
    for name in manifest["files"]:
        if Path(name).name != name or not name.endswith(".jsonl"):
            raise ValueError("Invalid client file name")
        path = root / name
        host.no_symlink(path.absolute())
        rows, state = client.read_client_file(path)
        events += rows
        health.append(state)
    if client.input_file_warnings(health):
        raise ValueError("Client recorder is incomplete; retain original data")
    host.no_symlink(args.host.absolute())
    rows, parse_issues, identity = host.load(args.host)
    if not rows or rows[0].get("client_id") != root.name:
        raise ValueError("Host file belongs to another client run")
    result = analyze(case, events, rows, parse_issues)
    result["host_input"] = identity
    result["case_file"] = str(args.case.resolve())
    destination = args.output or args.case.with_suffix(".window.json")
    host.atomic_write(destination, lambda stream: json.dump(result, stream, ensure_ascii=False, indent=2))
    print(json.dumps({"output": str(destination), "window_host_complete": result["window_host_complete"],
                      "whole_host_trace_complete": result["whole_host_trace_complete"],
                      "source_comparison_eligible": result["source"]["comparison_eligible"],
                      "client_render": result["client_cadence"]["render_return"],
                      "host_send": result["host_sender_cadence"]["send_return"],
                      "host_udp_payload_mbps": result["host_udp_payload_mbps"]}, indent=2))


if __name__ == "__main__":
    main()
