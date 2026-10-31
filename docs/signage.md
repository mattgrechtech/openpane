# Signage and the photo frame

Two built-in pages that play pictures, videos and pages on their own, kept on the device so they carry on without the network:

- **`kiosk:signage`**: a playlist of pictures, videos and web pages, each for a set time and only on the days and hours you choose. For a shop window, a menu board, a waiting room, or a hallway screen.
- **`kiosk:photos`**: a photo frame from a folder in Home Assistant's media, or from photo integrations through the media browser. It can be a page, or what the screen shows while it rests.

Use either wherever a page goes: the start page, the pages to rotate through, a scene by time of day, or `openpane.open_url`. Both are off until you set them up, and nothing runs or downloads until then.

## Signage

Set the playlist on the admin page (**Settings → Signage**) or with `openpane.apply_settings`. One item a line: seconds, then what to show, then any limits.

```
20 /local/signage/menu.jpg
0 /local/signage/promo.mp4 sound=on
60 /lovelace/specials hours=11:00-14:00
30 https://example.com/news days=mon-fri hours=07:00-18:00
15 kiosk:planner days=sat,sun
```

- **What to show:** a picture (`.jpg`, `.png`, `.webp`, `.gif`, `.bmp`), a video (`.mp4`, `.webm`, `.mkv`, `.m4v`) or a page. Use a path on the Home Assistant server (files in `config/www/` are at `/local/`; media folders are at `/media/local/…`), any http(s) address, or `kiosk:planner`.
- **Seconds:** 5–3600. `0` plays a video to its end. A video set to `0` that's the only item playing loops.
- **`days=`**: `mon-fri`, `sat,sun`, `fri-mon`, or a mix such as `mon,wed-fri`.
- **`hours=`**: such as `07:00-18:00`. A window can cross midnight (`22:00-02:00`); the hours after midnight belong to the day it started.
- **`sound=on`**: plays a video's sound. Videos are silent by default.

Items outside their days and hours are skipped. With nothing to play, the screen stays black and checks again every 30 seconds. For a whole-schedule switch (signage in opening hours, the dashboard after), use [scenes](api.md#settings): `08:00 kiosk:signage` and `18:00 /lovelace/0`.

**Offline.** Pictures and videos are copied to the device, and only played once they're there, so a slow download never shows half a picture. Each copy is checked again every 6 hours, cheaply, asking the server only whether it has changed. Copies the playlist no longer lists are deleted. Pictures are stored at screen size, so a 4 MB photo takes a few hundred KB. Without the network, pictures and videos keep playing and pages are skipped until it's back. `media_cache_mb` (default 500) caps the space used.

Signage never rests the screen, since it plays for whoever walks past. The night hours still darken it. The remote's right arrow, `openpane.next_item` or `POST /api/v1/next` moves on to the next item. `/state` reports what's on screen as `media`, for example `menu.jpg 1/5`.

## Photo frame

Set **Settings → Photo frame → Photos** to one or more lines:

- a media folder such as `/media/local/photos` (the *My media* folder Home Assistant shows in **Media**), and its sub-folders two levels down;
- a `media-source://` address of an album from the media browser, which also reaches photo integrations;
- single pictures: `/local/frame/1.jpg` or an http(s) address.

Up to 500 pictures, kept on the device like signage's. The frame shows each one for `photo_seconds` (default 20), crossfading and drifting slowly so nothing burns in. `photo_shuffle` (default on) mixes the order, and `photo_clock` (default on) puts the time in a corner. A picture is turned the right way up from its camera data. When it's close to the screen's shape it fills the screen; otherwise it's shown whole on black.

**As the rest.** Set *While resting* (`rest_style`) to *Photo frame* (`photos`). Instead of going black when nobody's around, the screen shows the photos, and the first touch, key or motion brings the dashboard back. Until the first photos have arrived it shows the clock. A scene can choose it too: `21:00 rest=photos rest_after=5`.

**Videos** in a photo folder are left out. Use signage for those.

## Settings

| Setting | Default | |
|---|---|---|
| `signage_playlist` | | The playlist, one item a line (above) |
| `media_cache_mb` | `500` | Most space kept on the device for signage and photos, 50–8000 MB. Past it, new items wait |
| `photo_source` | | Photo folders and pictures, one a line |
| `photo_seconds` | `20` | Seconds on each photo, 5–3600 |
| `photo_shuffle` | `true` | Mix the order |
| `photo_clock` | `true` | Show the time over the photos |
