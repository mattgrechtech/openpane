#!/usr/bin/env bash
# Installs and sets up the app on an Android TV over adb.
#
#   ./tools/provision.sh <tv-ip> <start-url> [apk] [--release]
#
# Example:
#   ./tools/provision.sh 192.168.1.60 "http://192.168.1.50:8123/house?tv=1"
#
# Default APK: the debug build (app/build/outputs/apk/debug/app-debug.apk).
# Prints the API token at the end: store it in Home Assistant's secrets.yaml, nowhere else.
set -euo pipefail

usage() { echo "Usage: $0 <tv-ip> <start-url> [apk] [--release]" >&2; exit 2; }
[ $# -ge 2 ] || usage

tv="$1"; url="$2"; shift 2
apk=""; release=0
for arg in "$@"; do
  case "$arg" in
    --release) release=1 ;;
    *) apk="$arg" ;;
  esac
done

root="$(cd "$(dirname "$0")/.." && pwd)"
pkg="io.github.mattgrechtech.openpane"
if [ "$release" = 1 ]; then
  apk="${apk:-$root/app/build/outputs/apk/release/app-release.apk}"
else
  pkg="$pkg.debug"
  apk="${apk:-$root/app/build/outputs/apk/debug/app-debug.apk}"
fi
ns="io.github.mattgrechtech.openpane"
receiver="$pkg/$ns.service.ProvisionReceiver"

case "$url" in http://*|https://*) ;; *) echo "The start URL must begin with http:// or https://" >&2; exit 2 ;; esac
[ -f "$apk" ] || { echo "No APK at $apk. Build it first: ./gradlew assembleDebug" >&2; exit 1; }

target="$tv:5555"
echo "Connecting to $target ..."
adb connect "$target" >/dev/null
adb -s "$target" get-state >/dev/null 2>&1 || {
  echo "Can't reach $target. Is network debugging on, and did you accept the prompt on the TV?" >&2; exit 1; }
a() { adb -s "$target" "$@"; }

echo "Installing $(basename "$apk") ..."
a install -r "$apk" >/dev/null

echo "Allowing pop-ups over other apps and background start ..."
a shell appops set "$pkg" SYSTEM_ALERT_WINDOW allow
a shell appops set "$pkg" TURN_SCREEN_ON allow 2>/dev/null || true  # Android 14+: wake on motion turns the screen on
a shell appops set "$pkg" AUTO_START allow 2>/dev/null || true  # TCL: lets boot and updates restart it
a shell appops set "$pkg" REQUEST_INSTALL_PACKAGES allow 2>/dev/null || true  # lets you install updates from Home Assistant (Android still asks)
echo "Excluding it from battery optimisation (keeps the service running) ..."
a shell dumpsys deviceidle whitelist "+$pkg" >/dev/null || true

# base64 keeps the JSON intact through every shell's quoting.
json="{\"start_url\":\"${url//\"/\\\"}\"}"
b64="$(printf '%s' "$json" | base64 | tr -d '\r\n')"
echo "Setting the start URL ..."
a shell am broadcast -n "$receiver" -a "$ns.CONFIGURE" --es json_b64 "$b64" | sed -n 's/.*data="\(.*\)"/  \1/p'

echo "Opening the dashboard ..."
a shell am start -n "$pkg/$ns.web.KioskActivity" >/dev/null

token="$(a shell am broadcast -n "$receiver" -a "$ns.GET_TOKEN" | sed -n 's/.*"token":"\([0-9a-f]*\)".*/\1/p')"
echo
echo "Done. API: http://$tv:2424/api/v1/state"
echo "Token (put it in Home Assistant's secrets.yaml, nowhere else):"
echo "  $token"
