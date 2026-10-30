"""One-press commands: wake, motion, reload, start page."""

from __future__ import annotations

from homeassistant.components.button import ButtonEntity
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import OpenPaneConfigEntry
from .api import OpenPaneError
from .entity import OpenPaneEntity

# key -> (route, args, config category)
BUTTONS = {
    "wake": ("wake", {}, False),
    "motion": ("motion", {}, False),
    "start_page": ("start_page", {}, False),
    "reload": ("reload", {}, False),
    "hard_reload": ("reload", {"hard": True}, True),
    "stop_sound": ("sound/stop", {}, False),
}


async def async_setup_entry(hass: HomeAssistant, entry: OpenPaneConfigEntry, add: AddEntitiesCallback) -> None:
    add(OpenPaneButton(entry.runtime_data, key) for key in BUTTONS)


class OpenPaneButton(OpenPaneEntity, ButtonEntity):
    def __init__(self, coordinator, key: str) -> None:
        super().__init__(coordinator, key)
        self._route, self._args, config = BUTTONS[key]
        if config:
            self._attr_entity_category = EntityCategory.CONFIG

    async def async_press(self) -> None:
        try:
            await self.coordinator.command(self._route, self._args)
        except OpenPaneError as err:
            raise HomeAssistantError(str(err)) from err
