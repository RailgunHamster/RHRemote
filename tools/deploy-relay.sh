#!/usr/bin/env bash
# Install only RHRemote's relay. Existing DERP/Tailscale services are untouched.
set -euo pipefail
if [ "$#" -lt 1 ]; then echo 'Pass at least one authorized endpoint public key' >&2; exit 2; fi
for endpoint in "$@"; do
  [[ "$endpoint" =~ ^[a-f0-9]{64}$ ]] || { echo 'Invalid endpoint public key' >&2; exit 2; }
done
root=/opt/rhremote-relay
config=/etc/rhremote-relay
unit=/etc/systemd/system/rhremote-relay.service
if sudo -n test -f "$unit" && ! sudo -n grep -q 'RHRemote managed relay' "$unit"; then
  echo 'Refusing to replace an unrelated service' >&2; exit 1
fi
stage=$(mktemp -d /tmp/rhremote-relay.XXXXXXXX)
archive="$stage/relay.tar.gz"
if [ -n "${RHREMOTE_RELAY_ARCHIVE:-}" ]; then
  cp -- "$RHREMOTE_RELAY_ARCHIVE" "$archive"
else
  curl --fail --location --silent --show-error --max-time 600 \
    'https://github.com/n0-computer/iroh/releases/download/v1.3.0/iroh-relay-v1.3.0-x86_64-unknown-linux-gnu.tar.gz' -o "$archive"
fi
printf '%s  %s\n' '50e0158f6b9aefc95b45b515485d3b94d786fb1bfa4155d25937c71b81905f8f' "$archive" | sha256sum --check --status
tar -xzf "$archive" -C "$stage"
binary=$(find "$stage" -type f -name iroh-relay -print -quit)
test -n "$binary"
sudo -n install -d -m 755 "$root" "$config"
sudo -n install -m 755 "$binary" "$root/iroh-relay"
# Only public endpoint identities are stored here. Keep earlier enrolled peers.
if sudo -n test -f "$config/endpoints"; then sudo -n cat "$config/endpoints" > "$stage/endpoints"; fi
printf '%s\n' "$@" >> "$stage/endpoints"
sort -u "$stage/endpoints" -o "$stage/endpoints"
while IFS= read -r endpoint; do [[ "$endpoint" =~ ^[a-f0-9]{64}$ ]] || exit 2; done < "$stage/endpoints"
sudo -n install -m 644 "$stage/endpoints" "$config/endpoints"
{
  printf '# RHRemote managed relay\nhttp_bind_addr = "0.0.0.0:33447"\nenable_metrics = false\nenable_quic_addr_discovery = false\naccess.allowlist = ['
  separator=''
  while IFS= read -r endpoint; do printf '%s"%s"' "$separator" "$endpoint"; separator=','; done < "$stage/endpoints"
  printf ']\n'
} > "$stage/config.toml"
sudo -n install -m 644 "$stage/config.toml" "$config/config.toml"
cat > "$stage/service" <<'UNIT'
# RHRemote managed relay
[Unit]
Description=RHRemote encrypted packet relay
After=network-online.target
Wants=network-online.target
[Service]
ExecStart=/opt/rhremote-relay/iroh-relay --config-path /etc/rhremote-relay/config.toml
Restart=on-failure
RestartSec=3
DynamicUser=yes
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
sudo -n systemctl enable --now rhremote-relay.service >/dev/null
sudo -n systemctl restart rhremote-relay.service
sleep 1
sudo -n systemctl is-active --quiet rhremote-relay.service
curl --fail --silent --max-time 3 http://127.0.0.1:33447/ping >/dev/null
printf '{"ok":true,"service":"rhremote-relay","port":33447,"allowlistedEndpoints":%s,"e2eEncryption":"QUIC endpoint identity","outerRelayTls":false}\n' "$(wc -l < "$stage/endpoints")"
