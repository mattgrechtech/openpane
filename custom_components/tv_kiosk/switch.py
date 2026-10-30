"""Someone present: mirror a presence sensor into it and the screen stays awake while it's on."""

from __future__ import annotations

from typing import Any

from homeassistant.components.switch import SwitchEntity
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import TvKioskConfigEntry
from .api import TvKioskError
from .entity import TvKioskEntity


async def async_setup_entry(hass: HomeAssistant, entry: TvKioskConfigEntry, add: AddEntitiesCallback) -> None:
    coordinator = entry.runtime_data
    add([TvKioskPresentSwitch(coordinator, "present_switch"), TvKioskDndSwitch(coordinator, "do_not_disturb")])


class TvKioskPresentSwitch(TvKioskEntity, SwitchEntity):
    @property
    def is_on(self) -> bool | None:
        return self.coordinator.data.get("present")

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._send(True)

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._send(False)

    async def _send(self, present: bool) -> None:
        try:
            await self.coordinator.command("motion", {"present": present})
        except TvKioskError as err:
            raise HomeAssistantError(str(err)) from err


class TvKioskDndSwitch(TvKioskEntity, SwitchEntity):
    """Do not disturb: pop-ups wait unless they have priority high."""

    @property
    def is_on(self) -> bool | None:
        return self.coordinator.data.get("do_not_disturb")

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._send(True)

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._send(False)

    async def _send(self, enabled: bool) -> None:
        try:
            await self.coordinator.command("do_not_disturb", {"enabled": enabled})
        except TvKioskError as err:
            raise HomeAssistantError(str(err)) from err
