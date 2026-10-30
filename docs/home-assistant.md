# Home Assistant set-up

Three options. **The TV Kiosk integration is the easiest:** it finds the TV, needs no YAML, and gives you actions like `tv_kiosk.notify`. REST commands and MQTT work too, without installing anything.

## The TV Kiosk integration

1. In HACS: ⋮ → *Custom repositories* → add `https://github.com/mattgrechtech/ha-tv-kiosk` as an *Integration*, then download **TV Kiosk** and restart Home Assistant.
2. Settings → Devices & services: the TV usually shows up under *Discovered* (it announces itself on the network). Otherwise *Add integration* → TV Kiosk and type the TV's IP address.
3. Enter the TV's API token (on the TV: Settings → Pop-ups and API → *Web admin, API address and token*).

The TV becomes a device with:

- **Actions** for one or more TVs: `tv_kiosk.notify` (every pop-up field, including `camera`, `sound`, `tts` and buttons), `tv_kiosk.clear_popup`, `tv_kiosk.navigate` and `tv_kiosk.open_url` (with `return_after`), `tv_kiosk.motion`, `tv_kiosk.wake`, `tv_kiosk.speak`, `tv_kiosk.play_sound`, `tv_kiosk.launch_app`.
- **Entities:** a Pop-up notify entity (for `notify.send_message`), Wake / Motion / Reload / Start page / Stop sound buttons, a Screen select, a Brightness number, a *Someone present* switch, Dashboard in front / Screen resting / Someone present sensors, the page, screen state, last motion, and diagnostics (memory, wake time, WebView version, HA connection).

```yaml
action: tv_kiosk.notify
data:
  device_id: <your TV>
  title: Front door
  message: Someone's at the door
  camera: camera.front_door_live_view
  sound: doorbell
  duration: 30
  actions:
    - label: Unlock
      event: unlock_front
```

Pop-up buttons with an `event` fire `tv_kiosk_action` in Home Assistant (see [api.md](api.md#pop-ups)).

> **Using ha-tv-kit?** Its pop-ups, bin night, garage and doorbell flows already exist: [`tv_kit_config.yaml`](https://github.com/mattgrechtech/ha-tv-kit/blob/main/homeassistant/packages/tv_kit_config.yaml) holds the TV list and the `rest_command`, and [`script.tv_popup`](https://github.com/mattgrechtech/ha-tv-kit/blob/main/homeassistant/packages/tv_kit_scripts.yaml) sends to every TV that's on, with the [doorbell blueprint](https://github.com/mattgrechtech/ha-tv-kit/blob/main/homeassistant/blueprints/automation/ha-tv-kit/camera_motion_tv_popup.yaml) on top. They work with this app unchanged through the TvOverlay-compatible mode; see [Moving the kit's pop-ups over](#moving-the-kits-pop-ups-over).

### Several TVs

With the integration, every action takes one TV or many:

- **A pop-up on every TV:** `tv_kiosk.notify` with all of them in `device_id`.
- **The same set-up everywhere:** `tv_kiosk.apply_settings` with, for example, `{rest_after_minutes: 5, rest_style: clock, popup_corner: bottom_end}`. Only the settings you list change; each TV keeps its own name and token.
- **One TV leads, the others follow:** when the lead TV's screen changes, do the same on the rest.

```yaml
alias: Bedtime on every TV
triggers:
  - trigger: state
    entity_id: select.living_room_tv_screen
actions:
  - action: select.select_option
    target:
      entity_id: [select.kitchen_tv_screen, select.bedroom_tv_screen]
    data:
      option: "{{ trigger.to_state.state }}"
```

## Option A: REST commands (no broker)

1. Get the TV's API token: the provisioning script prints it, or Settings → *Show API address and token*.
2. In `secrets.yaml`:
   ```yaml
   tv_kiosk_living_room_auth: "Bearer 0123...your-token...cdef"
   ```
3. In a package or `configuration.yaml`:
   ```yaml
   rest_command:
     tv_kiosk_living_room:
       url: "http://192.168.1.60:2424/api/v1/{{ command }}"
       method: POST
       headers:
         Authorization: !secret tv_kiosk_living_room_auth
       content_type: "application/json"
       payload: "{{ (args | default({})) | tojson }}"
       timeout: 8

   rest:
     - resource: "http://192.168.1.60:2424/api/v1/state"
       headers:
         Authorization: !secret tv_kiosk_living_room_auth
       scan_interval: 60
       sensor:
         - name: "Living room TV kiosk page"
           value_template: "{{ value_json.title }}"
           json_attributes: [url, screen, ha_connection, last_error, app_memory_mb, memory_available_mb, app_uptime_s, webview_version]
       binary_sensor:
         - name: "Living room TV kiosk visible"
           value_template: "{{ value_json.dashboard_visible }}"
   ```
4. Use it:
   ```yaml
   action: rest_command.tv_kiosk_living_room
   data:
     command: notify
     args:
       title: Front door
       message: Someone is at the door
       image: "http://192.168.1.50:8123{{ state_attr('camera.front_door', 'entity_picture') }}"
       duration: 20
   ```
   `image` must be a full URL the TV can reach. A camera's `entity_picture` is only a path, so put your Home Assistant address in front of it as above (the kit's `script.tv_popup` does this for you).

Other commands: `{command: reload}`, `{command: screen, args: {mode: dim, level: 60}}`, `{command: url, args: {url: "http://..."}}`, `{command: navigate, args: {path: /lovelace/cameras}}`, `{command: wake}`.

### Moving the kit's pop-ups over

The kit's `script.tv_popup` posts TvOverlay payloads to port 5001. Two ways:

- **No HA changes:** turn on *TvOverlay-compatible pop-ups* in the app's settings (port 5001) and uninstall or stop TvOverlay on that TV. Same trust model as TvOverlay: no token.
- **With the token (better):** point the kit's `rest_command.tvoverlay_notify` at `http://{{ host }}:2424/api/v1/notify` and add the `Authorization` header. The payload fields are the same.

## Option B: MQTT discovery

1. Install the Mosquitto broker app and the MQTT integration in HA, and create a dedicated HA user for the TVs (Settings → People → Users).
2. On the TV: Settings → MQTT: on, broker = HA's IP, port 1883, that user and password. Save.
3. The TV appears under Settings → Devices & services → MQTT with buttons, a screen select, a dim slider, a pointer switch, a page text field, a pop-up notify entity and diagnostics. See [api.md](api.md#mqtt).

Send a pop-up:

```yaml
action: notify.send_message
target:
  entity_id: notify.living_room_tv_pop_up
data:
  message: Bins go out tonight
```

For title, image and corner, publish JSON to the command topic instead:

```yaml
action: mqtt.publish
data:
  topic: tvkiosk/<device-id>/cmd/notify
  payload: '{"title":"Front door","message":"Motion","image":"http://192.168.1.50:8123/local/x.png"}'
```

## Doorbell pop-up with a camera picture

When someone presses the doorbell, the TV shows a pop-up with a picture from the camera, over the dashboard or whatever is playing. Tested with a Ring doorbell; any doorbell or camera that Home Assistant shows works the same way.

**What you need:**

- Your doorbell in Home Assistant (for Ring: Settings → Devices & services → Add integration → Ring). Note two entity names, from the doorbell's device page:
  - the **press event**, for example `event.front_door_ding` (Ring), or a `binary_sensor` that turns on when pressed;
  - the **camera**, for example `camera.front_door_live_view`. For a battery doorbell, the "last recording" camera often gives a picture faster.
- The `rest_command` from [Option A](#option-a-rest-commands-no-broker), steps 1 to 3.

**The automation** (Settings → Automations → Create → ⋮ → Edit in YAML, paste, change the names and the address, save):

```yaml
alias: Doorbell on the TV
triggers:
  - trigger: state
    entity_id: event.front_door_ding
    not_from: [unavailable, unknown]
actions:
  - action: rest_command.tv_kiosk_living_room
    data:
      command: notify
      args:
        id: doorbell
        title: Front door
        message: "Someone's at the door ({{ now().strftime('%-I:%M %p') }})"
        image: "http://192.168.1.50:8123{{ state_attr('camera.front_door_live_view', 'entity_picture') }}"
        duration: 20
        sound: doorbell
        actions:
          - label: Open camera
            navigate: /lovelace/cameras
mode: single
```

- `192.168.1.50:8123` is a placeholder: use your Home Assistant address, as the TV reaches it.
- `id: doorbell` makes a second press replace the pop-up instead of stacking another.
- **Live instead of a snapshot:** add `camera: camera.front_door_live_view` (keep `image` as the first picture) and set `duration` to 30 or so: the pop-up shows the camera live until it closes. Any camera whose live view works in Home Assistant can do this; for the smoothest picture, point `stream` at a go2rtc or Frigate MJPEG link. Live view hasn't been tested on a Ring doorbell yet.
- `sound: doorbell` plays a ding-dong on the TV. Other built-in sounds: `chime`, `alert`. Add `tts: "Someone's at the front door"` to have the TV say it too.
- A button can also act in Home Assistant, for example `{label: Unlock, event: unlock_front}` with an automation on the `tv_kiosk_action` event: see [Buttons that tell Home Assistant](api.md#pop-ups).
- The button is optional; change `/lovelace/cameras` to a dashboard page of yours, or remove `actions`. Back closes the pop-up.
- The picture link carries a short-lived access token from Home Assistant, so the TV loads it without signing in. Don't copy camera pictures into `/config/www`: anyone on your network can read that folder without a login.

**Using ha-tv-kit?** Its [doorbell blueprint](https://github.com/mattgrechtech/ha-tv-kit/blob/main/homeassistant/blueprints/automation/ha-tv-kit/camera_motion_tv_popup.yaml) does this for every TV that's on, with a cooldown and an optional phone alert. It's what the Ring test used.

## Now playing

When music starts on a player near the TV, show what's playing over whatever is on screen:

```yaml
triggers:
  - trigger: state
    entity_id: media_player.kitchen
    to: playing
actions:
  - action: tv_kiosk.now_playing
    data:
      device_id: <your TV>
      entity: media_player.kitchen
      corner: bottom_end
```

The pop-up keeps its artwork, title, artist and progress up to date and closes by itself 30 seconds after the music stops. Without the integration: `rest_command` with `{command: now_playing, args: {entity: media_player.kitchen}}`.

## Remote keys for automations

Turn on Settings → Screen → *Colour keys and digits to Home Assistant*. Then, while the dashboard is in front, each colour key and digit fires a `tv_kiosk_key` event, with `long: true` for a long press:

```yaml
triggers:
  - trigger: event
    event_type: tv_kiosk_key
    event_data: { key: red }
actions:
  - action: rest_command.tv_kiosk_living_room
    data: { command: navigate, args: { path: /lovelace/cameras, return_after: 120 } }
```

## Wake on motion

The dashboard goes dark when nobody is around and comes back the moment your sensors see someone. Waking from dark is instant: the page keeps running behind the dark screen, still connected to Home Assistant.

**1. In TV Kiosk** (Settings → Screen → Wake on motion):

- **Rest the screen after:** how long without motion before it goes dark, for example 5 min.
- **Motion brings the dashboard back:** "Unless something is playing" (the default) never takes the screen from a film or YouTube.
- **Ready to wake** should say "Yes". If it names something missing, run the provisioning script again or follow what it says.

**2. In Home Assistant:** import the blueprint.

[![Import the blueprint](https://my.home-assistant.io/badges/blueprint_import.svg)](https://my.home-assistant.io/redirect/blueprint_import/?blueprint_url=https%3A%2F%2Fgithub.com%2Fmattgrechtech%2Fha-tv-kiosk%2Fblob%2Fmain%2Fblueprints%2Fautomation%2Ftv-kiosk%2Fmotion_wake.yaml)

Then create an automation from it:

- **Sensors:** one or more motion, occupancy or presence sensors in the room. A presence sensor (mmWave) is best for a living room: it stays on while someone sits still, so the screen stays awake. A motion sensor (PIR) only sees movement, so pair it with a long enough rest time.
- **TV Kiosk rest_command:** the name from [Option A](#option-a-rest-commands-no-broker), for example `tv_kiosk_living_room`.
- **TV power (optional):** the TV's media player, only for a TV that stays reachable in standby (see below). The blueprint switches it on, then waits for TV Kiosk.
- **Switch the TV off after the room is clear (optional):** dark is not off: an LCD's backlight stays on while resting. This switches the TV off after a while, but only if TV Kiosk is in front.

**TVs: wake from rest, not from standby.** On a TV, wake on motion works from TV Kiosk's own rest (black, dimmed, clock or photo page): the TV is still on and connected, so it wakes in well under a second. A TV in standby is a different matter: Android TV cuts apps off the network, and on the TCL Google TV we tested, Home Assistant's own Android TV Remote integration went *unavailable* too, so nothing could switch it back on. So on a TV:

- let TV Kiosk do the resting, and keep the TV out of standby: turn off the TV's own sleep timer (TCL: Settings → System → Power & energy → *Auto sleep*, which defaults to 2 hours);
- leave the screensaver as it is if you like: motion ends it and brings the dashboard forward;
- for a TV you do want off at night, switch it off and on with your own automation at fixed times, or use the blueprint's *Switch the TV off* option and accept that motion won't bring it back.

Some TVs stay reachable in standby (Google TV *Energy modes* set to "Optimized" or "Increased", TCL *Screenless service*, or HDMI-CEC from another device). Then the blueprint's *TV power* option can switch it on first; expect several seconds while it starts. We haven't confirmed this on any TV yet: [device reports](https://github.com/mattgrechtech/ha-tv-kiosk/issues/new?template=device_report.yml) welcome.

**Tablets and wall displays: full wake.** With the screen fully off (not just resting), motion turns it on: tablets stay on the network with the screen off, especially while charging. Not yet tested on a tablet.

Without the blueprint, any automation can send `{command: motion}` (or `{command: motion, args: {present: true}}` / `{present: false}`) through the `rest_command`, or press the TV's **Motion** button / switch **Someone present** over MQTT. See [api.md](api.md#wake-on-motion).
