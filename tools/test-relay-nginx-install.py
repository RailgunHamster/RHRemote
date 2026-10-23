"""Linux regressions for the initial install, timer failure and explicit rollback."""
import importlib.util
import json
import os
import unittest
from pathlib import Path
from unittest.mock import patch

import relay_nginx_cert as cert
import deploy_relay_nginx443 as deploy

spec = importlib.util.spec_from_file_location('fixtures', Path(__file__).with_name('test-relay-nginx-cert.py'))
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)


class FakeSystemd:
    def __init__(self):
        self.files = {}
        self.enabled = False
        self.fail_install = False

    def preflight(self, files):
        if self.files:
            raise cert.SyncError('Existing unit')

    def install(self, files):
        self.files.update(files)
        self.enabled = True
        if self.fail_install:
            raise cert.SyncError('Timer activation failed')

    def verify_owned(self, files):
        if self.files != files:
            raise cert.SyncError('A unit changed')

    def remove(self, files):
        if self.files:
            self.verify_owned(files)
        self.files = {}
        self.enabled = False


class InstallTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixtures.CertificateSyncTest.setUpClass()

    @classmethod
    def tearDownClass(cls):
        fixtures.CertificateSyncTest.tearDownClass()

    def setUp(self):
        self.fixture = fixtures.CertificateSyncTest()
        self.fixture.setUp()
        self.root = self.fixture.root
        self.s = self.fixture.settings
        self.original = Path(self.s.v['nginxConfig']).read_bytes()
        self.candidate = (b'candidate\ninclude /etc/nginx/ssl/rhremote-443/current.conf;\n')
        self.s.v['originalConfigSha256'] = cert.digest(self.original)
        self.s.v['activeConfigSha256'] = cert.digest(self.candidate)
        self.s.include.unlink()  # Only the exact bootstrap owned by this fixture.
        original = self.original
        class Backend(fixtures.FakeNginx):
            def reload(inner):
                if Path(inner.s.v['nginxConfig']).read_bytes() == original:
                    inner.reloads += 1
                    inner.loaded = cert.fingerprint((Path(inner.s.v['sslDirectory']) / 'siyuan.crt').read_bytes())
                else:
                    super().reload()
        self.backend = Backend(self.s)
        self.units = FakeSystemd()
        self.installer = deploy.Installer(self.s, self.backend, self.units,
                                         program=self.root / 'program', config=self.root / 'etc' / 'config.json')

    def tearDown(self):
        self.fixture.tearDown()

    def test_dry_run_does_not_modify_files_or_create_state(self):
        result = self.installer.dry_run(self.candidate)
        self.assertFalse(result['productionChanged'])
        self.assertFalse(self.s.state_root.exists())
        self.assertFalse(self.s.include.exists())
        self.assertEqual(Path(self.s.v['nginxConfig']).read_bytes(), self.original)
        self.assertFalse(self.units.enabled)

    def test_apply_then_manual_rollback_preserves_mounted_inode(self):
        inode = Path(self.s.v['nginxConfig']).stat().st_ino
        result = self.installer.apply(self.candidate)
        self.assertTrue(result['hourlyTimerEnabled'])
        self.assertTrue(self.units.enabled)
        self.assertEqual(Path(self.s.v['nginxConfig']).stat().st_ino, inode)
        self.assertNotEqual(self.backend.loaded, cert.fingerprint(self.fixture.root_cert.read_bytes()))
        self.assertTrue(self.installer.rollback()['rolledBack'])
        self.assertEqual(Path(self.s.v['nginxConfig']).read_bytes(), self.original)
        self.assertEqual(Path(self.s.v['nginxConfig']).stat().st_ino, inode)
        self.assertFalse(self.s.include.exists())
        self.assertFalse(self.s.state_file.exists())
        self.assertFalse(self.units.enabled)
        self.assertEqual(json.loads(self.installer.journal.read_bytes())['status'], 'rolled-back')

    def test_timer_failure_restores_config_and_certificate(self):
        self.units.fail_install = True
        with self.assertRaisesRegex(cert.SyncError, 'original configuration'):
            self.installer.apply(self.candidate)
        self.assertEqual(Path(self.s.v['nginxConfig']).read_bytes(), self.original)
        self.assertEqual(self.backend.loaded, cert.fingerprint(self.fixture.root_cert.read_bytes()))
        self.assertFalse(self.units.enabled)
        self.assertFalse(self.s.include.exists())
        self.assertFalse(self.s.state_file.exists())
        self.assertFalse(self.installer.config.exists())

    def test_nginx_check_failure_does_not_leave_candidate_or_timer(self):
        self.backend.fail_check = True
        with self.assertRaises(cert.SyncError):
            self.installer.apply(self.candidate)
        self.assertEqual(Path(self.s.v['nginxConfig']).read_bytes(), self.original)
        self.assertFalse(self.units.enabled)
        self.assertFalse(self.s.include.exists())

    def test_candidate_mismatch_and_existing_units_are_rejected_before_writes(self):
        with self.assertRaises(cert.SyncError):
            self.installer.apply(self.candidate + b'changed')
        self.units.files = {'other': b'existing unit'}
        with self.assertRaises(cert.SyncError):
            self.installer.apply(self.candidate)
        self.assertEqual(Path(self.s.v['nginxConfig']).read_bytes(), self.original)
        self.assertFalse(self.s.include.exists())
        self.assertFalse(self.installer.journal.exists())

    def test_operator_main_config_edits_are_preserved_on_manual_rollback(self):
        self.installer.apply(self.candidate)
        Path(self.s.v['nginxConfig']).write_bytes(b'operator changed this')
        with self.assertRaises(cert.SyncError):
            self.installer.rollback()
        self.assertEqual(Path(self.s.v['nginxConfig']).read_bytes(), b'operator changed this')
        self.assertTrue(self.units.enabled)

    def test_operator_include_edits_are_preserved_before_stopping_timer(self):
        self.installer.apply(self.candidate)
        self.s.include.write_bytes(b'operator changed this include')
        with self.assertRaises(cert.SyncError):
            self.installer.rollback()
        self.assertEqual(self.s.include.read_bytes(), b'operator changed this include')
        self.assertTrue(self.units.enabled)

    def test_partial_in_place_write_restores_original_bytes(self):
        original_write = os.write
        calls = [0]
        def write(fd, data):
            calls[0] += 1
            if calls[0] == 1:
                return original_write(fd, data[:5])
            raise OSError('Injected storage failure')
        inode = Path(self.s.v['nginxConfig']).stat().st_ino
        with patch.object(deploy.os, 'write', side_effect=write):
            with self.assertRaises(OSError):
                deploy.write_in_place(Path(self.s.v['nginxConfig']), self.candidate, self.original)
        self.assertEqual(Path(self.s.v['nginxConfig']).read_bytes(), self.original)
        self.assertEqual(Path(self.s.v['nginxConfig']).stat().st_ino, inode)

    def test_unit_paths_cannot_inject_systemd_settings(self):
        with self.assertRaises(cert.SyncError):
            deploy.unit_files(self.s, program=Path('/tmp/a%u'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
