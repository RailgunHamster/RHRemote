"""Analyze one host-owned frame trace without subtracting clocks on different PCs.

The timeline uses the host steady clock only. Successful analysis saves all frame
and packet metadata before removing an unneeded, closed raw file. Partial traces
are retained. Use --keep-raw while the original is needed for an investigation.
"""
import argparse
import collections
import csv
import hashlib
import json
import math
import os
from pathlib import Path
import re
import tempfile


SCHEMA = "rhremote.host-frame/v1"
CAPTURE_WORK_SCHEMA = "rhremote.capture-work/v1"
CAPTURE_STEPS = ("acquire", "prepare", "image_pool", "map", "image_setup", "cpu_copy", "unmap", "cursor")
STAGES = {"capture_ready", "convert_begin", "convert_end", "encode_begin",
          "encode_end", "encode_output", "input_abandoned", "metadata_evicted", "metadata_missing",
          "send_begin", "send_end", "send_error"}
INTEGER_FIELDS = {"at_us": 0, "frame": -1, "generation": 0, "source": 0,
                  "source_present_us": None, "capture_ready_us": 0, "packet": 0,
                  "bytes": 0, "packets": 0, "fec_us": 0, "pacing_us": 0,
                  "send_us": 0, "rtp_timestamp": 0, "result": None}
BOOLEAN_FIELDS = ("source_time_known", "rtp_known", "wire_duplicate", "fresh", "metadata_known")
FRAME_COLUMNS = ("generation", "frame", "source", "fresh", "metadata_known",
                 "outcome", "abandon_reason", "abandoned_us",
                 "encode_begin_us", "encode_end_us", "encode_result", "encode_call_ms",
                 "conversion_calls", "conversion_errors", "packet_count", "sent_packets",
                 "send_errors", "first_output_us", "last_send_return_us")
PACKET_COLUMNS = ("packet", "generation", "frame", "source", "fresh", "metadata_known",
                  "output_us", "send_begin_us", "send_end_us", "result", "encoded_bytes",
                  "udp_payload_bytes", "datagrams", "rtp_known", "rtp_timestamp", "wire_duplicate",
                  "submit_to_output_ms", "encode_queue_ms", "send_span_ms", "fec_ms", "pacing_ms",
                  "send_call_ms", "capture_to_send_call_return_ms", "source_to_send_call_return_ms")
CAPTURE_COLUMNS = ("source", "source_time_known", "source_present_us", "capture_ready_us", "work_backend",
                   "work_begin_us", "work_end_us", "snapshot_ms", "callback_delivery_ms", "unattributed_ms",
                   *(field for step in CAPTURE_STEPS for field in (step + "_begin_us", step + "_end_us", step + "_ms")))


def tag(value):
    return (isinstance(value, str) and value not in (".", "..") and
            re.fullmatch(r"[A-Za-z0-9._-]{1,96}", value) is not None)


def integer(value, minimum=None):
    return type(value) is int and (minimum is None or value >= minimum)


def capture_work(row, declared=False):
    """Validate measured call boundaries; absent work is never interpreted as zero work."""
    if "capture_work" not in row:
        if declared:
            raise ValueError("Capture profile declared by the recorder is missing")
        return None
    work = row["capture_work"]
    if work is None:
        return None
    if (not isinstance(work, dict) or set(work) != {"backend", "begin_us", "end_us", "steps"} or
            work["backend"] != "dxgi_ram" or not integer(work["begin_us"], 0) or
            not integer(work["end_us"], work["begin_us"]) or
            not integer(row.get("capture_ready_us"), work["end_us"]) or
            not isinstance(work["steps"], dict) or set(work["steps"]) != set(CAPTURE_STEPS)):
        raise ValueError("Invalid capture work identity or snapshot boundaries")
    previous = work["begin_us"]
    for name in CAPTURE_STEPS:
        step = work["steps"][name]
        if step is None:
            if name in ("acquire", "prepare", "image_pool"):
                raise ValueError("Required capture work span is missing")
            continue
        if (not isinstance(step, dict) or set(step) != {"begin_us", "end_us"} or
                not integer(step["begin_us"], previous) or
                not integer(step["end_us"], step["begin_us"]) or step["end_us"] > work["end_us"]):
            raise ValueError("Invalid, overlapping or out-of-order capture work span")
        previous = step["end_us"]
    # CPU image creation/copy/unmap follow a real Map; dummy images leave all four unknown.
    mapped = work["steps"]["map"] is not None
    if any((work["steps"][name] is not None) != mapped for name in ("image_setup", "cpu_copy", "unmap")):
        raise ValueError("Capture readback spans do not share the actual Map lifetime")
    return work


def capture_table(rows):
    """Preserve individual valid capture samples when the analyzed raw file is later removed."""
    declared = rows[0].get("capture_work_schema") == CAPTURE_WORK_SCHEMA
    result = []
    for row in rows:
        if row.get("event") != "capture_ready":
            continue
        output = {k: row.get(k) for k in ("source", "source_time_known", "source_present_us", "capture_ready_us")}
        try:
            work = capture_work(row, declared)
        except ValueError:
            continue  # analyze() reports the defect and retains the raw file.
        if work is not None:
            output.update(work_backend=work["backend"], work_begin_us=work["begin_us"], work_end_us=work["end_us"],
                          snapshot_ms=(work["end_us"] - work["begin_us"]) / 1000,
                          callback_delivery_ms=(row["capture_ready_us"] - work["end_us"]) / 1000)
            measured = 0
            for name, step in work["steps"].items():
                if step is not None:
                    output.update({name + "_begin_us": step["begin_us"], name + "_end_us": step["end_us"],
                                   name + "_ms": (step["end_us"] - step["begin_us"]) / 1000})
                    measured += step["end_us"] - step["begin_us"]
            output["unattributed_ms"] = (work["end_us"] - work["begin_us"] - measured) / 1000
        result.append(output)
    return result


def distribution(values):
    values = sorted(values)
    if not values:
        return {"count": 0, "mean": None, "p50": None, "p95": None, "p99": None, "max": None}
    def percentile(p):
        return round(values[max(0, math.ceil(p * len(values)) - 1)], 6)
    return {"count": len(values), "mean": round(sum(values) / len(values), 6),
            "p50": percentile(.5), "p95": percentile(.95), "p99": percentile(.99),
            "max": round(values[-1], 6)}


def load(path, max_bytes=128 * 1024 * 1024, max_events=220000, max_line=16384):
    """Bound memory use and preserve malformed/truncated-input evidence."""
    if path.stat().st_size > max_bytes:
        raise ValueError("Host trace exceeds the analysis file limit")
    events, issues, digest = [], collections.Counter(), hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for number in range(max_events + 3):
            line = source.readline(max_line + 1)
            if not line:
                break
            size += len(line)
            if size > max_bytes or len(line) > max_line:
                raise ValueError("Host trace exceeds the analysis file/line limit")
            if number >= max_events + 2:
                raise ValueError("Host trace exceeds the analysis event limit")
            digest.update(line)
            if not line.endswith(b"\n"):
                issues["unterminated_line"] += 1
            try:
                row = json.loads(line.decode("utf-8-sig" if number == 0 else "utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                issues["malformed_line"] += 1
                continue
            if not isinstance(row, dict):
                issues["non_object_line"] += 1
                continue
            events.append(row)
    return events, issues, {"bytes": size, "sha256": digest.hexdigest()}


def analyze(rows, parse_issues=None):
    """Associate by encoder generation and PTS, and independently by packet ID."""
    issues = collections.Counter(parse_issues or {})
    if not rows or rows[0].get("event") != "trace_begin":
        raise ValueError("Host trace must start with trace_begin")
    header = rows[0]
    if (header.get("schema") != SCHEMA or not tag(header.get("trace_id")) or
            not tag(header.get("client_id"))):
        raise ValueError("Unsupported host trace schema or identity")
    if header.get("capture_work_schema", CAPTURE_WORK_SCHEMA) != CAPTURE_WORK_SCHEMA:
        raise ValueError("Unsupported capture work schema")
    for field in ("steady_origin_us", "unix_origin_us"):
        if not integer(header.get(field), 0):
            raise ValueError("Invalid host clock origin")
    if (header.get("clocks_synchronized") is not False or
            header.get("physical_scanout_measured") is not False or
            header.get("send_endpoint") != "send_call_return"):
        raise ValueError("Unsupported host measurement contract")

    events, footer = [], None
    for row in rows[1:]:
        name = row.get("event")
        if name == "trace_end":
            if footer is not None:
                issues["duplicate_footer"] += 1
            footer = row
            continue
        if footer is not None:
            issues["events_after_footer"] += 1
        if name not in STAGES:
            issues["unknown_event"] += 1
            continue
        if (row.get("trace_id") != header["trace_id"] or row.get("client_id") != header["client_id"]):
            issues["mixed_trace_identity"] += 1
            continue
        if (any(not integer(row.get(key), bound) for key, bound in INTEGER_FIELDS.items()) or
                any(type(row.get(key)) is not bool for key in BOOLEAN_FIELDS) or
                row["rtp_timestamp"] > 0xffffffff):
            issues["invalid_event_fields"] += 1
            continue
        if name not in ("capture_ready", "metadata_missing") and (row["generation"] == 0 or row["frame"] < 1):
            issues["missing_frame_identity"] += 1
        if name.startswith("send_") or name == "encode_output":
            if row["packet"] < 1:
                issues["missing_packet_identity"] += 1
        elif row["packet"]:
            issues["unexpected_packet_identity"] += 1
        events.append(row)
    if footer is None:
        issues["missing_footer"] += 1
    elif (not integer(footer.get("at_us"), 0) or not integer(footer.get("lost_events"), 0) or
          type(footer.get("size_limit_reached")) is not bool or type(footer.get("write_failed")) is not bool):
        issues["invalid_footer"] += 1
    else:
        if footer["lost_events"]:
            issues["recorder_lost_events"] += footer["lost_events"]
        if footer["size_limit_reached"]:
            issues["recorder_size_limit"] += 1
        if footer["write_failed"]:
            issues["recorder_write_failed"] += 1
        if events and footer["at_us"] < max(e["at_us"] for e in events):
            issues["footer_precedes_events"] += 1

    events.sort(key=lambda row: row["at_us"])
    captures, frames, packets = {}, {}, {}
    counts = collections.Counter(e["event"] for e in events)
    metrics = collections.defaultdict(list)
    spans = []
    capture_work_counts = collections.Counter()
    for row in events:
        name = row["event"]
        if name == "capture_ready":
            if row["source"] == 0:
                issues["missing_capture_identity"] += 1
            signature = (row["capture_ready_us"], row["source_time_known"], row["source_present_us"])
            if row["source"] in captures and captures[row["source"]] != signature:
                issues["conflicting_capture_identity"] += 1
            captures[row["source"]] = signature
            if row["source_time_known"]:
                duration(metrics, issues, "source_to_capture_ms", row["source_present_us"], row["capture_ready_us"])
            try:
                work = capture_work(row, "capture_work_schema" in header)
            except ValueError:
                issues["invalid_capture_work"] += 1
                continue
            if work is None:
                capture_work_counts["unknown"] += 1
            else:
                capture_work_counts["dxgi_ram"] += 1
                duration(metrics, issues, "capture_snapshot_ms", work["begin_us"], work["end_us"])
                duration(metrics, issues, "capture_callback_delivery_ms", work["end_us"], row["capture_ready_us"])
                spans.append(("capture_snapshot", dict(row, at_us=work["begin_us"]), dict(row, at_us=work["end_us"])))
                measured = 0
                for step, sample in work["steps"].items():
                    if sample is not None:
                        duration(metrics, issues, "capture_" + step + "_ms", sample["begin_us"], sample["end_us"])
                        spans.append(("capture_" + step, dict(row, at_us=sample["begin_us"]), dict(row, at_us=sample["end_us"])))
                        measured += sample["end_us"] - sample["begin_us"]
                metrics["capture_unattributed_ms"].append((work["end_us"] - work["begin_us"] - measured) / 1000)
            continue
        if name in ("metadata_missing", "metadata_evicted"):
            issues[name] += 1
            continue
        key = row["generation"], row["frame"]
        frame = frames.setdefault(key, {"generation": key[0], "frame": key[1], "rows": collections.defaultdict(list)})
        frame["rows"][name].append(row)
        if row["packet"] and (name.startswith("send_") or name == "encode_output"):
            packet = packets.setdefault(row["packet"], {"rows": {}, "key": key})
            if packet["key"] != key:
                issues["packet_reused_across_frames"] += 1
            if name in packet["rows"]:
                issues["duplicate_packet_stage"] += 1
            packet["rows"][name] = row
    if not frames:
        issues["no_encoded_frames"] += 1

    frame_table = []
    for key, frame in sorted(frames.items()):
        groups = frame["rows"]
        output = {"generation": key[0], "frame": key[1], "conversion_calls": 0, "conversion_errors": 0,
                  "packet_count": len(groups["encode_output"]), "sent_packets": len(groups["send_end"]),
                  "send_errors": len(groups["send_error"])}
        begins, ends = groups["encode_begin"], groups["encode_end"]
        abandoned = groups["input_abandoned"]
        abandonment = abandoned[0] if abandoned else None
        if abandonment:
            encoded_stages = ("encode_begin", "encode_end", "encode_output", "send_begin", "send_end", "send_error")
            if (len(abandoned) != 1 or any(groups[n] for n in encoded_stages) or
                    abandonment["result"] not in (1, 2) or abandonment["packet"] or not abandonment["metadata_known"]):
                issues["invalid_abandoned_input"] += 1
            output.update(outcome="abandoned_before_submit", abandoned_us=abandonment["at_us"],
                          abandon_reason={1: "superseded_conversion", 2: "encoder_released_before_submit"}.get(abandonment["result"], "unknown"))
        else:
            output["outcome"] = "submitted" if begins else "unresolved"
        if (begins or ends or not abandoned) and (len(begins) != 1 or len(ends) != 1):
            issues["missing_or_duplicate_encode_boundary"] += 1
        begin, end = (begins[0] if begins else None), (ends[0] if ends else None)
        context = begin or abandonment or (groups["encode_output"][0] if groups["encode_output"] else None)
        if context:
            output.update({key: context[key] for key in ("source", "fresh", "metadata_known")})
        if begin:
            output["encode_begin_us"] = begin["at_us"]
            if begin["fresh"] and begin["source"] not in captures:
                issues["capture_record_missing"] += 1
            if begin["fresh"] and not begin["source"]:
                issues["fresh_input_without_source"] += 1
        if end:
            output.update(encode_end_us=end["at_us"], encode_result=end["result"])
            if end["result"]:
                issues["encode_error"] += 1
            elif not groups["encode_output"]:
                issues["submission_without_output"] += 1
        if begin and end:
            output["encode_call_ms"] = duration(metrics, issues, "encode_call_ms", begin["at_us"], end["at_us"])
            span(spans, "encode_call", begin, end)
        pending = collections.defaultdict(list)
        successful_conversions = []
        for conversion in sorted(groups["convert_begin"] + groups["convert_end"], key=lambda r: r["at_us"]):
            identity = conversion["source"], conversion["fresh"]
            if conversion["event"] == "convert_begin":
                pending[identity].append(conversion)
            elif pending[identity]:
                start = pending[identity].pop(0)
                output["conversion_calls"] += 1
                duration(metrics, issues, "convert_ms", start["at_us"], conversion["at_us"])
                span(spans, "convert", start, conversion)
                if start["fresh"] and start["source"]:
                    duration(metrics, issues, "capture_to_convert_ms", start["capture_ready_us"], start["at_us"])
                if conversion["result"]:
                    output["conversion_errors"] += 1
                    issues["convert_error"] += 1
                else:
                    successful_conversions.append(conversion)
            else:
                issues["convert_end_without_begin"] += 1
        issues["convert_begin_without_end"] += sum(len(p) for p in pending.values())
        if abandonment:
            identity_fields = ("source", "fresh", "metadata_known", "source_time_known", "source_present_us", "capture_ready_us")
            if not any(c["at_us"] <= abandonment["at_us"] and all(c[k] == abandonment[k] for k in identity_fields)
                       for c in successful_conversions):
                issues["abandoned_without_matching_conversion"] += 1
        if begin and begin["fresh"] and not any(r["source"] == begin["source"] and r["fresh"]
                                                 for r in groups["convert_begin"]):
            issues["fresh_input_without_conversion"] += 1
        if groups["encode_output"]:
            output["first_output_us"] = min(r["at_us"] for r in groups["encode_output"])
        if groups["send_end"]:
            output["last_send_return_us"] = max(r["at_us"] for r in groups["send_end"])
        frame["begin"] = begin
        frame_table.append(output)

    packet_table = []
    for packet_id, packet in sorted(packets.items()):
        groups, key = packet["rows"], packet["key"]
        encoded, send, end, error = (groups.get(n) for n in ("encode_output", "send_begin", "send_end", "send_error"))
        context = encoded or send or end or error
        output = {"packet": packet_id, **{k: context[k] for k in ("generation", "frame", "source", "fresh", "metadata_known")}}
        if not encoded or not send or (bool(end) + bool(error)) != 1:
            issues["missing_packet_stage"] += 1
        if not context["metadata_known"]:
            issues["unknown_packet_metadata"] += 1
        for r in groups.values():
            if any(r[k] != context[k] for k in ("source", "fresh", "metadata_known", "source_time_known", "source_present_us", "capture_ready_us")):
                issues["packet_context_mismatch"] += 1
        begin = frames[key]["begin"]
        if encoded:
            output.update(output_us=encoded["at_us"], encoded_bytes=encoded["bytes"])
            if begin and encoded["metadata_known"]:
                if any(begin[k] != encoded[k] for k in ("source", "fresh", "source_time_known", "source_present_us", "capture_ready_us")):
                    issues["submission_context_mismatch"] += 1
                else:
                    output["submit_to_output_ms"] = duration(metrics, issues, "submit_to_output_ms", begin["at_us"], encoded["at_us"])
        if send:
            output["send_begin_us"] = send["at_us"]
            if encoded:
                output["encode_queue_ms"] = duration(metrics, issues, "encode_queue_ms", encoded["at_us"], send["at_us"])
                span(spans, "encode_queue", encoded, send)
        terminal = end or error
        if terminal:
            output.update(send_end_us=terminal["at_us"], result=terminal["result"],
                          udp_payload_bytes=terminal["bytes"], datagrams=terminal["packets"],
                          rtp_known=terminal["rtp_known"], rtp_timestamp=terminal["rtp_timestamp"],
                          wire_duplicate=terminal["wire_duplicate"])
            if error or terminal["result"]:
                issues["send_error"] += 1
            if send:
                output["send_span_ms"] = duration(metrics, issues, "send_span_ms", send["at_us"], terminal["at_us"])
                span(spans, "send", send, terminal)
            for field, metric in (("fec_us", "fec_ms"), ("pacing_us", "pacing_ms"), ("send_us", "send_call_ms")):
                output[metric] = terminal[field] / 1000
                metrics[metric].append(output[metric])
            # Failed sends describe a partial operation, not a successful endpoint.
            if end and not end["result"] and context["metadata_known"] and context["source"]:
                output["capture_to_send_call_return_ms"] = duration(metrics, issues, "capture_to_send_call_return_ms", context["capture_ready_us"], end["at_us"])
                if context["source_time_known"]:
                    output["source_to_send_call_return_ms"] = duration(metrics, issues, "source_to_send_call_return_ms", context["source_present_us"], end["at_us"])
        packet_table.append(output)

    cadence = {}
    for name in ("capture_ready", "encode_begin", "encode_output", "send_end"):
        stamps = sorted({e["at_us"] for e in events if e["event"] == name})
        gaps = [(b - a) / 1000 for a, b in zip(stamps, stamps[1:])]
        cadence[name] = {"events": counts[name], "distinct_timestamps": len(stamps),
                         "interval_ms": distribution(gaps), "over_50ms": sum(g > 50 for g in gaps)}
    issues = {name: count for name, count in sorted(issues.items()) if count}
    summary = {"schema": "rhremote.host-frame.analysis/v1", "trace_id": header["trace_id"],
               "client_id": header["client_id"], "complete": not issues, "issues": issues,
               "event_count": len(events), "stage_counts": dict(counts),
               "work_item_count": len(frames), "frame_count": sum(bool(f["rows"]["encode_begin"]) for f in frames.values()),
               "abandoned_input_count": sum(bool(f["rows"]["input_abandoned"]) for f in frames.values()),
               "packet_count": len(packets), "captures_delivered": len(captures),
               "capture_work": {"schema": header.get("capture_work_schema"), "samples": dict(capture_work_counts),
                                "unknown_is_zero": False},
               "fresh_submissions": sum(bool(f.get("fresh")) and f["outcome"] == "submitted" for f in frame_table),
               "repeat_or_priming_submissions": sum(f.get("fresh") is False and f["outcome"] == "submitted" for f in frame_table),
               "wire_duplicate_packets": sum(p.get("wire_duplicate") is True for p in packet_table),
               "metrics_ms": {k: distribution(v) for k, v in sorted(metrics.items())}, "cadence": cadence,
               "footer": footer, "measurement": {"clock": "host_steady", "clocks_synchronized": False,
               "physical_scanout_measured": False, "endpoint": "send_call_return",
               "network_latency_measured": False, "unique_content_fps_measured": False,
               "capture": "Source timestamp is platform desktop presentation, not application drawing or scanout.",
               "capture_work": "Opt-in CPU call spans for delivered DXGI RAM images only. Prepare ends at copy API return, not GPU completion. Unsupported/unexecuted work stays unknown. Timeouts and failed snapshots have no delivered image profile.",
               "encoding": "Output uses its actual PTS; multiple packets with one PTS do not create extra frames.",
               "abandoned": "Only an explicit terminal event resolves an unsubmitted conversion. File position or a footer never implies abandonment. frame_count counts submitted inputs; work_item_count includes converted candidates.",
               "repeats": "Source-to-send and capture-to-send distributions include repeated inputs, retaining the original source time.",
               "cadence": "Output/send cadence counts packets and timestamps, not distinct pictures or received/displayed frames.",
               "send": "Bytes/datagrams include UDP payload and FEC; no NIC completion or receipt is measured."}}
    lanes = {"capture": 1, "convert": 2, "encode": 3, "metadata": 4, "send": 5, "encode_queue": 6, "input": 7}
    timeline = {"traceEvents": [{"name": "process_name", "ph": "M", "pid": 1, "tid": 0,
                                  "args": {"name": "RHRemote host " + header["trace_id"]}}],
                "displayTimeUnit": "ms", "metadata": {**summary["measurement"], "trace_id": header["trace_id"]}}
    for name, thread in lanes.items():
        timeline["traceEvents"].append({"name": "thread_name", "ph": "M", "pid": 1, "tid": thread,
                                       "args": {"name": name}})
    origin = min([e["at_us"] for e in events] + [start["at_us"] for _, start, _ in spans],
                 default=header["steady_origin_us"])
    for name, start, end in spans:
        if end["at_us"] >= start["at_us"]:
            timeline["traceEvents"].append({"name": name, "ph": "X", "pid": 1,
                                            "tid": lanes[name if name in lanes else name.split("_")[0]],
                                            "ts": start["at_us"] - origin, "dur": end["at_us"] - start["at_us"],
                                            "args": {k: start[k] for k in ("generation", "frame", "packet", "source", "fresh")}})
    # Include every original validated event; spans are a navigational view, not a replacement.
    for row in events:
        timeline["traceEvents"].append({"name": row["event"], "ph": "i", "s": "t", "pid": 1,
                                       "tid": lanes[row["event"].split("_")[0]], "ts": row["at_us"] - origin,
                                       "args": {k: v for k, v in row.items() if k not in ("event", "at_us")}})
    return summary, frame_table, packet_table, timeline


def duration(metrics, issues, name, begin, end):
    if end < begin:
        issues["negative_" + name] += 1
        return None
    value = (end - begin) / 1000
    metrics[name].append(value)
    return value


def span(spans, name, start, end):
    spans.append((name, start, end))


def atomic_write(path, writer):
    if path.is_symlink():
        raise ValueError("Analysis output cannot replace a symlink")
    descriptor, temporary = tempfile.mkstemp(prefix=".host-analysis-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as stream:
            writer(stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def write_csv(path, columns, rows):
    def write(stream):
        output = csv.DictWriter(stream, fieldnames=columns, extrasaction="ignore")
        output.writeheader()
        output.writerows(rows)
    atomic_write(path, write)


def no_symlink(path):
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError("Host analysis paths cannot traverse symlinks")


def export(raw, output=None, keep_raw=False):
    raw = Path(os.path.abspath(raw))
    no_symlink(raw)
    output = Path(os.path.abspath(output)) if output else raw.parent / "analysis"
    no_symlink(output)
    rows, parse_issues, identity = load(raw)
    summary, frames, packets, timeline = analyze(rows, parse_issues)
    summary["input"] = identity
    summary["raw_deleted"] = False
    managed = raw.name == "frames.jsonl" and raw.parent.name == summary["trace_id"]
    summary["raw_retention_reason"] = ("requested" if keep_raw else "incomplete" if not summary["complete"]
                                       else "unmanaged_input_path" if not managed else "analysis_pending")
    output.mkdir(parents=True, exist_ok=True)
    targets = [output / name for name in ("summary.json", "frames.csv", "packets.csv", "timeline.json", "captures.csv")]
    if raw in targets:
        raise ValueError("Analysis output would overwrite the input")
    atomic_write(targets[0], lambda f: json.dump(summary, f, ensure_ascii=False, indent=2))
    write_csv(targets[1], FRAME_COLUMNS, frames)
    write_csv(targets[2], PACKET_COLUMNS, packets)
    atomic_write(targets[3], lambda f: json.dump(timeline, f, ensure_ascii=False, separators=(",", ":")))
    write_csv(targets[4], CAPTURE_COLUMNS, capture_table(rows))
    if summary["complete"] and not keep_raw and managed:
        # Only delete this explicitly analyzed closed file, after all outputs are durable.
        no_symlink(raw)
        check = hashlib.sha256()
        checked = 0
        with raw.open("rb") as source:
            while block := source.read(1024 * 1024):
                checked += len(block)
                if checked > identity["bytes"]:
                    raise ValueError("Host trace grew during analysis; raw file retained")
                check.update(block)
        if check.hexdigest() != identity["sha256"]:
            raise ValueError("Host trace changed during analysis; raw file retained")
        raw.unlink()
        summary["raw_deleted"] = True
        summary["raw_retention_reason"] = None
        atomic_write(targets[0], lambda f: json.dump(summary, f, ensure_ascii=False, indent=2))
    return summary, output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path, help="One frames.jsonl from a closed host session")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--keep-raw", action="store_true")
    args = parser.parse_args()
    summary, output = export(args.trace, args.output, args.keep_raw)
    print(json.dumps({"analysis": str(output), "complete": summary["complete"], "issues": summary["issues"],
                      "frames": summary["frame_count"], "packets": summary["packet_count"],
                      "raw_deleted": summary["raw_deleted"]}, ensure_ascii=False, indent=2))
    return 0 if summary["complete"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
