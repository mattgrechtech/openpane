package io.github.mattgrechtech.tvkiosk.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {

    private val discovery = Discovery("tvkiosk", "homeassistant", "a1b2c3d4", "Living room TV", "Model X", "Maker", "0.1.0")

    @Test fun topics() {
        assertEquals("tvkiosk/a1b2c3d4/state", discovery.stateTopic)
        assertEquals("tvkiosk/a1b2c3d4/availability", discovery.availabilityTopic)
        assertEquals("tvkiosk/a1b2c3d4/cmd/+", discovery.commandFilter)
        assertEquals("reload", discovery.commandName("tvkiosk/a1b2c3d4/cmd/reload"))
        assertNull(discovery.commandName("tvkiosk/other/cmd/reload"))
        assertNull(discovery.commandName("tvkiosk/a1b2c3d4/cmd/"))
    }

    @Test fun everyEntityIsWellFormed() {
        val messages = discovery.messages()
        val uniqueIds = messages.map { it.second.getString("unique_id") }
        assertEquals("unique ids must be unique", uniqueIds.size, uniqueIds.toSet().size)
        for ((topic, config) in messages) {
            assertTrue(topic, Regex("^homeassistant/[a-z_]+/tvkiosk_a1b2c3d4/[a-z_]+/config$").matches(topic))
            assertEquals("tvkiosk/a1b2c3d4/availability", config.getString("availability_topic"))
            assertEquals("tvkiosk_a1b2c3d4", config.getJSONObject("device").getJSONArray("identifiers").getString(0))
            assertTrue(topic, config.has("command_topic") || config.has("state_topic"))
            if (config.has("command_topic")) {
                assertTrue(discovery.commandName(config.getString("command_topic")) != null)
            }
        }
    }

    @Test fun everyCommandTopicIsUnderstood() {
        for ((_, config) in discovery.messages()) {
            if (!config.has("command_topic")) continue
            val name = discovery.commandName(config.getString("command_topic"))!!
            val sample = when {
                config.has("payload_press") -> config.getString("payload_press")
                name == "screen_mode" -> "dim"
                name == "dim_level" || name == "brightness_level" -> "40"
                name == "dnd_switch" || name == "present_switch" -> "ON"
                name == "pointer_switch" -> "ON"
                name == "url_text" -> "http://ha.test"
                else -> "hello"
            }
            assertTrue("$name with $sample", Discovery.toCommand(name, sample) != null)
        }
    }

    @Test fun plainPayloadsMapToCommands() {
        assertEquals("screen", Discovery.toCommand("screen_mode", "BLANK")!!.first)
        assertEquals("blank", Discovery.toCommand("screen_mode", "BLANK")!!.second.getString("mode"))
        Discovery.toCommand("dim_level", "35.0")!!.let {
            assertEquals("dim", it.second.getString("mode"))
            assertEquals(35, it.second.getInt("level"))
        }
        assertEquals(false, Discovery.toCommand("pointer_switch", "OFF")!!.second.getBoolean("enabled"))
        assertEquals("Bins tonight", Discovery.toCommand("notify", "Bins tonight")!!.second.getString("message"))
        assertEquals("Bins", Discovery.toCommand("notify", """{"title":"Bins"}""")!!.second.getString("title"))
        assertEquals(true, Discovery.toCommand("reload", """{"hard":true}""")!!.second.getBoolean("hard"))
        assertEquals("/lovelace/1", Discovery.toCommand("navigate", "/lovelace/1")!!.second.getString("path"))
    }

    @Test fun junkIsIgnored() {
        assertNull(Discovery.toCommand("reload", "not json"))
        assertNull(Discovery.toCommand("dim_level", "lots"))
        assertNull(Discovery.toCommand("format_disk", "{}"))
    }
}
