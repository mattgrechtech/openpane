#!/bin/sh
# Smoke test on a running emulator or device: install, start, no crash, web admin page answers.
# Usage: tools/smoke-test.sh path/to/app-debug.apk
set -eu
apk="$1"
pkg=io.github.mattgrechtech.openpane.debug

adb install -r "$apk"
adb shell appops set "$pkg" SYSTEM_ALERT_WINDOW allow || true
adb logcat -c
adb shell am start -n "$pkg/io.github.mattgrechtech.openpane.web.KioskActivity"
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
# Speed: a pop-up must reach the screen within its budget (allowing 4x on a software-drawn emulator).
token=$(adb shell am broadcast -n "$pkg/io.github.mattgrechtech.openpane.service.ProvisionReceiver" \
  -a io.github.mattgrechtech.openpane.GET_TOKEN | sed -n 's/.*"token":"\([0-9a-f]*\)".*/\1/p')
for i in 1 2 3; do
  curl -s -o /dev/null -H 'Host: localhost' -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -d "{\"title\":\"Smoke test $i\",\"duration\":3}" http://127.0.0.1:2424/api/v1/notify
  sleep 2
done
perf=$(curl -s -H 'Host: localhost' -H "Authorization: Bearer $token" http://127.0.0.1:2424/api/v1/diagnostics)
echo "$perf" | python3 -c '
import json, sys
perf = json.load(sys.stdin).get("perf", {})
popup = perf.get("popup")
if not popup:
    sys.exit("FAIL: no pop-up timing recorded")
limit = popup["budget"] * 4
print("pop-up: median %d ms, worst %d ms (emulator limit %d ms)" % (popup["median"], popup["worst"], limit))
if popup["median"] > limit:
    sys.exit("FAIL: pop-ups are too slow")
'
echo "OK: running, no crash, admin page up, API protected, pop-ups within budget"
