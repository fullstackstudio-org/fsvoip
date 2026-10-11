// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The customer card, parking routes, caller choice and notice routes beyond the fixtures. */
class CallExpTest {
    private val json = FsJson.default
    private val sink = MemoryLogSink()
    private val logger = FsLogger("api", sink)

    private fun client(transport: FakeTransport) =
        FsVoipApiClient("https://example.test/api/voip-app/v1", transport = transport, logger = logger).authenticated(Secret("fss_vapp_t"))

    // MARK: Customer card

    @Test
    fun anAmbiguousNumberIsNeverGuessed() {
        val lookup = json.decodeFromString(CallerLookup.serializer(), Fixtures.text("contact-lookup.json")).copy(ambiguous = true)

        assertNull(CallerContext.from(lookup))
    }

    @Test
    fun theLookupToleratesMissingAndNegativeFields() {
        val lookup = json.decodeFromString(CallerLookup.serializer(), """{"contact":null,"counters":{"openRequests":-3},"extra":"ignored"}""")

        assertEquals(CallerCounters(0, 0), lookup.counters)
        assertEquals(emptyList<TimelineItem>(), lookup.timeline)
        assertFalse(lookup.ambiguous)

        val odd = json.decodeFromString(CallerLookup.serializer(), """{"counters":{"openRequests":"veel","openOrders":4}}""")
        assertEquals(CallerCounters(0, 4), odd.counters)
    }

    @Test
    fun aCompanyOnlyContactUsesTheCompanyAsTheName() {
        val lookup = json.decodeFromString(CallerLookup.serializer(), Fixtures.text("contact-lookup.json"))
        val blank = lookup.copy(contact = lookup.contact!!.copy(name = "  "))

        assertEquals("Bakkerij Smit", CallerContext.from(blank)?.displayName)
    }

    @Test
    fun lookupRouteSendsTheNumberAsAQueryItem() = runTest {
        val transport = FakeTransport { response(200, Fixtures.text("contact-lookup.json")) }

        val lookup = client(transport).callerLookup("+31 70 123 4567&x=1")

        assertNotNull(lookup.contact)
        val request = transport.requests.single()
        assertEquals("GET", request.method)
        val url = java.net.URI(request.url)
        assertEquals("/api/voip-app/v1/contacts/lookup", url.rawPath)
        assertEquals("+31 70 123 4567&x=1", okhttp3.HttpUrl.Companion.run { request.url.toHttpUrl() }.queryParameter("number"))
        assertEquals("Bearer fss_vapp_t", request.headers["Authorization"])
        assertEquals(3, request.timeoutSeconds)
    }

    @Test
    fun theTimelineLimitIsClampedAndTheIdStaysOneSegment() = runTest {
        val transport = FakeTransport { response(200, """{"items":[],"nextCursor":null}""") }
        val api = client(transport)

        api.contactTimeline("6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c", limit = 5000, cursor = "c 1")
        val request = transport.requests.single()
        val parsed = okhttp3.HttpUrl.Companion.run { request.url.toHttpUrl() }
        assertEquals("100", parsed.queryParameter("limit"))
        assertEquals("c 1", parsed.queryParameter("cursor"))
        assertTrue(parsed.encodedPath.endsWith("/contacts/6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c/timeline"))

        for (bad in listOf("../admin/x", "a/b", "", "..")) {
            try {
                api.contactTimeline(bad)
                fail(bad)
            } catch (_: ApiException.NotFound) {
            }
        }

        assertEquals("no request for a hostile id", 1, transport.requests.size)
    }

    @Test
    fun theTimeoutGivesUpOnASlowLookup() = runTest {
        val started = System.nanoTime()
        val result = CallerLookupTimeout.run<Int>(100) {
            delay(5_000)
            1
        }

        assertNull(result)
        assertTrue("returns at the deadline", (System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test
    fun theTimeoutDoesNotWaitForABlockingCall() {
        val started = System.nanoTime()
        val result = kotlinx.coroutines.runBlocking {
            CallerLookupTimeout.run<Int>(100) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Thread.sleep(1_500)
                    1
                }
            }
        }

        assertNull(result)
        assertTrue("a call that cannot be cancelled never holds the caller back", (System.nanoTime() - started) / 1_000_000 < 1_000)
    }

    @Test
    fun theTimeoutPassesAFastAnswerAndSwallowsErrors() = runTest {
        assertEquals(7, CallerLookupTimeout.run(2_000) { 7 })
        assertNull(CallerLookupTimeout.run<Int>(2_000) { throw IllegalStateException("boom") })
        assertEquals(1_000L, CallerLookupTimeout.INCOMING_CALL_MILLIS)
    }

    @Test
    fun aNoticeNeedsAnHref() {
        expectMalformed("""{"v":1,"type":"notice","accountId":"a","title":"t","body":"b"}""")
    }

    @Test
    fun anUnknownPushTypeStillThrowsSoItIsNotMistakenForANotice() {
        try {
            PushMessage.decode("""{"v":1,"type":"surprise","accountId":"a"}""")
            fail()
        } catch (error: PushMessageException.UnknownType) {
            assertEquals("surprise", error.type)
        }
    }

    private fun expectMalformed(text: String) {
        try {
            PushMessage.decode(text)
            fail()
        } catch (_: PushMessageException.Malformed) {
        }
    }

    // MARK: Parking routes

    @Test
    fun parkingPostsTheCallIdAndHangingUpDeletesOneSegment() = runTest {
        val transport = FakeTransport { request ->
            if (request.method == "POST") response(201, Fixtures.text("park-response.json")) else response(200, """{"ok":true}""")
        }
        val api = client(transport)

        assertEquals(1, api.parkCall("sip-id@10.0.0.1").slot)
        val post = transport.requests[0]
        assertEquals("POST", post.method)
        assertEquals("/api/voip-app/v1/calls/park", java.net.URI(post.url).rawPath)
        assertEquals("""{"callId":"sip-id@10.0.0.1"}""", post.body!!.toString(Charsets.UTF_8))

        api.hangupParkedCall("Zm9v-YmFy_x")
        assertEquals("DELETE", transport.requests[1].method)
        assertTrue(transport.requests[1].url.endsWith("/parked/Zm9v-YmFy_x"))

        try {
            api.hangupParkedCall("../me")
            fail()
        } catch (_: ApiException.NotFound) {
        }
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun parkFailuresMapToTheUsersTerms() {
        assertEquals(ParkFailure.CallNotFound, ParkFailure.classify(ApiException.CallNotFound()))
        assertEquals(ParkFailure.Unavailable, ParkFailure.classify(ApiException.ParkUnavailable()))
        assertEquals(ParkFailure.NoFreeSlot, ParkFailure.classify(ApiException.Conflict("park_busy")))
        assertEquals(ParkFailure.NoFreeSlot, ParkFailure.classify(ApiException.NoFreeSlot()))
        assertEquals(ParkFailure.Uncertain, ParkFailure.classify(ApiException.ParkUncertain()))
        assertEquals(ParkFailure.ReadOnly, ParkFailure.classify(ApiException.ReadOnly()))
        assertEquals(ParkFailure.ReadOnly, ParkFailure.classify(ApiException.Conflict("read_only")))
        assertEquals(ParkFailure.Forbidden, ParkFailure.classify(ApiException.Forbidden()))
        assertEquals(ParkFailure.NotFound, ParkFailure.classify(ApiException.NotFound()))
        assertEquals(ParkFailure.Revoked, ParkFailure.classify(ApiException.Unauthorized()))
        assertEquals(ParkFailure.RateLimited(12), ParkFailure.classify(ApiException.RateLimited(12)))
        assertEquals(ParkFailure.Offline, ParkFailure.classify(ApiException.Transport("x")))
        assertEquals(ParkFailure.Other, ParkFailure.classify(IllegalStateException()))
    }

    @Test
    fun aTimeOutOrAGatewayErrorLeavesTheOutcomeOpen() {
        assertTrue(ParkFailure.outcomeIsUncertain(ApiException.Transport("timed out")))
        assertTrue(ParkFailure.outcomeIsUncertain(ApiException.UnexpectedStatus(502)))
        assertTrue(ParkFailure.outcomeIsUncertain(ApiException.UnexpectedStatus(504)))
        assertTrue(ParkFailure.outcomeIsUncertain(ApiException.Unavailable(true, null)))
        assertTrue(ParkFailure.outcomeIsUncertain(kotlinx.coroutines.CancellationException()))
        assertFalse(ParkFailure.outcomeIsUncertain(ApiException.CallNotFound()))
        assertFalse(ParkFailure.outcomeIsUncertain(ApiException.NoFreeSlot()))
    }

    // MARK: Caller choice

    private val numbers = listOf(OutboundNumber("1", "0850607848", "Hoofdnummer", true), OutboundNumber("2", "0850607849"))
    private val callerChoice = AppCapabilities(callerChoice = true)

    @Test
    fun callerChoiceHeader() {
        assertEquals("X-FSS-From", CallerChoice.HEADER_NAME)
        assertEquals(mapOf("X-FSS-From" to "0850607849"), CallerChoice.headers("0850607849", callerChoice, numbers))
        // Without the capability (or with an unknown one) nothing is sent.
        assertEquals(emptyMap<String, String>(), CallerChoice.headers("0850607849", AppCapabilities(callerChoice = false), numbers))
        assertEquals(emptyMap<String, String>(), CallerChoice.headers("0850607849", null, numbers))
        // Only a number the PBX lists.
        assertEquals(emptyMap<String, String>(), CallerChoice.headers("0851111111", callerChoice, numbers))
        assertEquals(emptyMap<String, String>(), CallerChoice.headers("+31850607849", callerChoice, numbers))
        assertEquals(emptyMap<String, String>(), CallerChoice.headers(null, callerChoice, numbers))
    }

    @Test
    fun callerChoiceNumberFormat() {
        assertTrue(CallerChoice.isValidNumber("0850607848"))
        assertTrue(CallerChoice.isValidNumber("0201234567"))
        assertFalse(CallerChoice.isValidNumber("085060784"))
        assertFalse(CallerChoice.isValidNumber("08506078489"))
        assertFalse(CallerChoice.isValidNumber("0050607848"))
        assertFalse(CallerChoice.isValidNumber("1850607848"))
        assertFalse(CallerChoice.isValidNumber("085060784x"))
    }

    @Test
    fun theChoiceNeverTouchesAnEmergencyNumber() {
        assertFalse(CallerChoice.appliesTo("112"))
        assertFalse(CallerChoice.appliesTo(" 112 "))
        assertTrue(CallerChoice.appliesTo("0612345678"))
    }

    @Test
    fun theOutboundNumbersReadOnlyTheNumbersOfTheOwnExtension() {
        val parsed = json.decodeFromString(OutboundNumbers.serializer(), Fixtures.text("self-extension.json"))

        assertTrue(parsed.numbers.size >= 2)
        assertEquals("0850607848", parsed.numbers.first().number)
        assertTrue(parsed.numbers.first().isDefault)
        assertNotNull(parsed.defaultNumber)
    }

    // MARK: Notice routes

    private val pbx = "99999999-8888-4777-8666-555555555555"
    private val project = "11111111-2222-4333-8444-555555555555"

    @Test
    fun knownPortalPathsMapToTheirScreens() {
        assertEquals(NoticeDestination.RECENTS, NoticeRoute.destination("https://fullstackstudio.nl/portal/voip/$pbx/calls"))
        assertEquals(NoticeDestination.VOICEMAIL, NoticeRoute.destination("https://fullstackstudio.nl/portal/voip/$pbx/voicemail"))
        assertEquals(NoticeDestination.CONTACTS, NoticeRoute.destination("https://fullstackstudio.nl/portal/contacts"))
        assertEquals(NoticeDestination.CONTACTS, NoticeRoute.destination("https://fullstackstudio.nl/portal/contacts/duplicates"))
        assertEquals(NoticeDestination.RECENTS, NoticeRoute.destination("/portal/voip/$pbx/calls?month=2026-10#top"))
    }

    @Test
    fun ticketsHaveNoScreenInTheAppAndOpenHome() {
        for (path in listOf(
            "/portal/content/$project/tickets/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
            "/portal/content/$project/orders",
            "/portal/billing/abc",
            "/portal",
            "/portal/voip",
            "/portal/voip/$pbx",
            "/portal/voip/$pbx/numbers",
        )) {
            assertEquals(path, NoticeDestination.HOME, NoticeRoute.destination("https://fullstackstudio.nl$path"))
        }
    }

    @Test
    fun anythingUnreadableOrOutsideThePortalOpensHome() {
        for (href in listOf("", "  ", "not a url", "ftp://x/portal/contacts", "mailto:a@b.nl", "https://fullstackstudio.nl/admin/contacts", "https://fullstackstudio.nl/", "javascript:alert(1)")) {
            assertEquals(href, NoticeDestination.HOME, NoticeRoute.destination(href))
        }
    }

    @Test
    fun traversalAndEncodedSlashesNeverMatch() {
        assertEquals(NoticeDestination.HOME, NoticeRoute.destination("https://fullstackstudio.nl/portal/../admin/contacts"))
        assertEquals(NoticeDestination.HOME, NoticeRoute.destination("https://fullstackstudio.nl/portal/voip/$pbx%2Fcalls"))
        assertEquals(NoticeDestination.HOME, NoticeRoute.destination("https://fullstackstudio.nl/portal/voip/$pbx/calls\u0000"))
        assertEquals(NoticeDestination.HOME, NoticeRoute.destination("a".repeat(600)))
    }

    @Test
    fun theHostIsIgnoredSoALookalikeHostCannotRedirectAnything() {
        // The app never opens the link; it only maps the path.
        assertEquals(NoticeDestination.CONTACTS, NoticeRoute.destination("https://evil.example/portal/contacts"))
    }
}
