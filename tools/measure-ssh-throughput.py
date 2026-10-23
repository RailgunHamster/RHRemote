"""Bounded synthetic TCP throughput over an existing SSH host alias.

No listeners, firewall changes, user files or media. SSH compression is disabled.
This is a path baseline, not a relay or video acceptance test.
"""
import argparse
import hashlib
import json
import re
import shlex
import subprocess
import threading
import time

BLOCK = bytes((i * 17 + 29) % 251 for i in range(65536))
REMOTE = '''import hashlib,json,os,sys,time
direction,total=sys.argv[1],int(sys.argv[2])
assert direction in ('download','upload') and 1048576<=total<=67108864
block=bytes((i*17+29)%251 for i in range(65536))
print(json.dumps({'ready':True,'bytes':total,'direction':direction}),flush=True)
def ssh_cpu():
    try:
        with open('/proc/'+str(os.getppid())+'/stat') as source:
            fields=source.read().rsplit(')',1)[1].split()
        return (int(fields[11])+int(fields[12]))/os.sysconf('SC_CLK_TCK')
    except (OSError,ValueError,IndexError): return None
digest=hashlib.sha256();remaining=total;started=time.monotonic();cpu=time.process_time();ssh_before=ssh_cpu()
while remaining:
    part=block[:min(len(block),remaining)] if direction=='download' else sys.stdin.buffer.read(min(65536,remaining))
    if not part: raise RuntimeError('Synthetic stream ended early')
    if direction=='download': sys.stdout.buffer.write(part)
    digest.update(part);remaining-=len(part)
if direction=='download': sys.stdout.buffer.flush()
ssh_after=ssh_cpu()
print(json.dumps({'ok':True,'bytes':total,'sha256':digest.hexdigest(),'seconds':time.monotonic()-started,
                 'pythonCpuSeconds':time.process_time()-cpu,
                 'sshCpuSeconds':None if ssh_before is None or ssh_after is None else ssh_after-ssh_before}),flush=True)
'''


def read_json(stream):
    value = stream.readline(4096)
    if not value.endswith(b'\n') or len(value) >= 4096:
        raise RuntimeError('Unexpected bounded SSH test response')
    return json.loads(value)


def expected_digest(size):
    digest = hashlib.sha256()
    for offset in range(0, size, len(BLOCK)):
        digest.update(BLOCK[:min(len(BLOCK), size - offset)])
    return digest.hexdigest()


def measure(host, direction, mib, timeout=90, ssh='ssh'):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,63}', host):
        raise ValueError('Use a configured SSH alias, without shell syntax')
    if direction not in ('download', 'upload') or type(mib) is not int or not 1 <= mib <= 64:
        raise ValueError('Expected download/upload and 1..64 MiB')
    size = mib * 1048576
    wanted = expected_digest(size)
    remote_command = 'python3 -u -c ' + shlex.quote(REMOTE) + ' ' + direction + ' ' + str(size)
    started = time.monotonic()
    process = subprocess.Popen([ssh, '-T', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10',
                                '-o', 'Compression=no', host, remote_command],
                               stdin=subprocess.PIPE if direction == 'upload' else subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    result, errors = {}, []

    def transfer():
        try:
            ready = read_json(process.stdout)
            if ready != dict(ready=True, bytes=size, direction=direction):
                raise RuntimeError('Remote synthetic test parameters differ')
            payload_started = sample_started = time.monotonic()
            count = sample_bytes = 0
            samples = []
            digest = hashlib.sha256()
            while count < size:
                remaining = min(len(BLOCK), size - count)
                if direction == 'download':
                    part = process.stdout.read(remaining)
                    if not part:
                        raise RuntimeError('Synthetic payload ended early')
                else:
                    part = BLOCK[:remaining]
                    process.stdin.write(part)
                digest.update(part)
                count += len(part)
                sample_bytes += len(part)
                now = time.monotonic()
                if now - sample_started >= 1:
                    samples.append(dict(seconds=round(now - sample_started, 6), bytes=sample_bytes,
                                        mbps=round(sample_bytes * 8 / (now - sample_started) / 1e6, 3)))
                    sample_started, sample_bytes = now, 0
            if direction == 'upload':
                process.stdin.flush()
                process.stdin.close()
            response = read_json(process.stdout)
            payload_seconds = time.monotonic() - payload_started
            if response.get('ok') is not True or response.get('bytes') != size:
                raise RuntimeError('Remote synthetic byte count differs')
            if digest.hexdigest() != wanted or response.get('sha256') != wanted:
                raise RuntimeError('Synthetic payload integrity check failed')
            if process.stdout.read(1):
                raise RuntimeError('Unexpected data after the bounded transfer')
            result.update(ok=True, direction=direction, bytes=size, sha256=wanted, integrityVerified=True,
                          payloadSeconds=round(payload_seconds, 6),
                          payloadMbps=round(size * 8 / payload_seconds / 1e6, 3),
                          remotePayloadSeconds=round(response['seconds'], 6),
                          remotePythonCpuSeconds=response['pythonCpuSeconds'],
                          remoteSshCpuSeconds=response['sshCpuSeconds'],
                          setupSeconds=round(payload_started - started, 6), samples=samples)
        except Exception as failure:
            errors.append(failure)

    worker = threading.Thread(target=transfer, daemon=True)
    worker.start()
    try:
        worker.join(timeout)
        if worker.is_alive():
            raise TimeoutError('Synthetic SSH transfer exceeded its deadline')
        if errors:
            raise errors[0]
        if process.wait(timeout=5):
            # Suppress raw SSH diagnostics; never copy local credential output.
            raise RuntimeError('SSH synthetic transfer exited unsuccessfully')
        return dict(result, totalSeconds=round(time.monotonic() - started, 6),
                    compressionEnabled=False, containsUserData=False, opensListeners=False)
    finally:
        if process.poll() is None:
            process.kill()
        process.wait(timeout=5)
        worker.join(2)
        for stream in (process.stdout, process.stderr, process.stdin):
            if stream is not None and not stream.closed:
                stream.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='tc')
    parser.add_argument('--direction', choices=('download', 'upload', 'both'), default='both')
    parser.add_argument('--mib', type=int, default=16)
    parser.add_argument('--timeout', type=int, default=90)
    args = parser.parse_args()
    if not 10 <= args.timeout <= 180:
        parser.error('timeout must be 10..180 seconds')
    directions = ('download', 'upload') if args.direction == 'both' else (args.direction,)
    for direction in directions:
        print(json.dumps(measure(args.host, direction, args.mib, args.timeout), sort_keys=True), flush=True)


if __name__ == '__main__':
    main()
