# Home Assistant set-up

Two options. Start with REST if you don't run MQTT already.

> **Using ha-tv-kit?** Its pop-ups, bin night, garage and doorbell flows already exist: [`tv_kit_config.yaml`](https://github.com/mattgrechtech/ha-tv-kit/blob/main/homeassistant/packages/tv_kit_config.yaml) holds the TV list and the `rest_command`, and [`script.tv_popup`](https://github.com/mattgrechtech/ha-tv-kit/blob/main/homeassistant/packages/tv_kit_scripts.yaml) sends to every TV that's on, with the [doorbell blueprint](https://github.com/mattgrechtech/ha-tv-kit/blob/main/homeassistant/blueprints/automation/ha-tv-kit/camera_motion_tv_popup.yaml) on top. They work with this app unchanged through the TvOverlay-compatible mode; see [Moving the kit's pop-ups over](#moving-the-kits-pop-ups-over).

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
        actions:
          - label: Open camera
            navigate: /lovelace/cameras
mode: single
```

- `192.168.1.50:8123` is a placeholder: use your Home Assistant address, as the TV reaches it.
- `id: doorbell` makes a second press replace the pop-up instead of stacking another.
- The button is optional; change `/lovelace/cameras` to a dashboard page of yours, or remove `actions`. Back closes the pop-up.
- The picture link carries a short-lived access token from Home Assistant, so the TV loads it without signing in. Don't copy camera pictures into `/config/www`: anyone on your network can read that folder without a login.

**Using ha-tv-kit?** Its [doorbell blueprint](https://github.com/mattgrechtech/ha-tv-kit/blob/main/homeassistant/blueprints/automation/ha-tv-kit/camera_motion_tv_popup.yaml) does this for every TV that's on, with a cooldown and an optional phone alert. It's what the Ring test used.

## Wake on doorbell (outline)

An app can't wake a TV from standby by itself: in deep standby the TV's network is off. The pattern:

1. HA turns the TV on: the **Android TV Remote** integration (`media_player.turn_on`), or HDMI-CEC from another device. Check your TV's "networked standby" / "Wake on network" setting.
2. Wait until the TV answers (the kit's TV `media_player` is `on`, or the app's state sensor becomes available).
3. `rest_command.tv_kiosk_...` with `{command: wake}` then `{command: navigate, args: {path: /dashboard-cameras/doorbell}}`, or a pop-up with the snapshot.

A blueprint for this is planned for the next phase.
