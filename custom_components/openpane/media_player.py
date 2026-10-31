"""A speaker for tablets and wall panels: tts.speak, announcements and sounds play on the device.

Only on tablets and wall panels, not TVs: a TV's sound belongs to what's on it, and announcers
(Jarvis, Music Assistant, tts.speak) would talk over a film. Playback is the app's existing
`POST /sound`, so nothing new runs on the device.
"""

from __future__ import annotations

from typing import Any

from homeassistant.components import media_source
from homeassistant.components.media_player import (
    BrowseMedia,
    MediaPlayerDeviceClass,
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
    MediaPlayerState,
    MediaType,
    async_process_play_media_url,
)
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import OpenPaneConfigEntry
from .api import OpenPaneError
from .entity import OpenPaneEntity


async def async_setup_entry(hass: HomeAssistant, entry: OpenPaneConfigEntry, add: AddEntitiesCallback) -> None:
    # Older app versions don't say whether they're on a TV: leave them out too.
    if (entry.runtime_data.data or {}).get("tv") is False:
        add([OpenPaneSpeaker(entry.runtime_data, "speaker")])


class OpenPaneSpeaker(OpenPaneEntity, MediaPlayerEntity):
    _attr_device_class = MediaPlayerDeviceClass.SPEAKER
    _attr_media_content_type = MediaType.MUSIC
    _attr_supported_features = (
        MediaPlayerEntityFeature.PLAY_MEDIA
        | MediaPlayerEntityFeature.BROWSE_MEDIA
        | MediaPlayerEntityFeature.MEDIA_ANNOUNCE
        | MediaPlayerEntityFeature.VOLUME_SET
        | MediaPlayerEntityFeature.STOP
    )

    @property
    def state(self) -> MediaPlayerState:
        return MediaPlayerState.PLAYING if self.coordinator.data.get("sound_playing") else MediaPlayerState.IDLE

    @property
    def volume_level(self) -> float | None:
        volume = self.coordinator.data.get("volume")
        return None if volume is None else volume / 100

    async def async_play_media(self, media_type: MediaType | str, media_id: str, **kwargs: Any) -> None:
        if media_source.is_media_source_id(media_id):
            item = await media_source.async_resolve_media(self.hass, media_id, self.entity_id)
            media_id = item.url
        # A path from Home Assistant (a TTS file, a media folder) becomes a full, signed link.
        await self._send("sound", {"url": async_process_play_media_url(self.hass, media_id)})

    async def async_browse_media(
        self, media_content_type: MediaType | str | None = None, media_content_id: str | None = None
    ) -> BrowseMedia:
        return await media_source.async_browse_media(
            self.hass, media_content_id, content_filter=lambda item: item.media_content_type.startswith("audio/")
        )

    async def async_set_volume_level(self, volume: float) -> None:
        await self._send("volume", {"level": round(volume * 100)})

    async def async_media_stop(self) -> None:
        await self._send("sound/stop")

    async def _send(self, route: str, args: dict[str, Any] | None = None) -> None:
        try:
            await self.coordinator.command(route, args)
        except OpenPaneError as err:
            raise HomeAssistantError(str(err)) from err
