"""The screen: on, dim or black."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import OpenPaneConfigEntry
from .api import OpenPaneError
from .entity import OpenPaneEntity


async def async_setup_entry(hass: HomeAssistant, entry: OpenPaneConfigEntry, add: AddEntitiesCallback) -> None:
    add([OpenPaneScreenSelect(entry.runtime_data, "screen_mode")])


class OpenPaneScreenSelect(OpenPaneEntity, SelectEntity):
    _attr_options = ["on", "dim", "blank"]

    @property
    def current_option(self) -> str | None:
        return self.coordinator.data.get("screen")

    async def async_select_option(self, option: str) -> None:
        try:
            await self.coordinator.command("screen", {"mode": option})
        except OpenPaneError as err:
            raise HomeAssistantError(str(err)) from err
