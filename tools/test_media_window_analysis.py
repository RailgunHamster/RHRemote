"""Measured windows retain whole-file defects and never subtract PC clocks."""
import copy
import importlib.util
from pathlib import Path
import unittest


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


analysis = module("media_window", "analyze-media-window.py")
fixtures = module("host_fixtures", "test_host_frame_analysis.py")


def case_and_frames():
    rows = fixtures.fixture()[:-1]
    for row in fixtures.fixture()[1:-1]:
        extra = dict(row, at_us=row["at_us"] + 10000, source=11,
                     frame=2 if row["frame"] > 0 else -1,
                     packet=2 if row["packet"] else 0,
                     capture_ready_us=row["capture_ready_us"] + 10000,
                     source_present_us=row["source_present_us"] + 10000)
        rows.append(extra)
    rows.append(dict(fixtures.footer(), at_us=40000))
    events = []
    for frame in (1, 2):
        for stage, delta in (("packet_first", 0), ("frame_assembled", 100), ("decode_submit", 200),
                             ("decode_submit_return", 250), ("decode_output", 600),
                             ("render_submit", 800), ("render_return", 900)):
            events.append(dict(frame=frame, event=stage, ts_us=2000000 + frame * 10000 + delta,
                               fields={"bytes": 100} if stage == "frame_assembled" else {}))
    return {"measurementStartUs": 2000000, "measurementEndUs": 2100000}, events, rows


class MediaWindowAnalysisTest(unittest.TestCase):
    def test_qsv_call_profiles_retain_backend_domain_in_the_aligned_window(self):
        case,events,rows=case_and_frames()
        rows[0]['encoder_work_schema']=analysis.host.ENCODER_WORK_SCHEMA
        begins={(r['generation'],r['frame']):r['at_us'] for r in rows if r.get('event')=='encode_begin'}
        for row in rows:
            if row.get('event')=='encode_end':
                begin=begins[(row['generation'],row['frame'])]
                row['encoder_work']={'backend':'qsv','discarded':0,'calls':[
                    dict(api='qsv_encode_async',begin_us=begin+10,end_us=begin+30,result=0),
                    dict(api='qsv_sync_operation',begin_us=begin+30,end_us=row['at_us']-10,result=0)]}
        result=analysis.analyze(case,events,rows)
        self.assertTrue(result['window_host_complete'],result['window_host_issues'])
        self.assertEqual(2,result['host_metrics_ms']['qsv_sync_operation_per_encode_ms']['count'])
        self.assertNotIn('mf_get_event_per_encode_ms',result['host_metrics_ms'])
        next(r for r in rows if r.get('event')=='encode_end')['encoder_work']['discarded']=1
        result=analysis.analyze(case,events,rows)
        self.assertFalse(result['window_host_complete'])
        self.assertFalse(result['whole_host_trace_complete'])

    def test_direct_clock_requires_actual_contract_on_every_measured_frame(self):
        case,events,rows=case_and_frames()
        contract={"contract":"rh-cml-frame-clock-v1","clock_domain":"rhstatistics","unit":"us"}
        for point in events:
            if point["event"] in ("packet_first","frame_assembled"):point["fields"].update(contract)
        marker=dict(event="frame_clock_contract",frame=-1,ts_us=1,fields=dict(contract,receive_site="udp_recv_return",assembly_site="decode_unit_ready"))
        events.insert(0,marker)
        self.assertTrue(analysis.analyze(case,events,rows)["measurement"]["receive_assembly_high_resolution_proven"])
        next(p for p in events if p["event"]=="packet_first")["fields"].pop("contract")
        self.assertFalse(analysis.analyze(case,events,rows)["measurement"]["receive_assembly_high_resolution_proven"])

    def test_source_health_is_recomputed_and_not_implied_by_complete_files_or_pass(self):
        case, events, rows = case_and_frames()
        case.update(result="PASS", sourcePerformanceValid=True,
                    sourceBefore={"phase": "playing", "ended": False, "elapsed_s": 10., "media_time_s": 35., "player_dropped_frames": 5, "source_file_fps": 30},
                    sourceAfter={"phase": "playing", "ended": False, "elapsed_s": 110., "media_time_s": 135., "player_dropped_frames": 65})
        result = analysis.analyze(case, events, rows)
        self.assertTrue(result["whole_host_trace_complete"])
        self.assertFalse(result["source"]["comparison_eligible"])
        self.assertEqual(.02, result["source"]["player_dropped_fraction"])
        case["sourceAfter"]["player_dropped_frames"] = 5
        self.assertTrue(analysis.analyze(case, events, rows)["source"]["comparison_eligible"])
        case["sourceAfter"]["media_time_s"] = 115.
        self.assertFalse(analysis.analyze(case, events, rows)["source"]["comparison_eligible"])

    def test_perfect_partial_source_cannot_clear_a_longer_client_window(self):
        case, events, rows = case_and_frames()
        case.update(sourceBefore={"phase": "playing", "ended": False, "elapsed_s": 1., "media_time_s": 35.,
                                  "player_dropped_frames": 0, "source_file_fps": 30},
                    sourceAfter={"phase": "playing", "ended": False, "elapsed_s": 1.05,
                                 "media_time_s": 35.05, "player_dropped_frames": 0})
        result = analysis.analyze(case, events, rows)
        self.assertTrue(result["window_host_complete"])
        self.assertFalse(result["source"]["scope_covers_client_window"])
        self.assertFalse(result["source"]["comparison_eligible"])

    def test_ended_failed_or_unconfirmed_source_cannot_clear_good_clock_and_drop_counters(self):
        for patch in ({"phase": "ended"}, {"ended": True}, {"error": "Viewport changed"},
                      {"phase": None}, {"ended": None}):
            case, events, rows = case_and_frames()
            case.update(sourceBefore={"phase": "playing", "ended": False, "elapsed_s": 1., "media_time_s": 35.,
                                      "player_dropped_frames": 0, "source_file_fps": 30},
                        sourceAfter={"phase": "playing", "ended": False, "elapsed_s": 2.,
                                     "media_time_s": 36., "player_dropped_frames": 0, **patch})
            result = analysis.analyze(case, events, rows)
            self.assertTrue(result["source"]["scope_covers_client_window"])
            self.assertFalse(result["source"]["controller_healthy_at_endpoints"])
            self.assertFalse(result["source"]["comparison_eligible"])

    def test_missing_source_observations_and_legacy_native_clock_remain_unproven(self):
        case, events, rows = case_and_frames()
        result = analysis.analyze(case, events, rows)
        self.assertIsNone(result["source"]["comparison_eligible"])
        self.assertFalse(result["measurement"]["receive_assembly_high_resolution_proven"])
        self.assertIn("millisecond-clock backprojections", result["measurement"]["receive_assembly_timing"])

    def test_clocks_can_move_independently_without_changing_stage_or_byte_metrics(self):
        case, events, rows = case_and_frames()
        expected = analysis.analyze(case, events, rows)
        changed = copy.deepcopy(rows)
        for row in changed:
            for key in ("at_us", "capture_ready_us", "source_present_us"):
                if key in row:
                    row[key] += 987654321
        case = {key: value + 4000000000 for key, value in case.items()}
        events = [dict(e, ts_us=e["ts_us"] + 4000000000) for e in events]
        actual = analysis.analyze(case, events, changed)
        self.assertTrue(actual["window_host_complete"], actual["window_host_issues"])
        for key in ("host_metrics_ms", "client_metrics_ms", "client_cadence", "host_sender_cadence",
                    "host_encoded_mbps", "host_udp_payload_mbps", "client_encoded_payload_mbps"):
            self.assertEqual(expected[key], actual[key], key)
        self.assertFalse(actual["measurement"]["absolute_network_latency_measured"])

    def test_unresolved_tail_is_reported_without_invalidating_a_complete_earlier_window(self):
        case, events, rows = case_and_frames()
        context = dict(frame=3, source=12, capture_ready_us=21000, source_present_us=20000)
        rows[-1:-1] = [fixtures.event("capture_ready", 21000, **dict(context, frame=-1, generation=0)),
                       fixtures.event("convert_begin", 21100, **context),
                       fixtures.event("convert_end", 21500, **context)]
        result = analysis.analyze(case, events, rows)
        self.assertFalse(result["whole_host_trace_complete"])
        self.assertIn("missing_or_duplicate_encode_boundary", result["whole_host_trace_issues"])
        self.assertTrue(result["window_host_complete"], result["window_host_issues"])
        self.assertEqual(2, result["host_submissions"])
        self.assertEqual(1.6, result["udp_payload_to_encoded_ratio"])

    def test_recording_loss_and_malformed_lines_cannot_be_hidden_outside_the_window(self):
        case, events, rows = case_and_frames()
        rows[-1]["lost_events"] = 3
        result = analysis.analyze(case, events, rows, {"malformed_line": 1})
        self.assertFalse(result["window_host_complete"])
        self.assertEqual(3, result["whole_file_integrity_issues"]["recorder_lost_events"])
        self.assertEqual(1, result["whole_file_integrity_issues"]["malformed_line"])

    def test_invalid_capture_work_outside_selected_frames_still_retains_integrity_warning(self):
        case, events, rows = case_and_frames()
        row = fixtures.event("capture_ready", 35000, frame=-1, generation=0, source=99)
        row["capture_work"] = {"backend": "invented"}
        rows.insert(-1, row)
        result = analysis.analyze(case, events, rows)
        self.assertFalse(result["window_host_complete"])
        self.assertEqual(1, result["whole_file_integrity_issues"]["invalid_capture_work"])

    def test_mixed_identity_outside_window_is_rejected(self):
        case, events, rows = case_and_frames()
        rows.insert(-1, dict(fixtures.event("encode_begin", 30000, frame=99), trace_id="another-host"))
        with self.assertRaisesRegex(ValueError, "identity/schema"):
            analysis.analyze(case, events, rows)

    def test_invalid_or_overflowed_encoder_calls_outside_window_cannot_be_hidden(self):
        for malformed in (True, False):
            case, events, rows = case_and_frames()
            context = dict(frame=3, source=12, capture_ready_us=21000, source_present_us=20000)
            work = {"backend": "mf", "discarded": 1 if not malformed else 0, "calls": []}
            if malformed:
                work["calls"] = [{"api": "guess", "begin_us": 23000, "end_us": 24000, "result": 0}]
            rows[-1:-1] = [fixtures.event("capture_ready", 21000, **dict(context, frame=-1, generation=0)),
                           fixtures.event("convert_begin", 21100, **context), fixtures.event("convert_end", 21500, **context),
                           fixtures.event("encode_begin", 22000, **context),
                           fixtures.event("encode_end", 25000, encoder_work=work, **context),
                           fixtures.event("submission_abandoned", 26000, result=2, **context)]
            result = analysis.analyze(case, events, rows)
            self.assertFalse(result["window_host_complete"])
            issue = "invalid_encoder_work" if malformed else "encoder_work_overflow"
            self.assertEqual(1, result["whole_file_integrity_issues"][issue])

    def test_missing_and_reused_wire_frame_numbers_cannot_be_guessed(self):
        case, events, rows = case_and_frames()
        with self.assertRaisesRegex(ValueError, "unmatched"):
            analysis.analyze(case, events, [r for r in rows if r.get("frame") != 2])
        rows.insert(-1, dict(fixtures.event("encode_begin", 30000), generation=99))
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            analysis.analyze(case, events, rows)

    def test_send_cadence_uses_the_last_packet_of_each_frame(self):
        case, events, rows = case_and_frames()
        # A delayed second codec packet of frame 1 finishes at 8000, not 4000.
        for row in fixtures.fixture()[5:9]:
            if row["event"] != "encode_end":
                rows.insert(-1, dict(row, packet=3, at_us=row["at_us"] + 4000))
        result = analysis.analyze(case, events, rows)
        self.assertTrue(result["window_host_complete"], result["window_host_issues"])
        self.assertEqual(6., result["host_sender_cadence"]["send_return"]["gap_ms"]["mean"])
        self.assertEqual(10., result["host_sender_cadence"]["output"]["gap_ms"]["mean"])

    def test_equal_timestamps_preserve_distinct_frame_count(self):
        result = analysis.cadence([1000, 1000, 2000])
        self.assertEqual(3, result["count"])
        self.assertEqual(.5, result["gap_ms"]["mean"])

    def test_invalid_measurement_interval_is_rejected(self):
        case, events, rows = case_and_frames()
        for value in (float("nan"), float("inf"), True, "1000"):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "measurement interval"):
                analysis.analyze(dict(case, measurementStartUs=value), events, rows)


if __name__ == "__main__":
    unittest.main()
