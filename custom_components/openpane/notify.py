"""A notify entity: notify.send_message shows a pop-up on the TV. openpane.notify has every field."""

from __future__ import annotations

from homeassistant.components.notify import NotifyEntity
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import OpenPaneConfigEntry
from .api import OpenPaneError
from .entity import OpenPaneEntity


async def async_setup_entry(hass: HomeAssistant, entry: OpenPaneConfigEntry, add: AddEntitiesCallback) -> None:
    add([OpenPaneNotify(entry.runtime_data, "popup")])


class OpenPaneNotify(OpenPaneEntity, NotifyEntity):
    async def async_send_message(self, message: str, title: str | None = None) -> None:
        args = {"message": message}
        if title:
            args["title"] = title
        try:
            await self.coordinator.command("notify", args)
        except OpenPaneError as err:
            raise HomeAssistantError(str(err)) from err
