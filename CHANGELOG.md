# Changelog

## 0.1.0 (unreleased)

First public version. Pre-alpha: tested on one TCL Google TV (Android 14), including a 72-hour run.

- Pop-ups can pause what's playing (`pause_media`, for the doorbell): Netflix, YouTube or live TV pause while it's up and resume when it closes, only if the pop-up paused them.
- TCL and similar TVs: the app reports when the maker's app manager blocks it restarting after an update or boot (*Auto-start*, *Background service* in the admin page); provisioning allows it.
- Family board: a Home Assistant to-do list (`planner_board`) shown as sticky notes across the top of the planner; post from the admin page, the HA app or automations, clear by tapping. `POST /planner/note`.
- Family planner (`kiosk:planner`): a built-in week view of Home Assistant calendars, each in its own colour, with to-do lists beside it; items tick off by touch or remote. Upright layout for tablets. Choose calendars, lists and days with `planner_calendars`, `planner_todos`, `planner_days`. New API routes `GET /planner` and `POST /planner/todo`.
- Phone remote: the admin page's Control tab has a D-pad, Back, Menu, play/pause and a box that types into the TV's focused field (`POST /remote`).
- Scenes by time of day (`scenes`): from a time, a page, brightness and rest style, one line each.
- Offline screen: when Home Assistant or the network drops and the page can't load, the last good dashboard shows dimmed with the time and "Reconnecting", instead of a warning.
- Pop-up buttons can `toggle` a light, fan, input boolean or media player directly; anything that could open, unlock or run something needs `event` and an automation.
- Do not disturb (`do_not_disturb`, a switch over MQTT and in the integration): pop-ups wait unless they have `priority: high`.
- Now playing: `now_playing` follows a Home Assistant media player in a pop-up (artwork, title, artist, progress), and closes itself after the music stops. Pop-ups replaced by `id` now keep their place without fading in again, and recent pictures are reused instead of downloaded again.
- Remote keys for automations (`key_events`, off by default): the colour keys and digits fire `tv_kiosk_key` (short or long press) while the dashboard is in front.
- Low-memory devices: the dashboard's renderer has important priority, and when Android reports low memory while nobody is using the screen, the page reloads (at most every 30 minutes) instead of slowing down for days.
- Pop-ups can show a QR code (`qr`: guest Wi-Fi, a link), a live countdown (`countdown`) and a progress bar (`progress`, moved by resending with the same `id`).
- Opt-in update check (`check_updates`, off by default): once a day, asks GitHub for the latest release and says so; never downloads or installs anything. The admin page can export settings to a file and import them on another TV.
- Home Assistant integration (`custom_components/tv_kiosk`, installable from HACS as a custom repository): found automatically (the TV announces `_tvkiosk._tcp`), set up with the token, actions `tv_kiosk.notify`, `navigate`, `open_url`, `motion`, `wake`, `speak`, `play_sound`, `launch_app`, `clear_popup`, and entities for the screen, brightness, presence, page, last motion and health. `/state` now includes `device_id` and `device_name`.
- Resting styles: a drifting clock on black, or another page (a photo frame or ambient dashboard, `rest_page`) that hands back to the dashboard on wake. A key press during a rest only wakes the screen.
- Live camera pop-ups: `camera` (a Home Assistant camera entity, streamed through Home Assistant with the TV's sign-in) or `stream` (any MJPEG URL, e.g. go2rtc or Frigate), shown at up to 10 frames a second over any app, with the snapshot as a placeholder. Ignored on the token-less TvOverlay port.
- Runs on Android 7.0 and newer (was 10), so older TV boxes, tablets and wall displays can use it. For tablets: an orientation setting, *Use as the home screen*, and a black screen that also turns the backlight down where the device allows it. Not yet tested below Android 14.
- Web admin page at `http://<tv-ip>:2424/`: status, controls, pop-ups, speech, every setting, the log and diagnostics, from any browser on the network. The TV's QR code opens it already connected. Strict Content-Security-Policy, no framing, nothing loaded from the internet. New `GET /api/v1/settings/schema`.
- `return_after` on `url` and `navigate` goes back to the start page after a set time ("laundry done → laundry view for 2 minutes"). A `brightness` command (and MQTT number) for screens that allow it. A burn-in guard moves the page a few pixels every 2 minutes for OLED screens.
- Pop-ups: `duration: 0` stays until closed; buttons can send a `tv_kiosk_action` event to Home Assistant (and fire an MQTT event entity) for automations such as "Unlock"; `sound` plays a built-in chime, doorbell or alert (made in code, no sound files) or a URL, and `tts` speaks text, when the pop-up appears.
- Wake on motion: the screen rests after a set time with nobody around (black or dimmed, the page kept running) and wakes on `motion` from Home Assistant (REST, MQTT button, or the *Someone present* switch for presence sensors). It doesn't take the screen from something playing (setting), stays dark at night unless allowed, and ignores motion for 3 s after resting starts. `wake` now turns the screen on and ends a screensaver, and holds the night screen off for `minutes`. On TVs it wakes from its own rest: keep the TV out of standby, where most TVs drop off the network. Tablets wake from screen-off. A pop-up wakes a resting screen, and the screen doesn't rest while one is showing. State reports `resting`, `present`, `last_motion_at`, the last wake's time to the next frame, and what the TV allows (*Ready to wake* in Settings). `POST /state` works, for `rest_command`.
- Security: the Home Assistant sign-in is tied to the server that issued it (changing the HA address, including over the API, forgets it rather than sending it on); the TvOverlay-compatible port only accepts JSON bodies (no pop-ups from web pages); a 10-second limit per API request and three connections per address; pop-up pictures scaled down as they load; secrets blanked from logs and adb exports; a lockout after five wrong PINs; the Gradle download is checked against its published checksum.
- A clear message, instead of a crash loop, when the TV's WebView is missing or updating.
- Pages can run their own remote navigation: inside an element marked `data-tvkiosk-keys="page"` the arrows, OK and Back (as Escape) go to the page. A focused slider, select or text field gets Left and Right.
- Pop-up settings: size (small, medium, large), background opacity, larger text, default corner, time on screen and how many stack per corner, with a sample that shows each change. Each pop-up can override them (`size`, `opacity`, `text_size`).
- Pop-up buttons (`actions`): open a Home Assistant page or a web page, or close. Back on the dashboard closes the newest pop-up first. An opt-in setting lets pop-ups with buttons take the remote over other apps while they're up. Buttons are ignored on the token-less TvOverlay-compatible port.
- First-run setup, laid out like the TV's own setup: choose Home Assistant or a web page. Home Assistant is found on the network by itself (mDNS), checked, signed in to, and a dashboard chosen, with no URL typing. "Run setup again" is in Settings.
- Web mode: show any web page, with no Home Assistant sign-in or bridge. Home Assistant-only settings are hidden.
- More pages: rotate through several pages (HA dashboards or any web pages) every 15 s to 1 h. Using the remote holds the current page; the quick menu has "Next page". HA pages on the same server switch instantly, without a reload.
- Night screen: the screen goes black between set hours (pop-ups still show); any button wakes it for 5 minutes.
- New settings: `mode`, `pages` (one URL per line, or a JSON array), `rotate_seconds`, `screen_off_from`, `screen_off_to`.
- Restarts itself after a crash (stops after 3 crashes in 10 minutes), says so with a pop-up, and keeps the crash details.
- A log on the TV with tokens blanked, readable in Settings > Status and about, over the API (`/api/v1/logs`, `/api/v1/diagnostics`) and over adb (`DIAGNOSTICS`).
- Home Assistant's dashboard header is hidden too, not just the sidebar (HA's own kiosk mode only hides the sidebar).
- Fixed: the request to hide HA's sidebar could arrive before the frontend was ready and be dropped; it's now retried.
- Pop-ups with camera snapshots show over any app (tested over YouTube).
- Remote control: "jump between buttons" is the new default. Arrows move a highlight between the page's clickable items (found automatically, inside Home Assistant's components too), OK presses, Back closes an open Home Assistant pop-up first. The pointer stays available.
- Settings rebuilt as a left rail of categories with the settings on the right, like the TV's own settings. Every change saves straight away; no Save button.
- "Choose a dashboard" lists your Home Assistant dashboards and panels (over HA's WebSocket API), so no URL typing.
- Sign-in reworked: a Sign in button on the dashboard when needed, a sign-in screen with progress, errors and a "Signed in" step, then straight back to the dashboard.
- Fixed: a second service start killed the app on Android 14.

Also in this version:

- Full-screen WebView dashboard with recovery: retry with back-off, renderer crash and freeze recovery, reload on network return, on long HA disconnects and nightly.
- HA frontend bridge (external auth and external bus): sign in once on the TV or a long-lived token, HA kiosk mode, in-app navigation, connection status.
- Remote: hold OK or Menu for the quick menu; optional on-screen pointer; Back goes back, then home.
- Pop-ups over other apps (TvOverlay-compatible fields), stacked per corner, replace by id.
- REST API with token, Host check and rate limiting; optional TvOverlay-compatible `/notify` endpoint.
- MQTT with Home Assistant discovery.
- Settings screen for the remote, encrypted secrets, JSON import/export, settings PIN.
- adb provisioning (receiver protected by the DUMP permission) and PowerShell/bash scripts.
- Boot start, foreground service, TV banner and icons.
