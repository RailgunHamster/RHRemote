"""Contract tests for host PTS association, incomplete evidence and safe retention."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("host_analysis", Path(__file__).with_name("analyze-host-frames.py"))
analysis = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(analysis)


def header(trace="host-1"):
    return {"event": "trace_begin", "schema": analysis.SCHEMA, "trace_id": trace, "client_id": "test-client-1",
            "steady_origin_us": 1, "unix_origin_us": 1700000000000000, "physical_scanout_measured": False,
            "clocks_synchronized": False, "send_endpoint": "send_call_return"}


def event(stage, timestamp, frame=1, generation=3, source=10, packet=0, **changes):
    row = dict(event=stage, trace_id="host-1", client_id="test-client-1", at_us=timestamp,
               frame=frame, generation=generation, source=source, packet=packet, capture_ready_us=1000,
               source_present_us=0, source_time_known=True, bytes=0, packets=0, fec_us=0, pacing_us=0,
               send_us=0, rtp_timestamp=0, rtp_known=False, wire_duplicate=False, fresh=True,
               metadata_known=True, result=0)
    row.update(changes)
    return row


def footer(**changes):
    return dict(event="trace_end", at_us=20000, lost_events=0, size_limit_reached=False,
                write_failed=False, **changes)


def fixture():
    return [header(), event("capture_ready", 1000, frame=-1, generation=0),
            event("convert_begin", 1100), event("convert_end", 1500),
            event("encode_begin", 2000), event("encode_output", 2500, packet=1, bytes=100),
            event("encode_end", 2700), event("send_begin", 3000, packet=1),
            event("send_end", 4000, packet=1, bytes=160, packets=2, fec_us=100, pacing_us=200,
                  send_us=300, rtp_known=True, rtp_timestamp=0), footer()]


def measured_capture():
    rows = fixture()
    rows[0]["capture_work_schema"] = analysis.CAPTURE_WORK_SCHEMA
    rows[1]["capture_work"] = {"backend": "dxgi_ram", "begin_us": 100, "end_us": 900,
        "steps": {"acquire": {"begin_us": 110, "end_us": 210},
                  "prepare": {"begin_us": 220, "end_us": 300},
                  "image_pool": {"begin_us": 310, "end_us": 330},
                  "map": {"begin_us": 340, "end_us": 740},
                  "image_setup": {"begin_us": 745, "end_us": 750},
                  "cpu_copy": {"begin_us": 760, "end_us": 820},
                  "unmap": {"begin_us": 830, "end_us": 840}, "cursor": None}}
    return rows


def measured_encoder():
    rows = fixture()
    rows[0]["encoder_work_schema"] = analysis.ENCODER_WORK_SCHEMA
    rows[5]["at_us"] = 2650
    rows[6]["encoder_work"] = {"backend": "mf", "discarded": 0, "calls": [
        {"api": "mf_get_event", "begin_us": 2100, "end_us": 2200, "result": 0},
        {"api": "mf_get_event", "begin_us": 2200, "end_us": 2400, "result": 0},
        {"api": "mf_process_input", "begin_us": 2410, "end_us": 2430, "result": 0},
        {"api": "mf_process_output", "begin_us": 2500, "end_us": 2600, "result": -1072873851}]}
    return rows


class HostFrameAnalysisTest(unittest.TestCase):
    def test_qsv_retries_and_sync_statuses_are_not_mf_hresults_or_gpu_execution(self):
        rows=measured_encoder()
        calls=[('qsv_encode_async',2100,2110,2),('qsv_busy_sleep',2110,2410,0),
               ('qsv_encode_async',2410,2420,1),('qsv_encode_async',2420,2430,0),
               ('qsv_sync_operation',2430,2500,1),('qsv_sync_operation',2500,2600,0)]
        rows[6]['encoder_work']={'backend':'qsv','discarded':0,'calls':[
            dict(api=api,begin_us=begin,end_us=end,result=status) for api,begin,end,status in calls]}
        result,frames,_,timeline=analysis.analyze(rows)
        self.assertTrue(result['complete'],result['issues'])
        self.assertEqual({'qsv':1},result['encoder_work']['samples'])
        self.assertEqual({},result['encoder_work']['hresults'])
        self.assertEqual(1,result['encoder_work']['results']['qsv_encode_async:2'])
        self.assertEqual('qsv',frames[0]['encoder_backend'])
        self.assertEqual(3,frames[0]['qsv_encode_async_calls'])
        self.assertEqual(2,frames[0]['qsv_sync_operation_calls'])
        self.assertAlmostEqual(.3,frames[0]['qsv_busy_sleep_ms'])
        self.assertNotIn('mf_get_event_per_encode_ms',result['metrics_ms'])
        spans=[r for r in timeline['traceEvents'] if r['ph']=='X' and r['name']=='qsv_encode_async']
        self.assertEqual([2,1,0],[r['args']['qsv_status'] for r in spans])
        self.assertTrue(all('mf_hresult' not in r['args'] for r in spans))
        self.assertIn('not GPU execution',result['measurement']['encoder_work'])

    def test_encoder_backend_domains_and_malformed_backend_are_rejected(self):
        for backend in ('qsv',[],{},True,'other'):
            rows=measured_encoder();rows[6]['encoder_work']['backend']=backend
            with self.subTest(backend=backend):
                result=analysis.analyze(rows)[0]
                self.assertFalse(result['complete'])
                self.assertEqual(1,result['issues']['invalid_encoder_work'])

    def test_encoder_calls_keep_invocation_identity_results_and_actual_call_spans(self):
        result, frames, _, timeline = analysis.analyze(measured_encoder())
        self.assertTrue(result["complete"], result["issues"])
        self.assertEqual({"mf": 1}, result["encoder_work"]["samples"])
        self.assertEqual(2, result["encoder_work"]["hresults"]["mf_get_event:0"])
        self.assertEqual(1, result["encoder_work"]["hresults"]["mf_process_output:-1072873851"])
        self.assertEqual(.15, result["metrics_ms"]["mf_get_event_call_ms"]["mean"])
        self.assertEqual(.3, result["metrics_ms"]["mf_get_event_per_encode_ms"]["mean"])
        self.assertAlmostEqual(.28, frames[0]["encoder_unattributed_ms"])
        self.assertEqual(4, frames[0]["encoder_calls"])
        calls = [r for r in timeline["traceEvents"] if r["name"] == "mf_process_output" and r["ph"] == "X"]
        self.assertEqual((1, 100, 1, -1072873851), (len(calls), calls[0]["dur"], calls[0]["args"]["frame"], calls[0]["args"]["mf_hresult"]))

    def test_encoder_unknown_and_measured_empty_calls_are_distinct(self):
        for declared in (False, True):
            rows = fixture()
            if declared:
                rows[0]["encoder_work_schema"] = analysis.ENCODER_WORK_SCHEMA
                rows[6]["encoder_work"] = None
            result, frames, _, _ = analysis.analyze(rows)
            self.assertTrue(result["complete"], result["issues"])
            self.assertEqual({"unknown": 1}, result["encoder_work"]["samples"])
            self.assertNotIn("mf_get_event_per_encode_ms", result["metrics_ms"])
            self.assertNotIn("encoder_unattributed_ms", frames[0])
        rows = measured_encoder()
        rows[6]["encoder_work"]["calls"] = []
        result, frames, _, _ = analysis.analyze(rows)
        self.assertTrue(result["complete"], result["issues"])
        self.assertEqual(0, frames[0]["mf_get_event_calls"])
        self.assertEqual(0, result["metrics_ms"]["mf_get_event_per_encode_ms"]["mean"])
        self.assertEqual(.7, frames[0]["encoder_unattributed_ms"])

    def test_encoder_work_rejects_missing_unknown_overlapping_outside_and_malformed_calls(self):
        mutations = [lambda w: w.update(backend="other"), lambda w: w.update(discarded=True),
                     lambda w: w.update(discarded=-1), lambda w: w.update(calls={}),
                     lambda w: w.update(calls=w["calls"] * 5), lambda w: w["calls"][0].update(api="guess"),
                     lambda w: w["calls"][0].update(begin_us=1999), lambda w: w["calls"][0].update(end_us=2701),
                     lambda w: w["calls"][1].update(begin_us=2199), lambda w: w["calls"][0].update(result=True),
                     lambda w: w["calls"][0].update(result=0x80000000), lambda w: w["calls"][0].pop("end_us")]
        for mutate in mutations:
            rows = measured_encoder()
            mutate(rows[6]["encoder_work"])
            with self.subTest(mutate=mutate):
                result, _, _, _ = analysis.analyze(rows)
                self.assertFalse(result["complete"])
                self.assertEqual(1, result["issues"]["invalid_encoder_work"])
        rows = measured_encoder()
        del rows[6]["encoder_work"]
        self.assertEqual(1, analysis.analyze(rows)[0]["issues"]["invalid_encoder_work"])
        rows = fixture()
        rows[4]["encoder_work"] = None
        self.assertEqual(1, analysis.analyze(rows)[0]["issues"]["invalid_encoder_work"])
        rows = measured_encoder()
        rows[0]["encoder_work_schema"] = "future"
        with self.assertRaisesRegex(ValueError, "encoder work schema"):
            analysis.analyze(rows)

    def test_encoder_api_overflow_is_incomplete_and_does_not_report_unattributed_time(self):
        rows = measured_encoder()
        rows[6]["encoder_work"]["discarded"] = 2
        result, frames, _, _ = analysis.analyze(rows)
        self.assertFalse(result["complete"])
        self.assertEqual(2, result["issues"]["encoder_work_overflow"])
        self.assertNotIn("encoder_unattributed_ms", frames[0])

    def test_encoder_work_survives_export_and_invalid_spans_keep_original_raw(self):
        import csv
        with tempfile.TemporaryDirectory() as temporary:
            raw = Path(temporary) / "host-1" / "frames.jsonl"
            raw.parent.mkdir()
            rows = measured_encoder()
            raw.write_text("\n".join(json.dumps(r) for r in rows) + "\n", encoding="utf-8")
            result, output = analysis.export(raw)
            self.assertTrue(result["raw_deleted"])
            with (output / "frames.csv").open(newline="", encoding="utf-8") as stream:
                frame = next(csv.DictReader(stream))
            self.assertEqual("mf", frame["encoder_backend"])
            self.assertEqual("2", frame["mf_get_event_calls"])
            self.assertAlmostEqual(.3, float(frame["mf_get_event_ms"]))
            rows[6]["encoder_work"]["calls"][0]["begin_us"] = -1
            raw.write_text("\n".join(json.dumps(r) for r in rows) + "\n", encoding="utf-8")
            result, _ = analysis.export(raw)
            self.assertFalse(result["complete"])
            self.assertTrue(raw.exists())

    def test_capture_work_exposes_map_pool_and_delivery_without_inventing_cursor_work(self):
        rows = measured_capture()
        result, _, _, timeline = analysis.analyze(rows)
        self.assertTrue(result["complete"], result["issues"])
        self.assertEqual({"dxgi_ram": 1}, result["capture_work"]["samples"])
        for name, expected in {"snapshot": .8, "acquire": .1, "prepare": .08, "image_pool": .02,
                               "map": .4, "image_setup": .005, "cpu_copy": .06, "unmap": .01,
                               "callback_delivery": .1, "unattributed": .125}.items():
            self.assertAlmostEqual(expected, result["metrics_ms"]["capture_" + name + "_ms"]["mean"])
        self.assertNotIn("capture_cursor_ms", result["metrics_ms"])
        maps = [e for e in timeline["traceEvents"] if e["name"] == "capture_map" and e["ph"] == "X"]
        self.assertEqual((1, 400, 10), (len(maps), maps[0]["dur"], maps[0]["args"]["source"]))
        self.assertTrue(all(e.get("ts", 0) >= 0 for e in timeline["traceEvents"]))

    def test_capture_work_missing_legacy_and_explicit_unknown_are_distinct_from_measured_zero(self):
        for declared in (False, True):
            rows = fixture()
            if declared:
                rows[0]["capture_work_schema"] = analysis.CAPTURE_WORK_SCHEMA
                rows[1]["capture_work"] = None
            result, _, _, _ = analysis.analyze(rows)
            self.assertTrue(result["complete"], result["issues"])
            self.assertEqual({"unknown": 1}, result["capture_work"]["samples"])
            self.assertNotIn("capture_map_ms", result["metrics_ms"])
        rows = measured_capture()
        del rows[1]["capture_work"]
        result, _, _, _ = analysis.analyze(rows)
        self.assertFalse(result["complete"])
        self.assertEqual(1, result["issues"]["invalid_capture_work"])

    def test_capture_work_rejects_malformed_overlapping_outside_and_incomplete_readback(self):
        changes = [lambda w: w.update(backend="unmeasured"),
                   lambda w: w.update(begin_us=True), lambda w: w.update(end_us=1001),
                   lambda w: w.update(steps=[]), lambda w: w["steps"].pop("cursor"),
                   lambda w: w["steps"].update(acquire=None),
                   lambda w: w["steps"]["map"].update(begin_us=100),
                   lambda w: w["steps"]["map"].update(end_us=200),
                   lambda w: w["steps"]["map"].update(end_us=1001),
                   lambda w: w["steps"].update(map=None),
                   lambda w: w["steps"].update(cpu_copy=None)]
        for change in changes:
            rows = measured_capture()
            change(rows[1]["capture_work"])
            with self.subTest(change=change):
                result, _, _, _ = analysis.analyze(rows)
                self.assertFalse(result["complete"])
                self.assertEqual(1, result["issues"]["invalid_capture_work"])
        rows = measured_capture()
        rows[0]["capture_work_schema"] = "rhremote.capture-work/future"
        with self.assertRaisesRegex(ValueError, "capture work schema"):
            analysis.analyze(rows)

    def test_capture_work_export_preserves_samples_before_removing_only_complete_raw(self):
        import csv
        with tempfile.TemporaryDirectory() as temporary:
            raw = Path(temporary) / "host-1" / "frames.jsonl"
            raw.parent.mkdir()
            raw.write_text("\n".join(json.dumps(r) for r in measured_capture()) + "\n", encoding="utf-8")
            result, output = analysis.export(raw)
            self.assertTrue(result["raw_deleted"])
            with (output / "captures.csv").open(newline="", encoding="utf-8") as stream:
                captures = list(csv.DictReader(stream))
            self.assertEqual(1, len(captures))
            self.assertEqual("dxgi_ram", captures[0]["work_backend"])
            self.assertEqual("0.4", captures[0]["map_ms"])
            self.assertEqual("", captures[0]["cursor_ms"])
            self.assertEqual("340", captures[0]["map_begin_us"])
            # A malformed span preserves raw evidence and cannot be reported complete.
            rows = measured_capture()
            rows[1]["capture_work"]["steps"]["map"]["end_us"] = -1
            raw.write_text("\n".join(json.dumps(r) for r in rows) + "\n", encoding="utf-8")
            result, _ = analysis.export(raw)
            self.assertFalse(result["complete"])
            self.assertTrue(raw.exists())

    def test_capture_work_export_failure_retains_the_original(self):
        with tempfile.TemporaryDirectory() as temporary:
            raw = Path(temporary) / "host-1" / "frames.jsonl"
            raw.parent.mkdir()
            raw.write_text("\n".join(json.dumps(r) for r in measured_capture()) + "\n", encoding="utf-8")
            original = analysis.write_csv
            def fail_capture_table(path, columns, rows):
                if path.name == "captures.csv":
                    raise OSError("capture export failed")
                return original(path, columns, rows)
            with patch.object(analysis, "write_csv", side_effect=fail_capture_table):
                with self.assertRaisesRegex(OSError, "capture export failed"):
                    analysis.export(raw)
            self.assertTrue(raw.exists())

    def test_real_stage_boundaries_and_known_zero_source_timestamp(self):
        result, frames, packets, timeline = analysis.analyze(fixture())
        self.assertTrue(result["complete"], result["issues"])
        self.assertEqual((1, 1), (result["frame_count"], result["packet_count"]))
        expected = {"source_to_capture_ms": 1., "convert_ms": .4, "capture_to_convert_ms": .1,
                    "encode_call_ms": .7, "submit_to_output_ms": .5, "encode_queue_ms": .5,
                    "send_span_ms": 1., "fec_ms": .1, "pacing_ms": .2, "send_call_ms": .3,
                    "capture_to_send_call_return_ms": 3., "source_to_send_call_return_ms": 4.}
        for key, value in expected.items():
            self.assertEqual(value, result["metrics_ms"][key]["mean"], key)
        self.assertEqual(0, packets[0]["rtp_timestamp"])
        self.assertEqual(160, packets[0]["udp_payload_bytes"])
        self.assertEqual(.7, frames[0]["encode_call_ms"])
        self.assertEqual(9 + 4 + 8, len(timeline["traceEvents"]))
        self.assertTrue(all(type(e["pid"]) is int and type(e["tid"]) is int for e in timeline["traceEvents"]))
        self.assertFalse(result["measurement"]["network_latency_measured"])
        self.assertFalse(result["measurement"]["physical_scanout_measured"])

    def test_out_of_order_pts_and_multiple_packets_keep_original_input(self):
        rows = fixture()[:-1]
        for row in fixture()[2:-1]:
            if row["event"].startswith("convert_"):
                continue
            shifted = dict(row, frame=2, at_us=row["at_us"] + 10000, fresh=False,
                           packet=2 if row["packet"] else 0)
            rows.append(shifted)
        # A delayed second packet of PTS 1 appears after input/output of PTS 2.
        for row in fixture()[5:9]:
            if row["event"] == "encode_end":
                continue
            rows.append(dict(row, packet=3, at_us=row["at_us"] + 15000))
        rows.append(footer())
        result, frames, packets, _ = analysis.analyze(rows)
        self.assertTrue(result["complete"], result["issues"])
        self.assertEqual((2, 3, 1, 1), (result["frame_count"], result["packet_count"],
                                      result["fresh_submissions"], result["repeat_or_priming_submissions"]))
        self.assertEqual(2, frames[0]["packet_count"])
        self.assertEqual(1, packets[2]["frame"])
        self.assertEqual(10, packets[2]["source"])
        self.assertEqual(15.5, packets[2]["submit_to_output_ms"])

    def test_generation_and_trace_id_isolate_frame_one(self):
        rows = fixture()[:-1]
        rows.extend(dict(row, generation=4, at_us=row["at_us"] + 10000,
                         packet=2 if row["packet"] else 0) for row in fixture()[2:-1])
        rows.append(footer())
        result, frames, _, _ = analysis.analyze(rows)
        self.assertTrue(result["complete"], result["issues"])
        self.assertEqual([3, 4], [f["generation"] for f in frames])
        other = [dict(row, trace_id="host-2") if "trace_id" in row else row for row in fixture()]
        self.assertEqual("host-2", analysis.analyze(other)[0]["trace_id"])
        contaminated = fixture()
        contaminated.insert(-1, other[5])
        self.assertEqual(1, analysis.analyze(contaminated)[0]["issues"]["mixed_trace_identity"])

    def test_unknown_source_time_never_invents_source_latency(self):
        rows = [dict(row, source_time_known=False) if "source_time_known" in row else row for row in fixture()]
        result, _, packets, _ = analysis.analyze(rows)
        self.assertTrue(result["complete"], result["issues"])
        self.assertNotIn("source_to_capture_ms", result["metrics_ms"])
        self.assertNotIn("source_to_send_call_return_ms", packets[0])
        self.assertEqual(3., packets[0]["capture_to_send_call_return_ms"])

    def test_priming_input_has_no_fake_capture(self):
        rows = fixture()
        del rows[1]
        for row in rows:
            if "source" in row:
                row.update(source=0, fresh=False, source_time_known=False, capture_ready_us=0)
        result, _, packets, _ = analysis.analyze(rows)
        self.assertTrue(result["complete"], result["issues"])
        self.assertNotIn("capture_to_send_call_return_ms", packets[0])
        self.assertEqual(1, result["repeat_or_priming_submissions"])

    def test_each_missing_boundary_is_incomplete(self):
        for name in analysis.STAGES - {"metadata_missing", "metadata_evicted", "send_error", "input_abandoned", "submission_abandoned"}:
            with self.subTest(name=name):
                rows = [r for r in fixture() if r["event"] != name]
                self.assertFalse(analysis.analyze(rows)[0]["complete"])
        rows = [r for r in fixture() if not r["event"].startswith("convert_")]
        self.assertIn("fresh_input_without_conversion", analysis.analyze(rows)[0]["issues"])

    def test_explicit_unsubmitted_conversion_is_not_an_encoded_frame(self):
        rows = fixture()[:-1]
        context = dict(frame=2, source=11, capture_ready_us=5000, source_present_us=4500)
        rows.extend([event("capture_ready", 5000, **dict(context, frame=-1, generation=0)),
                     event("convert_begin", 5100, **context), event("convert_end", 6000, **context),
                     event("input_abandoned", 6200, result=2, **context), footer()])
        summary, frames, packets, _ = analysis.analyze(rows)
        self.assertTrue(summary["complete"], summary["issues"])
        self.assertEqual((1, 2, 1), (summary["frame_count"], summary["work_item_count"], summary["abandoned_input_count"]))
        self.assertEqual(1, summary["fresh_submissions"])
        self.assertEqual(1, len(packets))
        self.assertEqual("encoder_released_before_submit", frames[1]["abandon_reason"])
        self.assertNotIn("encode_call_ms", frames[1])
        # A footer alone must never guess that the same unsubmitted conversion was cancelled.
        without_terminal = [row for row in rows if row["event"] != "input_abandoned"]
        self.assertIn("missing_or_duplicate_encode_boundary", analysis.analyze(without_terminal)[0]["issues"])
        for bad in (dict(result=0), dict(result=99), dict(packet=4), dict(metadata_known=False), dict(source=99)):
            invalid = copy.deepcopy(rows)
            invalid[-2].update(bad)
            self.assertFalse(analysis.analyze(invalid)[0]["complete"], bad)
        invalid = copy.deepcopy(rows)
        invalid.insert(-1, event("encode_begin", 6300, **context))
        self.assertIn("invalid_abandoned_input", analysis.analyze(invalid)[0]["issues"])
        missing_conversion = [row for row in rows if row["event"] not in ("convert_begin", "convert_end") or row["frame"] == 1]
        self.assertIn("abandoned_without_matching_conversion", analysis.analyze(missing_conversion)[0]["issues"])

    def submitted_tail(self, fresh=True):
        rows = fixture()[:-1]
        context = dict(frame=2, source=11, fresh=fresh, capture_ready_us=5000, source_present_us=4500)
        rows.extend([event("capture_ready", 5000, **dict(context, frame=-1, generation=0)),
                     event("convert_begin", 5100, **context), event("convert_end", 5500, **context),
                     event("encode_begin", 5600, **context), event("encode_end", 6000, **context),
                     event("submission_abandoned", 6200, result=2, **context), footer()])
        return rows

    def test_explicit_submitted_release_preserves_input_counts_and_encode_work(self):
        for fresh in (True, False):
            summary, frames, packets, timeline = analysis.analyze(self.submitted_tail(fresh))
            self.assertTrue(summary["complete"], summary["issues"])
            self.assertEqual((2, 2, 0, 1), (summary["frame_count"], summary["work_item_count"],
                                             summary["abandoned_input_count"], summary["abandoned_submission_count"]))
            self.assertEqual((2 if fresh else 1, 0 if fresh else 1),
                             (summary["fresh_submissions"], summary["repeat_or_priming_submissions"]))
            self.assertEqual(1, len(packets))
            self.assertEqual("abandoned_after_submit", frames[1]["outcome"])
            self.assertEqual("encoder_released_without_stream_output", frames[1]["abandon_reason"])
            self.assertEqual(.4, frames[1]["encode_call_ms"])
            self.assertEqual(0, frames[1]["packet_count"])
            events = [e for e in timeline["traceEvents"] if e["name"] == "submission_abandoned"]
            self.assertEqual(1, len(events))
            self.assertEqual(8, events[0]["tid"])

    def test_submitted_tail_without_an_actual_terminal_stays_incomplete(self):
        rows = [r for r in self.submitted_tail() if r["event"] != "submission_abandoned"]
        summary, frames, _, _ = analysis.analyze(rows)
        self.assertFalse(summary["complete"])
        self.assertEqual(1, summary["issues"]["submission_without_output"])
        self.assertEqual(0, summary["abandoned_submission_count"])
        self.assertEqual("submitted", frames[1]["outcome"])

    def test_submitted_terminal_rejects_corruption_and_contradictory_output(self):
        for change in ({"result": 0}, {"result": 1}, {"packet": 9}, {"source": 99},
                       {"fresh": False}, {"metadata_known": False}, {"source_time_known": False},
                       {"source_present_us": 4400}, {"capture_ready_us": 4999}, {"at_us": 5999}):
            rows = self.submitted_tail()
            rows[-2].update(change)
            with self.subTest(change=change):
                result = analysis.analyze(rows)[0]
                self.assertFalse(result["complete"])
                self.assertIn("invalid_abandoned_submission", result["issues"])
                self.assertIn("submission_without_output", result["issues"])
        rows = self.submitted_tail()
        rows.insert(-1, dict(rows[-2], at_us=6201))
        self.assertIn("invalid_abandoned_submission", analysis.analyze(rows)[0]["issues"])
        for stage in ("encode_output", "send_begin", "send_end", "send_error", "input_abandoned"):
            rows = self.submitted_tail()
            rows.insert(-2, dict(rows[-2], event=stage, at_us=6100, packet=2 if stage != "input_abandoned" else 0))
            self.assertIn("invalid_abandoned_submission", analysis.analyze(rows)[0]["issues"])

    def test_submitted_terminal_cannot_hide_errors_missing_boundaries_or_eviction(self):
        for missing in ("encode_begin", "encode_end"):
            rows = [r for r in self.submitted_tail() if r["event"] != missing or r.get("frame") != 2]
            summary = analysis.analyze(rows)[0]
            self.assertFalse(summary["complete"])
            self.assertIn("invalid_abandoned_submission", summary["issues"])
            self.assertIn("missing_or_duplicate_encode_boundary", summary["issues"])
        rows = self.submitted_tail()
        rows[-3]["result"] = -5
        self.assertIn("encode_error", analysis.analyze(rows)[0]["issues"])
        rows = self.submitted_tail()
        rows.insert(-2, dict(rows[-2], event="metadata_evicted", at_us=6100, result=1))
        self.assertIn("metadata_evicted", analysis.analyze(rows)[0]["issues"])
        rows = self.submitted_tail()
        rows[-3]["source"] = 99
        self.assertIn("invalid_abandoned_submission", analysis.analyze(rows)[0]["issues"])

    def test_submitted_release_export_preserves_terminal_before_raw_cleanup(self):
        import csv
        with tempfile.TemporaryDirectory() as temporary:
            raw = Path(temporary) / "host-1" / "frames.jsonl"
            raw.parent.mkdir()
            raw.write_text("\n".join(json.dumps(r) for r in self.submitted_tail()) + "\n", encoding="utf-8")
            summary, output = analysis.export(raw)
            self.assertTrue(summary["complete"], summary["issues"])
            self.assertTrue(summary["raw_deleted"])
            with (output / "frames.csv").open(newline="", encoding="utf-8") as stream:
                frames = list(csv.DictReader(stream))
            self.assertEqual("abandoned_after_submit", frames[1]["outcome"])
            self.assertEqual("encoder_released_without_stream_output", frames[1]["abandon_reason"])
            self.assertEqual("6200", frames[1]["abandoned_us"])

    def test_footers_loss_cap_failure_and_records_after_footer(self):
        for change in ({"lost_events": 9}, {"size_limit_reached": True}, {"write_failed": True},
                       {"lost_events": "0"}, {"at_us": 1}):
            rows = fixture()
            rows[-1].update(change)
            self.assertFalse(analysis.analyze(rows)[0]["complete"], change)
        self.assertIn("missing_footer", analysis.analyze(fixture()[:-1])[0]["issues"])
        self.assertIn("duplicate_footer", analysis.analyze(fixture() + [footer()])[0]["issues"])
        self.assertIn("events_after_footer", analysis.analyze(fixture() + [fixture()[1]])[0]["issues"])

    def test_unresolved_or_evicted_metadata_and_errors_are_explicit(self):
        rows = fixture()
        rows.insert(6, event("metadata_missing", 2600, result=1))
        rows.insert(7, event("metadata_evicted", 2601, result=1))
        for row in rows:
            if row.get("packet"):
                row["metadata_known"] = False
        result, _, packets, _ = analysis.analyze(rows)
        self.assertFalse(result["complete"])
        self.assertEqual(1, result["issues"]["metadata_missing"])
        self.assertEqual(1, result["issues"]["metadata_evicted"])
        self.assertEqual(1, result["issues"]["unknown_packet_metadata"])
        self.assertNotIn("submit_to_output_ms", packets[0])
        for name in ("convert_end", "encode_end", "send_error"):
            bad = fixture()
            target = next(r for r in bad if r["event"] == ("send_end" if name == "send_error" else name))
            target.update(event=name, result=-1)
            result, _, packets, _ = analysis.analyze(bad)
            self.assertFalse(result["complete"], name)
            if name == "send_error":
                self.assertNotIn("source_to_send_call_return_ms", packets[0])

    def test_duplicate_packet_and_context_mismatch(self):
        for mode in ("duplicate", "frame", "source", "submission_source"):
            rows = fixture()
            if mode == "duplicate":
                rows.insert(6, copy.deepcopy(rows[5]))
            elif mode == "frame":
                rows[8]["frame"] = 2
            elif mode == "source":
                rows[8]["source"] = 99
            else:
                rows[4]["source"] = 99
            self.assertFalse(analysis.analyze(rows)[0]["complete"], mode)

    def test_invalid_schema_types_unknown_events_and_negative_durations(self):
        for field, value in (("schema", "future"), ("clocks_synchronized", True),
                             ("trace_id", "../outside"), ("steady_origin_us", True)):
            rows = fixture()
            rows[0][field] = value
            with self.assertRaises(ValueError):
                analysis.analyze(rows)
        for changes in ({"at_us": True}, {"metadata_known": 1}, {"generation": 0},
                        {"frame": 0}, {"packet": 0}, {"rtp_timestamp": 2**32}, {"event": "future"}):
            rows = fixture()
            rows[5].update(changes)
            self.assertFalse(analysis.analyze(rows)[0]["complete"], changes)
        rows = fixture()
        rows[5]["at_us"] = 1900
        self.assertIn("negative_submit_to_output_ms", analysis.analyze(rows)[0]["issues"])

    def write(self, root, rows=None, suffix=""):
        path = Path(root) / "host-1" / "frames.jsonl"
        path.parent.mkdir(parents=True)
        path.write_text("\n".join(json.dumps(r) for r in (rows if rows is not None else fixture())) + "\n" + suffix,
                        encoding="utf-8")
        return path

    def test_bounded_parser_retains_truncated_and_malformed_evidence(self):
        with tempfile.TemporaryDirectory(prefix="rhremote-host-analyzer-") as root:
            raw = self.write(root, suffix='{"event":')
            rows, issues, identity = analysis.load(raw)
            self.assertEqual(1, issues["malformed_line"])
            self.assertEqual(1, issues["unterminated_line"])
            self.assertEqual(raw.stat().st_size, identity["bytes"])
            self.assertFalse(analysis.analyze(rows, issues)[0]["complete"])
            for bounds in ({"max_bytes": 5}, {"max_line": 16}, {"max_events": 1}):
                with self.assertRaises(ValueError):
                    analysis.load(raw, **bounds)

    def test_export_saves_complete_evidence_before_removing_only_own_raw_file(self):
        with tempfile.TemporaryDirectory(prefix="rhremote-host-analyzer-") as root:
            raw = self.write(root)
            sentinel = raw.parent / "other.jsonl"
            sentinel.write_text("leave me")
            result, output = analysis.export(raw)
            self.assertTrue(result["raw_deleted"])
            self.assertFalse(raw.exists())
            self.assertTrue(sentinel.exists())
            for name in ("summary.json", "frames.csv", "packets.csv", "timeline.json"):
                self.assertTrue((output / name).is_file())
            self.assertTrue(json.loads((output / "summary.json").read_text())["raw_deleted"])

    def test_incomplete_requested_and_unmanaged_inputs_remain(self):
        for kind in ("incomplete", "keep", "unmanaged"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory(prefix="rhremote-host-analyzer-") as root:
                raw = self.write(root, fixture()[:-1] if kind == "incomplete" else fixture())
                if kind == "unmanaged":
                    target = raw.with_name("capture.jsonl")
                    raw.rename(target)
                    raw = target
                result, _ = analysis.export(raw, keep_raw=kind == "keep")
                self.assertFalse(result["raw_deleted"])
                self.assertTrue(raw.exists())

    def test_export_failure_or_new_data_never_deletes_raw(self):
        with tempfile.TemporaryDirectory(prefix="rhremote-host-analyzer-") as root:
            raw = self.write(root)
            with patch.object(analysis, "write_csv", side_effect=OSError("disk full")):
                with self.assertRaises(OSError):
                    analysis.export(raw)
            self.assertTrue(raw.exists())
            real_write = analysis.atomic_write
            def changed(path, writer):
                real_write(path, writer)
                if path.name == "timeline.json":
                    with raw.open("a") as stream:
                        stream.write("new data\n")
            with patch.object(analysis, "atomic_write", changed):
                with self.assertRaisesRegex(ValueError, "grew"):
                    analysis.export(raw)
            self.assertTrue(raw.exists())

    def test_symlink_paths_and_outputs_rejected(self):
        with tempfile.TemporaryDirectory(prefix="rhremote-host-analyzer-") as root:
            raw = self.write(root)
            # Mock only filesystem symlink detection; Windows CI may lack symlink privilege.
            original = Path.is_symlink
            with patch.object(Path, "is_symlink", lambda path: path == raw or original(path)):
                with self.assertRaisesRegex(ValueError, "symlinks"):
                    analysis.export(raw)
            self.assertTrue(raw.exists())

    def test_client_report_requires_matching_run_before_any_raw_cleanup(self):
        spec = importlib.util.spec_from_file_location("client_analysis", Path(__file__).with_name("analyze-statistics.py"))
        client = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(client)
        with tempfile.TemporaryDirectory(prefix="rhremote-host-analyzer-") as directory:
            root = Path(directory)
            raw = self.write(root)
            wrong = root / "another-client"
            wrong.mkdir()
            with self.assertRaisesRegex(ValueError, "different client"):
                client.attach_host_frames({}, [], wrong, [raw])
            self.assertTrue(raw.exists())
            run = root / "test-client-1"
            run.mkdir()
            result = {}
            client.attach_host_frames(result, [{"frame": 1, "events": {"frame_assembled": 999999999}}], run, [raw])
            report = result["host_frame_traces"][0]
            self.assertEqual(1, report["client_frame_number_overlap"]["unambiguous"])
            self.assertNotIn("network_ms", report["metrics_ms"])
            self.assertFalse(raw.exists())
            self.assertTrue((run / report["artifacts"] / "timeline.json").is_file())

    def test_client_refresh_preserves_attached_independent_host_report(self):
        import subprocess
        import sys
        spec = importlib.util.spec_from_file_location("client_analysis", Path(__file__).with_name("analyze-statistics.py"))
        client = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(client)
        with tempfile.TemporaryDirectory(prefix="rhremote-host-analyzer-") as directory:
            root = Path(directory)
            raw = self.write(root)
            run = root / "test-client-1"
            run.mkdir()
            manifest = {"schema": "rhremote.statistics/v1", "platform": "windows", "files": ["native.jsonl"]}
            (run / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            rows = [{"event": name, "ts_us": at, "frame": frame, "fields": {}} for name, at, frame in (
                ("connect_action", 1000000, -1), ("frame_assembled", 1002000, 1),
                ("render_return", 1003000, 1), ("trace_end", 1004000, -1))]
            (run / "native.jsonl").write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
            command = [sys.executable, str(Path(client.__file__)), str(run)]
            subprocess.run(command + ["--host-frame-trace", str(raw)], check=True, capture_output=True)
            first = json.loads((run / "summary.json").read_text(encoding="utf-8"))
            self.assertFalse((run / "native.jsonl").exists())
            subprocess.run(command + ["--refresh-report"], check=True, capture_output=True)
            refreshed = json.loads((run / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(first["host_frame_traces"], refreshed["host_frame_traces"])
            self.assertEqual(3., refreshed["connection_measurement"]["total_ms"])

    def test_launch_extension_patch_is_repeatable_and_covers_shared_launch_resume_method(self):
        spec = importlib.util.spec_from_file_location("client_patch", Path(__file__).with_name("patch-frame-trace.py"))
        patcher = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(patcher)
        with tempfile.TemporaryDirectory(prefix="rhremote-host-analyzer-") as directory:
            root = Path(directory)
            windows = root / "app/backend/nvhttp.cpp"
            windows.parent.mkdir(parents=True)
            windows.write_text('    QString rhInitialDisplay;\nRhDisplayLaunch::query(rhInitialDisplay)+LiGetLaunchUrlQueryParameters()\n    const auto rhAck=getXmlString(response,"RHInitialDisplay");', encoding="utf-8")
            patcher.windows(root)
            once = windows.read_bytes()
            patcher.windows(root)
            self.assertEqual(once, windows.read_bytes())
            self.assertIn(b"rhFrameTraceQuery+RhDisplayLaunch", once)
            self.assertIn(b'getXmlString(response,"RHFrameTrace")', once)
            android = root / "app/src/main/java/com/limelight/nvstream/http/NvHTTP.java"
            android.parent.mkdir(parents=True)
            android.write_text('        String rhDisplayQuery=rhInitialDisplay==null?"":rhInitialDisplay.launchQuery();\nrhDisplayQuery + MoonBridge.getLaunchUrlQueryParameters()\n            if(rhInitialDisplay!=null)rhInitialDisplay.acknowledgeInitial(getXmlString(xmlStr,"RHInitialDisplay",false));', encoding="utf-8")
            patcher.android(root)
            once = android.read_bytes()
            patcher.android(root)
            self.assertEqual(once, android.read_bytes())
            self.assertIn(b'getXmlString(xmlStr,"RHFrameTrace",false)', once)
            windows.write_text("missing upstream anchor", encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "anchor missing"):
                patcher.windows(root)


if __name__ == "__main__":
    unittest.main()
