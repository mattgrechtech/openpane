"""The TV's page, last motion, wake time and health."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from homeassistant.components.sensor import SensorDeviceClass, SensorEntity, SensorEntityDescription, SensorStateClass
from homeassistant.const import EntityCategory, UnitOfInformation, UnitOfTime, PERCENTAGE, UnitOfTemperature
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import OpenPaneConfigEntry
from .entity import OpenPaneEntity


def _timestamp(seconds: Any) -> datetime | None:
    return datetime.fromtimestamp(seconds, tz=timezone.utc) if isinstance(seconds, (int, float)) else None


@dataclass(frozen=True, kw_only=True)
class OpenPaneSensorDescription(SensorEntityDescription):
    value: Callable[[dict[str, Any]], Any]
    attributes: Callable[[dict[str, Any]], dict[str, Any]] | None = None
    # Only for devices that have it (a battery: tablets, not TVs).
    exists: Callable[[dict[str, Any]], bool] = lambda d: True


SENSORS = (
    OpenPaneSensorDescription(
        key="battery", native_unit_of_measurement=PERCENTAGE, device_class=SensorDeviceClass.BATTERY,
        state_class=SensorStateClass.MEASUREMENT, value=lambda d: (d.get("battery") or {}).get("level"),
        exists=lambda d: bool(d.get("battery")),
    ),
    OpenPaneSensorDescription(
        key="battery_temperature", native_unit_of_measurement=UnitOfTemperature.CELSIUS, device_class=SensorDeviceClass.TEMPERATURE,
        state_class=SensorStateClass.MEASUREMENT, entity_category=EntityCategory.DIAGNOSTIC,
        value=lambda d: (d.get("battery") or {}).get("temperature"), exists=lambda d: bool(d.get("battery")),
    ),
    OpenPaneSensorDescription(
        key="page", value=lambda d: d.get("title") or d.get("url"),
        attributes=lambda d: {"url": d.get("url")},
    ),
    OpenPaneSensorDescription(
        key="screen", device_class=SensorDeviceClass.ENUM, options=["on", "dim", "blank", "resting", "night"],
        value=lambda d: "night" if d.get("night") else "resting" if d.get("resting") else d.get("screen"),
    ),
    OpenPaneSensorDescription(
        key="last_motion", device_class=SensorDeviceClass.TIMESTAMP, value=lambda d: _timestamp(d.get("last_motion_at")),
    ),
    OpenPaneSensorDescription(
        key="last_wake", native_unit_of_measurement=UnitOfTime.MILLISECONDS, device_class=SensorDeviceClass.DURATION,
        entity_category=EntityCategory.DIAGNOSTIC, value=lambda d: d.get("last_wake_ms"),
    ),
    OpenPaneSensorDescription(
        key="ha_connection", entity_category=EntityCategory.DIAGNOSTIC, value=lambda d: d.get("ha_connection"),
    ),
    OpenPaneSensorDescription(
        key="last_error", entity_category=EntityCategory.DIAGNOSTIC, value=lambda d: (d.get("last_error") or "")[:255] or None,
    ),
    OpenPaneSensorDescription(
        key="app_memory", native_unit_of_measurement=UnitOfInformation.MEGABYTES, device_class=SensorDeviceClass.DATA_SIZE,
        state_class=SensorStateClass.MEASUREMENT, entity_category=EntityCategory.DIAGNOSTIC, value=lambda d: d.get("app_memory_mb"),
    ),
    OpenPaneSensorDescription(
        key="memory_available", native_unit_of_measurement=UnitOfInformation.MEGABYTES, device_class=SensorDeviceClass.DATA_SIZE,
        state_class=SensorStateClass.MEASUREMENT, entity_category=EntityCategory.DIAGNOSTIC, value=lambda d: d.get("memory_available_mb"),
    ),
    OpenPaneSensorDescription(
        key="app_started", device_class=SensorDeviceClass.TIMESTAMP, entity_category=EntityCategory.DIAGNOSTIC,
        value=lambda d: _timestamp(d.get("app_started_at")),
    ),
    OpenPaneSensorDescription(
        key="webview_version", entity_category=EntityCategory.DIAGNOSTIC, value=lambda d: d.get("webview_version"),
    ),
)


async def async_setup_entry(hass: HomeAssistant, entry: OpenPaneConfigEntry, add: AddEntitiesCallback) -> None:
    data = entry.runtime_data.data or {}
    add(OpenPaneSensor(entry.runtime_data, description) for description in SENSORS if description.exists(data))


class OpenPaneSensor(OpenPaneEntity, SensorEntity):
    entity_description: OpenPaneSensorDescription

    def __init__(self, coordinator, description: OpenPaneSensorDescription) -> None:
        super().__init__(coordinator, description.key)
        self.entity_description = description

    @property
    def native_value(self) -> Any:
        return self.entity_description.value(self.coordinator.data)

    @property
    def extra_state_attributes(self) -> dict[str, Any] | None:
        if self.entity_description.attributes is None:
            return None
        return self.entity_description.attributes(self.coordinator.data)
