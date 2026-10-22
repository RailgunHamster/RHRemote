import importlib.util
import unittest
import tempfile
import json
import subprocess
import sys
from pathlib import Path

spec=importlib.util.spec_from_file_location("analysis",Path(__file__).with_name("analyze-statistics.py"));a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)
def e(name,t,frame=-1,**extra):return dict(event=name,ts_us=t,frame=frame,fields={},**extra)
class StatisticsTest(unittest.TestCase):
    def test_ui_queue_time_is_part_of_connection_total(self):
        rows=[e("connect_action",0),e("connect_request_received",620_000),e("native_spawn_begin",625_000),e("connection_begin",1_000_000),e("render_return",2_000_000,1)]
        measurement,phases=a.connection_measurement(rows,"windows")
        self.assertEqual(measurement["total_ms"],2000)
        self.assertEqual(phases[0]["duration_ms"],620)
        self.assertEqual(phases[0]["name"],"客户端操作排队")
        self.assertEqual(sum(phase["duration_ms"] for phase in phases),2000)
    def test_refresh_reimports_host_evidence_after_raw_traces_were_deleted(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);stamp=1_792_627_200_000_000
            manifest={"schema":"rhremote.statistics/v1","platform":"windows","files":["native.jsonl"]}
            rows=[e("connect_action",stamp),e("render_return",stamp+2_000_000,1),e("trace_end",stamp+3_000_000)]
            summary,frames=a.analyze(rows,manifest)
            summary["host_spans"]=[{"name":"host_encoder_reenumeration","ms":5000}]
            rows.append(dict(e("host_encoder_reenumeration",stamp,dur_us=5_000_000),thread="Sunshine launch"))
            (root/"manifest.json").write_text(json.dumps(manifest),encoding="utf-8")
            (root/"report.html").write_text(a.report_html(summary,frames,rows),encoding="utf-8")
            log_time=a.dt.datetime.fromtimestamp(stamp/1e6,a.dt.timezone.utc)
            lines=[f"[{log_time.isoformat(sep=' ',timespec='milliseconds')[:23]}]: Info: RHREMOTE_PHASE begin id=1 name=encoder_probe tick_us=1000",
                f"[{(log_time+a.dt.timedelta(seconds=1)).isoformat(sep=' ',timespec='milliseconds')[:23]}]: Info: RHREMOTE_PHASE end id=1 name=encoder_probe tick_us=1001000 elapsed_us=1000000"]
            evidence=root/"host-evidence.json";evidence.write_text(json.dumps({"utcOffsetMinutes":0,"log":lines}),encoding="utf-8")
            subprocess.run([sys.executable,str(Path(a.__file__)),str(root),"--refresh-report","--host-evidence",str(evidence)],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            refreshed=json.loads((root/"summary.json").read_text(encoding="utf-8"))
            self.assertEqual(refreshed["host_spans"],[{"name":"host_encoder_probe","ms":1000.0}])
            self.assertEqual(refreshed["connection_measurement"]["total_ms"],2000)
            self.assertFalse((root/"native.jsonl").exists())
            timeline=json.loads((root/"timeline.json").read_text(encoding="utf-8"))
            self.assertNotIn("host_encoder_reenumeration",[e["name"] for e in timeline["traceEvents"]])

    def test_exact_host_scopes_replace_log_intervals_including_idle_wait(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/"host.json"
            path.write_text(json.dumps({"utcOffsetMinutes":0,"log":[
                "[2026-10-22 00:00:00.000]: Info: Encoder reenumeration is required",
                "[2026-10-22 00:00:00.000]: Info: RHREMOTE_PHASE begin id=7 name=encoder_probe tick_us=1000",
                "[2026-10-22 00:00:00.100]: Info: Creating encoder [libx264]",
                "[2026-10-22 00:00:00.100]: Info: RHREMOTE_PHASE begin id=8 name=encoder_create tick_us=101000",
                "[2026-10-22 00:00:00.110]: Info: Streaming bitrate: 25000000",
                "[2026-10-22 00:00:00.120]: Info: RHREMOTE_PHASE end id=8 name=encoder_create tick_us=121000 elapsed_us=20000",
                "[2026-10-22 00:00:01.000]: Info: RHREMOTE_PHASE end id=7 name=encoder_probe tick_us=1001000 elapsed_us=1000000",
                "[2026-10-22 00:00:05.000]: Info: CLIENT CONNECTED"
            ]}),encoding="utf-8")
            rows,_=a.import_host(path,0,10**18)
            spans=[e for e in rows if e.get("dur_us")]
            self.assertEqual([(e["event"],e["dur_us"]) for e in spans],[("host_encoder_create",20000),("host_encoder_probe",1000000)])

    def test_instrumented_host_scopes_use_monotonic_duration(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/"host.json"
            path.write_text(json.dumps({"utcOffsetMinutes":0,"log":[
                "[2026-10-22 00:00:00.000]: Info: RHREMOTE_PHASE begin id=7 name=dda_device_init tick_us=1000",
                "[2026-10-22 00:00:01.500]: Info: RHREMOTE_PHASE end id=7 name=dda_device_init tick_us=1401000 elapsed_us=1400000"
            ]}),encoding="utf-8")
            rows,_=a.import_host(path,0,10**18)
            self.assertEqual(len(rows),1);self.assertEqual(rows[0]["dur_us"],1400000)
            self.assertEqual(rows[0]["event"],"host_dda_device_init")
    def test_connection_origin_excludes_idle_and_phases_sum_to_first_frame(self):
        start=1_000_000_000
        rows=[e("application_start",start-240_446_959),e("connect_action",start),e("connection_begin",start+3_167_606),e("connection_ready",start+3_354_511),e("packet_first",start+9_765_653,1),e("decode_output",start+9_815_586,1),e("render_return",start+9_817_465,1),e("trace_end",start+20_000_000)]
        summary,_=a.analyze(rows,{"platform":"windows"});m=summary["connection_measurement"]
        self.assertEqual(m["origin_us"],start);self.assertEqual(m["total_ms"],9817.465);self.assertEqual(m["endpoint_frame"],1);self.assertFalse(m["physical_scanout_measured"])
        self.assertAlmostEqual(sum(x["duration_ms"] for x in summary["connection_phases"]),9817.465)
    def test_android_uses_callback_arrival_not_reported_presentation_time(self):
        m,_=a.connection_measurement([e("connect_action",0),e("surface_rendered",9_000_000,1),e("surface_render_callback",9_200_000,1)],"android")
        self.assertEqual(m["origin_us"],0);self.assertEqual(m["total_ms"],9200)
    def test_frame_identity_and_stage_durations(self):
        rows=[e("connect_action",1000),e("host_launch_request",2000,dur_us=37000000),e("packet_first",38000000,17),e("frame_assembled",38002000,17),e("decode_submit",38003000,17),e("decode_submit_return",38004000,17),e("decode_output",38007000,17),e("render_submit",38009000,17),e("render_return",38010000,17),e("trace_end",39000000)]
        s,f=a.analyze(rows,{"platform":"windows"});self.assertFalse(s["warnings"]);self.assertEqual(f[0]["decode_ms"],4);self.assertEqual(f[0]["pacing_ms"],2);self.assertEqual(s["startup_spans"][0]["ms"],37000);self.assertIsNone(s["frame_metrics"]["surface_callback_delay_ms"])
    def test_missing_footer_and_recorder_loss_are_not_reported_complete(self):
        s,_=a.analyze([e("packet_first",0,1)],{});self.assertTrue(s["warnings"])
        s,_=a.analyze([dict(e("trace_end",10),lost_events=3)],{});self.assertEqual(s["recorder_lost_events"],3);self.assertTrue(s["warnings"])
    def test_gaps_drop_and_negative_duration(self):
        rows=[e("packet_first",100,5),e("frame_assembled",200,5),e("decode_submit",150,5),e("packet_first",1000,8),e("frame_assembled",1200,8),e("frame_drop",1300,8),e("trace_end",2000)]
        s,f=a.analyze(rows,{});self.assertEqual(s["missing_frame_numbers"],2);self.assertEqual(s["frame_drops"],1);self.assertEqual(s["frame_metrics"]["decoder_queue_ms"],None);self.assertTrue(s["warnings"])
if __name__=="__main__":unittest.main()
