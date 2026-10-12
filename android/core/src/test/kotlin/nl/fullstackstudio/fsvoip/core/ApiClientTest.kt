// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakeTransport(var respond: (HttpRequest) -> HttpResponse) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun send(request: HttpRequest): HttpResponse {
        requests += request
        return respond(request)
    }
}

fun response(status: Int, body: String = "{}", headers: Map<String, String> = emptyMap()) =
    HttpResponse(status, headers, body.toByteArray())

class ApiClientTest {
    private val sink = MemoryLogSink()
    private val logger = FsLogger("api", sink)

    @Test
    fun pairPostsWithoutAuthorization() = runTest {
        val transport = FakeTransport { response(201, Fixtures.text("pair-response.json")) }
        val client = FsVoipApiClient("https://example.test/api/voip-app/v1", transport = transport, userAgent = "FSVoip/1.0 (Android)", logger = logger)

        val result = client.pair(PairRequest("fss_vpair_" + "a".repeat(43), DeviceInfo(platform = ApiPlatform.ANDROID)))

        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://example.test/api/voip-app/v1/pair", request.url)
        assertNull(request.headers["Authorization"])
        assertEquals("FSVoip/1.0 (Android)", request.headers["User-Agent"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("102", result.sip.username)
        // Nothing secret in the log.
        assertFalse(sink.messages.any { it.contains("fixture-not-a-real-password") || it.contains("fss_vpair_aaa") })
    }

    @Test
    fun authenticatedRoutesSendTheBearerToken() = runTest {
        val transport = FakeTransport { response(200, Fixtures.text("me-response.json")) }
        val client = FsVoipApiClient("https://example.test/v1", transport = transport, logger = logger).authenticated(Secret("fss_vapp_token"))

        client.me()

        assertEquals("GET", transport.requests.single().method)
        assertEquals("Bearer fss_vapp_token", transport.requests.single().headers["Authorization"])
        assertNull(transport.requests.single().body)
    }

    @Test
    fun patchSendsAnExplicitNull() = runTest {
        val transport = FakeTransport { response(200, """{"label":"X","labelOverride":null}""") }
        val client = FsVoipApiClient("https://example.test/v1", transport = transport, logger = logger).authenticated(Secret("t"))

        client.setLabelOverride(null)

        val request = transport.requests.single()
        assertEquals("PATCH", request.method)
        val body = FsJson.default.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals(JsonNull, body["labelOverride"])
    }

    @Test
    fun missingTokenFailsWithoutARequest() = runTest {
        val transport = FakeTransport { response(200) }
        val client = FsVoipApiClient("https://example.test/v1", transport = transport, logger = logger)

        try {
            client.me()
            fail()
        } catch (_: ApiException.MissingDeviceToken) {
        }

        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun errorMapping() {
        assertTrue(FsVoipApiClient.errorFor(response(401, Fixtures.text("error-unauthorized.json"))) is ApiException.Unauthorized)
        assertTrue(FsVoipApiClient.errorFor(response(404, Fixtures.text("error-not-found.json"))) is ApiException.NotFound)
        assertTrue(FsVoipApiClient.errorFor(response(413)) is ApiException.PayloadTooLarge)

        val limited = FsVoipApiClient.errorFor(response(429, Fixtures.text("error-rate-limited.json"), mapOf("Retry-After" to "30")))
        assertEquals(30, (limited as ApiException.RateLimited).retryAfterSeconds)

        val unavailable = FsVoipApiClient.errorFor(response(503, Fixtures.text("error-unavailable-retryable.json")))
        assertTrue((unavailable as ApiException.Unavailable).retryable)

        val invalid = FsVoipApiClient.errorFor(response(400, Fixtures.text("error-invalid-request.json")))
        assertEquals("device.platform moet ios of android zijn.", (invalid as ApiException.InvalidRequest).detail)

        assertEquals(500, (FsVoipApiClient.errorFor(response(500, "<html>")) as ApiException.UnexpectedStatus).status)
    }

    @Test
    fun undecodableBodyIsADecodingError() = runTest {
        val transport = FakeTransport { response(200, "{\"nope\":true}") }
        val client = FsVoipApiClient("https://example.test/v1", transport = transport, logger = logger).authenticated(Secret("t"))

        try {
            client.me()
            fail()
        } catch (_: ApiException.Decoding) {
        }
    }

    private fun authenticated(transport: FakeTransport) =
        FsVoipApiClient("https://example.test/v1", transport = transport, logger = logger).authenticated(Secret("fss_vapp_token"))

    @Test
    fun callsAndVoicemailUseQueryParameters() = runTest {
        val transport = FakeTransport { request ->
            if (request.url.contains("/calls")) response(200, Fixtures.text("calls-page-team.json")) else response(200, Fixtures.text("voicemail-page.json"))
        }
        val client = authenticated(transport)

        assertEquals(3, client.calls("2026-10", "nl").calls.size)
        assertEquals("https://example.test/v1/calls?month=2026-10&locale=nl", transport.requests[0].url)

        client.calls(null, "en")
        assertEquals("https://example.test/v1/calls?locale=en", transport.requests[1].url)

        client.voicemail("7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57", "nl")
        assertEquals("https://example.test/v1/voicemail?box=7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57&locale=nl", transport.requests[2].url)
    }

    @Test
    fun voicemailDeleteAndAudioEncodeTheReference() = runTest {
        val transport = FakeTransport { response(200, """{"ok":true}""") }
        val client = authenticated(transport)

        client.deleteVoicemail("box-1", "v1.Zm9v/YmFy+==")
        assertEquals("DELETE", transport.requests[0].method)
        assertEquals("https://example.test/v1/voicemail/box-1/v1.Zm9v%2FYmFy%2B%3D%3D", transport.requests[0].url)

        val audio = client.voicemailAudio("box-1", "v1.abc")
        assertEquals("https://example.test/v1/voicemail/box-1/v1.abc/audio", audio.url)
        // The token goes in the header and nowhere else; printing the source shows nothing.
        assertEquals("Bearer fss_vapp_token", audio.headers["Authorization"])
        assertFalse(audio.url.contains("fss_vapp_token"))
        assertFalse(audio.toString().contains("fss_vapp_token"))
    }

    @Test
    fun parkSendsTheCallIdAndUncertaintyIsATypedError() = runTest {
        val transport = FakeTransport { request ->
            if (request.method == "POST") response(201, Fixtures.text("park-response.json")) else response(200, Fixtures.text("parked-calls.json"))
        }
        val client = authenticated(transport)

        assertEquals(1, client.park("3b9c1f0e2a7d4c58@192.0.2.10").slot)
        val body = FsJson.default.parseToJsonElement(transport.requests[0].body!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals("3b9c1f0e2a7d4c58@192.0.2.10", body["callId"]!!.toString().trim('"'))
        assertEquals(3, client.parked().calls.size)
        // The Call-ID is not written to the log.
        assertFalse(sink.messages.any { it.contains("3b9c1f0e") })

        val failing = authenticated(FakeTransport { response(503, Fixtures.text("error-park-uncertain.json")) })

        try {
            failing.park("x")
            fail()
        } catch (_: ApiException.ParkUncertain) {
        }
    }

    @Test
    fun contactSyncQueryAndConditionalSnapshot() = runTest {
        val transport = FakeTransport { request ->
            when {
                request.url.contains("/contact-lists/") && request.headers["If-None-Match"] == "\"3\"" -> response(304, "", mapOf("ETag" to "\"3\""))
                request.url.contains("/contact-lists/") -> response(200, Fixtures.text("contact-list-snapshot.json"), mapOf("etag" to "\"3\""))
                else -> response(200, Fixtures.text("contacts-since.json"))
            }
        }
        val client = authenticated(transport)

        val page = client.contacts(since = "2026-10-09T08:15:30.123Z", cursor = null, limit = 500)
        assertEquals("https://example.test/v1/contacts?since=2026-10-09T08%3A15%3A30.123Z&limit=500", transport.requests[0].url)
        assertEquals(1, page.deleted.size)

        val fresh = client.contactListSnapshot("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d") as ContactListSnapshotOutcome.Snapshot
        assertEquals("\"3\"", fresh.etag)
        assertEquals(2, fresh.snapshot.contacts.size)
        assertNull(transport.requests[1].headers["If-None-Match"])

        val same = client.contactListSnapshot("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", etag = ContactListETag.make(3))
        assertTrue(same is ContactListSnapshotOutcome.NotModified)

        // The server ignores If-None-Match on a cursor page, and so do we.
        client.contactListSnapshot("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", cursor = "abc", etag = ContactListETag.make(3))
        assertNull(transport.requests[3].headers["If-None-Match"])
    }

    @Test
    fun selfExtensionPatchCarriesTheVersion() = runTest {
        val transport = FakeTransport { response(200, Fixtures.text("self-extension-patch-response.json")) }
        val client = authenticated(transport)

        val result = client.patchSelfExtension(SelfExtensionPatch(version = 4, dnd = true))

        assertEquals("PATCH", transport.requests.single().method)
        assertEquals("https://example.test/v1/me/extension", transport.requests.single().url)
        assertEquals(5, result.state()!!.version)
    }
}
