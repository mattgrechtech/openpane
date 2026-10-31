"""Whether a newer OpenPane is out (needs "Check for updates" on the TV), and Install.

Install asks the TV to install it at its next quiet moment (dashboard in front, nothing playing);
the TV checks the download and Android asks for one confirmation on the screen.
"""

from __future__ import annotations

from typing import Any

from homeassistant.components.update import UpdateEntity, UpdateEntityFeature
from homeassistant.exceptions import HomeAssistantError
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import OpenPaneConfigEntry
from .api import OpenPaneError
from .entity import OpenPaneEntity


async def async_setup_entry(hass: HomeAssistant, entry: OpenPaneConfigEntry, add: AddEntitiesCallback) -> None:
    add([OpenPaneUpdate(entry.runtime_data, "app_update")])


class OpenPaneUpdate(OpenPaneEntity, UpdateEntity):
    _attr_supported_features = UpdateEntityFeature.INSTALL

    async def async_install(self, version: str | None, backup: bool, **kwargs: Any) -> None:
        try:
            await self.coordinator.command("install_update")
        except OpenPaneError as err:
            raise HomeAssistantError(str(err)) from err

    @property
    def in_progress(self) -> bool | None:
        status = (self.coordinator.data.get("update") or {}).get("install") or ""
        return status.startswith(("waiting", "checking", "downloading", "asking"))

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
