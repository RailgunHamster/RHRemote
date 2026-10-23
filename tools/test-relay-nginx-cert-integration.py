"""Real Nginx TLS/rotation test in an owned container, using generated test keys."""
import hashlib
import importlib.util
import json
import os
import re
import socket
import socketserver
import ssl
import sys
import tempfile
import threading
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import relay_nginx_cert as sync
import deploy_relay_nginx443 as deploy


def docker(*args):
    return sync.command(['docker', '--host', 'unix:///var/run/docker.sock', *args], timeout=30).decode().strip()


def production_snapshot():
    template = '{"Id":{{json .Id}},"Pid":{{json .State.Pid}},"Restarts":{{json .RestartCount}},"Image":{{json .Image}}}'
    value = json.loads(docker('inspect', '--format', template, 'siyuan-nginx'))
    for name, path in (
        ('config', '/home/ubuntu/docker/siyuan/siyuan-nginx/conf/nginx.conf'),
        ('certificate', '/home/ubuntu/docker/siyuan/siyuan-ssl/siyuan.crt')):
        value[name] = sync.digest(Path(path).read_bytes())
    return value


class Echo(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(10)
        header = b''
        while b'\r\n\r\n' not in header and len(header) < 8192:
            part = self.request.recv(1)
            if not part:
                return
            header += part
        if b'upgrade: rhremote-cert-test' not in header.lower():
            return
        self.request.sendall(b'HTTP/1.1 101 Switching Protocols\r\nUpgrade: rhremote-cert-test\r\nConnection: Upgrade\r\n\r\n')
        while True:
            data = self.request.recv(8192)
            if not data:
                return
            self.request.sendall(data)


def receive(stream, size):
    data = bytearray()
    while len(data) < size:
        part = stream.recv(size - len(data))
        if not part:
            raise RuntimeError('The upgraded connection closed during rotation')
        data.extend(part)
    return bytes(data)


def main():
    if os.geteuid() != 0:
        raise RuntimeError('This on-host integration test needs local Docker access')
    spec = importlib.util.spec_from_file_location('fixtures', Path(__file__).with_name('test-relay-nginx-cert.py'))
    fixtures = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fixtures)
    fixtures.CertificateSyncTest.setUpClass()
    temporary = tempfile.TemporaryDirectory(prefix='rhremote-nginx-integration-')
    root = Path(temporary.name)
    token = uuid.uuid4().hex
    name = 'rhremote-cert-test-' + token[:16]
    container = None
    echo = thread = None
    before = production_snapshot()
    result = {}
    try:
        gateway = docker('network', 'inspect', 'bridge', '--format', '{{(index .IPAM.Config 0).Gateway}}')
        import ipaddress
        ipaddress.IPv4Address(gateway)
        echo = socketserver.ThreadingTCPServer((gateway, 0), Echo)
        echo.daemon_threads = True
        thread = threading.Thread(target=echo.serve_forever, daemon=True)
        thread.start()
        ssl_dir = root / 'ssl'
        ssl_dir.mkdir(mode=0o700)
        legacy_key = ssl_dir / 'siyuan.key'
        legacy_crt = ssl_dir / 'siyuan.crt'
        sync.command(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '3',
                      '-subj', '/CN=RHRemote legacy test', '-keyout', str(legacy_key), '-out', str(legacy_crt)])
        legacy_key.chmod(0o600)
        source_cert, source_key = root / 'source.crt', root / 'source.key'
        source_cert.write_bytes(fixtures.CertificateSyncTest.materials[0][0])
        source_key.write_bytes(fixtures.CertificateSyncTest.materials[0][1])
        source_key.chmod(0o600)
        ca = root / 'test-ca.crt'
        ca.write_bytes(fixtures.CertificateSyncTest.root_cert.read_bytes())
        conf = root / 'nginx.conf'
        rsa_cipher = 'ECDHE-RSA-AES256-GCM-SHA384'
        text = f'''worker_processes 1;
pid /var/run/nginx.pid;
error_log /dev/stderr notice;
events {{ worker_connections 64; }}
http {{
    access_log off;
    map $http_upgrade $connection_upgrade {{ default upgrade; '' close; }}
    server {{
        listen 443 ssl;
        server_name 101.43.203.147;
        include /etc/nginx/ssl/rhremote-443/current.conf;
        ssl_protocols TLSv1.2 TLSv1.3;
        ssl_ciphers {rsa_cipher};
        location / {{ return 200 "RHRemote certificate test"; }}
        location /tunnel {{
            proxy_pass http://{gateway}:{echo.server_address[1]};
            proxy_http_version 1.1;
            proxy_set_header Upgrade $http_upgrade;
            proxy_set_header Connection $connection_upgrade;
            proxy_read_timeout 60s;
            proxy_buffering off;
        }}
    }}
}}
'''
        conf.write_text(text)
        managed = ssl_dir / 'rhremote-443'
        managed.mkdir(mode=0o700)
        include = managed / 'current.conf'
        include.write_text(sync.MARKER + 'ssl_certificate /etc/nginx/ssl/siyuan.crt;\nssl_certificate_key /etc/nginx/ssl/siyuan.key;\n')
        image = before['Image']
        if not re.fullmatch(r'sha256:[a-f0-9]{64}', image):
            raise RuntimeError('Cached test image identity is invalid')
        container = docker('run', '-d', '--pull', 'never', '--name', name,
                           '--label', 'org.rhremote.cert-sync-test=' + token,
                           '--read-only', '--tmpfs', '/var/cache/nginx', '--tmpfs', '/var/run', '--tmpfs', '/tmp',
                           '--publish', '127.0.0.1::443', '--mount', f'type=bind,src={conf},dst=/etc/nginx/nginx.conf,readonly',
                           '--mount', f'type=bind,src={ssl_dir},dst=/etc/nginx/ssl,readonly', image)
        if not re.fullmatch(r'[a-f0-9]{64}', container):
            raise RuntimeError('Test container did not return a concrete identity')
        mapping = docker('port', container, '443/tcp')
        match = re.fullmatch(r'127\.0\.0\.1:(\d+)', mapping)
        if not match:
            raise RuntimeError('The test listener must be loopback-only')
        config = dict(schema='rhremote.nginx-cert-sync/v1', publicIp='101.43.203.147',
                      sourceCertificate=str(source_cert), sourcePrivateKey=str(source_key), sourceUid=os.geteuid(),
                      caFile=str(ca), sslDirectory=str(ssl_dir), sslMount='/etc/nginx/ssl',
                      nginxConfig=str(conf), nginxMount='/etc/nginx/nginx.conf',
                      activeConfigSha256=sync.digest(conf.read_bytes()), originalConfigSha256=sync.digest(conf.read_bytes()),
                      container=name, containerId=container, stateDirectory=str(root / 'state'),
                      probeAddress='127.0.0.1', probePort=int(match.group(1)))
        settings = sync.Settings(config)
        backend = sync.DockerBackend(settings)
        engine = sync.Synchronizer(settings, backend)
        legacy_fp = sync.fingerprint(legacy_crt.read_bytes())
        backend.probe(legacy_fp, legacy=True)
        try:
            engine.sync()
        except sync.SyncError:
            backend.probe(legacy_fp, legacy=True)
            if settings.state_file.exists() or include.read_bytes() != settings.bootstrap():
                raise RuntimeError('ECDSA/TLS1.2 failure did not roll back the real listener')
        else:
            raise RuntimeError('RSA-only TLS1.2 unexpectedly accepted the ECDSA leaf')
        result['rsaOnlyEcdsaRejectedAndRolledBack'] = True
        inode = conf.stat().st_ino
        original = text.replace('include /etc/nginx/ssl/rhremote-443/current.conf;',
                                'ssl_certificate /etc/nginx/ssl/siyuan.crt;\n'
                                '        ssl_certificate_key /etc/nginx/ssl/siyuan.key;')
        deploy.write_in_place(conf, original.encode(), text.encode())
        backend.check()
        backend.reload()
        backend.probe(legacy_fp, legacy=True)
        sync.restore(include, None, settings.bootstrap())
        text = text.replace('ssl_ciphers ' + rsa_cipher + ';',
                            'ssl_ciphers ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-ECDSA-AES128-GCM-SHA256:' + rsa_cipher + ';')
        if conf.stat().st_ino != inode:
            raise RuntimeError('The test must preserve the single-file bind mount inode')
        config['originalConfigSha256'] = sync.digest(original.encode())
        config['activeConfigSha256'] = sync.digest(text.encode())
        settings = sync.Settings(config)
        backend = sync.DockerBackend(settings)
        engine = sync.Synchronizer(settings, backend)
        unit_spec = importlib.util.spec_from_file_location('install_fixtures', Path(__file__).with_name('test-relay-nginx-install.py'))
        unit_fixtures = importlib.util.module_from_spec(unit_spec)
        unit_spec.loader.exec_module(unit_fixtures)
        units = unit_fixtures.FakeSystemd()
        installer = deploy.Installer(settings, backend, units, program=root / 'program', config=root / 'etc' / 'config.json')
        installer.dry_run(text.encode())
        first = installer.apply(text.encode())
        assert units.enabled
        result['tls12And13FirstCertificateVerified'] = True
        unit_dir = root / 'units'
        unit_dir.mkdir()
        for unit_name, value in installer.files.items():
            (unit_dir / unit_name).write_bytes(value)
        sync.command(['systemd-analyze', 'verify', *[str(unit_dir / item) for item in installer.files]], timeout=30)
        result['systemdUnitSyntaxVerified'] = True
        context = ssl.create_default_context(cafile=str(ca))
        with socket.create_connection(('127.0.0.1', config['probePort']), timeout=5) as raw:
            with context.wrap_socket(raw, server_hostname=config['publicIp']) as tunnel:
                tunnel.settimeout(5)
                tunnel.sendall(b'GET /tunnel HTTP/1.1\r\nHost: 101.43.203.147\r\nUpgrade: rhremote-cert-test\r\nConnection: Upgrade\r\n\r\n')
                headers = b''
                while b'\r\n\r\n' not in headers and len(headers) < 8192:
                    headers += receive(tunnel, 1)
                if not headers.startswith(b'HTTP/1.1 101 '):
                    raise RuntimeError('Nginx did not preserve the actual protocol upgrade')
                tunnel.sendall(b'before rotation')
                assert receive(tunnel, 15) == b'before rotation'
                source_cert.write_bytes(fixtures.CertificateSyncTest.materials[1][0])
                source_key.write_bytes(fixtures.CertificateSyncTest.materials[1][1])
                second = engine.sync()
                if first['leafFingerprintSha256'] == second['leafFingerprintSha256']:
                    raise RuntimeError('The test did not rotate the actual key and certificate')
                sent, received = hashlib.sha256(), hashlib.sha256()
                for _ in range(256):
                    chunk = os.urandom(4096)
                    sent.update(chunk)
                    tunnel.sendall(chunk)
                    received.update(receive(tunnel, len(chunk)))
                if sent.digest() != received.digest():
                    raise RuntimeError('The upgraded stream changed bytes after rotation')
        result['longUpgradeSurvivedRotationBytes'] = 1024 * 1024
        state = settings.state_file.read_bytes()
        source_key.write_bytes(fixtures.CertificateSyncTest.materials[0][1])
        try:
            engine.sync()
        except sync.SyncError:
            backend.probe(second['leafFingerprintSha256'])
            assert settings.state_file.read_bytes() == state
        else:
            raise RuntimeError('Mismatched key was accepted')
        result['mismatchedKeyRejectedWithoutChangingLiveState'] = True
        assert installer.rollback()['rolledBack']
        assert conf.read_bytes() == original.encode() and conf.stat().st_ino == inode
        assert not include.exists() and not settings.state_file.exists() and not units.enabled
        backend.probe(legacy_fp, legacy=True)
        result['initialInstallAndExplicitRollbackRealNginx'] = True
        result['systemdActivationActuallyTested'] = False
    finally:
        if container:
            identity = docker('inspect', '--format', '{{.Id}} {{index .Config.Labels "org.rhremote.cert-sync-test"}}', container)
            if identity != container + ' ' + token:
                raise RuntimeError('Refusing to stop a container not owned by this test')
            docker('stop', '--time', '10', container)
            docker('rm', container)
        if echo:
            echo.shutdown()
            echo.server_close()
        assert root.parent.resolve() == Path(tempfile.gettempdir()).resolve()
        assert root.name.startswith('rhremote-nginx-integration-')
        temporary.cleanup()
        fixtures.CertificateSyncTest.tearDownClass()
        after = production_snapshot()
        if after != before:
            raise RuntimeError('Production Siyuan identity, config or certificate changed during the test')
    result.update(ok=True, productionSiyuanUnchanged=True, testContainerRemoved=True,
                  realProductionKeysCopied=False, realProductionReloaded=False)
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
