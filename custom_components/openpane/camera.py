"""What the TV's dashboard shows right now, as a camera: fetched only when someone looks."""

from __future__ import annotations

from homeassistant.components.camera import Camera
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import OpenPaneConfigEntry
from .api import OpenPaneError
from .coordinator import OpenPaneCoordinator
from .entity import OpenPaneEntity


async def async_setup_entry(hass: HomeAssistant, entry: OpenPaneConfigEntry, add: AddEntitiesCallback) -> None:
    add([OpenPaneScreenCamera(entry.runtime_data, "screen_picture")])


class OpenPaneScreenCamera(OpenPaneEntity, Camera):
    """A picture of the dashboard window (never other apps); empty while another app is in front."""

    _attr_entity_registry_enabled_default = False  # Opt in: it shows what the household sees.

    def __init__(self, coordinator: OpenPaneCoordinator, key: str) -> None:
        OpenPaneEntity.__init__(self, coordinator, key)
        Camera.__init__(self)

    async def async_camera_image(self, width: int | None = None, height: int | None = None) -> bytes | None:
        try:
            return await self.coordinator.api.screenshot()
        except OpenPaneError:
            return None
