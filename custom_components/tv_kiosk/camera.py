"""What the TV's dashboard shows right now, as a camera: fetched only when someone looks."""

from __future__ import annotations

from homeassistant.components.camera import Camera
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import TvKioskConfigEntry
from .api import TvKioskError
from .coordinator import TvKioskCoordinator
from .entity import TvKioskEntity


async def async_setup_entry(hass: HomeAssistant, entry: TvKioskConfigEntry, add: AddEntitiesCallback) -> None:
    add([TvKioskScreenCamera(entry.runtime_data, "screen_picture")])


class TvKioskScreenCamera(TvKioskEntity, Camera):
    """A picture of the dashboard window (never other apps); empty while another app is in front."""

    _attr_entity_registry_enabled_default = False  # Opt in: it shows what the household sees.

    def __init__(self, coordinator: TvKioskCoordinator, key: str) -> None:
        TvKioskEntity.__init__(self, coordinator, key)
        Camera.__init__(self)

    async def async_camera_image(self, width: int | None = None, height: int | None = None) -> bytes | None:
        try:
            return await self.coordinator.api.screenshot()
        except TvKioskError:
            return None
