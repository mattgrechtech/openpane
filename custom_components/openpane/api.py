"""A small client for the OpenPane REST API (http://<tv-ip>:2424/api/v1)."""

from __future__ import annotations

from typing import Any

import aiohttp


class OpenPaneError(Exception):
    """The TV couldn't be reached or refused the request."""


class OpenPaneAuthError(OpenPaneError):
    """The token was refused."""


class OpenPaneApi:
    """Talks to one TV. The TV checks the Host header, so `host` must be its IP address."""

    def __init__(self, session: aiohttp.ClientSession, host: str, port: int, token: str) -> None:
        self._session = session
        self._base = f"http://{host}:{port}/api/v1"
        self._headers = {"Authorization": f"Bearer {token}"}

    async def state(self) -> dict[str, Any]:
        """The TV's state: page, screen, presence, versions and more."""
        return await self._request("GET", "/state")

    async def command(self, route: str, args: dict[str, Any] | None = None) -> dict[str, Any]:
        """Runs a command route such as "notify", "navigate" or "sound/stop"."""
        return await self._request("POST", "/" + route, args or {})

    async def screenshot(self) -> bytes | None:
        """A JPEG of the dashboard, or None when it isn't on screen."""
        try:
            async with self._session.get(
                self._base + "/screenshot", headers=self._headers, timeout=aiohttp.ClientTimeout(total=15)
            ) as response:
                if response.status == 401:
                    raise OpenPaneAuthError("the TV refused the token")
                if response.status != 200:
                    return None
                return await response.read()
        except (aiohttp.ClientError, TimeoutError) as err:
            raise OpenPaneError(f"can't reach the TV: {err}") from err

    async def _request(self, method: str, path: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
        try:
            async with self._session.request(
                method,
                self._base + path,
                json=body,
                headers=self._headers,
                timeout=aiohttp.ClientTimeout(total=15),
            ) as response:
                if response.status == 401:
                    raise OpenPaneAuthError("the TV refused the token")
                if response.status == 421:
                    raise OpenPaneError("use the TV's IP address, not a host name")
                data = await response.json(content_type=None)
                if response.status >= 400:
                    raise OpenPaneError(data.get("error") or f"the TV answered {response.status}")
                return data
        except (aiohttp.ClientError, TimeoutError) as err:
            raise OpenPaneError(f"can't reach the TV: {err}") from err
