# Tested devices

What has actually been tried, where, and when. "Built" features not listed for a device haven't been tested on it. Please add your device: open an issue with the **Device report** template, even if everything worked.

## Tested

| Device | Android | WebView | Tested | What was tested | Result |
|---|---|---|---|---|---|
| TCL Smart TV Pro (Google TV) | 14 | updated 2026-10 | 2026-10 | First-run setup, sign-in, dashboard picker, remote navigation, pop-ups over apps (YouTube), Ring doorbell pop-up with picture, crash recovery, 72-hour run | Works |
| TCL Smart TV Pro (Google TV) | 14 | updated 2026-10 | 2026-10 | Wake on motion: dashboard brought forward from the launcher (207 ms), another app left alone while it played | Works |

## Not tested yet

Built, but not yet tried on any device:

- Wake on motion: waking from rest, from the screensaver, and from standby through the blueprint.
- Night screen, page rotation over a long run, MQTT discovery, start after boot, start after a power cut.

Devices we'd most like reports from: Chromecast with Google TV, Google TV Streamer, Nvidia Shield, Sony and Philips Android TVs, Fire TV, Android tablets (Fire, Samsung, Lenovo), wall displays (Shelly, Echo Show running LineageOS).

## What a TV needs

- Android 10 or newer (Android TV or Google TV), with Android System WebView or Chrome kept up to date.
- For pop-ups over other apps: "display over other apps" (many TVs only allow it over adb: see [Troubleshooting](troubleshooting.md#pop-ups)).
- For wake on motion on Android 14+: the turn-screen-on permission (the provisioning script grants it). To wake from standby: see [Wake on motion](home-assistant.md#wake-on-motion).
