#!/usr/bin/env bash
# Add an isolated TLS gateway. Existing 443 applications and relay listeners stay put.
set -euo pipefail
ip=${1:?Usage: deploy-relay-tls.sh PUBLIC_IPV4 [TLS_PORT]}
port=${2:-33449}
python3 - "$ip" "$port" <<'PY'
import ipaddress,sys
ipaddress.IPv4Address(sys.argv[1])
assert sys.argv[2].isdigit() and 1024<int(sys.argv[2])<65536
assert int(sys.argv[2]) not in (33447,33448,33450,33451)
PY
sudo -n true
config=/etc/rhremote-tls
unit=/etc/systemd/system/rhremote-tls.service
shared=/etc/caddy/Caddyfile
if sudo -n test -f "$unit" && ! sudo -n grep -q '# RHRemote managed TLS gateway' "$unit"; then
  echo 'Refusing to replace an unrelated TLS service' >&2; exit 1
fi
if sudo -n test -f "$config/ready" && curl --fail --silent --max-time 5 "https://$ip:$port/ping" >/dev/null; then
  echo "RHRemote TLS already healthy at https://$ip:$port/"; exit 0
fi
stage=$(mktemp -d /tmp/rhremote-tls.XXXXXXXX)
backup="$config/backups/$(date -u +%Y%m%dT%H%M%SZ)-$$"
sudo -n install -d -m 755 "$config"
sudo -n install -d -m 700 "$backup"
sudo -n cp -- "$shared" "$backup/shared-Caddyfile"
for file in "$unit" "$config/Caddyfile"; do
  if sudo -n test -f "$file"; then sudo -n cp -- "$file" "$backup/$(basename "$file")"; fi
done
original_hash=$(sudo -n sha256sum "$shared" | cut -d' ' -f1)
applied_hash=''
had_service=0
if sudo -n systemctl is-active --quiet rhremote-tls.service; then had_service=1; fi
rollback() {
  trap - ERR
  set +e
  if [ -n "$applied_hash" ] && [ "$(sudo -n sha256sum "$shared" | cut -d' ' -f1)" = "$applied_hash" ]; then
    sudo -n cp -- "$backup/shared-Caddyfile" "$shared"
    sudo -n caddy reload --config "$shared" --adapter caddyfile >/dev/null
  fi
  if [ "$had_service" = 1 ]; then
    for file in "$unit" "$config/Caddyfile"; do sudo -n cp -- "$backup/$(basename "$file")" "$file"; done
    sudo -n systemctl daemon-reload
    sudo -n systemctl restart rhremote-tls.service
  else
    sudo -n systemctl disable --now rhremote-tls.service >/dev/null 2>&1 || true
  fi
}
trap 'rollback' ERR
trap 'rollback; exit 130' INT
trap 'rollback; exit 143' TERM
# The shared server currently redirects this origin to 8443. Preserve that route.
redirect=$(curl --silent --max-time 3 -o /dev/null -w '%{redirect_url}' -H "Host: $ip" http://127.0.0.1/)
[ "$redirect" = "https://$ip:8443/" ] || { echo 'Unexpected existing HTTP route; review it before deployment' >&2; false; }
sudo -n python3 - "$shared" <<'PY'
import json,subprocess,sys,urllib.request
live=json.load(urllib.request.urlopen('http://127.0.0.1:2019/config/'))
disk=json.loads(subprocess.check_output(['caddy','adapt','--config',sys.argv[1],'--adapter','caddyfile'],stderr=subprocess.DEVNULL))
assert live==disk, 'Running Caddy differs from its saved source; refusing to replace runtime changes'
PY
sudo -n cat "$shared" > "$stage/shared-Caddyfile"
python3 - "$stage/shared-Caddyfile" "$ip" <<'PY'
import pathlib,sys
path=pathlib.Path(sys.argv[1]);ip=sys.argv[2];text=path.read_text()
block=f'''# BEGIN RHRemote ACME challenge
http://{ip}:80 {{
    handle /.well-known/acme-challenge/* {{
        reverse_proxy 127.0.0.1:33451
    }}
    handle {{
        redir https://{ip}:8443{{uri}} 308
    }}
}}
# END RHRemote ACME challenge'''
if '# BEGIN RHRemote ACME challenge' in text:
    assert block in text, 'Existing RHRemote challenge route differs; refusing to replace it'
else:
    path.write_text(text.rstrip()+'\n\n'+block+'\n')
PY
sudo -n caddy validate --config "$stage/shared-Caddyfile" --adapter caddyfile >/dev/null
candidate_hash=$(sha256sum "$stage/shared-Caddyfile" | cut -d' ' -f1)
[ "$(sudo -n sha256sum "$shared" | cut -d' ' -f1)" = "$original_hash" ]
sudo -n install -m 644 "$stage/shared-Caddyfile" "$shared"
applied_hash=$candidate_hash
sudo -n caddy reload --config "$shared" --adapter caddyfile >/dev/null
if ! id rhremote-tls >/dev/null 2>&1; then
  sudo -n useradd --system --home-dir /var/lib/rhremote-tls --shell /usr/sbin/nologin rhremote-tls
fi
sudo -n install -d -o rhremote-tls -g rhremote-tls -m 700 /var/lib/rhremote-tls
cat > "$stage/service" <<'UNIT'
# RHRemote managed TLS gateway
[Unit]
Description=RHRemote relay TLS gateway and certificate renewal
After=network-online.target rhremote-relay.service caddy.service
Wants=network-online.target
[Service]
User=rhremote-tls
Group=rhremote-tls
ExecStart=/usr/bin/caddy run --config /etc/rhremote-tls/Caddyfile --adapter caddyfile
ExecReload=/usr/bin/caddy reload --config /etc/rhremote-tls/Caddyfile --adapter caddyfile --address 127.0.0.1:33450
Restart=on-failure
RestartSec=5
StateDirectory=rhremote-tls
StateDirectoryMode=0700
Environment=XDG_DATA_HOME=/var/lib/rhremote-tls/data
Environment=XDG_CONFIG_HOME=/var/lib/rhremote-tls/config
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
MemoryMax=128M
TasksMax=128
LimitNOFILE=4096
[Install]
WantedBy=multi-user.target
UNIT
sudo -n install -m 644 "$stage/service" "$unit"
sudo -n systemctl daemon-reload
write_config() {
  local ca=$1 storage=$2
  cat > "$stage/Caddyfile" <<CONFIG
# RHRemote managed TLS gateway; automatic certificate renewal stays in this process.
{
    admin 127.0.0.1:33450
    http_port 33451
    https_port $port
    default_sni $ip
    auto_https disable_redirects
    storage file_system {
        root /var/lib/rhremote-tls/$storage
    }
}
https://$ip:$port {
    tls {
        issuer acme {
            dir $ca
            profile shortlived
            alt_http_port 33451
            disable_tlsalpn_challenge
        }
    }
    handle_path /rhremote/* {
        reverse_proxy 127.0.0.1:33448 {
            stream_close_delay 24h
        }
    }
    handle {
        reverse_proxy 127.0.0.1:33447 {
            stream_close_delay 24h
        }
    }
}
CONFIG
  sudo -n install -m 644 "$stage/Caddyfile" "$config/Caddyfile"
  sudo -n runuser -u rhremote-tls -- caddy validate --config "$config/Caddyfile" --adapter caddyfile >/dev/null
}
wait_ready() {
  local mode=$1
  for attempt in {1..60}; do
    if [ "$mode" = staging ]; then
      if curl --insecure --fail --silent --max-time 2 "https://$ip:$port/ping" >/dev/null; then return 0; fi
    elif curl --fail --silent --max-time 2 "https://$ip:$port/ping" >/dev/null; then
      return 0
    fi
    sleep 2
  done
  echo "TLS readiness failed ($mode); inspect rhremote-tls.service journal" >&2
  return 1
}
write_config https://acme-staging-v02.api.letsencrypt.org/directory staging
sudo -n systemctl start rhremote-tls.service
wait_ready staging
echo 'Staging certificate and HTTP challenge forwarding passed'
write_config https://acme-v02.api.letsencrypt.org/directory production
sudo -n systemctl restart rhremote-tls.service
wait_ready production
curl --fail --silent --max-time 5 "https://$ip:$port/rhremote/health"
printf '\n'
sudo -n systemctl enable rhremote-tls.service >/dev/null
printf '%s\n' "https://$ip:$port/" | sudo -n tee "$config/ready" >/dev/null
echo "RHRemote TLS ready at https://$ip:$port/; original 443 services were not changed"
