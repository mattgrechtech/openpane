package io.github.mattgrechtech.tvkiosk.overlay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PopupTest {

    @Test fun readsTvOverlayPayloadsFromTheKit() {
        // What the kit's script.tv_popup sends to TvOverlay today.
        val popup = Popup.fromJson(JSONObject("""
            {"title":"Front door","message":"Motion","corner":"bottom_start","duration":15,
             "image":"http://ha.test:8123/api/camera_proxy/camera.x?token=t","smallIcon":"mdi:cctv","smallIconColor":"#FF5A3C"}
        """.trimIndent()))!!
        assertEquals("Front door", popup.title)
        assertEquals("Motion", popup.message)
        assertEquals(Popup.Corner.BOTTOM_START, popup.corner)
        assertEquals(15, popup.durationSeconds)
        assertEquals("http://ha.test:8123/api/camera_proxy/camera.x?token=t", popup.image)
        assertEquals("mdi:cctv", popup.icon)
        assertEquals(0xFFFF5A3C.toInt(), popup.color)
    }

    @Test fun shortNamesAndDefaults() {
        val popup = Popup.fromJson(JSONObject("""{"message":"Hi","icon":"mdi:home","color":"80FF0000","largeIcon":"x"}"""))!!
        assertEquals("", popup.title)
        assertEquals(Popup.Corner.TOP_END, popup.corner)
        assertEquals(Popup.DEFAULT_DURATION, popup.durationSeconds)
        assertEquals("mdi:home", popup.icon)
        assertEquals(0x80FF0000.toInt(), popup.color)
        assertEquals("x", popup.image)
        assertNotNull(popup.id)
    }

    @Test fun nullsAndJunk() {
        assertNull(Popup.fromJson(JSONObject("""{"title":null,"message":""}""")))
        val popup = Popup.fromJson(JSONObject("""{"title":"T","image":null,"corner":"middle","duration":99999,"smallIconColor":"red"}"""))!!
        assertNull(popup.image)
        assertNull(popup.color)
        assertEquals(Popup.Corner.TOP_END, popup.corner)
        assertEquals(Popup.MAX_DURATION, popup.durationSeconds)
    }

    @Test fun keepsTheGivenId() {
        assertEquals("doorbell", Popup.fromJson(JSONObject("""{"title":"T","id":"doorbell"}"""))!!.id)
    }

    @Test fun settingsFillInWhatThePayloadLeavesOut() {
        val defaults = Popup.Defaults(Popup.Corner.BOTTOM_START, 20, Popup.Size.LARGE, 75, largeText = true)
        val plain = Popup.fromJson(JSONObject("""{"title":"T"}"""), defaults)!!
        assertEquals(Popup.Corner.BOTTOM_START, plain.corner)
        assertEquals(20, plain.durationSeconds)
        assertEquals(Popup.Size.LARGE, plain.size)
        assertEquals(75, plain.opacity)
        assertEquals(true, plain.largeText)

        val own = Popup.fromJson(JSONObject("""{"title":"T","corner":"top_end","duration":5,"size":"small","opacity":10,"text_size":"normal"}"""), defaults)!!
        assertEquals(Popup.Corner.TOP_END, own.corner)
        assertEquals(5, own.durationSeconds)
        assertEquals(Popup.Size.SMALL, own.size)
        assertEquals(40, own.opacity) // never so faint it can't be read
        assertEquals(false, own.largeText)
    }

    @Test fun buttonsActOnTheTvOnly() {
        val popup = Popup.fromJson(JSONObject("""{"title":"T","actions":[
            {"label":"Open camera","navigate":"/house"},
            {"label":"Weather","url":"https://weather.test"},
            {"label":"Bad path","navigate":"house"},
            {"label":"Bad scheme","url":"javascript:alert(1)"},
            {"navigate":"/no-label"},
            {"label":"Dismiss"},
            {"label":"Too many"}
        ]}"""))!!
        assertEquals(
            listOf(
                Popup.Action("Open camera", "/house", null),
                Popup.Action("Weather", null, "https://weather.test"),
                Popup.Action("Dismiss", null, null),
            ),
            popup.actions,
        )
        assertEquals(emptyList<Popup.Action>(), Popup.fromJson(JSONObject("""{"title":"T"}"""))!!.actions)
    }

    @Test fun durationZeroStaysUntilClosed() {
        assertEquals(0, Popup.fromJson(JSONObject("""{"title":"T","duration":0}"""))!!.durationSeconds)
        assertEquals(0, Popup.fromJson(JSONObject("""{"title":"T","duration":-5}"""))!!.durationSeconds)
    }

    @Test fun buttonsCanSendAnEventToHomeAssistant() {
        val popup = Popup.fromJson(JSONObject("""{"title":"T","actions":[
            {"label":"Unlock","event":"Unlock_Front"},
            {"label":"Bad name","event":"rm -rf"},
            {"label":"Open","navigate":"/house","event":"ignored"}
        ]}"""))!!
        assertEquals(
            listOf(
                Popup.Action("Unlock", null, null, "unlock_front"),
                Popup.Action("Open", "/house", null),
            ),
            popup.actions,
        )
    }

    @Test fun liveStreamAndCameraAreChecked() {
        val ok = Popup.fromJson(JSONObject("""{"title":"T","stream":"http://go2rtc.test:1984/api/stream.mjpeg?src=door","camera":"Camera.Front_Door"}"""))!!
        assertEquals("http://go2rtc.test:1984/api/stream.mjpeg?src=door", ok.stream)
        assertEquals("camera.front_door", ok.camera)
        assertEquals(true, ok.hasPicture)
        val bad = Popup.fromJson(JSONObject("""{"title":"T","stream":"file:///etc/passwd","camera":"light.kitchen"}"""))!!
        assertNull(bad.stream)
        assertNull(bad.camera)
        assertEquals(false, bad.hasPicture)
    }

    @Test fun mdiNamesMapToEmoji() {
        assertEquals("📹", MdiIcons.emojiFor("mdi:cctv"))
        assertEquals("🗑️", MdiIcons.emojiFor("mdi:trash-can-outline"))
        assertEquals("🔔", MdiIcons.emojiFor("mdi:doorbell-video"))
        assertNull(MdiIcons.emojiFor("mdi:zzz-unknown"))
        assertNull(MdiIcons.emojiFor("http://x/icon.png"))
    }
}
