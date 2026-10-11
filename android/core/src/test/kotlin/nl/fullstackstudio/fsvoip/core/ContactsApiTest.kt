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

class ContactsApiTest {
    private val base = "https://example.test/api/voip-app/v1"
    private val id = "6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c"

    private fun client(vararg answers: HttpResponse, sink: MemoryLogSink = MemoryLogSink()): Pair<FsVoipApiClient, FakeTransport> {
        val queue = answers.toMutableList()
        val transport = FakeTransport { queue.removeAt(0) }

        return FsVoipApiClient(base, Secret("fss_vapp_token"), transport, logger = FsLogger("api", sink)) to transport
    }

    private fun fixtureResponse(status: Int, name: String, headers: Map<String, String> = emptyMap()) = response(status, Fixtures.text(name), headers)

    private fun query(request: HttpRequest): Map<String, String> =
        request.url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    @Test
    fun contactRoutes() = runTest {
        val (api, transport) = client(
            fixtureResponse(200, "contacts-page.json"),
            fixtureResponse(200, "contact-detail.json"),
            fixtureResponse(201, "contact-detail.json"),
            fixtureResponse(200, "contact-update-response.json"),
            fixtureResponse(200, "contact-delete-response.json"),
            fixtureResponse(200, "contact-lists.json"),
        )

        api.contactsPage(since = "2026-10-09T08:15:30.123Z", cursor = "abc", limit = 100)
        val one = api.contact(id)
        api.createContact(ContactCreate(name = "Bakkerij Smit"))
        val updated = api.updateContact(id, ContactUpdate(one.contact.updatedAt, company = Change.Clear))
        val deleted = api.deleteContact(id)
        val lists = api.contactLists()

        assertTrue(updated.changed)
        assertEquals(1, deleted)
        assertEquals(2, lists.size)
        assertEquals(listOf("GET", "GET", "POST", "PATCH", "DELETE", "GET"), transport.requests.map { it.method })
        assertEquals(mapOf("since" to "2026-10-09T08:15:30.123Z", "cursor" to "abc", "limit" to "100"), query(transport.requests[0]))
        assertEquals(FsJson.default.parseToJsonElement("""{"company":null,"expectedUpdatedAt":"2026-10-07T09:30:12.345Z"}"""), FsJson.default.parseToJsonElement(transport.requests[3].body!!.toString(Charsets.UTF_8)))
        assertEquals("$base/contacts/$id", transport.requests[4].url)
        assertEquals("Bearer fss_vapp_token", transport.requests[0].headers["Authorization"])
    }

    @Test
    fun syncFollowsTheCursorAndKeepsTheFirstServerTime() = runTest {
        val page1 = """{"contacts":[{"id":"a","name":"A","phones":[],"tags":[],"updatedAt":"2026-10-09T08:00:00.000Z"},{"id":"b","name":"B","phones":[],"tags":[],"updatedAt":"2026-10-09T08:00:01.000Z"}],"deleted":["x"],"nextCursor":"c2","serverTime":"2026-10-09T08:15:30.123Z"}"""
        // The overlap sends "b" again with a newer version; "x" comes in again and "b"'s id is also listed as deleted (stale).
        val page2 = """{"contacts":[{"id":"b","name":"B2","phones":[],"tags":[],"updatedAt":"2026-10-09T08:10:00.000Z"}],"deleted":["x","b"],"nextCursor":null,"serverTime":"2026-10-09T08:15:30.123Z"}"""
        val (api, transport) = client(response(200, page1), response(200, page2))

        val result = api.syncContacts(since = "2026-10-09T07:00:00.000Z", pageSize = 2)

        assertEquals(listOf("a", "b"), result.contacts.map { it.id })
        assertEquals(listOf("A", "B2"), result.contacts.map { it.name })
        assertEquals(listOf("x"), result.deleted)
        assertEquals("2026-10-09T08:15:30.123Z", result.serverTime)
        assertFalse(result.isFull)
        assertEquals(2, transport.requests.size)
        assertEquals("c2", query(transport.requests[1])["cursor"])
        assertEquals("2026-10-09T07:00:00.000Z", query(transport.requests[1])["since"])
    }

    @Test
    fun aFullSyncHasNoSinceAndIsMarkedFull() = runTest {
        val (api, transport) = client(fixtureResponse(200, "contacts-page.json"))

        val result = api.syncContacts()

        assertTrue(result.isFull)
        assertEquals(2, result.contacts.size)
        assertFalse(transport.requests[0].url.contains('?'))
    }

    @Test
    fun resyncIsAnError() = runTest {
        val (api, _) = client(fixtureResponse(400, "error-resync.json"))

        try {
            api.syncContacts(since = "2025-01-01T00:00:00.000Z")
            fail("expected resync")
        } catch (error: ApiException.Resync) {
        }
    }

    @Test
    fun aRunThatNeverEndsGivesUp() = runTest {
        val endless = """{"contacts":[],"deleted":[],"nextCursor":"again","serverTime":"t"}"""
        val (api, transport) = client(*Array(5) { response(200, endless) })

        try {
            api.syncContacts(maxPages = 5)
            fail("expected a decoding error")
        } catch (error: ApiException.Decoding) {
        }

        assertEquals(5, transport.requests.size)
    }

    @Test
    fun snapshotSendsIfNoneMatchAndHandles304() = runTest {
        val (api, transport) = client(
            fixtureResponse(200, "contact-list-snapshot.json", mapOf("ETag" to "\"3\"")),
            response(304, "", mapOf("ETag" to "\"3\"")),
        )
        val list = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

        val first = api.contactListSnapshot(list) as ContactListSnapshotOutcome.Snapshot

        assertEquals(2, first.snapshot.contacts.size)
        assertEquals("\"3\"", first.etag)
        assertNull(transport.requests[0].headers["If-None-Match"])

        val second = api.contactListSnapshot(list, etag = first.etag)

        assertEquals(ContactListSnapshotOutcome.NotModified("\"3\""), second)
        assertEquals("\"3\"", transport.requests[1].headers["If-None-Match"])
        assertEquals("$base/contact-lists/$list/contacts", transport.requests[1].url)
    }

    @Test
    fun aSnapshotCursorPageDoesNotSendIfNoneMatch() = runTest {
        val (api, transport) = client(fixtureResponse(200, "contact-list-snapshot.json", mapOf("ETag" to "\"3\"")))

        api.contactListSnapshot("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", cursor = "next", etag = "\"3\"")

        assertNull(transport.requests[0].headers["If-None-Match"])
        assertEquals("next", query(transport.requests[0])["cursor"])
    }

    @Test
    fun a304IsAnErrorWhereNoConditionalGetWasMade() = runTest {
        val (api, _) = client(response(304, ""))

        try {
            api.contactLists()
            fail("expected an error")
        } catch (error: ApiException.UnexpectedStatus) {
            assertEquals(304, error.status)
        }
    }

    @Test
    fun timelinePagesFollowTheCursor() = runTest {
        val (api, transport) = client(fixtureResponse(200, "contact-timeline.json"), fixtureResponse(200, "contact-timeline-last.json"))

        val first = api.contactTimeline(id, limit = 2)
        assertEquals(listOf("e1", "e2"), first.items.map { it.id })
        val cursor = first.nextCursor!!

        val second = api.contactTimeline(id, cursor = cursor, limit = 2)
        assertEquals(listOf("e3"), second.items.map { it.id })
        assertNull(second.nextCursor)

        assertTrue(transport.requests[0].url.startsWith("$base/contacts/$id/timeline?"))
        assertEquals(mapOf("limit" to "2"), query(transport.requests[0]))
        assertEquals(mapOf("limit" to "2", "cursor" to cursor), query(transport.requests[1]))
    }

    @Test
    fun theTimelineLimitIsClampedAndTheIdStaysOneSegment() = runTest {
        val (api, transport) = client(fixtureResponse(200, "contact-timeline-last.json"), fixtureResponse(200, "contact-timeline-last.json"))

        api.contactTimeline(id, limit = 5000)
        api.contactTimeline(id, limit = 0)
        try {
            api.contactTimeline("../admin/x")
            fail("a slash cannot leave the segment")
        } catch (_: ApiException.NotFound) {
        }

        val url = java.net.URI(transport.requests[0].url)
        assertEquals("100", query(transport.requests[0])["limit"])
        assertFalse(url.rawPath.contains("/admin/"))
        assertTrue(url.rawPath.endsWith("/timeline"))
        assertTrue(url.rawPath.startsWith("/api/voip-app/v1/contacts/"))
        assertEquals("1", query(transport.requests[1])["limit"])

        try {
            api.contact("..")
            fail("a dot segment is refused")
        } catch (_: ApiException.NotFound) {
        }
    }

    @Test
    fun anErrorStatusIsAnApiException() = runTest {
        val (api, _) = client(fixtureResponse(404, "error-not-found.json"))

        try {
            api.contactTimeline(id)
            fail("expected an error")
        } catch (_: ApiException.NotFound) {
        }
    }

    @Test
    fun statusCodesMapToErrors() {
        assertTrue(FsVoipApiClient.errorFor(fixtureResponse(403, "error-forbidden.json")) is ApiException.Forbidden)
        assertEquals("limit_reached", (FsVoipApiClient.errorFor(fixtureResponse(409, "error-conflict-limit.json")) as ApiException.Conflict).code)
        val invalid = FsVoipApiClient.errorFor(fixtureResponse(400, "error-invalid-field.json")) as ApiException.Invalid
        assertEquals("invalid_phone", invalid.code)
        assertEquals("phones", invalid.field)
        assertEquals(60, (FsVoipApiClient.errorFor(fixtureResponse(429, "error-rate-limited.json", mapOf("Retry-After" to "60"))) as ApiException.RateLimited).retryAfterSeconds)
    }

    @Test
    fun theClientNeverLogsTokensOrQueries() = runTest {
        val sink = MemoryLogSink()
        val (api, _) = client(fixtureResponse(200, "contacts-page.json"), sink = sink)

        api.contactsPage(since = "2026-10-09T08:15:30.123Z", cursor = "secretcursor")

        val log = sink.messages.joined()
        assertFalse(log.isEmpty())
        assertFalse(log.contains("fss_vapp_"))
        assertFalse(log.contains("secretcursor"))
        assertFalse(log.contains("2026-10-09T08"))
    }

    private fun List<String>.joined() = joinToString("\n")
}
