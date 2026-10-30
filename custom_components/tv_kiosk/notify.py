"""A notify entity: notify.send_message shows a pop-up on the TV. tv_kiosk.notify has every field."""

from __future__ import annotations

from homeassistant.components.notify import NotifyEntity
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import TvKioskConfigEntry
from .api import TvKioskError
from .entity import TvKioskEntity


async def async_setup_entry(hass: HomeAssistant, entry: TvKioskConfigEntry, add: AddEntitiesCallback) -> None:
    add([TvKioskNotify(entry.runtime_data, "popup")])


class TvKioskNotify(TvKioskEntity, NotifyEntity):
    async def async_send_message(self, message: str, title: str | None = None) -> None:
        args = {"message": message}
        if title:
            args["title"] = title
        try:
            await self.coordinator.command("notify", args)
        except TvKioskError as err:
            raise HomeAssistantError(str(err)) from err
