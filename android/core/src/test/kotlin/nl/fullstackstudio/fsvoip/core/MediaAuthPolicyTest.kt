// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAuthPolicyTest {
    private val api = "https://fullstackstudio.nl/api/voip-app/v1"
    private val auth = mapOf("Authorization" to "Bearer secret")

    @Test
    fun theTokenOnlyGoesToTheApiHost() {
        assertEquals(auth, MediaAuthPolicy.headersFor("https://fullstackstudio.nl/api/voip-app/v1/calls/1/recording", api, auth))
        assertEquals(auth, MediaAuthPolicy.headersFor("https://FullStackStudio.nl:443/x", api, auth))
        assertTrue(MediaAuthPolicy.headersFor("https://cdn.example.com/a.mp3", api, auth).isEmpty())
        assertTrue(MediaAuthPolicy.headersFor("https://fullstackstudio.nl.evil.example/a", api, auth).isEmpty())
        assertTrue(MediaAuthPolicy.headersFor("https://fullstackstudio.nl:8443/a", api, auth).isEmpty())
        // The same host over plain http is not the API either.
        assertTrue(MediaAuthPolicy.headersFor("http://fullstackstudio.nl/a", api, auth).isEmpty())
        assertTrue(MediaAuthPolicy.headersFor("not a url", api, auth).isEmpty())
    }

    @Test
    fun aRedirectKeepsTheTokenOnlyWhenItStaysOnTheApiHost() {
        val from = "https://fullstackstudio.nl/api/voip-app/v1/voicemail/b/r/audio"
        val sameHost = MediaAuthPolicy.resolveRedirect(from, "/api/voip-app/v1/other")!!
        val elsewhere = MediaAuthPolicy.resolveRedirect(from, "https://storage.example.com/blob?sig=1")!!

        assertEquals("https://fullstackstudio.nl/api/voip-app/v1/other", sameHost)
        assertEquals(auth, MediaAuthPolicy.headersFor(sameHost, api, auth))
        assertTrue(MediaAuthPolicy.headersFor(elsewhere, api, auth).isEmpty())
    }

    @Test
    fun aDowngradeOrAnOddLocationIsNotFollowed() {
        val from = "https://fullstackstudio.nl/a"

        assertNull(MediaAuthPolicy.resolveRedirect(from, "http://fullstackstudio.nl/a"))
        assertNull(MediaAuthPolicy.resolveRedirect(from, "file:///etc/passwd"))
        assertNull(MediaAuthPolicy.resolveRedirect(from, "javascript:alert(1)"))
        assertNull(MediaAuthPolicy.resolveRedirect(from, ""))
        assertNull(MediaAuthPolicy.resolveRedirect(from, null))
        assertFalse(MediaAuthPolicy.resolveRedirect("http://127.0.0.1:8787/a", "http://127.0.0.1:8787/b") == null)
    }
}
