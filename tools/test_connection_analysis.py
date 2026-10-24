import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("connection_analysis", Path(__file__).with_name("analyze-connection.py"))
c = importlib.util.module_from_spec(spec)
spec.loader.exec_module(c)


def event(name, tick, frame=-1, fields=None, thread="rust-ui"):
    return dict(event=name, ts_us=tick, frame=frame, thread=thread, fields=fields or {})


class ConnectionAnalysisTest(unittest.TestCase):
    def make_run(self, root, name, *, origin=1_000_000, app=100_000, host="host-a", start=100,
                 end=18_000_000, timeout=False, resolve_fail=False, native=True, render=None, closed=True):
        path = root / name
        path.mkdir()
        manifest = dict(schema="rhremote.statistics/v1", platform="windows", clock="monotonic_anchored_utc_us",
                        files=["ui.jsonl", "native.jsonl"], profile={"password":"private-profile-value"})
        (path / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        ui = [event("application_start", app), event("connect_action", origin),
              event("network_resolve_begin", origin+start, fields={"uuid":host})]
        if resolve_fail:
            ui.append(event("network_resolve_failed", origin+start+1, fields={"uuid":host}))
        else:
            ui += [event("network_resolve_end", origin+start+1, fields={"uuid":host,"route":"lan","selectedAddress":"192.168.1.2","token":"private-network-value"}),
                   event("native_spawn_begin", origin+start+2)]
        if timeout:
            ui += [event("route_first_frame_timeout", origin+15_000_000, fields={"nativeElapsedMs":15000,"protocolReadyObserved":False}),
                   event("route_media_failed", origin+17_000_000)]
        ui.append(event("trace_end", origin+end))
        (path / "ui.jsonl").write_text("\n".join(map(json.dumps, ui))+"\n", encoding="utf-8")
        if native:
            rows = [event("native_ui_ready", origin+start+3, thread="native")]
            if render is not None:
                rows += [event("packet_first",origin+render-100,1,thread="native"),
                         event("frame_assembled",origin+render-80,1,thread="native"),
                         event("decode_submit",origin+render-70,1,thread="native"),
                         event("decode_output",origin+render-20,1,thread="native"),
                         event("render_return",origin+render,1,thread="native")]
            if closed:
                rows.append(event("trace_end", origin+end, thread="native"))
            (path / "native.jsonl").write_text("\n".join(map(json.dumps, rows))+"\n", encoding="utf-8")
        return path

    def test_first_timeout_is_not_hidden_by_a_last_attempt_without_native(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = self.make_run(root,"1000-aaaa",timeout=True,end=17_100_000)
            last = self.make_run(root,"2000-bbbb",start=17_200_000,end=17_500_000,resolve_fail=True,native=False)
            hashes = {p:hashlib.sha256(p.read_bytes()).hexdigest() for p in root.rglob("*.jsonl")}
            report = c.analyze_connection(last)
            self.assertEqual(2, report["attempt_count"])
            self.assertIsNone(report["first_frame_ms"])
            self.assertEqual(17500, report["observed_until_ms"])
            self.assertEqual("等待首帧超时", report["primary_outcome"])
            self.assertEqual(first.name, report["primary_attempt"])
            self.assertEqual("无可用线路", report["last_attempt_outcome"])
            self.assertTrue(report["attempts"][0]["native_file_present"])
            self.assertFalse(report["attempts"][1]["native_file_present"])
            self.assertFalse(report["attempts"][1]["native_launch_announced"])
            self.assertFalse(report["raw_integrity_ok"])
            self.assertFalse(report["performance_accepted"])
            for path, digest in hashes.items():
                self.assertEqual(digest, hashlib.sha256(path.read_bytes()).hexdigest())

    def test_fallback_first_frame_is_measured_from_the_original_request(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_run(root,"1000-aaaa",timeout=True,end=17_100_000)
            last = self.make_run(root,"2000-bbbb",start=17_200_000,end=22_000_000,render=21_000_000)
            report = c.analyze_connection(last)
            self.assertEqual(21000, report["first_frame_ms"])
            self.assertEqual("render_return", report["first_frame_endpoint"])
            self.assertEqual(last.name, report["first_frame_attempt"])
            self.assertEqual("收到首帧", report["primary_outcome"])
            self.assertEqual([0,1], [a["frame_count"] for a in report["attempts"]])
            self.assertFalse(report["physical_scanout_measured"])
            self.assertNotIn("frame_metrics", report)

    def test_different_admissions_app_lifetimes_or_targets_do_not_merge(self):
        for change in ({"origin":2_000_000}, {"app":200_000}, {"host":"host-b"}):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                root=Path(directory)
                self.make_run(root,"1000-aaaa",timeout=True)
                selected=self.make_run(root,"2000-bbbb",**change)
                self.assertEqual(1,c.analyze_connection(selected)["attempt_count"])

    def test_incomplete_earlier_attempt_remains_visible_and_retains_raw(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            first=self.make_run(root,"1000-aaaa",timeout=True,closed=False)
            last=self.make_run(root,"2000-bbbb",start=19_000_000,end=20_000_000,resolve_fail=True,native=False)
            report=c.analyze_connection(last)
            self.assertEqual(2,report["attempt_count"])
            self.assertFalse(report["attempts"][0]["raw_integrity_ok"])
            self.assertTrue(any("expected one trace_end" in warning for warning in report["attempts"][0]["warnings"]))
            self.assertTrue((first/"native.jsonl").exists())
            self.assertFalse((first/"analysis-complete.json").exists())

    def test_previously_deleted_raw_is_not_recreated_or_claimed_reaudited(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            first=self.make_run(root,"1000-aaaa",timeout=True)
            manifest=json.loads((first/"manifest.json").read_text())
            events=[]
            for name in manifest["files"]:
                rows,_=c.single.read_client_file(first/name);events+=rows
            summary,frames=c.single.analyze(events,manifest)
            (first/"report.html").write_text(c.single.report_html(summary,frames,events),encoding="utf-8")
            for name in manifest["files"]:(first/name).unlink()
            last=self.make_run(root,"2000-bbbb",start=19_000_000,end=20_000_000,resolve_fail=True,native=False)
            report=c.analyze_connection(last)
            old=report["attempts"][0]
            self.assertFalse(old["raw_available"])
            self.assertFalse(old["raw_integrity_ok"])
            self.assertTrue(old["native_events_embedded"])
            self.assertFalse(old["native_file_present"])
            self.assertFalse((first/"native.jsonl").exists())
            self.assertEqual("../1000-aaaa/report.html",old["detail_report"])

    def test_unknown_target_or_clock_does_not_use_a_time_profile_heuristic(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            self.make_run(root,"1000-aaaa",timeout=True,host="")
            last=self.make_run(root,"2000-bbbb",host="")
            self.assertEqual(1,c.analyze_connection(last)["attempt_count"])
            manifest=json.loads((last/"manifest.json").read_text())
            manifest["clock"]="unknown"
            (last/"manifest.json").write_text(json.dumps(manifest))
            with self.assertRaises(c.IdentityUnavailable):c.analyze_connection(last)

    def test_report_is_escaped_and_does_not_copy_private_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            path=self.make_run(Path(directory),"1000-aaaa")
            ui=[json.loads(line) for line in (path/"ui.jsonl").read_text().splitlines()]
            next(row for row in ui if row["event"]=="network_resolve_end")["fields"]["selectedAddress"]='</script><img src=x onerror=alert(1)>'
            (path/"ui.jsonl").write_text("\n".join(map(json.dumps,ui))+"\n")
            data=c.analyze_connection(path)
            html=c.report_html(data)
            self.assertNotIn("</script><img",html)
            self.assertIn("\\u003c/script>",html)
            self.assertNotIn("private-profile-value",html)
            self.assertNotIn("private-network-value",html)


if __name__ == "__main__":
    unittest.main()
