"""Check the generated synthetic protocol without contacting a remote host."""
import hashlib
import importlib.util
import io
import json
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('measure', Path(__file__).with_name('measure-ssh-throughput.py'))
measure = importlib.util.module_from_spec(spec)
spec.loader.exec_module(measure)


class SyntheticProtocolTest(unittest.TestCase):
    def test_download_has_exact_length_and_verified_content(self):
        result = subprocess.run([sys.executable, '-u', '-c', measure.REMOTE, 'download', '1048576'],
                                capture_output=True, check=True, timeout=10)
        stream = io.BytesIO(result.stdout)
        self.assertEqual(measure.read_json(stream), dict(ready=True, bytes=1048576, direction='download'))
        payload = stream.read(1048576)
        response = measure.read_json(stream)
        self.assertEqual(len(payload), 1048576)
        self.assertEqual(hashlib.sha256(payload).hexdigest(), measure.expected_digest(1048576))
        self.assertEqual(response['sha256'], measure.expected_digest(1048576))
        self.assertEqual(stream.read(), b'')

    def test_upload_hash_is_computed_from_received_bytes(self):
        payload = b'X' * 1048576
        result = subprocess.run([sys.executable, '-u', '-c', measure.REMOTE, 'upload', '1048576'],
                                input=payload, capture_output=True, check=True, timeout=10)
        stream = io.BytesIO(result.stdout)
        self.assertTrue(measure.read_json(stream)['ready'])
        response = measure.read_json(stream)
        self.assertEqual(response['sha256'], hashlib.sha256(payload).hexdigest())
        self.assertNotEqual(response['sha256'], measure.expected_digest(1048576))

    def test_early_eof_does_not_report_success(self):
        result = subprocess.run([sys.executable, '-u', '-c', measure.REMOTE, 'upload', '1048576'],
                                input=b'truncated', capture_output=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        stream = io.BytesIO(result.stdout)
        self.assertTrue(measure.read_json(stream)['ready'])
        self.assertEqual(stream.read(), b'')

    def test_unbounded_and_shell_arguments_are_rejected_before_starting_ssh(self):
        with patch.object(measure.subprocess, 'Popen') as start:
            for host, direction, size in [('tc;echo', 'download', 1), ('-oProxyCommand=x', 'download', 1),
                                           ('tc', 'bad', 1), ('tc', 'upload', 1000)]:
                with self.assertRaises(ValueError):
                    measure.measure(host, direction, size)
            start.assert_not_called()
        with self.assertRaises(RuntimeError):
            measure.read_json(io.BytesIO(b'x' * 4096 + b'\n'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
