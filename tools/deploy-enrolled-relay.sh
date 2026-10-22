#!/usr/bin/env bash
# Deploy the CI-built RHRemote relay with signed paired-client enrollment.
set -euo pipefail
if [ "$#" -lt 3 ]; then echo 'Usage: deploy-enrolled-relay.sh BINARY SHA256 HOST_PUBLIC_ID...' >&2; exit 2; fi
binary=$1
expected=$2
shift 2
[[ "$expected" =~ ^[a-fA-F0-9]{64}$ ]] || exit 2
for endpoint in "$@"; do [[ "$endpoint" =~ ^[a-f0-9]{64}$ ]] || exit 2; done
printf '%s  %s\n' "$expected" "$binary" | sha256sum --check --status
root=/opt/rhremote-relay
config=/etc/rhremote-relay
unit=/etc/systemd/system/rhremote-relay.service
if sudo -n test -f "$unit" && ! sudo -n grep -q 'RHRemote managed relay' "$unit"; then
  echo 'Refusing to replace an unrelated service' >&2; exit 1
fi
stage=$(mktemp -d /tmp/rhremote-enrolled-relay.XXXXXXXX)
backup="$config/backups/$(date -u +%Y%m%dT%H%M%SZ)-$$"
sudo -n install -d -m 755 "$root" "$config"
sudo -n install -d -m 700 "$backup"
for file in "$unit" "$root/RHRemoteRelay" "$config/roots"; do
  if sudo -n test -f "$file"; then sudo -n cp -- "$file" "$backup/$(basename "$file")"; fi
done
if [ "${RHREMOTE_REPLACE_ROOTS:-0}" != 1 ] && sudo -n test -f "$config/roots"; then sudo -n cat "$config/roots" > "$stage/roots"; fi
printf '%s\n' "$@" >> "$stage/roots"
sort -u "$stage/roots" -o "$stage/roots"
while IFS= read -r endpoint; do [[ "$endpoint" =~ ^[a-f0-9]{64}$ ]] || exit 2; done < "$stage/roots"
rollback() {
  for file in "$unit" "$root/RHRemoteRelay" "$config/roots"; do
    if sudo -n test -f "$backup/$(basename "$file")"; then sudo -n cp -- "$backup/$(basename "$file")" "$file"; fi
  done
  sudo -n systemctl daemon-reload
  sudo -n systemctl restart rhremote-relay.service
}
trap 'rollback' ERR
sudo -n install -m 755 "$binary" "$root/RHRemoteRelay"
sudo -n install -m 644 "$stage/roots" "$config/roots"
cat > "$stage/service" <<'UNIT'
# RHRemote managed relay
[Unit]
Description=RHRemote encrypted relay and paired identity enrollment
After=network-online.target
Wants=network-online.target
[Service]
ExecStart=/opt/rhremote-relay/RHRemoteRelay /etc/rhremote-relay/roots /var/lib/rhremote-relay/enrollments.json
Restart=on-failure
RestartSec=3
DynamicUser=yes
StateDirectory=rhremote-relay
StateDirectoryMode=0700
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK
MemoryMax=128M
TasksMax=128
LimitNOFILE=4096
Environment=RUST_LOG=warn
StandardOutput=journal
StandardError=journal
[Install]
WantedBy=multi-user.target
UNIT
sudo -n install -m 644 "$stage/service" "$unit"
sudo -n systemctl daemon-reload
sudo -n systemctl enable rhremote-relay.service >/dev/null
sudo -n systemctl restart rhremote-relay.service
for attempt in {1..10}; do
  if curl --fail --silent --max-time 2 http://127.0.0.1:33448/health > "$stage/health" &&
     curl --fail --silent --max-time 2 http://127.0.0.1:33447/ping >/dev/null; then
    sudo -n systemctl is-active --quiet rhremote-relay.service
    cat "$stage/health"
    printf '\n'
    exit 0
  fi
  sleep 1
done
echo 'Relay readiness failed; restoring the previous managed relay' >&2
false
