"""TV Kiosk: a Home Assistant dashboard, pop-ups and wake on motion on Android TVs and tablets."""

from __future__ import annotations

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry, ConfigEntryState
from homeassistant.const import CONF_HOST, CONF_PORT, CONF_TOKEN, Platform
from homeassistant.core import HomeAssistant, ServiceCall, ServiceResponse, SupportsResponse
from homeassistant.exceptions import HomeAssistantError, ServiceValidationError
from homeassistant.helpers import config_validation as cv, device_registry as dr
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.typing import ConfigType

from .api import TvKioskApi, TvKioskError
from .const import DOMAIN
from .coordinator import TvKioskCoordinator

PLATFORMS = [
    Platform.BINARY_SENSOR,
    Platform.BUTTON,
    Platform.NOTIFY,
    Platform.NUMBER,
    Platform.SELECT,
    Platform.SENSOR,
    Platform.SWITCH,
    Platform.UPDATE,
]

CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)

type TvKioskConfigEntry = ConfigEntry[TvKioskCoordinator]

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
        vol.Optional("actions"): vol.All(cv.ensure_list, [_BUTTON]),
    }),
    "clear_popup": ("notify/clear", {vol.Optional("id"): cv.string}),
    "navigate": ("navigate", {vol.Required("path"): cv.string, vol.Optional("return_after"): vol.Coerce(int)}),
    "open_url": ("url", {vol.Required("url"): cv.url, vol.Optional("return_after"): vol.Coerce(int)}),
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
    # The same settings on every TV given: a fleet in one action. Only these keys change.
    "apply_settings": ("settings", {vol.Required("settings"): dict}),
}


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Registers the tv_kiosk.* actions once; each one targets one or more TVs by device."""

    async def handle(call: ServiceCall) -> ServiceResponse:
        route, _ = SERVICES[call.service]
        args = call.data["settings"] if call.service == "apply_settings" else {
            key: value for key, value in call.data.items() if key != "device_id"
        }
        results = {}
        for coordinator in _coordinators(hass, call.data["device_id"]):
            try:
                results[coordinator.config_entry.title] = await coordinator.command(route, args)
            except TvKioskError as err:
                raise HomeAssistantError(f"{coordinator.config_entry.title}: {err}") from err
        return results if call.return_response else None

    for name, (_, fields) in SERVICES.items():
        hass.services.async_register(
            DOMAIN, name, handle,
            schema=vol.Schema({**_TARGET, **fields}),
            supports_response=SupportsResponse.OPTIONAL,
        )
    return True


def _coordinators(hass: HomeAssistant, device_ids: list[str]) -> list[TvKioskCoordinator]:
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
            raise ServiceValidationError(f"{device_id} isn't a TV Kiosk TV that's set up and loaded")
        found.append(entry.runtime_data)
    return found


async def async_setup_entry(hass: HomeAssistant, entry: TvKioskConfigEntry) -> bool:
    api = TvKioskApi(async_get_clientsession(hass), entry.data[CONF_HOST], entry.data[CONF_PORT], entry.data[CONF_TOKEN])
    coordinator = TvKioskCoordinator(hass, entry, api)
    await coordinator.async_config_entry_first_refresh()
    entry.runtime_data = coordinator
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: TvKioskConfigEntry) -> bool:
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
