import importlib.util
import unittest
import tempfile
import json
from pathlib import Path

spec=importlib.util.spec_from_file_location("analysis",Path(__file__).with_name("analyze-statistics.py"));a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)
def e(name,t,frame=-1,**extra):return dict(event=name,ts_us=t,frame=frame,fields={},**extra)
class StatisticsTest(unittest.TestCase):
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
