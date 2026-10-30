"""The TV's page, last motion, wake time and health."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from homeassistant.components.sensor import SensorDeviceClass, SensorEntity, SensorEntityDescription, SensorStateClass
from homeassistant.const import EntityCategory, UnitOfInformation, UnitOfTime
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import TvKioskConfigEntry
from .entity import TvKioskEntity


def _timestamp(seconds: Any) -> datetime | None:
    return datetime.fromtimestamp(seconds, tz=timezone.utc) if isinstance(seconds, (int, float)) else None


@dataclass(frozen=True, kw_only=True)
class TvKioskSensorDescription(SensorEntityDescription):
    value: Callable[[dict[str, Any]], Any]
    attributes: Callable[[dict[str, Any]], dict[str, Any]] | None = None


SENSORS = (
    TvKioskSensorDescription(
        key="page", value=lambda d: d.get("title") or d.get("url"),
        attributes=lambda d: {"url": d.get("url")},
    ),
    TvKioskSensorDescription(
        key="screen", device_class=SensorDeviceClass.ENUM, options=["on", "dim", "blank", "resting", "night"],
        value=lambda d: "night" if d.get("night") else "resting" if d.get("resting") else d.get("screen"),
    ),
    TvKioskSensorDescription(
        key="last_motion", device_class=SensorDeviceClass.TIMESTAMP, value=lambda d: _timestamp(d.get("last_motion_at")),
    ),
    TvKioskSensorDescription(
        key="last_wake", native_unit_of_measurement=UnitOfTime.MILLISECONDS, device_class=SensorDeviceClass.DURATION,
        entity_category=EntityCategory.DIAGNOSTIC, value=lambda d: d.get("last_wake_ms"),
    ),
    TvKioskSensorDescription(
        key="ha_connection", entity_category=EntityCategory.DIAGNOSTIC, value=lambda d: d.get("ha_connection"),
    ),
    TvKioskSensorDescription(
        key="last_error", entity_category=EntityCategory.DIAGNOSTIC, value=lambda d: (d.get("last_error") or "")[:255] or None,
    ),
    TvKioskSensorDescription(
        key="app_memory", native_unit_of_measurement=UnitOfInformation.MEGABYTES, device_class=SensorDeviceClass.DATA_SIZE,
        state_class=SensorStateClass.MEASUREMENT, entity_category=EntityCategory.DIAGNOSTIC, value=lambda d: d.get("app_memory_mb"),
    ),
    TvKioskSensorDescription(
        key="memory_available", native_unit_of_measurement=UnitOfInformation.MEGABYTES, device_class=SensorDeviceClass.DATA_SIZE,
        state_class=SensorStateClass.MEASUREMENT, entity_category=EntityCategory.DIAGNOSTIC, value=lambda d: d.get("memory_available_mb"),
    ),
    TvKioskSensorDescription(
        key="app_started", device_class=SensorDeviceClass.TIMESTAMP, entity_category=EntityCategory.DIAGNOSTIC,
        value=lambda d: _timestamp(d.get("app_started_at")),
    ),
    TvKioskSensorDescription(
        key="webview_version", entity_category=EntityCategory.DIAGNOSTIC, value=lambda d: d.get("webview_version"),
    ),
)


async def async_setup_entry(hass: HomeAssistant, entry: TvKioskConfigEntry, add: AddEntitiesCallback) -> None:
    add(TvKioskSensor(entry.runtime_data, description) for description in SENSORS)


class TvKioskSensor(TvKioskEntity, SensorEntity):
    entity_description: TvKioskSensorDescription

    def __init__(self, coordinator, description: TvKioskSensorDescription) -> None:
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
