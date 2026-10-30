"""Polls the TV's state."""

from __future__ import annotations

from datetime import timedelta
import logging
import time
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryAuthFailed
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed

from . import health
from .api import TvKioskApi, TvKioskAuthError, TvKioskError
from .const import DOMAIN, SCAN_INTERVAL_SECONDS

_LOGGER = logging.getLogger(__name__)


class TvKioskCoordinator(DataUpdateCoordinator[dict[str, Any]]):
    """Keeps the latest state of one TV."""

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry, api: TvKioskApi) -> None:
        super().__init__(
            hass,
            _LOGGER,
            config_entry=entry,
            name=f"{DOMAIN} {entry.title}",
            update_interval=timedelta(seconds=SCAN_INTERVAL_SECONDS),
        )
        self.api = api
        self._failing_since: float | None = None

    async def _async_update_data(self) -> dict[str, Any]:
        entry = self.config_entry
        try:
            data = await self.api.state()
        except TvKioskAuthError as err:
            raise ConfigEntryAuthFailed(str(err)) from err
        except TvKioskError as err:
            now = time.monotonic()
            self._failing_since = self._failing_since or now
            if now - self._failing_since >= health.UNREACHABLE_SECONDS:
                health.update(self.hass, entry.entry_id, entry.title, {"unreachable"})
            raise UpdateFailed(str(err)) from err
        self._failing_since = None
        health.update(self.hass, entry.entry_id, entry.title, health.problems(data), data)
        return data

    async def command(self, route: str, args: dict[str, Any] | None = None) -> dict[str, Any]:
        """Runs a command, then refreshes the state soon after."""
        result = await self.api.command(route, args)
        await self.async_request_refresh()
        return result
