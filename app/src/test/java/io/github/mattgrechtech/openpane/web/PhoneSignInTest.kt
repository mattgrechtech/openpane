package io.github.mattgrechtech.openpane.web

import io.github.mattgrechtech.openpane.settings.MemoryStore
import io.github.mattgrechtech.openpane.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class PhoneSignInTest {

    private val settings = Settings(MemoryStore(), MemoryStore())
    private val signIn = PhoneSignIn(settings, HaAuth(settings))

    private fun params(url: String) = url.substringAfter('?').split('&').associate {
        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    @Test fun loginUsesTheTvAsClientAndCallback() {
        val url = signIn.start("http://192.168.1.50:8123/lovelace/0", "http://192.168.1.60:2424")
        assertTrue(url.startsWith("http://192.168.1.50:8123/auth/authorize?response_type=code"))
        val p = params(url)
        assertEquals("http://192.168.1.60:2424/", p["client_id"])
        assertEquals("http://192.168.1.60:2424/setup/callback", p["redirect_uri"])
        assertEquals(32, p.getValue("state").length)
    }

    @Test fun refusesWithoutAMatchingSignIn() {
        assertNotNull(signIn.finish("code", "anything")) // nothing started
        val state = params(signIn.start("http://192.168.1.50:8123", "http://192.168.1.60:2424")).getValue("state")
        assertTrue(signIn.finish("code", "wrong")!!.contains("doesn't match"))
        assertTrue(signIn.finish(null, state)!!.contains("doesn't match"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun needsAnHttpAddress() {
        signIn.start("homeassistant.local", "http://192.168.1.60:2424")
    }
}
