#!/usr/bin/env bash
# Use only a new session bus and an ephemeral keyring, never the user's vault.
set -euo pipefail
binary="$(realpath "$1")"
[[ -x "$binary" ]]
if [[ ${RHREMOTE_SECRET_TEST_INNER:-0} != 1 ]]; then
    exec dbus-run-session -- env RHREMOTE_SECRET_TEST_INNER=1 bash "$0" "$binary"
fi
fixture="$(mktemp -d /tmp/rhremote-secrets.XXXXXXXX)"
[[ "$fixture" == /tmp/rhremote-secrets.* && -d "$fixture" && ! -L "$fixture" ]]
daemon_pid=''
cleanup(){
    if [[ -n "$daemon_pid" ]]; then kill "$daemon_pid" 2>/dev/null || true; wait "$daemon_pid" 2>/dev/null || true; fi
    rm -rf -- "$fixture"
}
trap cleanup EXIT
export XDG_DATA_HOME="$fixture/data" XDG_CONFIG_HOME="$fixture/config" GNOME_KEYRING_CONTROL="$fixture/control"
mkdir -m700 "$XDG_DATA_HOME" "$XDG_CONFIG_HOME" "$GNOME_KEYRING_CONTROL"
python3 -c 'import secrets; print(secrets.token_hex(32))' > "$fixture/password"
chmod 600 "$fixture/password"
gnome-keyring-daemon --foreground --components=secrets --unlock --control-directory="$GNOME_KEYRING_CONTROL" < "$fixture/password" > "$fixture/daemon.log" 2>&1 &
daemon_pid=$!
for _ in {1..50}; do
    if dbus-send --session --print-reply --dest=org.freedesktop.DBus /org/freedesktop/DBus org.freedesktop.DBus.NameHasOwner string:org.freedesktop.secrets | grep -q 'boolean true'; then break; fi
    sleep .1
done
"$binary" --exact config_sync::tests::windows_vault_roundtrip_never_exposes_password_in_json --include-ignored --nocapture
