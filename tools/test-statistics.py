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
    def direct_rows(self):
        contract={"contract":"rh-cml-frame-clock-v1","clock_domain":"rhstatistics","unit":"us"}
        marker=dict(e("frame_clock_contract",200),fields=dict(contract,receive_site="udp_recv_return",assembly_site="decode_unit_ready"))
        return [e("connect_action",100),marker,e("connection_ready",500),
                dict(e("packet_first",1001,1),fields=contract.copy()),dict(e("frame_assembled",1234,1),fields=contract.copy()),
                e("decode_submit",1301,1),e("decode_output",1500,1),e("render_return",2001,1),e("trace_end",2200)]

    def test_direct_producer_clock_keeps_submillisecond_spans_and_exact_first_frame(self):
        summary,_=a.analyze(self.direct_rows(),{"platform":"windows"})
        self.assertTrue(summary["receive_assembly_timing"]["high_resolution_proven"])
        self.assertEqual([],summary["receive_assembly_timing"]["estimated_metrics"])
        self.assertEqual(.233,summary["frame_metrics"]["reassembly_ms"]["mean"])
        self.assertEqual(.067,summary["frame_metrics"]["decoder_queue_ms"]["mean"])
        self.assertEqual(1.901,summary["connection_measurement"]["total_ms"])
        self.assertFalse(any("估计" in p["name"] for p in summary["connection_phases"]))
        self.assertEqual([],summary["warnings"])

    def test_a_clock_declaration_does_not_upgrade_legacy_mixed_or_invalid_points(self):
        import copy
        rows=self.direct_rows()
        variants=[]
        for stage in ("packet_first","frame_assembled","frame_clock_contract"):
            missing=copy.deepcopy(rows);missing=[r for r in missing if r["event"]!=stage];variants.append(missing)
        for field,value in (("clock_domain","other"),("contract","future"),("unit","ms")):
            mixed=copy.deepcopy(rows);next(r for r in mixed if r["event"]=="packet_first")["fields"][field]=value;variants.append(mixed)
        for value in (0,-1,float("inf"),True,1500):
            invalid=copy.deepcopy(rows);next(r for r in invalid if r["event"]=="packet_first")["ts_us"]=value;variants.append(invalid)
        variants.append(rows+[e("packet_first",3000,2),e("frame_assembled",3200,2)])
        variants.append(rows+[e("frame_clock_missing",2100,2)])
        for variant in variants:self.assertFalse(a.direct_frame_clock(variant))

    def test_missing_direct_clock_is_visible_and_never_backprojected(self):
        rows=self.direct_rows()+[e("frame_clock_missing",2100,2),e("decode_submit",2101,2)]
        summary,frames=a.analyze(rows,{"platform":"windows"})
        self.assertFalse(summary["receive_assembly_timing"]["high_resolution_proven"])
        self.assertTrue(any("missing receive/assembly" in warning for warning in summary["warnings"]))
        missing=next(f for f in frames if f["frame"]==2)
        self.assertNotIn("packet_first",missing["events"]);self.assertNotIn("frame_assembled",missing["events"])

    def test_legacy_receive_clock_is_qualified_without_changing_first_frame_or_completeness(self):
        rows=[e("connect_action",0),e("connection_ready",500),e("packet_first",1000,1),
              e("frame_assembled",2000,1),e("decode_submit",3000,1),e("decode_output",5000,1),
              e("render_return",7000,1),e("trace_end",10000)]
        summary,_=a.analyze(rows,{"platform":"windows"})
        quality=summary["receive_assembly_timing"]
        self.assertFalse(quality["high_resolution_proven"])
        self.assertEqual(set(quality["estimated_metrics"]),{"reassembly_ms","decoder_queue_ms","client_pipeline_ms"})
        self.assertNotIn("decode_ms",quality["estimated_metrics"])
        self.assertEqual(summary["frame_metrics"]["reassembly_ms"]["mean"],1)
        self.assertEqual(summary["frame_metrics"]["decode_ms"]["mean"],2)
        self.assertEqual(summary["connection_measurement"]["total_ms"],7)
        self.assertEqual(summary["warnings"],[])
        estimated=[p for p in summary["connection_phases"] if p.get("timing_quality")=="legacy_receive_estimate"]
        self.assertEqual([(p["from"],p["to"]) for p in estimated],[("connection_ready","packet_first"),("packet_first","decode_output")])
        self.assertTrue(all(p["name"].endswith("（估计）") for p in estimated))
        self.assertEqual(sum(p["duration_ms"] for p in summary["connection_phases"]),7)

    def test_android_does_not_inherit_the_windows_backprojection_claim(self):
        summary,_=a.analyze([e("connect_action",0),e("surface_render_callback",1000,1),e("trace_end",2000)],{"platform":"android"})
        self.assertNotIn("receive_assembly_timing",summary)
        self.assertEqual(summary["connection_measurement"]["total_ms"],1)

    def test_incomplete_host_keeps_healthy_client_raw_and_prevents_pruning_after_refresh(self):
        from test_host_frame_analysis import fixture as host_fixture
        with tempfile.TemporaryDirectory(prefix="rhremote-partial-host-") as directory:
            root=Path(directory);run=root/"test-client-1";run.mkdir();host=root/"host-1";host.mkdir()
            manifest={"schema":"rhremote.statistics/v1","platform":"windows","files":["native.jsonl"]}
            (run/"manifest.json").write_text(json.dumps(manifest),encoding="utf-8")
            client=[e("connect_action",1000),e("frame_assembled",1500,1),e("render_return",2000,1),e("trace_end",2100)]
            client_file=run/"native.jsonl";client_file.write_text("\n".join(json.dumps(row) for row in client)+"\n",encoding="utf-8")
            rows=host_fixture();rows[-1]["lost_events"]=1
            host_file=host/"frames.jsonl";host_file.write_text("\n".join(json.dumps(row) for row in rows)+"\n",encoding="utf-8")
            command=[sys.executable,str(Path(a.__file__)),str(run)]
            subprocess.run(command+["--host-frame-trace",str(host_file)],check=True,capture_output=True)
            first=json.loads((run/"summary.json").read_text(encoding="utf-8"))
            self.assertFalse(first["host_frame_traces"][0]["complete"])
            self.assertTrue(any("Host trace host-1 is incomplete" in warning for warning in first["warnings"]))
            for arguments in ([],["--refresh-report"]):
                if arguments:subprocess.run(command+arguments,check=True,capture_output=True)
                refreshed=json.loads((run/"summary.json").read_text(encoding="utf-8"))
                self.assertEqual(first["warnings"],refreshed["warnings"])
                self.assertTrue(client_file.exists());self.assertTrue(host_file.exists())
                self.assertFalse((run/"analysis-complete.json").exists())
    def run_files(self,files,contents,refresh=False):
        with tempfile.TemporaryDirectory(prefix="rhremote-client-trace-") as directory:
            root=Path(directory);manifest={"schema":"rhremote.statistics/v1","platform":"windows","files":files}
            (root/"manifest.json").write_text(json.dumps(manifest),encoding="utf-8")
            for name,rows in contents.items():(root/name).write_text("\n".join(json.dumps(row) for row in rows)+"\n",encoding="utf-8")
            command=[sys.executable,str(Path(a.__file__)),str(root)]
            subprocess.run(command,check=True,capture_output=True)
            first=json.loads((root/"summary.json").read_text(encoding="utf-8"))
            remaining={name:(root/name).exists() for name in contents}
            if refresh:
                subprocess.run(command+["--refresh-report"],check=True,capture_output=True)
                second=json.loads((root/"summary.json").read_text(encoding="utf-8"))
                self.assertEqual(first["input_files"],second["input_files"])
                self.assertEqual(first["warnings"],second["warnings"])
            return first,remaining
    def test_healthy_native_footer_cannot_hide_a_crashed_ui_writer(self):
        summary,remaining=self.run_files(["ui.jsonl","native.jsonl"],{
            "ui.jsonl":[e("connect_action",1000),e("native_spawn_begin",1100)],
            "native.jsonl":[e("render_return",2000,1),e("trace_end",2100)]},refresh=True)
        self.assertTrue(any("ui.jsonl" in warning and "found 0" in warning for warning in summary["warnings"]))
        self.assertTrue(all(remaining.values()))
    def test_missing_declared_file_and_file_cap_are_incomplete(self):
        summary,remaining=self.run_files(["ui.jsonl","native.jsonl"],{"ui.jsonl":[e("connect_action",1000),e("trace_end",2000)]})
        self.assertTrue(any("native.jsonl: file is missing" in warning for warning in summary["warnings"]))
        self.assertTrue(remaining["ui.jsonl"])
        for field,value in (("size_limit_reached",True),("lost_events",2),("lost_events","invalid")):
            footer=dict(e("trace_end",2100),**{field:value})
            summary,remaining=self.run_files(["ui.jsonl","native.jsonl"],{
                "ui.jsonl":[e("connect_action",1000),e("trace_end",1900)],
                "native.jsonl":[e("render_return",2000,1),footer]})
            self.assertTrue(summary["warnings"],field);self.assertTrue(all(remaining.values()))
    def test_independently_closed_files_can_be_deleted_after_report(self):
        summary,remaining=self.run_files(["ui.jsonl","native.jsonl"],{
            "ui.jsonl":[e("connect_action",1000),e("trace_end",1900)],
            "native.jsonl":[e("render_return",2000,1),e("trace_end",2100)]},refresh=True)
        self.assertFalse(summary["warnings"]);self.assertFalse(any(remaining.values()))
    def test_duplicate_footer_late_and_truncated_rows_are_reported_per_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"native.jsonl"
            path.write_text("\n".join(json.dumps(row) for row in [e("trace_end",10),e("render_return",20,1),e("trace_end",30)])+'\n{"event":',encoding="utf-8")
            rows,health=a.read_client_file(path)
            self.assertEqual(2,health["footer_count"]);self.assertEqual(1,health["events_after_footer"])
            self.assertEqual(1,health["malformed_records"]);self.assertEqual(1,health["unterminated_lines"])
            self.assertTrue(a.input_file_warnings([health]))
    def test_overlapped_display_preparation_is_metadata_not_an_extra_video_frame(self):
        rows=[e("connect_action",0),e("connection_begin",1_000_000),e("render_return",2_000_000,1),e("trace_end",2_100_000)]
        rows.append(dict(event="host_display_prepared",ts_us=900_000,frame=-1,fields={"path":"native","hostRequestMs":800,"workerMs":790,"captureMs":770}))
        summary,frames=a.analyze(rows,{"platform":"windows"})
        self.assertEqual(summary["frame_count"],1)
        self.assertEqual(summary["display_preparation"]["hostRequestMs"],800)
        self.assertEqual(summary["connection_measurement"]["total_ms"],2000)
        self.assertEqual(sum(p["duration_ms"] for p in summary["connection_phases"]),2000)
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
    def test_nested_encoder_initialization_keeps_actual_substage_durations(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/"host.json"
            records=[("begin",1,"encoder_create",1000,None),
                     ("begin",2,"encoder_hardware_derive",2000,None),
                     ("end",2,"encoder_hardware_derive",39_002_000,39_000_000),
                     ("begin",3,"encoder_codec_open",39_003_000,None),
                     ("end",3,"encoder_codec_open",39_008_000,5000),
                     ("end",1,"encoder_create",39_456_192,39_455_192)]
            lines=["[2026-10-22 00:00:00.000]: Info: RHREMOTE_PHASE "+
                   f"{kind} id={identity} name={name} tick_us={tick}"+
                   (f" elapsed_us={elapsed}" if elapsed is not None else "")
                   for kind,identity,name,tick,elapsed in records]
            path.write_text(json.dumps({"utcOffsetMinutes":0,"log":lines}),encoding="utf-8")
            rows,_=a.import_host(path,0,10**18)
            measured={row["event"]:row["dur_us"] for row in rows}
            self.assertEqual(measured,{"host_encoder_hardware_derive":39_000_000,
                                       "host_encoder_codec_open":5000,
                                       "host_encoder_create":39_455_192})
            self.assertTrue(all(row["fields"]["measurement"].startswith("server steady clock") for row in rows))

    def test_repeated_codec_attempts_keep_their_own_scope_identity(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/"host.json"
            lines=[]
            for identity,duration in [(21,35_000_000),(22,5000)]:
                prefix="[2026-10-22 00:00:00.000]: Info: RHREMOTE_PHASE "
                lines += [prefix+f"begin id={identity} name=encoder_codec_open tick_us=1000",
                          prefix+f"end id={identity} name=encoder_codec_open tick_us={duration+1000} elapsed_us={duration}"]
            path.write_text(json.dumps({"utcOffsetMinutes":0,"log":lines}),encoding="utf-8")
            rows,_=a.import_host(path,0,10**18)
            self.assertEqual([row["dur_us"] for row in rows],[35_000_000,5000])
            self.assertEqual([row["fields"]["scopeId"] for row in rows],["21","22"])
    def test_connection_origin_excludes_idle_and_phases_sum_to_first_frame(self):
        start=1_000_000_000
        rows=[e("application_start",start-240_446_959),e("connect_action",start),e("connection_begin",start+3_167_606),e("connection_ready",start+3_354_511),e("packet_first",start+9_765_653,1),e("decode_output",start+9_815_586,1),e("render_return",start+9_817_465,1),e("trace_end",start+20_000_000)]
        summary,_=a.analyze(rows,{"platform":"windows"});m=summary["connection_measurement"]
        self.assertEqual(m["origin_us"],start);self.assertEqual(m["total_ms"],9817.465);self.assertEqual(m["endpoint_frame"],1);self.assertFalse(m["physical_scanout_measured"])
        self.assertAlmostEqual(sum(x["duration_ms"] for x in summary["connection_phases"]),9817.465)
    def test_android_uses_callback_arrival_not_reported_presentation_time(self):
        m,_=a.connection_measurement([e("connect_action",0),e("surface_rendered",9_000_000,1),e("surface_render_callback",9_200_000,1)],"android")
        self.assertEqual(m["origin_us"],0);self.assertEqual(m["total_ms"],9200)
    def test_first_frame_selection_scope_requires_current_attempt_ack(self):
        base=[e("connect_action",1000),e("render_return",5000,1)]
        m,_=a.connection_measurement(base,"windows");self.assertIsNone(m["initial_display_before_capture_accepted"])
        for value in [False,True]:
            selection=dict(e("initial_display_launch",3000),fields={"beforeCapture":value})
            m,_=a.connection_measurement(base+[selection],"windows")
            self.assertIs(m["initial_display_before_capture_accepted"],value);self.assertFalse(m["physical_scanout_measured"])
        for stamp in [500,6000]:
            m,_=a.connection_measurement(base+[dict(e("initial_display_launch",stamp),fields={"beforeCapture":True})],"windows")
            self.assertIsNone(m["initial_display_before_capture_accepted"])
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
