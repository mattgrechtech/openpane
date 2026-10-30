package io.github.mattgrechtech.openpane.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {

    @Test fun blanksTokensInUrls() {
        assertEquals(
            "GET http://ha.test/api/camera_proxy/camera.x?token=… done",
            AppLog.redact("GET http://ha.test/api/camera_proxy/camera.x?token=53fa65e695c28a27 done"),
        )
        assertEquals("redirect /?auth_callback=1&code=…&state=…", AppLog.redact("redirect /?auth_callback=1&code=abc123&state=s1").replace("state=s1", "state=…"))
    }

    @Test fun blanksBearerHeadersAndLongHex() {
        assertEquals("Authorization: Bearer …", AppLog.redact("Authorization: Bearer 0123abcd"))
        val hex = "a9e35176705ddc8b4974d13483daa594fd896e9ea011149bac0593a168d4dda1"
        assertFalse(AppLog.redact("token is $hex").contains(hex))
    }

    @Test fun blanksJwts() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl"
        assertFalse(AppLog.redact("access $jwt").contains(jwt))
    }

    @Test fun blanksSecretsInJson() {
        val line = AppLog.redact("""{"mqtt_password":"hunter2","settings_pin": "1234","api_token":"abc","device_name":"Lounge"}""")
        assertFalse(line.contains("hunter2"))
        assertFalse(line.contains("1234"))
        assertFalse(line.contains("\"abc\""))
        assertTrue(line.contains("Lounge"))
    }

    @Test fun leavesOrdinaryTextAlone() {
        assertEquals("Page loaded in 1.2 s, 7 items", AppLog.redact("Page loaded in 1.2 s, 7 items"))
    }
}
