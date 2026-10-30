# Security

This app trusts your **local network**, like the rest of ha-tv-kit. Read this before installing.

## What listens on the network

| Port | What | Protection |
|---|---|---|
| 2424 (configurable), announced as `_tvkiosk._tcp` | REST API, and the web admin page | API: bearer token (random, 256-bit, rotatable); `Host` header must be the TV's address; 10 bad tokens per minute blocks the sender for a minute. The admin page's own files (`/`, `/admin.js`, `/admin.css`) are served without the token because they contain nothing secret; the page asks for the token and does everything through the API. It loads nothing from the internet, can't be framed (`frame-ancestors 'none'`), runs only its own script (strict Content-Security-Policy) and shows TV data as text only |
| 5001 (off by default) | TvOverlay-compatible `/notify` | **None**, like TvOverlay itself. It can only show plain pop-ups: it passes TvOverlay's fields and the look, and drops buttons, cameras, streams, sounds, speech, QR codes, priority and pop-ups that stay up |
| none | MQTT | Outgoing connection to your broker with its own user; TLS optional |

Never forward these ports from the internet. Put visitors and untrusted gadgets on a guest Wi-Fi that can't reach your TVs.

## What it can't do, by design

- No remote shell, no running scripts in the page, no installing or removing apps, no file access.
- The adb set-up receiver requires a permission only adb and the system hold.
- The bridge that gives Home Assistant's frontend its sign-in token only answers pages on your HA address. With current WebViews the WebView enforces this; on old ones every call checks the page's origin.
- The TV's Home Assistant sign-in stays with the server that issued it. If the HA address changes (in setup, by hand, or by someone with the API token), the old sign-in is forgotten, never sent to the new address.
- The token-less TvOverlay-compatible port only accepts `application/json` bodies, so a web page someone opens can't post pop-ups to it.
- The API answers one request per connection within 10 seconds, at most three connections per address. Pictures in pop-ups are scaled down as they load, so a huge image can't run the TV out of memory.
- Web pages can't open other apps (`intent:` links are blocked) and can't read files on the TV.
- No cloud, no analytics, no crash reporting to anyone.

## Acting in Home Assistant

The TV's own Home Assistant sign-in is used only for: firing `tv_kiosk_action` / `tv_kiosk_key` events, reading the state of a `camera.*` or `media_player.*` entity named in a pop-up or `now_playing` (entity ids are checked strictly before they go into a URL), and toggling a `light`, `fan`, `input_boolean` or `media_player` from a pop-up button. Anything that could open, unlock or run something (covers, switches, scripts, scenes, locks, alarms) needs an event and an automation the household wrote. Answers from Home Assistant are never written to the log.

The Home Assistant integration never follows a network announcement to a new address by itself: a TV that moved is fixed by the user, so a fake announcement can't collect the token.

## Secrets

- The API token, MQTT password, HA long-lived token, HA refresh token and settings PIN are stored encrypted with a key held in the Android Keystore.
- Exports leave secrets out unless you ask for them over adb; the HA refresh token is never exported.
- The API token is shown on the TV's settings screen. Anyone who can open settings on the TV can read it: set a settings PIN if that matters in your home. After five wrong PINs it locks for 30 seconds, then longer each time. A forgotten PIN can be removed with the API token or adb: see [Troubleshooting](docs/troubleshooting.md#forgotten-settings-pin).
- Logs and diagnostics blank tokens, passwords and the PIN. Settings files written for adb never contain secrets.
- If the token leaks: Settings → *New API token*, then update Home Assistant.

## Sign-in trade-offs

- **Sign in once on this TV** (recommended): the TV keeps a refresh token for the HA user you signed in as. Revoke it in that user's HA profile (Security → Refresh tokens) if the TV is lost or replaced.
- **Long-lived token:** simple, but it never expires on its own. Delete it in HA when you stop using it.
- **trusted_networks:** see the kit's notes: anyone who takes over the TV's IP gets that HA user's session.

In every case use a **non-admin HA user** for the TV. Non-admin isn't read-only: the TV user can still control devices.

## Reporting a problem

Open a GitHub issue without exploit details and ask for a private contact, or use GitHub's private vulnerability reporting on the repository if it's enabled.
