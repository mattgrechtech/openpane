# TV Kiosk

**TV Kiosk for Android TV and Google TV: shows a Home Assistant dashboard, or any web page, full screen, and lets Home Assistant control the TV.** One free, open-source app instead of a kiosk browser (such as Fully Kiosk), adb scripts and a separate pop-up app (such as TvOverlay).

## Home Assistant on your TV, in one app

1. **Install TV Kiosk** on the TV ([Install](#install)).
2. **Open it and follow setup with the remote.** It finds Home Assistant on your network, you sign in once, and you pick a dashboard from a list. No URLs to type.
3. **Add pop-ups.** Home Assistant can show a pop-up on the TV over whatever is playing, for example a doorbell press with a picture from the camera: [Doorbell pop-up with a camera picture](docs/home-assistant.md#doorbell-pop-up-with-a-camera-picture).

That's the dashboard, the pop-ups and remote control of the TV, without a second app. Tested with a Ring doorbell (Home Assistant's Ring integration) on one TV; any camera Home Assistant shows should work the same way.

> Status: **pre-alpha.** Tested on one TCL Google TV (Android 14), including a 72-hour run. Reports from other TVs are very welcome: see [Contributing](CONTRIBUTING.md). Companion to [ha-tv-kit](https://github.com/mattgrechtech/ha-tv-kit).

<p align="center">
  <img src="docs/screenshots/dashboard-popup.jpg" width="49%" alt="A Home Assistant dashboard with the remote's highlight on a card and a doorbell pop-up with buttons">
  <img src="docs/screenshots/setup.jpg" width="49%" alt="First-run setup: choose Home Assistant or a web page">
  <img src="docs/screenshots/settings.jpg" width="49%" alt="Settings laid out like the TV's own settings">
  <img src="docs/screenshots/popup-settings.jpg" width="49%" alt="Pop-up settings with a sample pop-up">
</p>
<p align="center"><sub>Shown with Home Assistant's public demo.</sub></p>

## What it does

- **Set up with the remote:** first-run setup finds Home Assistant on the network, signs in and lists your dashboards. Or choose "A web page" for any site: no Home Assistant needed.
- **Dashboard:** any HA dashboard full screen, signed in without typing a password on the TV, HA's header and sidebar hidden.
- **Wake on motion:** the screen rests (goes dark) when nobody is around and wakes when your Home Assistant motion or presence sensors see someone. The page keeps running behind the dark screen, so it's back at once with current values. It never takes the screen from a film, and a blueprint switches the TV on first if it's off. No camera needed. New, and still being tested on TVs.
- **Several pages:** rotate through dashboards or web pages; the remote holds the current one. A night schedule blacks the screen for set hours.
- **Remote-friendly:** the arrows jump between a page's buttons and OK presses them, so touch dashboards work with a remote. An on-screen pointer for anything else. Back goes back, then home. Hold **OK** (or press Menu) for a quick menu.
- **Pop-ups over any app:** doorbell snapshots, bin night, alerts, in any corner, stacked, replaceable by id, with your choice of size, opacity and text size, and optional buttons ("Open camera"). Speaks TvOverlay's `/notify` format, so existing automations keep working.
- **Home Assistant control:** load a page, navigate, reload, dim or blank the screen, wake, pop-ups, volume, speech, sounds, open another TV app, read state (page, connection, memory, uptime, WebView version).
  - **REST API** with a token, LAN only.
  - **MQTT** with HA discovery: the TV shows up as a device with buttons, a screen select, a dim slider and diagnostics.
- **Stays up:** retries failed loads; replaces a crashed or frozen WebView; reloads when the network comes back, when HA stays unreachable, and optionally every night; restarts itself after a crash.
- **Web admin page:** manage the TV from a phone or computer: scan the QR code in the TV's settings and you're in. Status, wake and screen controls, sending pop-ups and speech, every setting with search, the log and diagnostics. No app to install, nothing in the cloud.
- **Easy to look after:** a log and diagnostics on the TV and over the API (passwords and tokens blanked), a settings PIN, settings export and import, and set-up over adb in one script.

Not included on purpose: remote shell or script injection, camera motion detection, device-owner lockdown, replacing the TV launcher.

## Install

1. On the TV, allow apps from unknown sources for the app you'll install with (Settings → Apps → Security & restrictions, or Settings → Privacy → Security, depending on the TV).
2. Get the APK from [Releases](https://github.com/mattgrechtech/ha-tv-kiosk/releases) (the first one, v0.1.0, is on its way; until it's there, build it as below):
   - with the **Downloader** app on the TV: enter the APK's link, then install;
   - or from a computer with adb: `adb connect 192.168.1.60:5555`, then `adb install tv-kiosk-v0.1.0.apk`.
3. Open **TV Kiosk**. Setup asks whether the TV shows Home Assistant or a web page, finds Home Assistant on your network and signs in.
4. For pop-ups over other apps, grant "display over other apps": the app asks, and many TVs need it done once over adb (see [Troubleshooting](docs/troubleshooting.md#pop-ups)).
5. Connect Home Assistant to the TV: [docs/home-assistant.md](docs/home-assistant.md).

Developers can build it instead ([docs/build-setup.md](docs/build-setup.md): JDK 17 and the Android command-line tools, no Android Studio) and install and configure it in one step:

```bash
./tools/provision.sh 192.168.1.60 "http://192.168.1.50:8123/lovelace/0"
```

The example IPs are placeholders: `192.168.1.50` for Home Assistant, `192.168.1.60` for a TV.

## Open source, for real

TV Kiosk is open source under the [Apache-2.0 licence](LICENSE). That means you can:

- use it at home, in a business, or as part of something you sell or install for clients;
- change it, and share or sell your changed version;
- fork it and carry on if this project ever stops.

The only conditions are the usual Apache ones: keep the licence and notices, and say what you changed. There are no licence keys, no per-device fees and no "Plus" version: every feature in this repository is free.

## How it's made and released

- **Built in the open.** Every change is in this repository. CI runs the unit tests, lint and a build on every change, and you can [build it yourself](docs/build-setup.md).
- **Made with an AI coding assistant.** Much of the code is written with Claude Code, following the rules in [CONTRIBUTING.md](CONTRIBUTING.md). The maintainer decides what ships, tests on real TVs, and pushes every release by hand. The [tested devices](docs/devices.md) list says exactly what has been tried where.
- **Calm releases.** About one release a month, plus a fix release only when something is broken. No surprise updates: the app never updates itself and never contacts anything you didn't set up. See [Releasing](docs/releasing.md#release-policy).
- **Signed APKs** with SHA-256 checksums, built by GitHub Actions from a tagged commit.

## Privacy

The app talks only to the addresses you give it (Home Assistant, the web pages you choose, your MQTT broker) and to devices on your network that use its API. It has no accounts, no analytics, no crash reporting and no Google services. Sign-in tokens and passwords are stored encrypted on the TV. See [SECURITY.md](SECURITY.md) for what listens on the network and how it's protected.

## Docs

- [Home Assistant set-up](docs/home-assistant.md): doorbell pop-ups with a camera picture, wake on motion, REST commands, MQTT, moving TvOverlay or ha-tv-kit pop-ups over.
- [API](docs/api.md): REST routes, every setting, pop-up fields, MQTT topics and entities, adb, remote keys for your own pages.
- [Troubleshooting](docs/troubleshooting.md): setup, sign-in, pop-ups, a forgotten PIN, updating and uninstalling.
- [Tested devices](docs/devices.md): what has been tested on which TV, and how to add yours.
- [Build set-up](docs/build-setup.md), [Releasing](docs/releasing.md) and [Contributing](CONTRIBUTING.md).
- [Security](SECURITY.md) and [Changelog](CHANGELOG.md).

## Requirements

**Android 7.0 or newer**: Android TV and Google TV, tablets, and Android wall displays. It needs an up-to-date Android System WebView (or Chrome): Home Assistant's dashboards need a recent browser engine, which some old tablets can't get. So far it has only been tested on Android 14 TVs: see [tested devices](docs/devices.md). Built for a remote first, and it works by touch. On tablets it can follow the device's orientation and act as the home screen.

## Layout

```
app/src/main/java/io/github/mattgrechtech/tvkiosk/
  KioskApp.kt          app start: settings, state, controller, sign-in
  settings/            typed settings, encrypted secrets, JSON import/export
  web/                 dashboard activity, HA frontend bridge, sign-in, pointer
  control/             command execution and reported state
  overlay/             pop-ups
  api/                 HTTP server, REST routes, TvOverlay-compatible endpoint
  mqtt/                MQTT client and HA discovery
  service/             foreground service, boot and adb receivers
  ui/                  first-run setup, settings screen and dialogs
app/src/test/          unit tests for everything that doesn't need Android
tools/                 provisioning scripts, banner/icon generator
docs/
```

## Licence

[Apache-2.0](LICENSE). Third-party components: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Not affiliated with Home Assistant, Nabu Casa, Google, TvOverlay or Fully Kiosk Browser.
