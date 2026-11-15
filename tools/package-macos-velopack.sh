#!/bin/bash
set -Eeuo pipefail

local_build=false
if [[ ${1:-} == --local ]]; then
    local_build=true
    shift
fi
if [[ $# != 5 ]]; then
    echo "Usage: $0 [--local] VERSION UI_EXECUTABLE MOONLIGHT_APP RELEASE_NOTES OUTPUT_DIRECTORY" >&2
    exit 2
fi
version=$1
ui=$2
engine=$3
notes=$4
output=$5
repo="$(cd "$(dirname "$0")/.." && pwd)"
vpk=${VPK:-vpk}

[[ $(uname -sm) == "Darwin arm64" && $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
grep -Fxq "version = \"$version\"" "$repo/client/crates/rhremote-ui/Cargo.toml"
[[ -x "$ui" && -r "$notes" && -d "$engine" && ! -e "$output" && ! -L "$output" ]]
for executable in "$ui" "$engine/Contents/MacOS/Moonlight"; do
    [[ $(lipo -archs "$executable") == arm64 ]]
done
[[ -d "$engine/Contents/Frameworks/QtCore.framework" ]]
[[ -f "$engine/Contents/PlugIns/platforms/libqcocoa.dylib" ]]
if [[ -n $(find "$engine" -type f \( -iname '*.ini' -o -iname '*.log' -o -iname '*.pem' -o -iname '*.key' -o -iname '*.pfx' -o -iname '*.p12' \) -print -quit) ]]; then
    echo "The native engine bundle contains runtime configuration or credential files" >&2
    exit 1
fi

sign_args=(--noInst --signAppIdentity -)
if $local_build; then
    unset VPK_SIGN_INSTALL_IDENTITY VPK_NOTARY_PROFILE
else
    : "${VPK_SIGN_APP_IDENTITY:?Set the Developer ID Application identity}"
    : "${VPK_SIGN_INSTALL_IDENTITY:?Set the Developer ID Installer identity}"
    : "${VPK_NOTARY_PROFILE:?Set the notarytool Keychain profile}"
    sign_args=(--signAppIdentity "$VPK_SIGN_APP_IDENTITY"
               --signInstallIdentity "$VPK_SIGN_INSTALL_IDENTITY"
               --notaryProfile "$VPK_NOTARY_PROFILE")
fi

stage="$(mktemp -d "${TMPDIR:-/tmp}/rhremote-macos.XXXXXXXX")"
trap 'rm -rf -- "$stage"' EXIT
app="$stage/RHRemote-Moonlight.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources" "$app/Contents/Helpers"
install -m 755 "$ui" "$app/Contents/MacOS/RHRemote"
install -m 644 "$repo/client/LICENSE" "$app/Contents/Resources/LICENSE"
ditto "$engine" "$app/Contents/Helpers/Moonlight.app"

info="$app/Contents/Info.plist"
plutil -create xml1 "$info"
plutil -insert CFBundleIdentifier -string com.railgunhamster.rhremote.moonlight "$info"
plutil -insert CFBundleExecutable -string RHRemote "$info"
plutil -insert CFBundleName -string RHRemote-Moonlight "$info"
plutil -insert CFBundleDisplayName -string RHRemote-Moonlight "$info"
plutil -insert CFBundleShortVersionString -string "$version" "$info"
plutil -insert CFBundleVersion -string "$version" "$info"
plutil -insert CFBundlePackageType -string APPL "$info"
plutil -insert CFBundleIconFile -string RHRemote "$info"
plutil -insert LSMinimumSystemVersion -string 14.4 "$info"
plutil -insert NSLocalNetworkUsageDescription -string 'RHRemote-Moonlight connects to remote devices on your local network.' "$info"

iconset="$stage/RHRemote.iconset"
icon="$repo/client/shared/icons/rhremote.png"
mkdir "$iconset"
for size in 16 32 128 256 512; do
    doubled=$((2 * size))
    sips -z "$size" "$size" "$icon" --out "$iconset/icon_${size}x${size}.png" >/dev/null
    sips -z "$doubled" "$doubled" "$icon" --out "$iconset/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$iconset" -o "$app/Contents/Resources/RHRemote.icns"

"$vpk" --skip-updates -y pack --packId RHRemote-Moonlight --packVersion "$version" \
    --packDir "$app" --mainExe RHRemote --packAuthors RailgunHamster \
    --packTitle RHRemote-Moonlight --channel osx-arm64 --runtime osx-arm64 \
    --releaseNotes "$notes" --delta None --outputDir "$output" "${sign_args[@]}"
