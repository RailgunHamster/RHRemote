"""On-host certificate validation and transactional Nginx renewal.

Private keys stay on the relay host. The default CLI mode is read-only; the
initial routing installer must create the managed bootstrap include explicitly.
"""
import argparse
import hashlib
import ipaddress
import json
import os
import re
import socket
import ssl
import stat
import subprocess
import tempfile
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path

MARKER = '# RHRemote managed certificate include v1\n'
HEX64 = re.compile(r'[a-f0-9]{64}')
CERT = re.compile(br'-----BEGIN CERTIFICATE-----\s+[A-Za-z0-9+/=\r\n]+-----END CERTIFICATE-----')
KEY = re.compile(br'-----BEGIN (PRIVATE KEY|RSA PRIVATE KEY|EC PRIVATE KEY)-----\s+[A-Za-z0-9+/=\r\n]+-----END \1-----')


class SyncError(RuntimeError):
    pass


def digest(value):
    return hashlib.sha256(value).hexdigest()


def no_symlinks(path):
    path = Path(path).absolute()
    for item in (path, *path.parents):
        if item.is_symlink():
            raise SyncError('Symbolic links are not accepted in managed file paths')
    return path


def read_regular(path, limit=65536, uid=None, private=False):
    path = no_symlinks(path)
    fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > limit:
            raise SyncError('Expected a bounded regular file')
        if uid is not None and info.st_uid != uid:
            raise SyncError('File owner differs from its configured service owner')
        if private and info.st_mode & 0o077:
            raise SyncError('Private key permissions allow another local account')
        value = stream.read(limit + 1)
        if len(value) > limit:
            raise SyncError('File exceeded the input limit')
        return value


def optional(path):
    try:
        return read_regular(path, uid=os.geteuid())
    except FileNotFoundError:
        return None


def private_directory(path):
    path = no_symlinks(path)
    if not path.exists():
        path.mkdir(mode=0o700)
    info = path.stat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077:
        raise SyncError('Managed directory must be private and owned by this service')
    return path


def atomic_replace(path, value, expected):
    path = no_symlinks(path)
    temporary = path.parent / ('.rhremote-write-' + uuid.uuid4().hex)
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
        if optional(path) != expected:
            raise SyncError('Concurrent file change; refusing to overwrite it')
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def restore(path, previous, ours):
    if optional(path) != ours:
        raise SyncError('Concurrent change prevented automatic rollback')
    if previous is None:
        path.unlink()
    else:
        atomic_replace(path, previous, ours)


def command(args, data=None, timeout=10):
    result = subprocess.run(args, input=data if data is not None else b'', stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=timeout, check=False)
    if result.returncode:
        # Never include subprocess buffers: a caller may be handling key material.
        raise SyncError('External certificate or container validation failed')
    return result.stdout


def certificates(value):
    parts = CERT.findall(value)
    if not parts or CERT.sub(b'', value).strip():
        raise SyncError('Certificate file must contain only PEM certificates')
    return parts


def fingerprint(value):
    leaf = certificates(value)[0]
    return digest(ssl.PEM_cert_to_DER_cert(leaf.decode('ascii')))


@dataclass(frozen=True)
class Material:
    pem_id: str
    fingerprint: str
    certificate: bytes = field(repr=False)
    key: bytes = field(repr=False)

    def public(self):
        return {'certificatePemSha256': self.pem_id, 'leafFingerprintSha256': self.fingerprint}


def validate_material(certificate, key, public_ip, ca_file, minimum_valid=86400, now=None):
    ipaddress.IPv4Address(public_ip)
    parts = certificates(certificate)
    if not KEY.fullmatch(key.strip()) or len(key) > 16384:
        raise SyncError('Expected one unencrypted PEM private key')
    public = command(['openssl', 'x509', '-pubkey', '-noout'], parts[0])
    cert_public = command(['openssl', 'pkey', '-pubin', '-outform', 'DER'], public)
    key_public = command(['openssl', 'pkey', '-pubout', '-outform', 'DER', '-passin', 'pass:'], key)
    if cert_public != key_public:
        raise SyncError('Certificate and private key do not match')
    instant = int(time.time() if now is None else now)
    with tempfile.TemporaryDirectory(prefix='rhremote-public-chain-') as folder:
        leaf = Path(folder) / 'leaf.pem'
        chain = Path(folder) / 'chain.pem'
        leaf.write_bytes(parts[0] + b'\n')
        chain.write_bytes(certificate)
        for at in (instant, instant + minimum_valid):
            command(['openssl', 'verify', '-CAfile', str(ca_file), '-untrusted', str(chain),
                     '-purpose', 'sslserver', '-verify_ip', public_ip, '-attime', str(at), str(leaf)])
    return Material(digest(certificate), fingerprint(certificate), certificate, key)


class Settings:
    def __init__(self, value):
        required = {'schema', 'publicIp', 'sourceCertificate', 'sourcePrivateKey', 'sourceUid',
                    'caFile', 'sslDirectory', 'sslMount', 'nginxConfig', 'nginxMount',
                    'activeConfigSha256', 'originalConfigSha256', 'container', 'containerId',
                    'stateDirectory', 'probeAddress', 'probePort'}
        if set(value) != required or value['schema'] != 'rhremote.nginx-cert-sync/v1':
            raise SyncError('Unsupported or incomplete certificate synchronizer configuration')
        ipaddress.IPv4Address(value['publicIp'])
        ipaddress.ip_address(value['probeAddress'])
        if type(value['probePort']) is not int or (value['probePort'] != 443 and not 1024 < value['probePort'] < 65536):
            raise SyncError('Invalid local TLS probe port')
        for key in ('activeConfigSha256', 'originalConfigSha256', 'containerId'):
            if not isinstance(value[key], str) or not HEX64.fullmatch(value[key]):
                raise SyncError('Expected a pinned configuration or container identity')
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', value['container']):
            raise SyncError('Invalid container name')
        if type(value['sourceUid']) is not int or value['sourceUid'] < 0:
            raise SyncError('Invalid source service owner')
        for key in ('sourceCertificate', 'sourcePrivateKey', 'caFile', 'sslDirectory',
                    'sslMount', 'nginxConfig', 'nginxMount', 'stateDirectory'):
            if not isinstance(value[key], str) or not value[key].startswith('/') or any(c in value[key] for c in '\r\n\0'):
                raise SyncError('Expected an absolute on-host path')
        for key in ('sslMount', 'nginxMount'):
            if not re.fullmatch(r'/[A-Za-z0-9_./-]+', value[key]) or '..' in Path(value[key]).parts:
                raise SyncError('Container paths cannot contain Nginx directives')
        self.v = value
        self.root = Path(value['sslDirectory']) / 'rhremote-443'
        self.include = self.root / 'current.conf'
        self.state_root = Path(value['stateDirectory'])
        self.state_file = self.state_root / 'state.json'

    def bootstrap(self):
        mount = self.v['sslMount'].rstrip('/')
        return (MARKER + f'ssl_certificate {mount}/siyuan.crt;\n'
                f'ssl_certificate_key {mount}/siyuan.key;\n').encode()

    def include_for(self, material):
        mount = self.v['sslMount'].rstrip('/')
        return (MARKER + f'ssl_certificate {mount}/rhremote-443/{material.pem_id}/fullchain.pem;\n'
                f'ssl_certificate_key {mount}/rhremote-443/{material.pem_id}/private.key;\n').encode()

    def material(self):
        cert = read_regular(self.v['sourceCertificate'], uid=self.v['sourceUid'])
        key = read_regular(self.v['sourcePrivateKey'], limit=16384, uid=self.v['sourceUid'], private=True)
        return validate_material(cert, key, self.v['publicIp'], self.v['caFile'])


class DockerBackend:
    def __init__(self, settings):
        self.s = settings

    def docker(self, *args):
        return command(['docker', '--host', 'unix:///var/run/docker.sock', *args], timeout=20)

    def binding(self, allowed):
        v = self.s.v
        template = '{"Id":{{json .Id}},"Running":{{json .State.Running}},"Mounts":{{json .Mounts}}}'
        info = json.loads(self.docker('inspect', '--format', template, v['container']))
        if info['Id'] != v['containerId'] or not info['Running']:
            raise SyncError('The selected container is no longer the inspected instance')
        mounts = {item['Destination']: item for item in info['Mounts']}
        for destination, source in ((v['nginxMount'], v['nginxConfig']), (v['sslMount'], v['sslDirectory'])):
            item = mounts.get(destination)
            if not item or item['Type'] != 'bind' or item['Source'] != source or item['RW']:
                raise SyncError('Container mounts differ from the inspected read-only bindings')
        local = read_regular(v['nginxConfig'])
        remote = self.docker('exec', v['container'], 'cat', v['nginxMount'])
        if local != remote or digest(local) not in allowed:
            raise SyncError('Nginx source changed or the file mount still exposes another inode')
        return digest(local)

    def check(self):
        self.docker('exec', self.s.v['container'], 'nginx', '-t', '-c', self.s.v['nginxMount'])

    def reload(self):
        source = read_regular(self.s.v['nginxConfig']).decode('utf-8')
        paths = re.findall(r'(?m)^\s*pid\s+([^;\s]+);\s*$', source)
        if len(paths) > 1:
            raise SyncError('Ambiguous Nginx master PID file')
        pidfile = paths[0] if paths else '/var/run/nginx.pid'
        if not re.fullmatch(r'/[A-Za-z0-9_./-]+', pidfile):
            raise SyncError('Invalid Nginx master PID file')
        pid = self.docker('exec', self.s.v['container'], 'cat', pidfile).strip()
        if not pid.isdigit() or int(pid) < 1:
            raise SyncError('Nginx master PID is unavailable')
        title = self.docker('exec', self.s.v['container'], 'cat', '/proc/' + pid.decode() + '/cmdline')
        if b'nginx: master process' not in title:
            raise SyncError('The PID file does not belong to an Nginx master')
        self.docker('exec', self.s.v['container'], 'nginx', '-s', 'reload', '-c', self.s.v['nginxMount'])
        if self.docker('exec', self.s.v['container'], 'cat', pidfile).strip() != pid:
            raise SyncError('Nginx master identity changed during reload')

    def probe_one(self, expected, version, legacy=False):
        if legacy:
            known = fingerprint(read_regular(Path(self.s.v['sslDirectory']) / 'siyuan.crt'))
            if expected != known:
                raise SyncError('Only the inspected legacy certificate may use fingerprint pinning')
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
        else:
            context = ssl.create_default_context(cafile=self.s.v['caFile'])
        context.minimum_version = context.maximum_version = version
        with socket.create_connection((self.s.v['probeAddress'], self.s.v['probePort']), timeout=2) as raw:
            with context.wrap_socket(raw, server_hostname=self.s.v['publicIp']) as stream:
                if digest(stream.getpeercert(binary_form=True)) != expected:
                    raise SyncError('Nginx has not loaded the expected certificate')

    def probe(self, expected, legacy=False):
        # Reload signals are asynchronous. One matching handshake can still be
        # served between two queued worker generations, so require a stable
        # window before committing or starting a subsequent reload.
        deadline = time.monotonic() + 12
        stable_since = None
        while True:
            try:
                for version in (ssl.TLSVersion.TLSv1_2, ssl.TLSVersion.TLSv1_3):
                    self.probe_one(expected, version, legacy)
                now = time.monotonic()
                if stable_since is None:
                    stable_since = now
                elif now - stable_since >= 1:
                    return
            except (OSError, SyncError):
                stable_since = None
            if time.monotonic() >= deadline:
                raise SyncError('Live TLS 1.2/1.3 certificate verification failed') from None
            time.sleep(0.2)


class Synchronizer:
    def __init__(self, settings, backend):
        self.s, self.backend = settings, backend

    def dry_run(self):
        self.backend.binding({self.s.v['activeConfigSha256'], self.s.v['originalConfigSha256']})
        material = self.s.material()
        return dict(ok=True, mode='dry-run', privateKeysCopied=False, reloaded=False, **material.public())

    def stage_version(self, material):
        root = private_directory(self.s.root)
        version = root / material.pem_id
        if version.exists():
            private_directory(version)
            if (read_regular(version / 'fullchain.pem', uid=os.geteuid()) != material.certificate
                    or read_regular(version / 'private.key', uid=os.geteuid(), private=True) != material.key):
                raise SyncError('Existing immutable certificate version differs from the source')
            return
        temporary = root / ('.stage-' + uuid.uuid4().hex)
        temporary.mkdir(mode=0o700)
        try:
            for name, data in (('fullchain.pem', material.certificate), ('private.key', material.key)):
                atomic_replace(temporary / name, data, None)
            os.rename(temporary, version)
        finally:
            if temporary.exists():
                # Only our two exact files; never recursively remove an operator's directory.
                for name in ('fullchain.pem', 'private.key'):
                    path = temporary / name
                    if path.exists():
                        path.unlink()
                temporary.rmdir()

    def sync(self):
        import fcntl
        private_directory(self.s.state_root)
        lock = os.open(no_symlinks(self.s.state_root / 'sync.lock'), os.O_WRONLY | os.O_CREAT | getattr(os, 'O_NOFOLLOW', 0), 0o600)
        try:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise SyncError('Another certificate synchronization is still running') from None
            return self._sync_locked()
        finally:
            os.close(lock)

    def _sync_locked(self):
        self.backend.binding({self.s.v['activeConfigSha256']})
        material = self.s.material()
        private_directory(self.s.root)
        old_include = optional(self.s.include)
        old_bytes = optional(self.s.state_file)
        previous = json.loads(old_bytes) if old_bytes is not None else None
        if previous:
            if (previous.get('schema') != 'rhremote.nginx-cert-state/v1'
                    or previous.get('containerId') != self.s.v['containerId']
                    or previous.get('includeSha256') != digest(old_include or b'')):
                raise SyncError('Managed certificate state or include was changed')
            old_fingerprint = previous['leafFingerprintSha256']
        else:
            if old_include != self.s.bootstrap():
                raise SyncError('The initial installer must create the exact managed bootstrap include')
            old_fingerprint = fingerprint(read_regular(Path(self.s.v['sslDirectory']) / 'siyuan.crt'))
        new_include = self.s.include_for(material)
        self.backend.probe(old_fingerprint, legacy=previous is None)
        self.stage_version(material)
        if previous and previous.get('certificatePemSha256') == material.pem_id and old_include == new_include:
            return dict(ok=True, changed=False, reloaded=False, **material.public())
        backup = self.s.state_root / 'previous.json'
        backup_bytes = json.dumps({'schema': 'rhremote.nginx-cert-backup/v1', 'include': old_include.decode(),
                                   'state': previous, 'nextFingerprintSha256': material.fingerprint}).encode()
        atomic_replace(backup, backup_bytes, optional(backup))
        state = dict(schema='rhremote.nginx-cert-state/v1', containerId=self.s.v['containerId'],
                     nginxConfigSha256=self.s.v['activeConfigSha256'], includeSha256=digest(new_include),
                     updatedAt=int(time.time()), **material.public())
        state_bytes = json.dumps(state, sort_keys=True).encode()
        promoted = reloaded = False
        try:
            self.backend.binding({self.s.v['activeConfigSha256']})
            atomic_replace(self.s.include, new_include, old_include)
            promoted = True
            self.backend.check()
            reloaded = True
            self.backend.reload()
            self.backend.probe(material.fingerprint)
            atomic_replace(self.s.state_file, state_bytes, old_bytes)
        except Exception as failure:
            try:
                if promoted:
                    restore(self.s.include, old_include, new_include)
                if optional(self.s.state_file) == state_bytes:
                    restore(self.s.state_file, old_bytes, state_bytes)
                if reloaded:
                    self.backend.check()
                    self.backend.reload()
                    self.backend.probe(old_fingerprint, legacy=previous is None)
            except Exception:
                raise SyncError('Certificate update failed and rollback needs on-host inspection') from None
            raise SyncError('Certificate update rejected; previous include and live certificate restored') from failure
        return dict(ok=True, changed=True, reloaded=True, **material.public())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--mode', choices=['dry-run', 'sync'], default='dry-run')
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise SyncError('Run the on-host service with its configured root identity')
    value = json.loads(read_regular(args.config, uid=0, private=True))
    settings = Settings(value)
    synchronizer = Synchronizer(settings, DockerBackend(settings))
    result = synchronizer.dry_run() if args.mode == 'dry-run' else synchronizer.sync()
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        message = str(error) if isinstance(error, SyncError) else 'Certificate synchronization failed; inspect on-host diagnostics'
        print(json.dumps({'ok': False, 'error': message}))
        raise SystemExit(1)
