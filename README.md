# OpenPane

**OpenPane puts a Home Assistant dashboard, or any web page, full screen on Android TVs, tablets and wall displays, and lets Home Assistant control the screen.** Free and open source (Apache-2.0, commercial use welcome): one app instead of a kiosk browser (such as Fully Kiosk), adb scripts and a separate pop-up app (such as TvOverlay).

## Home Assistant on your TV or tablet, in one app

1. **Install OpenPane** on the TV or tablet ([Install](#install)).
2. **Open it and set it up from your phone, or with the remote.** Choose *Set up from your phone*, scan the code, and sign in to Home Assistant on your phone: no typing with the remote. Or follow setup with the remote: it finds Home Assistant on your network, you sign in once, and you pick a dashboard from a list.
3. **Add pop-ups.** Home Assistant can show a pop-up on the TV over whatever is playing, for example a doorbell press with a picture from the camera: [Doorbell pop-up with a camera picture](docs/home-assistant.md#doorbell-pop-up-with-a-camera-picture).

That's the dashboard, the pop-ups and remote control of the TV, without a second app. Tested with a Ring doorbell (Home Assistant's Ring integration) on one TV; any camera Home Assistant shows should work the same way.

> Status: **pre-alpha.** Tested on two TCL Google TVs (Android 14), including a 72-hour run; tablets and wall displays are supported but not yet tested. Reports from other TVs are very welcome: see [Contributing](CONTRIBUTING.md). Companion to [ha-tv-kit](https://github.com/mattgrechtech/ha-tv-kit).

<p align="center">
  <img src="docs/screenshots/dashboard-popup.jpg" width="49%" alt="A Home Assistant dashboard with the remote's highlight on a card and a doorbell pop-up with buttons">
  <img src="docs/screenshots/setup.jpg" width="49%" alt="First-run setup: choose Home Assistant or a web page">
  <img src="docs/screenshots/settings.jpg" width="49%" alt="Settings laid out like the TV's own settings">
  <img src="docs/screenshots/popup-settings.jpg" width="49%" alt="Pop-up settings with a sample pop-up">
</p>
<p align="center"><sub>Shown with Home Assistant's public demo.</sub></p>

## What it does

**On the screen**
- **Set up with the remote:** first-run setup finds Home Assistant on the network, signs in and lists your dashboards. Or choose "A web page" for any site: no Home Assistant needed.
- **Dashboard:** any HA dashboard full screen, signed in without typing a password on the TV, HA's header and sidebar hidden.
- **Remote-friendly:** the arrows jump between a page's buttons and OK presses them, so touch dashboards work with a remote. An on-screen pointer for anything else. Hold **OK** (or press Menu) for a quick menu. Works by touch on tablets.
- **Several pages:** rotate through dashboards or web pages; the remote holds the current one. Automations can open a page and come back by themselves (`return_after`).
- **Scenes by time of day:** the planner and a bright screen at breakfast, the cameras in the evening, dim and quick to rest at night. One line each.
- **Signage and a photo frame:** a playlist of pictures, videos and pages with days and hours, kept on the device so it plays offline; your photos from Home Assistant's media while nobody's around. [More](docs/signage.md).
- **Family planner:** a built-in week view of every Home Assistant calendar, in colour, with the shopping and chores lists beside it; tick items off by touch or remote. [More](docs/planner.md).
- **Offline, calmly:** if Home Assistant or the network drops, the screen keeps the dashboard as it last was, dimmed, with the time and "Reconnecting", instead of an error page.

**Pop-ups over any app** (YouTube, Netflix, live TV)
- Doorbell snapshots, or the camera **live**; bin night; alerts. Any corner, stacked, replaced or closed by id, your choice of size, opacity and text size.
- A built-in **chime, doorbell or alert sound**, or **spoken text**.
- **Buttons** that open a page or **act in Home Assistant** ("Unlock").
- Extras: a **QR code** (guest Wi-Fi), a **countdown** ("Pizza ready in 4:59"), a **progress bar** ("Washing 75 %"), and **now playing** that follows a media player.
- Speaks TvOverlay's `/notify` format, so existing automations keep working.

**When nobody's watching**
- **Wake on motion:** the screen rests when nobody is around (black, dimmed, a drifting clock, or a photo-frame page) and wakes when your Home Assistant motion or presence sensors see someone. The page keeps running behind it, so it's back at once with current values. It never takes the screen from a film. No camera needed. On TVs it wakes from its own rest (keep the TV out of standby); tablets wake from screen-off.
- **Battery care for wall tablets** (a blueprint): charges between 30 and 80 % with a smart plug, so the battery doesn't swell.
- **Bedtime and screen-time limits** (a blueprint): a countdown warning over whatever's playing, then the TV switches off.
- **Night screen** for set hours, a **burn-in guard** for OLED screens, and the backlight turned down when black where the device allows it.

**Home Assistant**
- **The OpenPane integration** (HACS): found automatically, no YAML, actions like `openpane.notify` for one TV or all of them, entities for the screen, presence, page and health, and `apply_settings` to set up several TVs at once.
- Or **REST** (token, LAN only) or **MQTT** with discovery.
- **Remote keys for automations:** the colour keys and digits can trigger anything in Home Assistant.

**Easy to look after**
- **Web admin page:** scan the QR code in the TV's settings and manage it from your phone: status, controls, pop-ups, every setting, the log, export and import. No app, nothing in the cloud.
- **Voice on tablets:** push-to-talk to Home Assistant's Assist, which works on plain `http://` set-ups where the browser's mic button can't.
- **Lock mode for shared tablets:** Back never leaves, links to other sites don't open, the menu needs a PIN. Plus bigger text and high contrast for the app's own screens.
- **Your own cards can drive the screen** (`window.openPane`, opt-in): pop-ups, wake, presence, state, voice.
- **Phone remote:** arrows, OK, Back and Menu in the admin page, and a box to **type into the TV** (search, Wi-Fi passwords, sign-ins) instead of the on-screen keyboard.
- **Stays up:** retries failed loads; replaces a crashed or frozen WebView; reloads when the network comes back, when HA stays unreachable, when memory runs low while nobody is looking, and optionally every night; restarts itself after a crash.
- A settings PIN, a log and diagnostics with passwords and tokens blanked, set-up over adb in one script, and an opt-in check for new releases.
- **TVs, tablets and wall displays** on Android 7 and newer: orientation setting, "use as home screen", brightness control.

Not included on purpose: remote shell or script injection, camera motion detection, device-owner lockdown.

## Install

1. On the TV, allow apps from unknown sources for the app you'll install with (Settings → Apps → Security & restrictions, or Settings → Privacy → Security, depending on the TV).
2. Get the APK from [Releases](https://github.com/mattgrechtech/openpane/releases) (the first one, v0.1.0, is on its way; until it's there, build it as below):
   - with the **Downloader** app on the TV: enter the APK's link, then install;
   - or from a computer with adb: `adb connect 192.168.1.60:5555`, then `adb install openpane-v0.1.0.apk`.
3. Open **OpenPane**. Setup asks whether the TV shows Home Assistant or a web page, finds Home Assistant on your network and signs in.
4. For pop-ups over other apps, grant "display over other apps": the app asks, and many TVs need it done once over adb (see [Troubleshooting](docs/troubleshooting.md#pop-ups)).
5. Connect Home Assistant to the TV: install the **OpenPane integration** from HACS (it finds the TV by itself), or use REST commands or MQTT: [docs/home-assistant.md](docs/home-assistant.md).

Developers can build it instead ([docs/build-setup.md](docs/build-setup.md): JDK 17 and the Android command-line tools, no Android Studio) and install and configure it in one step:

```bash
./tools/provision.sh 192.168.1.60 "http://192.168.1.50:8123/lovelace/0"
```

The example IPs are placeholders: `192.168.1.50` for Home Assistant, `192.168.1.60` for a TV.

## Open source, for real

OpenPane is open source under the [Apache-2.0 licence](LICENSE). That means you can:

- use it at home, in a business, or as part of something you sell or install for clients;
- change it, and share or sell your changed version;
- fork it and carry on if this project ever stops.

The only conditions are the usual Apache ones: keep the licence and notices, and say what you changed. There are no licence keys, no per-device fees and no "Plus" version: every feature in this repository is free.

## How it's made and released

- **Built in the open.** Every change is in this repository. CI runs the unit tests, lint and a build on every change, and you can [build it yourself](docs/build-setup.md).
- **Made with an AI coding assistant.** Much of the code is written with Claude Code, following the rules in [CONTRIBUTING.md](CONTRIBUTING.md). The maintainer decides what ships, tests on real TVs, and pushes every release by hand. The [tested devices](docs/devices.md) list says exactly what has been tried where.
- **Calm releases.** About one release a month, plus a fix release only when something is broken. No surprise updates: the app never updates itself, and it only checks for new releases if you turn that on (once a day, GitHub only). See [Releasing](docs/releasing.md#release-policy).
- **Signed APKs** with SHA-256 checksums, built by GitHub Actions from a tagged commit.

## Privacy

The app talks only to the addresses you give it (Home Assistant, the web pages you choose, your MQTT broker) and to devices on your network that use its API. While its API is on, it announces itself on your local network (mDNS: its name, id and version) so Home Assistant can find it. It has no accounts, no analytics, no crash reporting and no Google services. The only internet address it ever contacts by itself is GitHub, once a day, and only if you turn on *Check for updates*. Sign-in tokens and passwords are stored encrypted on the TV. See [SECURITY.md](SECURITY.md) for what listens on the network and how it's protected.

## Docs

- [Family planner](docs/planner.md): calendars and to-do lists on the TV or a wall tablet.
- [Signage and the photo frame](docs/signage.md): playlists, offline copies, time windows, photos.
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
app/src/main/java/io/github/mattgrechtech/openpane/
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
