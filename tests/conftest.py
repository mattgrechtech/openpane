"""Tests for the TV Kiosk Home Assistant integration (pytest-homeassistant-custom-component)."""

import pytest


@pytest.fixture(autouse=True)
def auto_enable_custom_integrations(enable_custom_integrations):
    """Loads custom_components/ in every test."""
    yield
