"""Home Assistant Repairs for a TV that needs attention: unreachable, can't show pop-ups, old browser engine."""

from __future__ import annotations

from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.helpers import issue_registry as ir

from .const import DOMAIN

# Home Assistant's frontend needs a recent browser engine; older WebViews show a blank page.
MIN_WEBVIEW_MAJOR = 100
# Unreachable this long before it's worth a Repair (a TV switched off for the night isn't news).
UNREACHABLE_SECONDS = 6 * 3600

ISSUES = ("unreachable", "no_overlay", "background_blocked", "old_webview")


def problems(data: dict[str, Any]) -> set[str]:
    """The issues a TV's state shows (not "unreachable": that comes from failed polls)."""
    found: set[str] = set()
    wake = data.get("wake") or {}
    if wake.get("overlay_permission") is False:
        found.add("no_overlay")
    if wake.get("auto_start") == "denied" or wake.get("service_protected") is False:
        found.add("background_blocked")
    version = str(data.get("webview_version") or "")
    major = version.split(".", 1)[0]
    if major.isdigit() and int(major) < MIN_WEBVIEW_MAJOR:
        found.add("old_webview")
    return found


def update(hass: HomeAssistant, entry_id: str, name: str, current: set[str], data: dict[str, Any] | None = None) -> None:
    """Raises the Repairs in [current] for this TV and clears the rest."""
    for issue in ISSUES:
        issue_id = f"{entry_id}_{issue}"
        if issue in current:
            ir.async_create_issue(
                hass, DOMAIN, issue_id,
                is_fixable=False,
                severity=ir.IssueSeverity.WARNING,
                translation_key=issue,
                translation_placeholders={"name": name, "version": str((data or {}).get("webview_version") or "")},
                learn_more_url="https://github.com/mattgrechtech/openpane/blob/main/docs/troubleshooting.md",
            )
        else:
            ir.async_delete_issue(hass, DOMAIN, issue_id)


def clear_all(hass: HomeAssistant, entry_id: str) -> None:
    for issue in ISSUES:
        ir.async_delete_issue(hass, DOMAIN, f"{entry_id}_{issue}")
