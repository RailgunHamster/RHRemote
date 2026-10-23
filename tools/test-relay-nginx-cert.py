"""Linux/OpenSSL regression tests for certificate rotation and rollback."""
import copy
import importlib.util
import json
import os
import re
import stat
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
import relay_nginx_cert as sync


class FakeNginx:
    def __init__(self, settings):
        self.s = settings
        self.loaded = sync.fingerprint((Path(settings.v['sslDirectory']) / 'siyuan.crt').read_bytes())
        self.reloads = 0
        self.fail_check = self.fail_reload = self.fail_probe = self.foreign_change = False
        self.changed_binding = False

    def binding(self, allowed):
        value = sync.digest(Path(self.s.v['nginxConfig']).read_bytes())
        if self.changed_binding or value not in allowed:
            raise sync.SyncError('Configuration changed')
        return value

    def check(self):
        if self.foreign_change:
            self.s.include.write_bytes(b'operator edited this include\n')
            raise sync.SyncError('Concurrent operator edit')
        if self.fail_check:
            self.fail_check = False
            raise sync.SyncError('nginx -t rejected the candidate')

    def reload(self):
        self.reloads += 1
        text = self.s.include.read_text()
        remote = re.search(r'^ssl_certificate ([^;]+);', text, re.M).group(1)
        relative = remote.removeprefix(self.s.v['sslMount'] + '/')
        self.loaded = sync.fingerprint((Path(self.s.v['sslDirectory']) / relative).read_bytes())
        if self.fail_reload:
            self.fail_reload = False
            raise sync.SyncError('Reload acknowledgement failed after applying')

    def probe(self, fingerprint, legacy=False):
        if self.fail_probe and not legacy:
            self.fail_probe = False
            raise sync.SyncError('The new TLS listener was not ready')
        if self.loaded != fingerprint:
            raise sync.SyncError('Wrong live certificate')


class CertificateSyncTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = tempfile.TemporaryDirectory(prefix='rhremote-cert-fixture-')
        cls.base = Path(cls.fixture.name)
        cls.root_cert = cls.base / 'ca.crt'
        cls.root_key = cls.base / 'ca.key'
        sync.command(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '30',
                      '-subj', '/CN=RHRemote unit test CA', '-keyout', str(cls.root_key), '-out', str(cls.root_cert)])
        cls.materials = []
        for name in ('a', 'b'):
            key = cls.base / (name + '.key')
            csr = cls.base / (name + '.csr')
            cert = cls.base / (name + '.crt')
            ext = cls.base / (name + '.ext')
            ext.write_text('subjectAltName=IP:101.43.203.147\nextendedKeyUsage=serverAuth\n'
                           'keyUsage=critical,digitalSignature\nbasicConstraints=critical,CA:FALSE\n')
            sync.command(['openssl', 'req', '-new', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256',
                          '-nodes', '-subj', '/CN=RHRemote test leaf', '-keyout', str(key), '-out', str(csr)])
            sync.command(['openssl', 'x509', '-req', '-in', str(csr), '-CA', str(cls.root_cert),
                          '-CAkey', str(cls.root_key), '-CAcreateserial', '-days', '3', '-extfile', str(ext), '-out', str(cert)])
            key.chmod(0o600)
            cls.materials.append((cert.read_bytes(), key.read_bytes()))

    @classmethod
    def tearDownClass(cls):
        assert cls.base.parent.resolve() == Path(tempfile.gettempdir()).resolve()
        assert cls.base.name.startswith('rhremote-cert-fixture-')
        cls.fixture.cleanup()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='rhremote-cert-test-')
        self.root = Path(self.temporary.name)
        self.ssl = self.root / 'ssl'
        self.ssl.mkdir(mode=0o700)
        (self.ssl / 'siyuan.crt').write_bytes(self.root_cert.read_bytes())
        (self.root / 'nginx.conf').write_bytes(b'isolated Nginx configuration\n')
        self.config = dict(schema='rhremote.nginx-cert-sync/v1', publicIp='101.43.203.147',
            sourceCertificate=str(self.root / 'source.crt'), sourcePrivateKey=str(self.root / 'source.key'),
            sourceUid=os.geteuid(), caFile=str(self.root_cert), sslDirectory=str(self.ssl), sslMount='/etc/nginx/ssl',
            nginxConfig=str(self.root / 'nginx.conf'), nginxMount='/etc/nginx/nginx.conf',
            activeConfigSha256=sync.digest((self.root / 'nginx.conf').read_bytes()), originalConfigSha256='a' * 64,
            container='rhremote-test', containerId='b' * 64, stateDirectory=str(self.root / 'state'),
            probeAddress='127.0.0.1', probePort=33452)
        self.settings = sync.Settings(self.config)
        self.settings.root.mkdir(mode=0o700)
        self.settings.include.write_bytes(self.settings.bootstrap())
        self.source(0)
        self.backend = FakeNginx(self.settings)
        self.engine = sync.Synchronizer(self.settings, self.backend)

    def tearDown(self):
        assert self.root.parent.resolve() == Path(tempfile.gettempdir()).resolve()
        assert self.root.name.startswith('rhremote-cert-test-')
        self.temporary.cleanup()

    def source(self, index):
        cert, key = self.materials[index]
        Path(self.config['sourceCertificate']).write_bytes(cert)
        Path(self.config['sourcePrivateKey']).write_bytes(key)
        Path(self.config['sourcePrivateKey']).chmod(0o600)

    def test_dry_run_validates_without_copying_keys_or_reloading(self):
        result = self.engine.dry_run()
        self.assertTrue(result['ok'])
        self.assertFalse(result['privateKeysCopied'])
        self.assertFalse(self.settings.state_root.exists())
        self.assertEqual(self.backend.reloads, 0)

    def test_rejects_wrong_ip_key_chain_and_expiring_certificate(self):
        cert, key = self.materials[0]
        cases = [(cert, key, '101.43.203.148', self.root_cert, None),
                 (cert, self.materials[1][1], '101.43.203.147', self.root_cert, None),
                 (cert, key, '101.43.203.147', self.root / 'missing-ca.crt', None),
                 (cert, key, '101.43.203.147', self.root_cert, time.time() + 3 * 86400 - 1800)]
        for certificate, private_key, ip, ca, now in cases:
            with self.subTest(ip=ip, ca=ca, now=now):
                with self.assertRaises(sync.SyncError):
                    sync.validate_material(certificate, private_key, ip, ca, minimum_valid=3600, now=now)

    def test_certificate_files_cannot_smuggle_private_material(self):
        cert, key = self.materials[0]
        for certificate, private_key in ((cert + key, key), (cert, key + cert), (b'invalid', key)):
            with self.assertRaises(sync.SyncError):
                sync.validate_material(certificate, private_key, '101.43.203.147', self.root_cert)
        material = sync.validate_material(cert, key, '101.43.203.147', self.root_cert)
        self.assertNotIn('BEGIN', repr(material))
        self.assertNotIn('PRIVATE', json.dumps(material.public()))

    def test_private_key_permissions_and_symlinks_are_rejected(self):
        path = Path(self.config['sourcePrivateKey'])
        path.chmod(0o644)
        with self.assertRaises(sync.SyncError):
            self.engine.dry_run()
        path.chmod(0o600)
        elsewhere = self.root / 'elsewhere.key'
        path.rename(elsewhere)
        path.symlink_to(elsewhere)
        with self.assertRaises(sync.SyncError):
            self.engine.dry_run()

    def test_initial_sync_then_unchanged_source_does_not_reload_again(self):
        result = self.engine.sync()
        self.assertTrue(result['changed'])
        self.assertEqual(self.backend.reloads, 1)
        version = self.settings.root / result['certificatePemSha256']
        self.assertEqual(stat.S_IMODE((version / 'private.key').stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(version.stat().st_mode), 0o700)
        self.assertFalse(self.engine.sync()['changed'])
        self.assertEqual(self.backend.reloads, 1)

    def test_renewal_switches_both_files_and_keeps_previous_state(self):
        old = self.engine.sync()
        self.source(1)
        new = self.engine.sync()
        self.assertNotEqual(old['leafFingerprintSha256'], new['leafFingerprintSha256'])
        self.assertEqual(self.backend.reloads, 2)
        backup = json.loads((self.settings.state_root / 'previous.json').read_bytes())
        self.assertEqual(backup['state']['leafFingerprintSha256'], old['leafFingerprintSha256'])
        self.assertEqual(self.backend.loaded, new['leafFingerprintSha256'])

    def test_nginx_check_failure_restores_include_without_reloading(self):
        old = self.settings.include.read_bytes()
        self.backend.fail_check = True
        with self.assertRaises(sync.SyncError):
            self.engine.sync()
        self.assertEqual(self.settings.include.read_bytes(), old)
        self.assertEqual(self.backend.reloads, 0)
        self.assertFalse(self.settings.state_file.exists())

    def test_reload_failure_after_applying_rolls_back_the_live_certificate(self):
        old = self.settings.include.read_bytes()
        fingerprint = self.backend.loaded
        self.backend.fail_reload = True
        with self.assertRaises(sync.SyncError):
            self.engine.sync()
        self.assertEqual(self.settings.include.read_bytes(), old)
        self.assertEqual(self.backend.loaded, fingerprint)
        self.assertEqual(self.backend.reloads, 2)

    def test_probe_failure_rolls_back_an_existing_successful_renewal(self):
        self.engine.sync()
        old = self.settings.include.read_bytes()
        state = self.settings.state_file.read_bytes()
        fingerprint = self.backend.loaded
        self.source(1)
        self.backend.fail_probe = True
        with self.assertRaises(sync.SyncError):
            self.engine.sync()
        self.assertEqual(self.settings.include.read_bytes(), old)
        self.assertEqual(self.settings.state_file.read_bytes(), state)
        self.assertEqual(self.backend.loaded, fingerprint)

    def test_state_write_failure_also_rolls_back_the_certificate(self):
        original = sync.atomic_replace
        old = self.settings.include.read_bytes()
        def write(path, value, expected):
            if path == self.settings.state_file:
                raise OSError('Injected storage failure')
            return original(path, value, expected)
        with patch.object(sync, 'atomic_replace', side_effect=write):
            with self.assertRaises(sync.SyncError):
                self.engine.sync()
        self.assertEqual(self.settings.include.read_bytes(), old)
        self.assertFalse(self.settings.state_file.exists())

    def test_concurrent_include_edits_are_preserved_during_rollback(self):
        self.backend.foreign_change = True
        with self.assertRaisesRegex(sync.SyncError, 'inspection'):
            self.engine.sync()
        self.assertEqual(self.settings.include.read_bytes(), b'operator edited this include\n')

    def test_changed_binding_or_existing_unowned_include_is_not_overwritten(self):
        self.backend.changed_binding = True
        with self.assertRaises(sync.SyncError):
            self.engine.sync()
        self.backend.changed_binding = False
        self.settings.include.write_bytes(b'unrelated operator data\n')
        with self.assertRaises(sync.SyncError):
            self.engine.sync()
        self.assertEqual(self.settings.include.read_bytes(), b'unrelated operator data\n')

    def test_sync_lock_prevents_overlapping_transactions(self):
        import fcntl
        self.settings.state_root.mkdir(mode=0o700)
        with open(self.settings.state_root / 'sync.lock', 'wb') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaisesRegex(sync.SyncError, 'still running'):
                self.engine.sync()

    def test_settings_cannot_inject_nginx_directives(self):
        for key, value in (('sslMount', '/etc/nginx/ssl; include /tmp/x'), ('nginxMount', '/tmp/a\nreload'),
                           ('container', 'a; rm x'), ('probePort', 80), ('sourceUid', True)):
            modified = copy.deepcopy(self.config)
            modified[key] = value
            with self.assertRaises(sync.SyncError):
                sync.Settings(modified)

    def test_live_probe_requires_a_stable_worker_generation(self):
        backend = sync.DockerBackend(self.settings)
        clock = [0.0]
        calls = []
        def handshake(expected, version, legacy=False):
            calls.append(clock[0])
            if 0.4 <= clock[0] < 0.6:
                raise sync.SyncError('An older worker still accepted this connection')
        def advance(seconds):
            clock[0] += seconds
        with patch.object(backend, 'probe_one', side_effect=handshake), \
                patch.object(sync.time, 'monotonic', side_effect=lambda: clock[0]), \
                patch.object(sync.time, 'sleep', side_effect=advance):
            backend.probe('test fingerprint')
        self.assertGreaterEqual(clock[0], 1.6)
        self.assertGreater(len(calls), 6)


if __name__ == '__main__':
    unittest.main(verbosity=2)
