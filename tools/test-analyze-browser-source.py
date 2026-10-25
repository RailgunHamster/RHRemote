import copy
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("source_analysis", Path(__file__).with_name("analyze-browser-source.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def fixture(fps=60, processing=.002):
    frames = [{"nowMs": i * 1000 / fps, "mediaTime": 30 + i / fps,
               "presentationTime": i * 1000 / fps, "expectedDisplayTime": (i + 1) * 1000 / fps,
               "presentedFrames": i, "width": 1920, "height": 1080, "processingDuration": processing}
              for i in range(1, fps * 2 + 1)]
    packets = []
    for seq, count, now, ended in ((1, 0, 0, False), (2, fps, 1000, False), (3, fps * 2, 2000, False), (4, fps * 2, 2100, True)):
        packets.append({"schema": "rhremote.browser-source/v1", "seq": seq, "phase": "ended" if ended else "playing",
                        "ended": ended, "error": None, "nowMs": now, "currentTime": 30 + now / 1000,
                        "callbackCount": count, "lastFrame": copy.deepcopy(frames[count-1]) if count else None,
                        "quality": {"totalVideoFrames": count, "droppedVideoFrames": 0},
                        "frames": copy.deepcopy(frames[(seq-2)*fps:(seq-1)*fps]) if seq in (2, 3) else [],
                        "width": 1920, "height": 1080, "innerWidth": 1920, "innerHeight": 1080,
                        "devicePixelRatio": 1, "visible": "visible", "paused": ended, "seeking": False,
                        "muted": True, "volume": 0, "playbackRate": 1})
    case = {"before": {"producer": "browser_movie", "phase": "playing", "ended": False,
                       "source_file_fps": 60, "browser": copy.deepcopy(packets[1])},
            "after": {"producer": "browser_movie", "phase": "playing", "ended": False,
                      "source_file_fps": 60, "browser": copy.deepcopy(packets[2])}}
    return case, packets


def records(packets):
    return [{"sequence": p["seq"], "receivedQpc": p["seq"] * 10000, "json": json.dumps(p)} for p in packets]


class SourceAnalysis(unittest.TestCase):
    def test_perfect_short_source_cannot_validate_a_longer_client_measurement(self):
        case, packets = fixture()
        case["measurementSeconds"] = 150
        result = module.analyze(case, records(packets))
        self.assertTrue(result["originalClockDropGatePass"])
        self.assertFalse(result["sourceScopeCoversClientMeasurement"])
        self.assertFalse(result["sourceComparisonEligible"])

    def test_complete_source_scope_covers_a_shorter_measurement(self):
        case, packets = fixture()
        case["measurementSeconds"] = .9
        result = module.analyze(case, records(packets))
        self.assertTrue(result["sourceScopeCoversClientMeasurement"])
        self.assertTrue(result["sourceComparisonEligible"])

    def test_controller_exit_or_error_cannot_be_hidden_by_its_last_playing_packet(self):
        for patch in ({"ended": True}, {"error": "Owned movie geometry changed"}, {"phase": "error"},
                      {"ended": None}, {"phase": None}):
            case, packets = fixture()
            case["after"].update(patch)
            result = module.analyze(case, records(packets))
            self.assertTrue(result["originalClockDropGatePass"])
            self.assertFalse(result["sourceControllerHealthyAtEndpoints"])
            self.assertFalse(result["sourceComparisonEligible"])

    def test_absent_measurement_duration_remains_unknown(self):
        case, packets = fixture()
        result = module.analyze(case, records(packets))
        self.assertIsNone(result["sourceScopeCoversClientMeasurement"])
        self.assertIsNone(result["clientMeasurementSeconds"])

    def test_invalid_client_duration_is_rejected(self):
        for seconds in (0, -1, True, float("nan")):
            case, packets = fixture()
            case["measurementSeconds"] = seconds
            with self.assertRaises(ValueError):
                module.analyze(case, records(packets))

    def test_valid_source_does_not_accept_remote_or_claim_cpu(self):
        case, packets = fixture()
        result = module.analyze(case, records(packets))
        self.assertTrue(result["sourceComparisonEligible"])
        self.assertFalse(result["remotePerformanceAccepted"])
        self.assertEqual(result["compositorSubmittedFps"], 60)
        self.assertEqual(result["windowCallbacks"], 60)
        self.assertIsNone(result["processCpuPercentOfOneCore"])

    def test_zero_reported_drops_cannot_accept_a_30fps_compositor(self):
        case, packets = fixture(fps=30)
        result = module.analyze(case, records(packets))
        self.assertTrue(result["originalClockDropGatePass"])
        self.assertFalse(result["sourceComparisonEligible"])
        self.assertEqual(result["compositorSubmittedFps"], 30)

    def test_missing_decode_duration_stays_unknown(self):
        case, packets = fixture(processing=None)
        result = module.analyze(case, records(packets))
        self.assertEqual(result["unknownProcessingDurationCount"], 60)
        self.assertIsNone(result["decodeReadyDurationMs"]["mean"])

    def test_missing_counter_cannot_become_zero(self):
        case, packets = fixture()
        packets[2]["quality"]["droppedVideoFrames"] = None
        case["after"]["browser"] = copy.deepcopy(packets[2])
        with self.assertRaisesRegex(ValueError, "unknown"):
            module.analyze(case, records(packets))

    def test_missing_packet_is_not_a_complete_trace(self):
        case, packets = fixture()
        with self.assertRaisesRegex(ValueError, "packet"):
            module.analyze(case, records(packets)[1:])

    def test_missing_callback_batch_is_detected(self):
        case, packets = fixture()
        packets[2]["frames"].pop()
        with self.assertRaisesRegex(ValueError, "batches"):
            module.analyze(case, records(packets))

    def test_endpoint_must_exist_in_actual_raw_packets(self):
        case, packets = fixture()
        case["after"]["browser"]["quality"]["droppedVideoFrames"] = 0.5
        with self.assertRaisesRegex(ValueError, "endpoint"):
            module.analyze(case, records(packets))

    def test_repeated_media_frame_is_not_unique_input(self):
        case, packets = fixture()
        packets[2]["frames"][20]["mediaTime"] = packets[2]["frames"][19]["mediaTime"]
        with self.assertRaisesRegex(ValueError, "identity"):
            module.analyze(case, records(packets))

    def test_incomplete_end_is_not_accepted(self):
        case, packets = fixture()
        packets[-1]["ended"] = False
        with self.assertRaisesRegex(ValueError, "close"):
            module.analyze(case, records(packets))

    def test_hidden_or_audible_input_is_not_accepted(self):
        for key, value in (("visible", "hidden"), ("muted", False), ("innerHeight", 1000), ("playbackRate", .8)):
            case, packets = fixture()
            packets[2][key] = value
            case["after"]["browser"] = copy.deepcopy(packets[2])
            with self.assertRaisesRegex(ValueError, "viewport"):
                module.analyze(case, records(packets))

    def test_cpu_uses_its_actual_sample_interval(self):
        case, packets = fixture()
        case["before"].update(cpu_seconds=1, cpu_sample_elapsed_s=1.25)
        case["after"].update(cpu_seconds=2, cpu_sample_elapsed_s=2.5)
        self.assertEqual(module.analyze(case, records(packets))["processCpuPercentOfOneCore"], 80)

    def test_missing_cpu_clock_is_not_assumed(self):
        case, packets = fixture()
        case["before"]["cpu_seconds"] = 1
        case["after"]["cpu_seconds"] = 2
        with self.assertRaisesRegex(ValueError, "CPU sample clock"):
            module.analyze(case, records(packets))


if __name__ == "__main__":
    unittest.main()
