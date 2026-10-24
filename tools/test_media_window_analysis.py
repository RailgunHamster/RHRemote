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
