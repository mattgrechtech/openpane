"""Set-up, entities and actions of the OpenPane integration, against a mocked TV."""

from ipaddress import ip_address

import aiohttp
import pytest
from homeassistant import config_entries
from homeassistant.const import CONF_HOST, CONF_PORT, CONF_TOKEN
from homeassistant.core import HomeAssistant
from homeassistant.data_entry_flow import FlowResultType
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo
from pytest_homeassistant_custom_component.common import MockConfigEntry
from pytest_homeassistant_custom_component.test_util.aiohttp import AiohttpClientMocker

from custom_components.openpane.const import DOMAIN

HOST = "192.168.1.60"
BASE = f"http://{HOST}:2424/api/v1"
STATE = {
    "ok": True, "url": "http://192.168.1.50:8123/house", "title": "House", "screen": "on", "resting": False,
    "night": False, "present": False, "brightness": -1, "dashboard_visible": True, "overlay_permission": True,
    "ha_connection": "connected", "last_error": "", "last_motion_at": None, "last_wake_ms": 207,
    "app_memory_mb": 180, "memory_available_mb": 640, "app_started_at": 1793000000, "app_version": "0.1.0",
    "webview_version": "130.0", "model": "Smart TV Pro", "manufacturer": "TCL",
    "device_id": "a1b2c3d4", "device_name": "Living room TV",
}


async def test_user_flow_creates_the_tv(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    aioclient_mock.get(f"{BASE}/state", json=STATE)
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_USER})
    assert result["type"] is FlowResultType.FORM
    result = await hass.config_entries.flow.async_configure(
        result["flow_id"], {CONF_HOST: HOST, CONF_PORT: 2424, CONF_TOKEN: "secret"}
    )
    assert result["type"] is FlowResultType.CREATE_ENTRY
    assert result["title"] == "Living room TV"
    assert result["result"].unique_id == "a1b2c3d4"
    assert aioclient_mock.mock_calls[0][3]["Authorization"] == "Bearer secret"


async def test_user_flow_wrong_token(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    aioclient_mock.get(f"{BASE}/state", status=401, json={"ok": False, "error": "missing or wrong token"})
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_USER})
    result = await hass.config_entries.flow.async_configure(
        result["flow_id"], {CONF_HOST: HOST, CONF_PORT: 2424, CONF_TOKEN: "wrong"}
    )
    assert result["type"] is FlowResultType.FORM
    assert result["errors"] == {"base": "invalid_auth"}


async def test_discovered_tv_asks_only_for_the_token(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    aioclient_mock.get(f"{BASE}/state", json=STATE)
    info = ZeroconfServiceInfo(
        ip_address=ip_address(HOST), ip_addresses=[ip_address(HOST)], hostname="tv.local.", name="OpenPane Living room TV",
        port=2424, type="_openpane._tcp.local.", properties={"id": "a1b2c3d4", "name": "Living room TV", "version": "0.1.0"},
    )
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_ZEROCONF}, data=info)
    assert result["type"] is FlowResultType.FORM
    assert result["step_id"] == "zeroconf_confirm"
    result = await hass.config_entries.flow.async_configure(result["flow_id"], {CONF_TOKEN: "secret"})
    assert result["type"] is FlowResultType.CREATE_ENTRY
    assert result["data"] == {CONF_HOST: HOST, CONF_PORT: 2424, CONF_TOKEN: "secret"}


async def test_typed_in_tv_wins_over_a_waiting_discovery(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    aioclient_mock.get(f"{BASE}/state", json=STATE)
    info = ZeroconfServiceInfo(
        ip_address=ip_address(HOST), ip_addresses=[ip_address(HOST)], hostname="tv.local.", name="x",
        port=2424, type="_openpane._tcp.local.", properties={"id": "a1b2c3d4"},
    )
    await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_ZEROCONF}, data=info)
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_USER})
    result = await hass.config_entries.flow.async_configure(
        result["flow_id"], {CONF_HOST: HOST, CONF_PORT: 2424, CONF_TOKEN: "secret"}
    )
    assert result["type"] is FlowResultType.CREATE_ENTRY
    await hass.async_block_till_done()
    # The discovered one closes: the TV is set up.
    assert not hass.config_entries.flow.async_progress_by_handler(DOMAIN)


async def test_rediscovery_never_moves_the_tv(hass: HomeAssistant) -> None:
    entry = MockConfigEntry(domain=DOMAIN, unique_id="a1b2c3d4", data={CONF_HOST: "192.168.1.61", CONF_PORT: 2424, CONF_TOKEN: "t"})
    entry.add_to_hass(hass)
    info = ZeroconfServiceInfo(
        ip_address=ip_address(HOST), ip_addresses=[ip_address(HOST)], hostname="tv.local.", name="x",
        port=2424, type="_openpane._tcp.local.", properties={"id": "a1b2c3d4"},
    )
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_ZEROCONF}, data=info)
    assert result["type"] is FlowResultType.ABORT
    # An announcement alone must not send the token somewhere new.
    assert entry.data[CONF_HOST] == "192.168.1.61"


async def _set_up(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> MockConfigEntry:
    aioclient_mock.get(f"{BASE}/state", json=STATE)
    entry = MockConfigEntry(domain=DOMAIN, unique_id="a1b2c3d4", title="Living room TV",
                            data={CONF_HOST: HOST, CONF_PORT: 2424, CONF_TOKEN: "secret"})
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    return entry


async def test_entities_show_the_state(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    await _set_up(hass, aioclient_mock)
    assert hass.states.get("sensor.living_room_tv_page").state == "House"
    assert hass.states.get("binary_sensor.living_room_tv_dashboard_in_front").state == "on"
    assert hass.states.get("select.living_room_tv_screen").state == "on"
    assert hass.states.get("sensor.living_room_tv_screen_state").state == "on"


async def test_notify_action_reaches_the_tv(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    await _set_up(hass, aioclient_mock)
    aioclient_mock.post(f"{BASE}/notify", json={"ok": True, "id": "x"})
    device = dr.async_get(hass).async_get_device(identifiers={(DOMAIN, "a1b2c3d4")})
    await hass.services.async_call(
        DOMAIN, "notify",
        {"device_id": device.id, "title": "Front door", "sound": "doorbell", "actions": [{"label": "Unlock", "event": "unlock_front"}]},
        blocking=True,
    )
    sent = [call for call in aioclient_mock.mock_calls if str(call[1]).endswith("/notify")]
    assert sent[0][2] == {"title": "Front door", "sound": "doorbell", "actions": [{"label": "Unlock", "event": "unlock_front"}]}


async def test_one_tv_off_doesnt_stop_the_others(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    await _set_up(hass, aioclient_mock)
    other_base = "http://192.168.1.61:2424/api/v1"
    aioclient_mock.get(f"{other_base}/state", json={**STATE, "device_id": "e5f6", "device_name": "Kitchen tablet"})
    other = MockConfigEntry(domain=DOMAIN, unique_id="e5f6", title="Kitchen tablet",
                            data={CONF_HOST: "192.168.1.61", CONF_PORT: 2424, CONF_TOKEN: "secret"})
    other.add_to_hass(hass)
    assert await hass.config_entries.async_setup(other.entry_id)
    await hass.async_block_till_done()
    aioclient_mock.post(f"{BASE}/settings", exc=aiohttp.ClientError("off"))
    aioclient_mock.post(f"{other_base}/settings", json={"ok": True, "applied": ["rest_after_minutes"]})
    registry = dr.async_get(hass)
    first = registry.async_get_device(identifiers={(DOMAIN, "a1b2c3d4")})
    second = registry.async_get_device(identifiers={(DOMAIN, "e5f6")})
    with pytest.raises(HomeAssistantError, match="Living room TV"):
        await hass.services.async_call(
            DOMAIN, "apply_settings", {"device_id": [first.id, second.id], "settings": {"rest_after_minutes": 5}}, blocking=True,
        )
    assert any(str(call[1]).startswith(other_base) and str(call[1]).endswith("/settings") for call in aioclient_mock.mock_calls)


async def test_non_json_answer_is_a_clear_error(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    aioclient_mock.get(f"{BASE}/state", text="<html>not here</html>", status=200)
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_USER})
    result = await hass.config_entries.flow.async_configure(
        result["flow_id"], {CONF_HOST: HOST, CONF_PORT: 2424, CONF_TOKEN: "secret"}
    )
    assert result["errors"] == {"base": "cannot_connect"}


async def test_discovery_uses_the_ipv4_address(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    aioclient_mock.get(f"{BASE}/state", json=STATE)
    info = ZeroconfServiceInfo(
        ip_address=ip_address("fe80::1"), ip_addresses=[ip_address("fe80::1"), ip_address(HOST)], hostname="tv.local.",
        name="x", port=2424, type="_openpane._tcp.local.", properties={"id": "a1b2c3d4"},
    )
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_ZEROCONF}, data=info)
    result = await hass.config_entries.flow.async_configure(result["flow_id"], {CONF_TOKEN: "secret"})
    assert result["data"][CONF_HOST] == HOST


async def test_health_repairs(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    from homeassistant.helpers import issue_registry as ir

    aioclient_mock.get(
        f"{BASE}/state",
        json={**STATE, "webview_version": "91.0.4472", "wake": {"overlay_permission": False, "auto_start": "allowed", "service_protected": True}},
    )
    entry = MockConfigEntry(domain=DOMAIN, unique_id="a1b2c3d4", title="Living room TV",
                            data={CONF_HOST: HOST, CONF_PORT: 2424, CONF_TOKEN: "secret"})
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    issues = ir.async_get(hass)
    assert issues.async_get_issue(DOMAIN, f"{entry.entry_id}_old_webview") is not None
    assert issues.async_get_issue(DOMAIN, f"{entry.entry_id}_no_overlay") is not None
    assert issues.async_get_issue(DOMAIN, f"{entry.entry_id}_background_blocked") is None


async def test_healthy_tv_has_no_repairs(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    from homeassistant.helpers import issue_registry as ir

    entry = await _set_up(hass, aioclient_mock)
    assert not [i for i in ir.async_get(hass).issues.values() if i.domain == DOMAIN and i.issue_id.startswith(entry.entry_id)]


async def test_battery_only_on_tablets(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    await _set_up(hass, aioclient_mock)
    # The TV in STATE has no battery: no battery entities.
    assert hass.states.get("sensor.living_room_tv_battery") is None
    assert hass.states.get("binary_sensor.living_room_tv_charging") is None


async def test_tablet_battery(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    aioclient_mock.get(f"{BASE}/state", json={**STATE, "battery": {"level": 64, "charging": True, "plugged": True, "temperature": 31.5}})
    entry = MockConfigEntry(domain=DOMAIN, unique_id="a1b2c3d4", title="Living room TV",
                            data={CONF_HOST: HOST, CONF_PORT: 2424, CONF_TOKEN: "secret"})
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    assert hass.states.get("sensor.living_room_tv_battery").state == "64"
    assert hass.states.get("binary_sensor.living_room_tv_charging").state == "on"


async def _set_up_with(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker, **state) -> MockConfigEntry:
    aioclient_mock.get(f"{BASE}/state", json={**STATE, **state})
    entry = MockConfigEntry(domain=DOMAIN, unique_id="a1b2c3d4", title="Living room TV",
                            data={CONF_HOST: HOST, CONF_PORT: 2424, CONF_TOKEN: "secret"})
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    return entry


async def test_speaker_on_tablets_only(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    await _set_up_with(hass, aioclient_mock, tv=True)
    assert hass.states.get("media_player.living_room_tv_speaker") is None


async def test_no_speaker_on_older_apps(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    await _set_up(hass, aioclient_mock)
    assert hass.states.get("media_player.living_room_tv_speaker") is None


async def test_tablet_speaker_plays_and_sets_volume(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    await _set_up_with(hass, aioclient_mock, tv=False, volume=40, sound_playing=False)
    speaker = "media_player.living_room_tv_speaker"
    state = hass.states.get(speaker)
    assert state.state == "idle"
    assert state.attributes["volume_level"] == 0.4
    aioclient_mock.post(f"{BASE}/sound", json={"ok": True})
    aioclient_mock.post(f"{BASE}/volume", json={"ok": True})
    aioclient_mock.post(f"{BASE}/sound/stop", json={"ok": True})
    await hass.services.async_call("media_player", "play_media", {
        "entity_id": speaker, "media_content_type": "music",
        "media_content_id": "http://192.168.1.50:8123/api/tts_proxy/abc.mp3", "announce": True,
    }, blocking=True)
    await hass.services.async_call("media_player", "volume_set", {"entity_id": speaker, "volume_level": 0.7}, blocking=True)
    await hass.services.async_call("media_player", "media_stop", {"entity_id": speaker}, blocking=True)
    sent = {str(call[1]).rsplit("/api/v1", 1)[1]: call[2] for call in aioclient_mock.mock_calls if call[0] == "POST"}
    assert sent["/sound"] == {"url": "http://192.168.1.50:8123/api/tts_proxy/abc.mp3"}
    assert sent["/volume"] == {"level": 70}
    assert "/sound/stop" in sent


async def test_update_install_asks_the_tv(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    aioclient_mock.get(f"{BASE}/state", json={**STATE, "update": {"latest": "0.2.0", "url": "x", "available": True, "install": "idle"}})
    entry = MockConfigEntry(domain=DOMAIN, unique_id="a1b2c3d4", title="Living room TV",
                            data={CONF_HOST: HOST, CONF_PORT: 2424, CONF_TOKEN: "secret"})
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    aioclient_mock.post(f"{BASE}/install_update", json={"ok": True, "install": "waiting for a quiet moment"})
    await hass.services.async_call("update", "install", {"entity_id": "update.living_room_tv_openpane_app"}, blocking=True)
    assert any(str(call[1]).endswith("/install_update") for call in aioclient_mock.mock_calls)


async def test_remote_and_note_actions(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    await _set_up(hass, aioclient_mock)
    aioclient_mock.post(f"{BASE}/remote", json={"ok": True})
    aioclient_mock.post(f"{BASE}/planner/note", json={"ok": True})
    device = dr.async_get(hass).async_get_device(identifiers={(DOMAIN, "a1b2c3d4")})
    await hass.services.async_call(DOMAIN, "remote", {"device_id": device.id, "key": "ok"}, blocking=True)
    await hass.services.async_call(DOMAIN, "post_note", {"device_id": device.id, "text": "Back at 5"}, blocking=True)
    sent = {str(call[1]).rsplit("/api/v1", 1)[1]: call[2] for call in aioclient_mock.mock_calls if call[0] == "POST"}
    assert sent["/remote"] == {"key": "ok"}
    assert sent["/planner/note"] == {"text": "Back at 5"}


async def test_signage_pages_and_next(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    await _set_up(hass, aioclient_mock)
    aioclient_mock.post(f"{BASE}/url", json={"ok": True})
    aioclient_mock.post(f"{BASE}/next", json={"ok": True})
    device = dr.async_get(hass).async_get_device(identifiers={(DOMAIN, "a1b2c3d4")})
    await hass.services.async_call(DOMAIN, "open_url", {"device_id": device.id, "url": "kiosk:signage"}, blocking=True)
    await hass.services.async_call(DOMAIN, "next_item", {"device_id": device.id}, blocking=True)
    sent = {str(call[1]).rsplit("/api/v1", 1)[1]: call[2] for call in aioclient_mock.mock_calls if call[0] == "POST"}
    assert sent["/url"] == {"url": "kiosk:signage"}
    assert "/next" in sent


async def test_wake_button(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    await _set_up(hass, aioclient_mock)
    aioclient_mock.post(f"{BASE}/wake", json={"ok": True})
    await hass.services.async_call("button", "press", {"entity_id": "button.living_room_tv_wake"}, blocking=True)
    assert any(str(call[1]).endswith("/wake") for call in aioclient_mock.mock_calls)
