"""Group Windows admission attempts without merging frame IDs or touching raw data."""
import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
from urllib.parse import quote

spec = importlib.util.spec_from_file_location("rhremote_single_attempt", Path(__file__).with_name("analyze-statistics.py"))
single = importlib.util.module_from_spec(spec)
spec.loader.exec_module(single)
RUN_ID = re.compile(r"^\d+-[a-f0-9-]+$")
PRELUDE = {"application_start", "preferences_loaded", "devices_restored", "ui_backend_selected", "ui_created", "ui_shown", "connect_action", "connect_request_received"}


class IdentityUnavailable(ValueError):
    """An older or ambiguous trace cannot safely identify admission siblings."""


def finite_tick(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def identity(rows):
    values = []
    for name in ("application_start", "connect_action"):
        stamps = {r.get("ts_us") for r in rows if r.get("event") == name and finite_tick(r.get("ts_us"))}
        if len(stamps) != 1:
            raise IdentityUnavailable("需要唯一的应用启动和连接操作时间；无法可靠关联尝试")
        values.append(next(iter(stamps)))
    if values[0] <= 0 or values[1] < values[0]:
        raise IdentityUnavailable("连接时间不属于可识别的应用生命周期")
    hosts = {r["fields"]["uuid"] for r in rows if r.get("event") in ("network_resolve_begin", "network_resolve_end", "network_resolve_failed")
             and isinstance(r.get("fields"), dict) and isinstance(r["fields"].get("uuid"), str) and r["fields"]["uuid"]}
    if len(hosts) > 1:
        raise IdentityUnavailable("同一尝试出现多个目标标识，不能自动关联")
    return tuple(values), next(iter(hosts), None)


def metadata(path):
    if path.is_symlink() or not path.is_dir() or (path / "manifest.json").is_symlink():
        raise ValueError("Statistics directory/manifest symlinks are not accepted")
    manifest_path = path / "manifest.json"
    if manifest_path.stat().st_size > 1024 * 1024:
        raise ValueError("Statistics manifest exceeds its bound")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    if manifest.get("schema") != "rhremote.statistics/v1" or manifest.get("platform") != "windows":
        raise IdentityUnavailable("该汇总只关联 Windows Rust UI 的连接请求")
    if manifest.get("clock") != "monotonic_anchored_utc_us":
        raise IdentityUnavailable("未声明 Windows UI 的单调锚定时钟，不能自动关联")
    ui = path / "ui.jsonl"
    if ui.is_symlink():
        raise ValueError("UI metadata symlinks are not accepted")
    if ui.exists():
        rows, _ = single.read_client_file(ui)
        return manifest, identity(rows), rows, None
    report = path / "report.html"
    if report.is_symlink() or not report.is_file() or report.stat().st_size > 128 * 1024 * 1024:
        raise IdentityUnavailable("原始 UI 记录和有界历史报告均不可用")
    html = report.read_text(encoding="utf-8")
    match = re.search(r"<script>\s*const D=", html)
    if not match:
        raise IdentityUnavailable("历史报告没有可读取的嵌入事件")
    saved, _ = json.JSONDecoder().raw_decode(html[match.end():])
    events = saved.get("events")
    if not isinstance(events, list):
        raise IdentityUnavailable("历史报告缺少事件数组")
    rows = [r for r in events if isinstance(r, dict) and r.get("thread") == "rust-ui"]
    return manifest, identity(rows), rows, saved


def attempt(path, loaded, origin):
    manifest, (_, host), ui, saved = loaded
    warnings, input_files, events, hashes = [], [], [], {}
    if saved is not None:
        events = saved["events"]
        input_files = saved.get("summary", {}).get("input_files", [])
        warnings.append("原始记录不可用，使用历史报告；无法重新审计原始完整性")
        raw_ok = False
    else:
        names = manifest.get("files", [])
        if not isinstance(names, list) or not names or len(names) != len(set(names)) or any(n not in ("ui.jsonl", "native.jsonl") for n in names):
            raise ValueError("Unexpected Windows statistics input list")
        for name in names:
            source = path / name
            if source.is_symlink():
                raise ValueError("Statistics input symlinks are not accepted")
            rows, health = single.read_client_file(source)
            events.extend(rows)
            input_files.append(health)
            if source.is_file():
                hashes[name] = hashlib.sha256(source.read_bytes()).hexdigest()
        warnings.extend(single.input_file_warnings(input_files))
        raw_ok = not warnings
    summary, _ = single.analyze(events, manifest)
    warnings.extend(summary.get("warnings", []))
    raw_ok = raw_ok and not summary.get("warnings")
    own = [r for r in ui if finite_tick(r.get("ts_us")) and r["ts_us"] >= origin]
    observed = [r["ts_us"] + (r.get("dur_us", 0) if finite_tick(r.get("dur_us", 0)) else 0) for r in events
                if finite_tick(r.get("ts_us")) and r["ts_us"] >= origin]
    start = min((r["ts_us"] for r in own if r["event"] not in PRELUDE and r["event"] != "trace_end"), default=origin)
    end = max(observed, default=start)
    footer = [r for r in own if r["event"] == "trace_end"]
    timeout = next((r for r in own if r["event"] == "route_first_frame_timeout"), None)
    failed_route = next((r for r in own if r["event"] == "route_media_failed"), None)
    resolution_failed = any(r["event"] == "network_resolve_failed" for r in own)
    resolved = next((r.get("fields", {}) for r in own if r["event"] == "network_resolve_end"), {})
    measurement = summary["connection_measurement"]
    endpoint = measurement.get("endpoint_us")
    rendered = finite_tick(endpoint) and endpoint >= origin
    if endpoint is not None and not rendered:
        warnings.append("首帧时刻早于连接请求或无效，不能作为首帧耗时")
        raw_ok = False
    outcome = "收到首帧后中断" if rendered and failed_route else "收到首帧" if rendered else "等待首帧超时" if timeout else "媒体连接失败" if failed_route else "无可用线路" if resolution_failed else "未收到首帧，结果未知"
    native_present = (path / "native.jsonl").is_file()
    native_embedded = saved is not None and any(r.get("event") in ("native_ui_ready", "connection_begin", "packet_first", "render_return") for r in events)
    native_announced = any(r["event"] == "native_spawn_begin" for r in own)
    link = "../" + quote(path.name, safe="") + "/report.html" if (path / "report.html").is_file() and not (path / "report.html").is_symlink() else None
    return dict(run_id=path.name, host_uuid=host, start_us=start, end_us=end,
                start_ms=round((start-origin)/1000, 3), end_ms=round((end-origin)/1000, 3),
                outcome=outcome, route=resolved.get("route"), address=resolved.get("selectedAddress"),
                endpoint_us=endpoint if rendered else None, endpoint_event=measurement.get("endpoint_event") if rendered else None,
                first_frame_from_request_ms=measurement.get("total_ms") if rendered else None, frame_count=summary["frame_count"],
                native_file_present=native_present, native_events_embedded=native_embedded, native_launch_announced=native_announced,
                ui_footer_count=len(footer), raw_integrity_ok=raw_ok, raw_available=saved is None,
                input_files=input_files, input_sha256=hashes, warnings=warnings,
                timeout_native_ms=timeout.get("fields", {}).get("nativeElapsedMs") if timeout else None,
                protocol_ready_observed=timeout.get("fields", {}).get("protocolReadyObserved") if timeout else None,
                connection_phases=summary["connection_phases"], detail_report=link)


def analyze_connection(selected):
    selected = Path(selected)
    if selected.is_symlink() or selected.parent.is_symlink():
        raise ValueError("Statistics directory symlinks are not accepted")
    selected = selected.resolve()
    if not RUN_ID.fullmatch(selected.name):
        raise ValueError("Expected an actual Windows statistics run ID")
    loaded = metadata(selected)
    key, target = loaded[1]
    candidates = [(selected, loaded)]
    skipped = []
    # No target UUID means no automatic sibling association. Never guess by time/profile.
    if target:
        siblings = sorted(p for p in selected.parent.iterdir() if p.name != selected.name and RUN_ID.fullmatch(p.name) and not p.is_symlink() and p.is_dir())
        if len(siblings) > 4096:
            raise ValueError("Statistics sibling inventory exceeds its bound")
        for sibling in siblings:
            try:
                item = metadata(sibling)
            except (OSError, ValueError, KeyError, TypeError) as error:
                skipped.append(dict(run_id=sibling.name, reason=type(error).__name__))
                continue
            if item[1] == (key, target):
                candidates.append((sibling, item))
    rows = sorted((attempt(path, item, key[1]) for path, item in candidates), key=lambda row:(row["start_us"], row["run_id"]))
    endpoints = [row for row in rows if row["endpoint_us"] is not None and row["endpoint_us"] >= key[1]]
    first = min(endpoints, key=lambda row:row["endpoint_us"]) if endpoints else None
    failures = [row for row in rows if row["timeout_native_ms"] is not None]
    primary = failures[0] if failures else next((row for row in rows if row["outcome"] == "媒体连接失败"), rows[-1])
    return dict(schema="rhremote.connection-analysis/v1", selected_run=selected.name,
                application_start_us=key[0], origin_us=key[1], host_uuid=target,
                association="Exact application_start + connect_action + Sunshine UUID; no time/profile heuristic",
                attempt_count=len(rows), attempts=rows, skipped_unreadable_siblings=skipped,
                first_frame_ms=round((first["endpoint_us"]-key[1])/1000, 3) if first else None,
                first_frame_attempt=first["run_id"] if first else None,
                first_frame_endpoint=first["endpoint_event"] if first else None,
                observed_until_ms=round((max(row["end_us"] for row in rows)-key[1])/1000, 3),
                primary_outcome=rows[-1]["outcome"] if first else primary["outcome"], primary_attempt=rows[-1]["run_id"] if first else primary["run_id"],
                last_attempt_outcome=rows[-1]["outcome"],
                preceding_failure=dict(run_id=primary["run_id"], outcome=primary["outcome"]) if failures or any(r["outcome"] == "媒体连接失败" for r in rows) else None,
                raw_integrity_ok=all(row["raw_integrity_ok"] for row in rows),
                raw_modified=False, physical_scanout_measured=False, performance_accepted=False,
                limits="First-frame time is from the original admission, not a sum of nested phases. Frame IDs and metrics remain separate per attempt. Automatic recovery with a new admission origin is a separate measurement. Missing raw remains unknown; this tool never recreates/deletes raw or writes completion/retention markers.")


def report_html(data):
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":")).replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    template = Path(__file__).with_name("connection-viewer.html").read_text(encoding="utf-8")
    return template.replace("/*RHREMOTE_CONNECTION_DATA*/", payload)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    args = parser.parse_args()
    try:
        data = analyze_connection(args.run)
    except IdentityUnavailable as error:
        print(json.dumps(dict(available=False, reason=str(error))))
        return
    root = args.run.resolve()
    (root / "connection-summary.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    report = root / "connection-report.html"
    report.write_text(report_html(data), encoding="utf-8")
    print(json.dumps(dict(report=str(report), attempts=data["attempt_count"], first_frame_ms=data["first_frame_ms"], observed_until_ms=data["observed_until_ms"], primary_outcome=data["primary_outcome"], raw_modified=False)))


if __name__ == "__main__":
    main()
