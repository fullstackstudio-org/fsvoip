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
}
