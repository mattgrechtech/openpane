package io.github.mattgrechtech.openpane.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaDiscoveryTest {

    @Test fun prefersTheInternalUrl() {
        val txt = mapOf("internal_url" to "http://ha.test:8123/", "base_url" to "http://other.test:8123")
        assertEquals("http://ha.test:8123", HaDiscovery.urlFrom(txt, "192.168.1.50", 8123))
    }

    @Test fun fallsBackToBaseUrlThenTheAddress() {
        assertEquals("https://ha.test", HaDiscovery.urlFrom(mapOf("internal_url" to "", "base_url" to "https://ha.test"), "192.168.1.50", 8123))
        assertEquals("http://192.168.1.50:8123", HaDiscovery.urlFrom(mapOf("internal_url" to null), "192.168.1.50", 8123))
    }

    @Test fun skipsDotLocalNamesWhenThereIsAnAddress() {
        val txt = mapOf("base_url" to "http://homeassistant.local:8123")
        assertEquals("http://192.168.1.50:8123", HaDiscovery.urlFrom(txt, "192.168.1.50", 8123))
        assertEquals("http://homeassistant.local:8123", HaDiscovery.urlFrom(txt, null, 0))
    }

    @Test fun ignoresNonWebUrlsAndMissingAddresses() {
        assertNull(HaDiscovery.urlFrom(mapOf("base_url" to "ftp://ha.test"), null, 8123))
        assertEquals("http://[fe80::1]:8123", HaDiscovery.urlFrom(emptyMap(), "[fe80::1]", 8123))
    }
}
