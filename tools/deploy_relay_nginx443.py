"""Install the reviewed 443 configuration, or restore its saved original.

Defaults to a read-only check. Run on the relay host as root after reviewing the
certificate change. Certificate keys never leave that host.
"""
import argparse
import json
import os
import re
from contextlib import contextmanager
from pathlib import Path

import relay_nginx_cert as cert

PROGRAM = Path('/usr/local/lib/rhremote-nginx443')
CONFIG = Path('/etc/rhremote-nginx443/config.json')
UNITS = Path('/etc/systemd/system')
NAME = 'rhremote-nginx-cert-sync'
UNIT_MARKER = '# RHRemote managed Nginx certificate synchronization v1\n'


def unit_files(settings, program=PROGRAM, config=CONFIG):
    values = (str(program), str(config), str(settings.root), str(settings.state_root))
    if any(not re.fullmatch(r'/[A-Za-z0-9_./-]+', value) for value in values):
        raise cert.SyncError('Service paths must not contain systemd directives or substitutions')
    service = UNIT_MARKER + f'''[Unit]
Description=RHRemote Nginx certificate synchronization
After=docker.service rhremote-tls.service
Requires=docker.service
[Service]
Type=oneshot
User=root
ExecStart=/usr/bin/python3 -B {program}/relay_nginx_cert.py --config {config} --mode sync
TimeoutStartSec=180
UMask=0077
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=read-only
ReadWritePaths={settings.root} {settings.state_root}
PrivateTmp=yes
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
MemoryMax=128M
TasksMax=64
'''
    timer = UNIT_MARKER + f'''[Unit]
Description=Check RHRemote Nginx certificate hourly
[Timer]
OnCalendar=hourly
RandomizedDelaySec=90s
Persistent=true
Unit={NAME}.service
[Install]
WantedBy=timers.target
'''
    return {NAME + '.service': service.encode(), NAME + '.timer': timer.encode()}


class Systemd:
    def __init__(self, directory=UNITS):
        self.directory = directory

    def command(self, *args):
        return cert.command(['systemctl', *args], timeout=30)

    def preflight(self, files):
        for name in files:
            if cert.optional(self.directory / name) is not None:
                raise cert.SyncError('An existing unit requires an explicit upgrade plan')
            # Includes aliases and units from vendor/transient directories.
            if self.command('show', name, '--property=LoadState', '--value').strip() != b'not-found':
                raise cert.SyncError('The certificate synchronization unit name is already in use')

    def install(self, files):
        for name, value in files.items():
            cert.atomic_replace(self.directory / name, value, None)
        self.command('daemon-reload')
        self.command('enable', '--now', NAME + '.timer')
        self.command('is-enabled', '--quiet', NAME + '.timer')
        self.command('is-active', '--quiet', NAME + '.timer')

    def verify_owned(self, files):
        for name, value in files.items():
            if cert.optional(self.directory / name) != value:
                raise cert.SyncError('A managed systemd unit changed; refusing to overwrite it')

    def remove(self, files):
        # A partial first install can leave only one unit. Never remove a file
        # whose current contents do not exactly match this installation.
        present = {name: value for name, value in files.items()
                   if cert.optional(self.directory / name) is not None}
        for name, value in present.items():
            if cert.optional(self.directory / name) != value:
                raise cert.SyncError('A systemd unit changed during rollback')
        if present:
            self.command('daemon-reload')
        if NAME + '.timer' in present:
            self.command('disable', '--now', NAME + '.timer')
        if NAME + '.service' in present:
            self.command('stop', NAME + '.service')
        for name, value in present.items():
            cert.restore(self.directory / name, None, value)
        self.command('daemon-reload')


def write_in_place(path, value, expected):
    """Keep the inode used by Docker's single-file mount; restore partial writes."""
    path = cert.no_symlinks(path)
    fd = os.open(path, os.O_RDWR | getattr(os, 'O_NOFOLLOW', 0))
    try:
        info = os.fstat(fd)
        if cert.read_regular(path) != expected or os.read(fd, len(expected) + 1) != expected:
            raise cert.SyncError('Nginx changed before the in-place write')
        written = 0
        truncated = False
        try:
            os.ftruncate(fd, 0)
            truncated = True
            os.lseek(fd, 0, os.SEEK_SET)
            while written < len(value):
                count = os.write(fd, value[written:])
                if count <= 0:
                    raise OSError('In-place configuration write made no progress')
                written += count
            os.fsync(fd)
            current = path.stat()
            if (current.st_dev, current.st_ino) != (info.st_dev, info.st_ino) or cert.read_regular(path) != value:
                raise cert.SyncError('Nginx source or mount inode changed during the write')
        except Exception:
            current = path.stat()
            if truncated:
                if ((current.st_dev, current.st_ino) != (info.st_dev, info.st_ino)
                        or cert.read_regular(path) != value[:written]):
                    raise cert.SyncError('Concurrent change prevents restoring a partial configuration write') from None
                os.lseek(fd, 0, os.SEEK_SET)
                os.ftruncate(fd, 0)
                with os.fdopen(os.dup(fd), 'wb') as stream:
                    stream.write(expected)
                    stream.flush()
                    os.fsync(stream.fileno())
            raise
    finally:
        os.close(fd)


@contextmanager
def transaction_lock(settings, name='install.lock'):
    import fcntl
    cert.private_directory(settings.state_root)
    path = cert.no_symlinks(settings.state_root / name)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise cert.SyncError('Another installation or rollback is running') from None
        yield
    finally:
        os.close(fd)


class Installer:
    def __init__(self, settings, backend=None, units=None, program=PROGRAM, config=CONFIG):
        self.s = settings
        self.backend = backend or cert.DockerBackend(settings)
        self.units = units or Systemd()
        self.program, self.config = Path(program), Path(config)
        self.journal = settings.state_root / 'install.json'
        self.files = unit_files(settings, self.program, self.config)

    def candidate(self, value):
        if cert.digest(value) != self.s.v['activeConfigSha256'] or len(value) > 65536:
            raise cert.SyncError('Candidate does not match the reviewed configuration hash')
        include = f"include {self.s.v['sslMount']}/rhremote-443/current.conf;".encode()
        if value.count(include) != 1:
            raise cert.SyncError('Candidate must contain the exact managed certificate include')

    def dry_run(self, value):
        self.candidate(value)
        result = cert.Synchronizer(self.s, self.backend).dry_run()
        self.backend.binding({self.s.v['originalConfigSha256']})
        self.units.preflight(self.files)
        if cert.optional(self.s.include) is not None or cert.optional(self.s.state_file) is not None:
            raise cert.SyncError('Existing certificate state requires review before a first install')
        for path in (self.program / 'relay_nginx_cert.py', self.program / Path(__file__).name, self.config):
            if cert.optional(path) is not None:
                raise cert.SyncError('Existing installed code or configuration requires an upgrade plan')
        return dict(result, candidateSha256=cert.digest(value), systemdUnits=list(self.files), productionChanged=False)

    def apply(self, value):
        with transaction_lock(self.s):
            self.dry_run(value)
            if cert.optional(self.journal) is not None:
                raise cert.SyncError('An earlier installation journal requires inspection')
            original = cert.read_regular(self.s.v['nginxConfig'])
            legacy = cert.read_regular(Path(self.s.v['sslDirectory']) / 'siyuan.crt')
            legacy_fp = cert.fingerprint(legacy)
            self.backend.probe(legacy_fp, legacy=True)
            originals = {'nginxConfig': original.decode(), 'legacyPemSha256': cert.digest(legacy),
                         'legacyFingerprintSha256': legacy_fp}
            installed = {
                self.program / 'relay_nginx_cert.py': Path(cert.__file__).read_bytes(),
                self.program / Path(__file__).name: Path(__file__).read_bytes(),
                self.config: json.dumps(self.s.v, sort_keys=True).encode(),
            }
            journal = dict(schema='rhremote.nginx443.install/v1', status='preparing',
                           containerId=self.s.v['containerId'], candidateSha256=cert.digest(value),
                           installedHashes={str(path): cert.digest(data) for path, data in installed.items()},
                           **originals)
            journal_bytes = json.dumps(journal, sort_keys=True).encode()
            cert.atomic_replace(self.journal, journal_bytes, None)
            created = {}
            changed = include_created = False
            try:
                cert.private_directory(self.s.root)
                cert.atomic_replace(self.s.include, self.s.bootstrap(), None)
                include_created = True
                # Parent directories are private and reserved for this service.
                for path, data in installed.items():
                    cert.private_directory(path.parent)
                    cert.atomic_replace(path, data, None)
                    created[path] = data
                write_in_place(Path(self.s.v['nginxConfig']), value, original)
                changed = True
                self.backend.binding({self.s.v['activeConfigSha256']})
                self.backend.check()
                result = cert.Synchronizer(self.s, self.backend).sync()
                self.units.install(self.files)
                journal['status'] = 'applied'
                cert.atomic_replace(self.journal, json.dumps(journal, sort_keys=True).encode(), journal_bytes)
            except Exception as failure:
                try:
                    self.units.remove(self.files)
                    if changed:
                        write_in_place(Path(self.s.v['nginxConfig']), original, value)
                        self.backend.binding({self.s.v['originalConfigSha256']})
                        self.backend.check()
                        self.backend.reload()
                        self.backend.probe(legacy_fp, legacy=True)
                    # Key versions and diagnostics remain private for inspection.
                    # Remove only the include/state that this transaction owns.
                    if include_created:
                        self._remove_owned_state()
                    for path, data in created.items():
                        cert.restore(path, None, data)
                    journal['status'] = 'rolled-back'
                    cert.atomic_replace(self.journal, json.dumps(journal, sort_keys=True).encode(), journal_bytes)
                except Exception:
                    raise cert.SyncError('Installation failed; automatic rollback needs on-host inspection') from None
                raise cert.SyncError('Installation rejected; original configuration and certificate restored') from failure
            return dict(result, installed=True, hourlyTimerEnabled=True, backup=str(self.journal))

    def _owned_state(self):
        include, state_bytes = cert.optional(self.s.include), cert.optional(self.s.state_file)
        if state_bytes is None:
            if include != self.s.bootstrap():
                raise cert.SyncError('Bootstrap include changed during rollback')
        else:
            state = json.loads(state_bytes)
            if (state.get('schema') != 'rhremote.nginx-cert-state/v1'
                    or state.get('containerId') != self.s.v['containerId']
                    or state.get('includeSha256') != cert.digest(include or b'')):
                raise cert.SyncError('Certificate state changed during rollback')
        return include, state_bytes

    def _remove_owned_state(self):
        include, state_bytes = self._owned_state()
        if state_bytes is not None:
            cert.restore(self.s.state_file, None, state_bytes)
        cert.restore(self.s.include, None, include)

    def rollback(self):
        with transaction_lock(self.s):
            journal_bytes = cert.read_regular(self.journal, uid=os.geteuid(), private=True)
            journal = json.loads(journal_bytes)
            if (journal.get('schema') != 'rhremote.nginx443.install/v1' or journal.get('status') != 'applied'
                    or journal.get('containerId') != self.s.v['containerId']
                    or journal.get('candidateSha256') != self.s.v['activeConfigSha256']):
                raise cert.SyncError('No matching completed installation is available to roll back')
            original = journal['nginxConfig'].encode()
            if cert.digest(original) != self.s.v['originalConfigSha256']:
                raise cert.SyncError('Original configuration backup changed')
            legacy = cert.read_regular(Path(self.s.v['sslDirectory']) / 'siyuan.crt')
            if cert.digest(legacy) != journal['legacyPemSha256']:
                raise cert.SyncError('Original certificate changed; review rollback before proceeding')
            self.backend.binding({self.s.v['activeConfigSha256']})
            self.units.verify_owned(self.files)
            self._owned_state()
            self.units.remove(self.files)
            # Stopping the service above waits for an in-progress timer run.
            with transaction_lock(self.s, 'sync.lock'):
                self.backend.binding({self.s.v['activeConfigSha256']})
                current = cert.read_regular(self.s.v['nginxConfig'])
                if cert.digest(current) != self.s.v['activeConfigSha256']:
                    raise cert.SyncError('Nginx changed while stopping certificate synchronization')
                write_in_place(Path(self.s.v['nginxConfig']), original, current)
                self.backend.binding({self.s.v['originalConfigSha256']})
                self.backend.check()
                self.backend.reload()
                self.backend.probe(journal['legacyFingerprintSha256'], legacy=True)
                self._remove_owned_state()
            journal['status'] = 'rolled-back'
            cert.atomic_replace(self.journal, json.dumps(journal, sort_keys=True).encode(), journal_bytes)
            return dict(ok=True, rolledBack=True, originalConfigSha256=cert.digest(original), timerEnabled=False,
                        retainedPrivateDiagnostics=str(self.s.state_root))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--candidate', type=Path)
    parser.add_argument('--mode', choices=['dry-run', 'apply', 'rollback'], default='dry-run')
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise cert.SyncError('Run the on-host installer as root')
    settings = cert.Settings(json.loads(cert.read_regular(args.config, uid=0, private=True)))
    installer = Installer(settings)
    if args.mode == 'rollback':
        result = installer.rollback()
    else:
        if not args.candidate:
            raise cert.SyncError('A reviewed candidate is required')
        value = cert.read_regular(args.candidate, uid=0, private=True)
        result = installer.apply(value) if args.mode == 'apply' else installer.dry_run(value)
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        message = str(error) if isinstance(error, cert.SyncError) else 'Installation failed; inspect on-host diagnostics'
        print(json.dumps({'ok': False, 'error': message}))
        raise SystemExit(1)
