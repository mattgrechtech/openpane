"""Brightness, where the device allows it (-1 is the device's own setting)."""

from __future__ import annotations

from homeassistant.components.number import NumberEntity, NumberMode
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import OpenPaneConfigEntry
from .api import OpenPaneError
from .entity import OpenPaneEntity


async def async_setup_entry(hass: HomeAssistant, entry: OpenPaneConfigEntry, add: AddEntitiesCallback) -> None:
    add([OpenPaneBrightness(entry.runtime_data, "brightness")])


class OpenPaneBrightness(OpenPaneEntity, NumberEntity):
    _attr_native_min_value = -1
    _attr_native_max_value = 100
    _attr_native_step = 1
    _attr_mode = NumberMode.SLIDER

    @property
    def native_value(self) -> float | None:
        return self.coordinator.data.get("brightness")

    async def async_set_native_value(self, value: float) -> None:
        try:
            await self.coordinator.command("brightness", {"level": int(value)})
        except OpenPaneError as err:
            raise HomeAssistantError(str(err)) from err
