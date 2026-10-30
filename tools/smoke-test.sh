#!/bin/sh
# Smoke test on a running emulator or device: install, start, no crash, web admin page answers.
# Usage: tools/smoke-test.sh path/to/app-debug.apk
set -eu
apk="$1"
pkg=io.github.mattgrechtech.tvkiosk.debug

adb install -r "$apk"
adb shell appops set "$pkg" SYSTEM_ALERT_WINDOW allow || true
adb logcat -c
adb shell am start -n "$pkg/io.github.mattgrechtech.tvkiosk.web.KioskActivity"
sleep 25

if ! adb shell pidof "$pkg" >/dev/null; then
  echo "FAIL: the app isn't running"; adb logcat -d | tail -100; exit 1
fi
if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  echo "FAIL: crash"; adb logcat -d | grep -A 30 "FATAL EXCEPTION"; exit 1
fi

adb forward tcp:2424 tcp:2424
status=$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: localhost' http://127.0.0.1:2424/)
if [ "$status" != "200" ]; then
  echo "FAIL: the web admin page answered $status"; exit 1
fi
status=$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: localhost' http://127.0.0.1:2424/api/v1/state)
if [ "$status" != "401" ]; then
  echo "FAIL: the API answered $status without a token (expected 401)"; exit 1
fi
echo "OK: running, no crash, admin page up, API protected"
