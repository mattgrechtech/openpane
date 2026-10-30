package io.github.mattgrechtech.openpane.web

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class HaDashboardsTest {

    @Test fun listsShowablePanelsDefaultFirst() {
        val result = JSONObject("""
            {
              "lovelace": {"component_name": "lovelace", "url_path": "lovelace", "title": null, "require_admin": false},
              "house": {"component_name": "custom", "url_path": "house", "title": "House", "require_admin": false},
              "dashboard-home": {"component_name": "lovelace", "url_path": "dashboard-home", "title": "Home", "require_admin": false},
              "map": {"component_name": "map", "url_path": "map", "title": "Map", "require_admin": false},
              "config": {"component_name": "config", "url_path": "config", "title": null, "require_admin": true},
              "developer-tools": {"component_name": "developer-tools", "url_path": "developer-tools", "title": null, "require_admin": true},
              "admin-only": {"component_name": "lovelace", "url_path": "admin-only", "title": "Admin", "require_admin": true},
              "profile": {"component_name": "profile", "url_path": "profile", "title": null, "require_admin": false},
              "energy-x": {"component_name": "energy", "url_path": "energy-x", "title": "energy", "require_admin": false}
            }
        """.trimIndent())
        val dashboards = HaDashboards.parsePanels(result)
        // Default first, then your dashboards and panels, then HA's own pages.
        assertEquals(
            listOf("lovelace", "dashboard-home", "house", "energy-x", "map"),
            dashboards.map { it.path },
        )
        assertEquals(listOf("Overview", "Home", "House", "Energy", "Map"), dashboards.map { it.title })
        assertEquals(listOf(false, false, false, true, true), dashboards.map { it.builtIn })
    }

    @Test fun framesRoundTrip() {
        val mask = byteArrayOf(1, 2, 3, 4)
        for (size in listOf(0, 5, 125, 126, 300, 70_000)) {
            val payload = ByteArray(size) { (it % 251).toByte() }
            val frame = MiniWebSocket.encodeFrame(MiniWebSocket.OPCODE_TEXT, payload, mask)
            val decoded = MiniWebSocket.readFrame(ByteArrayInputStream(frame))
            assertTrue(decoded.fin)
            assertEquals(MiniWebSocket.OPCODE_TEXT, decoded.opcode)
            assertArrayEquals("size $size", payload, decoded.payload)
        }
    }

    @Test fun clientFramesAreMasked() {
        val frame = MiniWebSocket.encodeFrame(MiniWebSocket.OPCODE_TEXT, "hi".toByteArray(), byteArrayOf(9, 9, 9, 9))
        assertEquals(0x81, frame[0].toInt() and 0xFF)
        assertEquals(0x80 or 2, frame[1].toInt() and 0xFF)
    }

    @Test fun readsUnmaskedServerFrames() {
        val server = byteArrayOf(0x81.toByte(), 0x05) + "hello".toByteArray()
        assertEquals("hello", String(MiniWebSocket.readFrame(ByteArrayInputStream(server)).payload))
    }
}
