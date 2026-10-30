# API

Three ways to control the app, all LAN-only: a REST API, MQTT, and adb. They run the same commands, and take the same [settings](#settings).

## Web admin page

Open `http://<tv-ip>:2424/` in a browser on the same network, or scan the QR code in the TV's Settings → Pop-ups and API → *Web admin, API address and token* (it opens the page already connected). The page uses the API below with the token, so anything it does an automation can do too. It has: status and *Ready to wake*, screen and page controls, a pop-up and speech sender, every setting (grouped, searchable, only changed ones are saved), the log, and a diagnostics download. The token is kept in the browser only if you tick *Remember on this device*.

## REST API

- Base: `http://<tv-ip>:2424/api/v1` (port in settings).
- Every request needs `Authorization: Bearer <token>`. The token is shown in Settings → *Show API address and token* (with a QR code), or over adb (see below). Ten wrong tokens from one address in a minute block that address for a minute.
- The `Host` header must be the TV's IP address (or `localhost`). This stops a web page in someone's browser from reaching the API through DNS tricks. Normal clients (curl, Home Assistant) send it automatically.
- Bodies are JSON with a `Content-Length` (no chunked uploads), up to 4 MB.
- Answers are JSON with `"ok": true|false` and, on failure, `"error"`.

```bash
curl -H "Authorization: Bearer $TOKEN" http://192.168.1.60:2424/api/v1/state
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"title":"Front door","message":"Someone is there","icon":"mdi:doorbell"}' \
     http://192.168.1.60:2424/api/v1/notify
```

| Route | Body | What it does |
|---|---|---|
| `GET /state` | | Everything below under [State](#state). `POST` works too, for Home Assistant's `rest_command` |
| `POST /url` | `{"url", "return_after"}` | Load a page (http/https only). `return_after` (seconds, 5–86400) goes back to the start page that long after, unless another page command comes first; also on `navigate` |
| `POST /navigate` | `{"path"}` | Go to an HA page without a reload, e.g. `/lovelace/1` (falls back to a full load) |
| `POST /reload` | `{"hard": false}` | Reload; `hard` clears the cache first |
| `POST /screen` | `{"mode": "on"\|"dim"\|"blank", "level": 50}` | Dim (5–95 %) or blank the dashboard with a black layer. The backlight stays on: TVs don't let apps control it |
| `POST /wake` | `{"minutes": 5}` | Turn the screen on (ending a screensaver), lift a dim, rest or night screen, and bring the dashboard to the front, even over another app. The night hours stay away for `minutes` (1–240) |
| `POST /motion` | `{"present": true\|false}` | Someone was seen: see [Wake on motion](#wake-on-motion). Without `present` it's a moment of motion; `present: true` holds the screen awake until `present: false` |
| `POST /brightness` | `{"level": 0-100}` | Screen brightness while the dashboard is in front; `-1` goes back to the device's own setting. Works on most tablets; many TVs ignore it |
| `POST /now_playing` | `{"entity": "media_player.kitchen", "corner", "size"}` | A pop-up that follows a Home Assistant media player: artwork, title, artist, progress, updated every 2 s, closed 30 s after it stops. Needs the TV's Home Assistant sign-in |
| `POST /now_playing/stop` | | Close it |
| `POST /pointer` | `{"enabled": true}` | On-screen pointer on/off (omit `enabled` to toggle) |
| `POST /notify` | see [Pop-ups](#pop-ups) | Show a pop-up |
| `POST /notify/clear` | `{"id"}` | Remove one pop-up, or all without `id` |
| `POST /start_page` | | Back to the start page |
| `POST /launch` | `{"package"}` | Open another TV app, e.g. `com.google.android.youtube.tv` |
| `POST /volume` | `{"level": 0-100}` | Media volume |
| `POST /tts` | `{"message", "language": "en-AU"}` | Speak with the TV's text-to-speech engine, if it has one |
| `POST /sound` | `{"url", "volume"}` | Play a sound or HA TTS audio URL |
| `POST /sound/stop` | | Stop sound and speech |
| `GET /settings` | | Settings as JSON (no secrets) |
| `GET /settings/schema` | | Every setting's type, default, choices and whether it's secret (write-only) |
| `POST /settings` | partial settings JSON | Change settings (see [Settings](#settings)); answers which keys were applied and which were rejected |
| `GET /diagnostics` | | State, settings without secrets, the last crash and the recent log, for bug reports |
| `GET /logs?lines=200` | | The recent log as plain text (tokens and passwords blanked) |

Status codes: `200` done, `202` started (speech, sound), `400` bad input, `401` token, `404` unknown route, `405` wrong method, `409` can't right now (e.g. the dashboard isn't open), `421` wrong Host, `429` blocked for bad tokens, `503` not ready yet (text-to-speech still starting: try again).

### Settings

The same names work in `POST /settings`, adb `CONFIGURE` and a settings export. Secrets are never sent back.

| Setting | Default | Values |
|---|---|---|
| `mode` | `home_assistant` | `home_assistant` or `web` (any web page, no Home Assistant sign-in) |
| `start_url` | | The page to show, `http(s)://...`. Empty: Home Assistant's default dashboard |
| `ha_url` | | Home Assistant's address. Empty: the start URL's address |
| `auth_mode` | `login` | `login` (sign in once on the TV), `token` (long-lived token), `none` (the page signs in) |
| `ha_long_lived_token` | | Secret. Used with `auth_mode: token` |
| `ha_kiosk_mode` | `true` | Hide Home Assistant's header and sidebar |
| `pages` | | More pages to rotate through: one URL per line, or a JSON array |
| `rotate_seconds` | `0` | Seconds on each page; `0` is off, else 10–86400 |
| `screen_off_from` / `screen_off_to` | `-1` / `7` | Hours (0–23) the screen goes black each night; `-1` is never |
| `remote_mode` | `buttons` | `buttons` (jump between items), `pointer` (on-screen pointer), `page` (arrows go to the page) |
| `keep_screen_on` | `true` | Stop the TV's screensaver over the dashboard |
| `orientation` | `auto` | `auto`, `landscape`, `portrait`, `reverse_landscape`, `reverse_portrait` |
| `home_app` | `false` | Offer TV Kiosk as the home screen (tablets, wall displays); then press Home and choose it |
| `key_events` | `false` | While the dashboard is in front, the colour keys (`red`, `green`, `yellow`, `blue`) and digits `0`–`9` fire a `tv_kiosk_key` event in Home Assistant (`key`, `long`, `device_id`, `device_name`) and the MQTT event entity (`event_type: key`). Navigation, Back, Home, volume and media keys are never taken |
| `burn_in_guard` | `false` | Move the page a few pixels every 2 minutes, against burn-in on OLED screens |
| `check_updates` | `false` | Once a day, ask GitHub whether a newer release is out; shown in Settings, the admin page, `/state` (`update`) and the integration's update entity. Never installs anything |
| `rest_after_minutes` | `0` | Rest (darken) the screen after this many minutes without motion, presence or the remote; `0` is never, else 1–240 |
| `rest_style` | `black` | While resting: `black`, `dim` (80 %), `clock` (time and date on black, moving every minute) or `page` (shows `rest_page`) |
| `rest_page` | | The page shown while resting with `rest_style: page`: a photo frame or ambient dashboard. The dashboard comes back on wake |
| `motion_front` | `unless_playing` | When motion brings the dashboard in front of another app: `unless_playing` (not while sound is playing), `always`, `never` (only wake the screen) |
| `motion_at_night` | `false` | Whether motion wakes the screen during the night hours |
| `boot_start` | `false` | Open the dashboard when the TV starts |
| `reload_on_network` | `true` | Reload when the network comes back |
| `reload_on_disconnect_seconds` | `60` | Reload when Home Assistant stays disconnected this long; `0` is off, else 10–3600 |
| `nightly_reload_hour` | `-1` | Reload every day at this hour; `-1` is off |
| `popup_size` | `medium` | `small`, `medium`, `large` |
| `popup_opacity` | `93` | Background opacity, 40–100 % |
| `popup_text_size` | `normal` | `normal` or `large` |
| `popup_corner` | `top_end` | `top_start`, `top_end`, `bottom_start`, `bottom_end` |
| `popup_duration` | `10` | Seconds, 1–600 |
| `popup_max_per_corner` | `3` | 1–3 |
| `popup_take_remote` | `false` | Pop-ups with buttons may take the remote over other apps |
| `api_enabled` | `true` | The REST API |
| `api_port` | `2424` | |
| `api_token` | generated | Secret. The REST API token |
| `tvoverlay_compat` | `false` | The token-less TvOverlay-compatible endpoint |
| `tvoverlay_port` | `5001` | |
| `mqtt_enabled` | `false` | |
| `mqtt_host`, `mqtt_port`, `mqtt_tls`, `mqtt_user` | `""`, `1883`, `false`, `""` | Broker connection |
| `mqtt_password` | | Secret |
| `mqtt_base_topic` | `tvkiosk` | |
| `mqtt_discovery_prefix` | `homeassistant` | |
| `device_name` | the TV's model | The name in Home Assistant and in diagnostics |
| `settings_pin` | | Secret. 4–8 digits to protect the TV's settings screen. Send `""` to remove it |

For example `{"pages": ["http://192.168.1.50:8123/cameras"], "rotate_seconds": 60}`.

### Pop-ups

| Field | Also accepted as | Meaning |
|---|---|---|
| `title` | | Heading (title or message required) |
| `message` | | Body text |
| `source` | | Small grey line under the message |
| `image` | `largeIcon` | Picture across the top: URL, base64 or `data:` URI (a camera's `entity_picture` link works) |
| `icon` | `smallIcon` | `mdi:name`, URL or base64. MDI names show as a matching emoji for now |
| `color` | `smallIconColor` | Accent colour, `#RRGGBB` or `#AARRGGBB` |
| `corner` | | `top_start`, `top_end`, `bottom_start`, `bottom_end` |
| `duration` | | Seconds, 1–600. `0` stays until it's closed: by its `id`, a button, or Back (at most an hour) |
| `size` | | `small`, `medium`, `large` |
| `opacity` | | Background opacity in percent, 40–100. Images and text stay solid |
| `text_size` | | `normal` or `large` |
| `actions` | | Up to 3 buttons: `[{"label": "Open camera", "navigate": "/lovelace/cameras"}]`. `navigate` opens a Home Assistant path, `url` opens a web page, `event` tells Home Assistant (see below), none of them just closes the pop-up. A Close button is always added |
| `camera` | | A Home Assistant camera to show **live** in the picture area, e.g. `camera.front_door`. The TV asks Home Assistant for the camera's stream with its own sign-in. Give `image` too, and the snapshot shows until the stream starts |
| `stream` | | Any live MJPEG URL instead, e.g. go2rtc's `http://<host>:1984/api/stream.mjpeg?src=front_door` or Frigate's. Played at up to 10 frames a second, scaled to fit, until the pop-up closes |
| `qr` | | Text shown as a QR code beside the message (up to 500 characters): a guest Wi-Fi login (`WIFI:T:WPA;S:Guest;P:password;;`), a link |
| `countdown` | | Seconds counting down to 0:00 in the pop-up, e.g. `300` for "Pizza ready in 4:59" |
| `progress` | | 0–100: a progress bar under the text. Send it again with the same `id` to move it ("Washing 75 %") |
| `sound` | | `chime`, `doorbell` or `alert` (built in), or an http(s) URL. Plays when the pop-up appears |
| `tts` | | Text the TV speaks when the pop-up appears, after the sound. `language` (e.g. `en-AU`) picks the voice |
| `id` | | Same id replaces the pop-up on screen; `POST /notify/clear` with it closes it early |

The token-less TvOverlay-compatible port ignores `camera` and `actions`, because they act with the TV's own sign-in.

**Buttons that tell Home Assistant.** A button with `"event": "unlock_front"` (lower case letters, digits and `_`) fires a `tv_kiosk_action` event in Home Assistant when pressed, with `action`, `label`, `popup_id`, `title`, `device_id` and `device_name`. The TV sends it with its own sign-in, so nothing else is needed. Over MQTT the TV's *Pop-up button* event entity fires too. An automation acts on it:

```yaml
triggers:
  - trigger: event
    event_type: tv_kiosk_action
    event_data: { action: unlock_front }
actions:
  - action: lock.unlock
    target: { entity_id: lock.front_door }
```

Anything left out comes from Settings → Pop-ups and API → Look and behaviour (defaults: top right, 10 s, medium, 93 %, normal text).

Pop-ups appear over any app when the app may "display over other apps" (the provisioning script grants it). Without that permission they only show while the dashboard is open, and the answer includes a `warning`.

**Buttons and the remote.** Pop-ups never take the remote from the app in front, with one exception: a pop-up with `actions` takes it while the dashboard is in front, and over other apps too if *Pop-ups with buttons can take the remote* is on. Then OK presses a button and Back closes the pop-up; the remote goes back as soon as it closes or times out. Back on the dashboard always closes the newest pop-up first.

### TvOverlay-compatible endpoint

Off by default. When on (Settings → Local API), `POST http://<tv-ip>:5001/notify` accepts TvOverlay's fields, so automations written for TvOverlay work by changing nothing, or only the port. Like TvOverlay, it has **no token**: anyone on the network can show pop-ups, nothing else. For that reason `actions` are ignored there; use the token-protected `/api/v1/notify` for pop-ups with buttons. Don't run it and TvOverlay on the same port.

### Wake on motion

Home Assistant sends `motion` when your sensors see someone ([blueprint](home-assistant.md#wake-on-motion)). What the app does with it:

- **Resting:** with `rest_after_minutes` set, the screen goes dark that long after the last motion, presence or remote press, never while someone is present. The page keeps running behind it, connected to Home Assistant, so waking shows it straight away with current values.
- **Waking:** `motion` wakes a resting screen, turns the screen on if Android has it off or a screensaver is up, and brings the dashboard forward as `motion_front` allows. Any remote button wakes it too.
- **Not straight back:** for 3 seconds after resting starts, motion is ignored, so the change in light can't wake it again.
- **Night:** in the night hours (`screen_off_from` / `screen_off_to`) motion is ignored unless `motion_at_night` is on.
- **Standby:** a TV in standby can't hear the app's API (Android 14 cuts apps off the network). Home Assistant has to switch the TV on first; the blueprint does.

The answer says what happened, for example `{"result": "woke", "present": true, "screen_was_off": false, "was_dark": true, "dashboard": "in front"}`. `result` is `woke`, `awake` (wasn't resting: the timer starts again), `cleared`, `ignored_grace` or `ignored_quiet`. `dashboard` is `in front`, `brought to front`, `left behind the app in front` or `behind other apps` (with a `warning`).

On Android 14 and newer, turning the screen on needs a permission the TV doesn't offer in its settings: the provisioning script grants it, or `adb shell appops set <package> TURN_SCREEN_ON allow`. Settings → Screen → *Ready to wake* says what's missing.

## State

```json
{
  "url": "http://192.168.1.50:8123/house?tv=1&external_auth=1",
  "title": "House",
  "screen": "on", "dim_level": 0,
  "resting": false, "night": false, "present": false,
  "last_motion_at": 1793000000, "last_motion_iso": "2026-10-30T09:00:00Z", "last_wake_ms": 207,
  "wake": {"screen_on": true, "overlay_permission": true, "turn_screen_on": "allowed",
           "battery_optimization_exempt": true, "low_power_standby": true, "low_power_standby_exempt": false},
  "dashboard_visible": true, "pointer": false,
  "ha_connection": "connected",
  "last_error": "", "last_reload_reason": "network back", "last_reload_at": 1793000000, "reload_count": 3,
  "app_uptime_s": 86400, "device_uptime_s": 172800, "app_started_at": 1793000000, "app_started_iso": "2026-10-30T09:00:00Z",
  "memory_available_mb": 812, "memory_total_mb": 1946, "memory_low": false, "app_memory_mb": 210,
  "overlay_permission": true, "ip": "192.168.1.60",
  "app_version": "0.1.0", "webview_version": "130.0.6723.102", "webview_package": "com.google.android.webview",
  "android_version": "12", "android_sdk": 31, "model": "...", "manufacturer": "..."
}
```

`last_wake_ms` is how long the last wake took, from the command arriving to the dashboard's next frame. `wake.turn_screen_on` is `allowed`, `default`, `denied` or `not needed` (before Android 14); `low_power_standby` (Android 13+) means the TV cuts apps off the network in standby.

`ha_connection` comes from HA's frontend through the app bridge, so it is only known when the app manages sign-in (auth mode `login` or `token`); otherwise it stays `unknown`.

## MQTT

Settings → MQTT: broker address, port, user and password (create a dedicated HA user for it), optional TLS (the broker's certificate must be trusted by Android; self-signed certificates aren't supported yet).

The TV appears in Home Assistant as one device through MQTT discovery, with:

| Entity | Type |
|---|---|
| Reload, Reload and clear cache, Wake, Motion, Go to start page | buttons |
| Someone present (turn on and off from your presence sensor) | switch |
| Screen resting | binary sensor |
| Last motion | timestamp sensor |
| Screen (on / dim / blank) | select |
| Dim level, Brightness | numbers |
| Pointer | switch |
| Page (current URL; set it to load a page) | text |
| Pop-up | notify (plain text, or a JSON pop-up) |
| Dashboard visible, Can show pop-ups over apps | binary sensors |
| HA connection, last error, app started, reloads, last wake time, app memory, memory available, WebView version, IP | diagnostic sensors |

Topics, with `<base>` = base topic (default `tvkiosk`) and `<id>` = the device id:

- `<base>/<id>/availability`: `online`/`offline` (retained; `offline` is the last will).
- `<base>/<id>/state`: the [State](#state) JSON (retained), refreshed on changes and every minute.
- `<base>/<id>/cmd/<command>`: commands. The payload is the same JSON as the REST body, for `url`, `navigate`, `reload`, `screen`, `wake`, `motion`, `start_page`, `pointer`, `notify`, `notify_clear`, `launch`, `volume`, `tts`, `sound`, `stop_sound`. `notify`, `tts`, `url` and `navigate` also take plain text. Retained command messages are ignored.

## adb

For set-up scripts. The receiver needs a permission only adb (the shell) has.

```bash
PKG=io.github.mattgrechtech.tvkiosk.debug     # release builds: without .debug
R=$PKG/io.github.mattgrechtech.tvkiosk.service.ProvisionReceiver
A=io.github.mattgrechtech.tvkiosk

adb shell am broadcast -n $R -a $A.CONFIGURE --es json '{"start_url":"http://192.168.1.50:8123/house?tv=1"}'
adb shell am broadcast -n $R -a $A.GET_TOKEN
adb shell am broadcast -n $R -a $A.EXPORT                       # also writes Android/data/$PKG/files/settings.json
adb shell am broadcast -n $R -a $A.COMMAND --es command reload
adb shell am broadcast -n $R -a $A.DIAGNOSTICS                  # also writes Android/data/$PKG/files/diagnostics.json
```

`--es json_b64 <base64>` works wherever `--es json` does; the provisioning scripts use it because PowerShell 5.1 strips the quotes in JSON.

## Remote keys and your own pages

In the default "jump between buttons" remote mode, the app moves its own highlight between a page's clickable items. A page with its own remote navigation can take the keys instead:

- Put `data-tvkiosk-keys="page"` on the element that holds your controls (it works through shadow roots). While focus is inside it, the arrow keys go to the page as normal key events, OK as Enter, and Back as an Escape keydown (the app doesn't go back in history). Move focus out of it, for example back to a button outside it, to hand the keys back to the app.
- A focused slider, select or text field gets Left and Right without any marking; Up and Down still move the app's highlight.
