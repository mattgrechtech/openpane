"""OpenPane: a Home Assistant dashboard, pop-ups and wake on motion on Android TVs and tablets."""

from __future__ import annotations

import json

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry, ConfigEntryState
from homeassistant.const import CONF_HOST, CONF_PORT, CONF_TOKEN, Platform
from homeassistant.core import HomeAssistant, ServiceCall, ServiceResponse, SupportsResponse
from homeassistant.exceptions import HomeAssistantError, ServiceValidationError
from homeassistant.helpers import config_validation as cv, device_registry as dr
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.typing import ConfigType

from . import health
from .api import OpenPaneApi, OpenPaneError
from .const import DOMAIN
from .coordinator import OpenPaneCoordinator

PLATFORMS = [
    Platform.BINARY_SENSOR,
    Platform.BUTTON,
    Platform.CAMERA,
    Platform.MEDIA_PLAYER,
    Platform.NOTIFY,
    Platform.NUMBER,
    Platform.SELECT,
    Platform.SENSOR,
    Platform.SWITCH,
    Platform.UPDATE,
]

CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)

type OpenPaneConfigEntry = ConfigEntry[OpenPaneCoordinator]

_TARGET = {vol.Required("device_id"): vol.All(cv.ensure_list, [cv.string])}
_BUTTON = vol.Schema({
    vol.Required("label"): cv.string,
    vol.Optional("navigate"): cv.string,
    vol.Optional("url"): cv.url,
    vol.Optional("event"): cv.string,
    vol.Optional("toggle"): cv.entity_id,
})

# Action name -> (the TV's REST route, its fields besides the target TVs).
SERVICES: dict[str, tuple[str, dict]] = {
    "notify": ("notify", {
        vol.Optional("title"): cv.string,
        vol.Optional("message"): cv.string,
        vol.Optional("image"): cv.string,
        vol.Optional("camera"): cv.entity_domain("camera"),
        vol.Optional("stream"): cv.url,
        vol.Optional("icon"): cv.string,
        vol.Optional("color"): cv.string,
        vol.Optional("corner"): vol.In(["top_start", "top_end", "bottom_start", "bottom_end"]),
        vol.Optional("duration"): vol.All(vol.Coerce(int), vol.Range(min=0, max=600)),
        vol.Optional("size"): vol.In(["small", "medium", "large"]),
        vol.Optional("sound"): cv.string,
        vol.Optional("tts"): cv.string,
        vol.Optional("id"): cv.string,
        vol.Optional("priority"): vol.In(["normal", "high"]),
        vol.Optional("qr"): cv.string,
        vol.Optional("countdown"): vol.All(vol.Coerce(int), vol.Range(min=1, max=86400)),
        vol.Optional("progress"): vol.All(vol.Coerce(float), vol.Range(min=0, max=100)),
        vol.Optional("pause_media"): cv.boolean,
        vol.Optional("actions"): vol.All(cv.ensure_list, [_BUTTON]),
    }),
    "clear_popup": ("notify/clear", {vol.Optional("id"): cv.string}),
    "navigate": ("navigate", {vol.Required("path"): cv.string, vol.Optional("return_after"): vol.Coerce(int)}),
    "open_url": ("url", {
        vol.Required("url"): vol.Any("kiosk:planner", "kiosk:signage", "kiosk:photos", cv.url),
        vol.Optional("return_after"): vol.Coerce(int),
    }),
    "next_item": ("next", {}),
    "motion": ("motion", {vol.Optional("present"): cv.boolean}),
    "wake": ("wake", {vol.Optional("minutes"): vol.All(vol.Coerce(int), vol.Range(min=1, max=240))}),
    "speak": ("tts", {vol.Required("message"): cv.string, vol.Optional("language"): cv.string}),
    "play_sound": ("sound", {
        vol.Required("url"): cv.url,
        vol.Optional("volume"): vol.All(vol.Coerce(int), vol.Range(min=0, max=100)),
    }),
    "launch_app": ("launch", {vol.Required("package"): cv.string}),
    "now_playing": ("now_playing", {
        vol.Required("entity"): cv.entity_domain("media_player"),
        vol.Optional("corner"): vol.In(["top_start", "top_end", "bottom_start", "bottom_end"]),
        vol.Optional("size"): vol.In(["small", "medium", "large"]),
    }),
    "now_playing_stop": ("now_playing/stop", {}),
    "remote": ("remote", {
        vol.Exclusive("key", "what"): vol.In(["up", "down", "left", "right", "ok", "back", "menu", "play_pause", "enter", "backspace"]),
        vol.Exclusive("text", "what"): vol.All(cv.string, vol.Length(min=1, max=500)),
    }),
    "post_note": ("planner/note", {vol.Required("text"): vol.All(cv.string, vol.Length(min=1, max=200))}),
    # The same settings on every TV given: a fleet in one action. Only these keys change.
    "apply_settings": ("settings", {vol.Required("settings"): dict}),
}


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Registers the openpane.* actions once; each one targets one or more TVs by device."""

    async def handle(call: ServiceCall) -> ServiceResponse:
        route, _ = SERVICES[call.service]
        args = call.data["settings"] if call.service == "apply_settings" else {
            key: value for key, value in call.data.items() if key != "device_id"
        }
        results = {}
        failed = []
        # Every TV gets its turn: one that's off doesn't stop the others.
        for coordinator in _coordinators(hass, call.data["device_id"]):
            title = coordinator.config_entry.title
            try:
                results[title] = await coordinator.command(route, args)
            except OpenPaneError as err:
                failed.append(f"{title}: {err}")
        if failed:
            done = f" (done on {', '.join(results)})" if results else ""
            raise HomeAssistantError("; ".join(failed) + done)
        return results if call.return_response else None

    for name, (_, fields) in SERVICES.items():
        hass.services.async_register(
            DOMAIN, name, handle,
            schema=vol.Schema({**_TARGET, **fields}),
            supports_response=SupportsResponse.OPTIONAL,
        )
    await _register_card(hass)
    return True


CARD_URL = "/openpane/openpane-card.js"


async def _register_card(hass: HomeAssistant) -> None:
    """Serves the OpenPane card and adds it to every dashboard: no resource to add by hand."""
    if hass.http is None:  # No web server (tests, safe mode): nothing to serve.
        return
    from pathlib import Path

    from homeassistant.components.frontend import add_extra_js_url
    from homeassistant.components.http import StaticPathConfig

    path = Path(__file__).parent / "frontend" / "openpane-card.js"
    await hass.http.async_register_static_paths([StaticPathConfig(CARD_URL, str(path), cache_headers=False)])
    version = json.loads((Path(__file__).parent / "manifest.json").read_text(encoding="utf-8")).get("version", "0")
    add_extra_js_url(hass, f"{CARD_URL}?v={version}")


def _coordinators(hass: HomeAssistant, device_ids: list[str]) -> list[OpenPaneCoordinator]:
    """The loaded TV for each device id; anything else is a mistake in the action call."""
    registry = dr.async_get(hass)
    found = []
    for device_id in device_ids:
        device = registry.async_get(device_id)
        entry = None
        for entry_id in device.config_entries if device else ():
            candidate = hass.config_entries.async_get_entry(entry_id)
            if candidate and candidate.domain == DOMAIN and candidate.state is ConfigEntryState.LOADED:
                entry = candidate
                break
        if entry is None:
            raise ServiceValidationError(f"{device_id} isn't an OpenPane TV that's set up and loaded")
        found.append(entry.runtime_data)
    return found


async def async_setup_entry(hass: HomeAssistant, entry: OpenPaneConfigEntry) -> bool:
    api = OpenPaneApi(async_get_clientsession(hass), entry.data[CONF_HOST], entry.data[CONF_PORT], entry.data[CONF_TOKEN])
    coordinator = OpenPaneCoordinator(hass, entry, api)
    await coordinator.async_config_entry_first_refresh()
    entry.runtime_data = coordinator
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: OpenPaneConfigEntry) -> bool:
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)


async def async_remove_entry(hass: HomeAssistant, entry: OpenPaneConfigEntry) -> None:
    """A removed TV leaves no Repairs behind."""
    health.clear_all(hass, entry.entry_id)
