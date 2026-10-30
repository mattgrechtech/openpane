"""Whether a newer OpenPane is out: needs "Check for updates" on the TV. Installing stays manual."""

from __future__ import annotations

from homeassistant.components.update import UpdateEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import OpenPaneConfigEntry
from .entity import OpenPaneEntity


async def async_setup_entry(hass: HomeAssistant, entry: OpenPaneConfigEntry, add: AddEntitiesCallback) -> None:
    add([OpenPaneUpdate(entry.runtime_data, "app_update")])


class OpenPaneUpdate(OpenPaneEntity, UpdateEntity):
    @property
    def installed_version(self) -> str | None:
        return self.coordinator.data.get("app_version")

    @property
    def latest_version(self) -> str | None:
        update = self.coordinator.data.get("update") or {}
        # Without the TV's update check there's nothing to compare with: report it as current.
        return update.get("latest") or self.installed_version

    @property
    def release_url(self) -> str | None:
        return (self.coordinator.data.get("update") or {}).get("url")
