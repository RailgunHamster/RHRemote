"""Prepare a reviewable Nginx 443 change; never modify or reload a server."""
import argparse
import difflib
import hashlib
import ipaddress
import json
import re
from pathlib import Path

BEGIN = '        # BEGIN RHRemote relay routes\n'
END = '        # END RHRemote relay routes\n'
ANCHOR = '        # Main Siyuan Proxy\n'
OLD_CERT = ('        ssl_certificate      /etc/nginx/ssl/siyuan.crt;\n'
            '        ssl_certificate_key  /etc/nginx/ssl/siyuan.key;\n')
CERT_INCLUDE = '        include /etc/nginx/ssl/rhremote-443/current.conf;\n'
ECDSA_CIPHERS = ('ECDHE-ECDSA-AES256-GCM-SHA384', 'ECDHE-ECDSA-AES128-GCM-SHA256')


def compatible_ciphers(text):
    matches = list(re.finditer(r'(?m)^([ \t]*)ssl_ciphers[ \t]+([^;\r\n]+);$', text))
    if len(matches) != 1:
        raise ValueError('Review the TLS cipher policy before installing an ECDSA certificate')
    match = matches[0]
    old = match.group(0)
    existing = match.group(2).split(':')
    missing = [cipher for cipher in ECDSA_CIPHERS if cipher not in existing]
    new = match.group(1) + 'ssl_ciphers ' + ':'.join(missing + existing) + ';'
    return text[:match.start()] + new + text[match.end():], old, new


def routes(gateway):
    address = ipaddress.IPv4Address(gateway)
    if not any(address in ipaddress.IPv4Network(net) for net in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')):
        raise ValueError('The upstream must be an inspected private Docker gateway')
    out = [BEGIN]
    for path in ('/relay', '/derp', '/ping', '/generate_204'):
        out.append(f'''        location = {path} {{
            proxy_pass http://{address}:33447;
            proxy_http_version 1.1;
            proxy_set_header Upgrade $http_upgrade;
            proxy_set_header Connection $connection_upgrade;
            proxy_set_header Host $host;
            proxy_read_timeout 86400s;
            proxy_send_timeout 86400s;
            proxy_buffering off;
        }}
''')
    out.append(f'''        location ^~ /rhremote/ {{
            proxy_pass http://{address}:33448/;
            proxy_http_version 1.1;
            proxy_set_header Host $host;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_read_timeout 30s;
        }}
''')
    return ''.join(out) + END + '\n'


def prepare(original, gateway, public_ip, cert_id):
    ipaddress.IPv4Address(public_ip)
    if not re.fullmatch(r'[a-f0-9]{64}', cert_id):
        raise ValueError('Expected the inspected certificate PEM SHA256')
    for marker in (ANCHOR, OLD_CERT, f'        server_name  {public_ip};'):
        if original.count(marker) != 1:
            raise ValueError(f'Unexpected configuration anchor: {marker[:70]}')
    if (len(original.encode()) > 262144 or BEGIN in original
            or len(re.findall(r'\blisten\s+443\s+ssl;', original)) != 1
            or original.count('map $http_upgrade $connection_upgrade') != 1):
        raise ValueError('Review the changed Nginx layout before generating a plan')
    addition = routes(gateway)
    syntax_candidate = original.replace(ANCHOR, addition + ANCHOR)
    candidate = syntax_candidate.replace(OLD_CERT, CERT_INCLUDE)
    candidate, old_ciphers, new_ciphers = compatible_ciphers(candidate)
    # Removing only our route block and TLS include must restore the exact snapshot.
    if candidate.replace(addition, '').replace(CERT_INCLUDE, OLD_CERT).replace(new_ciphers, old_ciphers) != original:
        raise ValueError('Unrelated configuration changed')
    certificate = ('# RHRemote managed certificate include v1\n'
                   f'ssl_certificate /etc/nginx/ssl/rhremote-443/{cert_id}/fullchain.pem;\n'
                   f'ssl_certificate_key /etc/nginx/ssl/rhremote-443/{cert_id}/private.key;\n')
    return {
        'routes-only.nginx.conf': syntax_candidate,
        'https-443.nginx.conf': candidate,
        'certificate-current.conf': certificate,
        'proposed.diff': ''.join(difflib.unified_diff(original.splitlines(True), candidate.splitlines(True),
                                                    fromfile='siyuan-nginx.original.conf', tofile='https-443.nginx.conf')),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--nginx-conf', type=Path, required=True)
    parser.add_argument('--gateway', required=True)
    parser.add_argument('--public-ip', required=True)
    parser.add_argument('--cert-id', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    original = args.nginx_conf.read_text(encoding='utf-8')
    files = prepare(original, args.gateway, args.public_ip, args.cert_id)
    args.output.mkdir(parents=True, exist_ok=False)
    hashes = {}
    for name, content in files.items():
        data = content.encode()
        (args.output / name).write_bytes(data)
        hashes[name] = hashlib.sha256(data).hexdigest()
    manifest = dict(schema='rhremote.relay443.plan/v1', status='prepared-not-applied',
                    sourceSha256=hashlib.sha256(original.encode()).hexdigest(),
                    publicIp=args.public_ip, gateway=args.gateway, certificatePemSha256=args.cert_id,
                    hashes=hashes, reloadPerformed=False, privateKeysCopied=False,
                    requiresReview='Changes the certificate presented by the existing Siyuan 443 endpoint',
                    gates=['Verify snapshot and Docker mount identity before applying',
                           'Keep the single-file bind mount inode; replacing its host path may leave the old mounted file',
                           'Install and verify a matching valid certificate/key pair locally on tc',
                           'Test the final TLS configuration inside the actual container before graceful reload',
                           'Install automatic certificate synchronization before accepting a short-lived certificate',
                           'Verify Siyuan HTTP/WebSocket behavior and actual RHRemote TLS media',
                           'Test a network restricted to TCP443; home LAN tests are insufficient'])
    (args.output / 'plan.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(dict(output=str(args.output.resolve()), sourceSha256=manifest['sourceSha256'],
                          files=list(files), applied=False)))


if __name__ == '__main__':
    main()
