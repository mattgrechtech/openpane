"""Polls the TV's state."""

from __future__ import annotations

from datetime import timedelta
import logging
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryAuthFailed
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed

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

    async def _async_update_data(self) -> dict[str, Any]:
        try:
            return await self.api.state()
        except TvKioskAuthError as err:
            raise ConfigEntryAuthFailed(str(err)) from err
        except TvKioskError as err:
            raise UpdateFailed(str(err)) from err

    async def command(self, route: str, args: dict[str, Any] | None = None) -> dict[str, Any]:
        """Runs a command, then refreshes the state soon after."""
        result = await self.api.command(route, args)
        await self.async_request_refresh()
        return result
