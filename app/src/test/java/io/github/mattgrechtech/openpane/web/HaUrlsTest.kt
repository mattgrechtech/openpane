package io.github.mattgrechtech.openpane.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaUrlsTest {

    private val base = "http://ha.test:8123"

    @Test fun addsExternalAuthOnTheHaOrigin() {
        assertEquals("http://ha.test:8123/?external_auth=1", HaUrls.withExternalAuth("http://ha.test:8123", base))
        assertEquals("http://ha.test:8123/house?external_auth=1", HaUrls.withExternalAuth("http://ha.test:8123/house", base))
        assertEquals("http://ha.test:8123/house?tv=1&external_auth=1", HaUrls.withExternalAuth("http://ha.test:8123/house?tv=1", base))
        assertEquals(
            "http://ha.test:8123/lovelace/0?kiosk&external_auth=1#top",
            HaUrls.withExternalAuth("http://ha.test:8123/lovelace/0?kiosk#top", base),
        )
    }

    @Test fun leavesOtherUrlsAlone() {
        assertEquals("http://other.test/x", HaUrls.withExternalAuth("http://other.test/x", base))
        assertEquals("http://ha.test:8123/?external_auth=1", HaUrls.withExternalAuth("http://ha.test:8123/?external_auth=1", base))
    }

    @Test fun loginUrls() {
        assertEquals("http://ha.test:8123/", HaUrls.clientId(base))
        assertEquals(
            "http://ha.test:8123/auth/authorize?response_type=code" +
                "&client_id=http%3A%2F%2Fha.test%3A8123%2F" +
                "&redirect_uri=http%3A%2F%2Fha.test%3A8123%2F%3Fauth_callback%3D1" +
                "&state=s1",
            HaUrls.authorizeUrl(base, "s1"),
        )
    }

    @Test fun readsTheCodeFromTheRedirect() {
        assertEquals("abc", HaUrls.authCodeFrom("http://ha.test:8123/?auth_callback=1&code=abc&state=s1", base, "s1"))
        assertNull("state must match", HaUrls.authCodeFrom("http://ha.test:8123/?auth_callback=1&code=abc&state=other", base, "s1"))
        assertNull("other pages aren't the redirect", HaUrls.authCodeFrom("http://ha.test:8123/lovelace?code=abc&state=s1", base, "s1"))
    }

    @Test fun formBodyIsEncoded() {
        assertEquals("grant_type=refresh_token&client_id=http%3A%2F%2Fh%2F", HaUrls.formBody("grant_type" to "refresh_token", "client_id" to "http://h/"))
    }
}
