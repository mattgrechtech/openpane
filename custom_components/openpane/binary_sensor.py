"""What the TV is showing: dashboard in front, resting, someone present."""

from __future__ import annotations

from homeassistant.components.binary_sensor import BinarySensorDeviceClass, BinarySensorEntity
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import OpenPaneConfigEntry
from .entity import OpenPaneEntity

# key -> (state field, device class, diagnostic)
SENSORS = {
    "dashboard_visible": ("dashboard_visible", None, False),
    "resting": ("resting", None, False),
    "present": ("present", BinarySensorDeviceClass.OCCUPANCY, False),
    "overlay_permission": ("overlay_permission", None, True),
    "charging": ("battery.charging", BinarySensorDeviceClass.BATTERY_CHARGING, False),
}
# Only where the device has a battery.
NEEDS_BATTERY = {"charging"}


async def async_setup_entry(hass: HomeAssistant, entry: OpenPaneConfigEntry, add: AddEntitiesCallback) -> None:
    has_battery = bool((entry.runtime_data.data or {}).get("battery"))
    add(OpenPaneBinarySensor(entry.runtime_data, key) for key in SENSORS if has_battery or key not in NEEDS_BATTERY)


class OpenPaneBinarySensor(OpenPaneEntity, BinarySensorEntity):
    def __init__(self, coordinator, key: str) -> None:
        super().__init__(coordinator, key)
        self._field, self._attr_device_class, diagnostic = SENSORS[key]
        if diagnostic:
            self._attr_entity_category = EntityCategory.DIAGNOSTIC

    @property
    def is_on(self) -> bool | None:
        value = self.coordinator.data
        for part in self._field.split("."):
            value = (value or {}).get(part)
        return None if value is None else bool(value)
