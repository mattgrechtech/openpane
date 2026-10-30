"""Base entity for an OpenPane TV."""

from __future__ import annotations

from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import DOMAIN
from .coordinator import OpenPaneCoordinator


class OpenPaneEntity(CoordinatorEntity[OpenPaneCoordinator]):
    """An entity of one TV; all of them share the TV's device."""

    _attr_has_entity_name = True

    def __init__(self, coordinator: OpenPaneCoordinator, key: str) -> None:
        super().__init__(coordinator)
        entry = coordinator.config_entry
        device_key = entry.unique_id or entry.entry_id
        self._attr_unique_id = f"{device_key}_{key}"
        self._attr_translation_key = key
        data = coordinator.data or {}
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, device_key)},
            name=entry.title,
            manufacturer=data.get("manufacturer"),
            model=data.get("model"),
            sw_version=data.get("app_version"),
            configuration_url=f"http://{entry.data['host']}:{entry.data['port']}/",
        )
