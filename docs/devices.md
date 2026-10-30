# Tested devices

What has actually been tried, where, and when. "Built" features not listed for a device haven't been tested on it. Please add your device: open an issue with the **Device report** template, even if everything worked.

## Tested

| Device | Android | WebView | Tested | What was tested | Result |
|---|---|---|---|---|---|
| TCL Smart TV Pro (Google TV) | 14 | updated 2026-10 | 2026-10 | First-run setup, sign-in, dashboard picker, remote navigation, pop-ups over apps (YouTube), Ring doorbell pop-up with picture, crash recovery, 72-hour run | Works |
| TCL Smart TV Pro (Google TV) | 14 | updated 2026-10 | 2026-10 | Wake on motion: from rest in 32 ms, dashboard brought forward from the launcher, another app left alone while it played; live camera pop-up. Standby: TV and Android TV Remote drop off the network, so it can't be woken | Works (not from standby) |

## Not tested yet

Built, but not yet tried on any device:

- Anything below Android 14, and any tablet or wall display (support for Android 7-13, touch, orientation and the home-screen option is new).

- Wake on motion: waking from the screensaver, from standby on a TV that stays reachable, and from screen-off on a tablet.
- Night screen, page rotation over a long run, MQTT discovery, start after boot, start after a power cut.

Devices we'd most like reports from: Chromecast with Google TV, Google TV Streamer, Nvidia Shield, Sony and Philips Android TVs, Fire TV, Android tablets (Fire, Samsung, Lenovo), wall displays (Shelly, Echo Show running LineageOS).

## What a TV needs

- Android 7.0 or newer (TV, tablet or wall display), with Android System WebView or Chrome kept up to date. Home Assistant's frontend needs a recent engine: if the dashboard stays blank on an old tablet, its WebView is too old (Settings → Status and about shows the version).
- Tablets and wall displays: Settings → Screen has the orientation and *Use as the home screen*.
- For pop-ups over other apps: "display over other apps" (many TVs only allow it over adb: see [Troubleshooting](troubleshooting.md#pop-ups)).
- For wake on motion on Android 14+: the turn-screen-on permission (the provisioning script grants it). To wake from standby: see [Wake on motion](home-assistant.md#wake-on-motion).
