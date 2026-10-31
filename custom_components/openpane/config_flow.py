"""Set-up from the UI: found on the network (zeroconf) or typed in, then the TV's API token."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigFlow, ConfigFlowResult
from homeassistant.const import CONF_HOST, CONF_PORT, CONF_TOKEN
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo

from .api import OpenPaneApi, OpenPaneAuthError, OpenPaneError
from .const import DEFAULT_PORT, DOMAIN


class OpenPaneConfigFlow(ConfigFlow, domain=DOMAIN):
    """One config entry per TV, keyed by the TV's device id."""

    VERSION = 1

    def __init__(self) -> None:
        self._host: str | None = None
        self._port = DEFAULT_PORT
        self._name: str | None = None

    async def _check(self, host: str, port: int, token: str) -> tuple[dict[str, Any] | None, str | None]:
        api = OpenPaneApi(async_get_clientsession(self.hass), host, port, token)
        try:
            return await api.state(), None
        except OpenPaneAuthError:
            return None, "invalid_auth"
        except OpenPaneError:
            return None, "cannot_connect"

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            state, error = await self._check(user_input[CONF_HOST], user_input[CONF_PORT], user_input[CONF_TOKEN])
            if error:
                errors["base"] = error
            else:
                return await self._create(state, user_input)
        schema = vol.Schema({
            vol.Required(CONF_HOST, default=(user_input or {}).get(CONF_HOST, "")): str,
            vol.Required(CONF_PORT, default=(user_input or {}).get(CONF_PORT, DEFAULT_PORT)): int,
            vol.Required(CONF_TOKEN): str,
        })
        return self.async_show_form(step_id="user", data_schema=schema, errors=errors)

    async def async_step_zeroconf(self, discovery_info: ZeroconfServiceInfo) -> ConfigFlowResult:
        device_id = discovery_info.properties.get("id")
        if not device_id:
            return self.async_abort(reason="not_openpane")
        self._host = str(discovery_info.ip_address)
        self._port = discovery_info.port or DEFAULT_PORT
        self._name = discovery_info.properties.get("name") or "OpenPane"
        await self.async_set_unique_id(device_id)
        # Already set up: leave it alone. Never follow an announcement to a new address by itself,
        # or anyone on the network could announce this TV's id and receive its token.
        self._abort_if_unique_id_configured()
        self.context["title_placeholders"] = {"name": self._name}
        return await self.async_step_zeroconf_confirm()

    async def async_step_zeroconf_confirm(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            data = {CONF_HOST: self._host, CONF_PORT: self._port, CONF_TOKEN: user_input[CONF_TOKEN]}
            state, error = await self._check(self._host, self._port, user_input[CONF_TOKEN])
            if error:
                errors["base"] = error
            else:
                return await self._create(state, data)
        return self.async_show_form(
            step_id="zeroconf_confirm",
            data_schema=vol.Schema({vol.Required(CONF_TOKEN): str}),
            description_placeholders={"name": self._name or "", "host": self._host or ""},
            errors=errors,
        )

    async def async_step_reauth(self, entry_data: Mapping[str, Any]) -> ConfigFlowResult:
        return await self.async_step_reauth_confirm()

    async def async_step_reauth_confirm(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        entry = self._get_reauth_entry()
        errors: dict[str, str] = {}
        if user_input is not None:
            _, error = await self._check(entry.data[CONF_HOST], entry.data[CONF_PORT], user_input[CONF_TOKEN])
            if error:
                errors["base"] = error
            else:
                return self.async_update_reload_and_abort(entry, data_updates={CONF_TOKEN: user_input[CONF_TOKEN]})
        return self.async_show_form(
            step_id="reauth_confirm", data_schema=vol.Schema({vol.Required(CONF_TOKEN): str}), errors=errors
        )

    async def _create(self, state: dict[str, Any] | None, data: dict[str, Any]) -> ConfigFlowResult:
        state = state or {}
        device_id = state.get("device_id")
        if device_id:
            # A typed-in set-up wins over the same TV found on the network and still waiting.
            await self.async_set_unique_id(device_id, raise_on_progress=False)
            self._abort_if_unique_id_configured(updates={CONF_HOST: data[CONF_HOST], CONF_PORT: data[CONF_PORT]})
        title = state.get("device_name") or state.get("model") or data[CONF_HOST]
        return self.async_create_entry(title=title, data=data)
