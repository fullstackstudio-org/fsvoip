// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The call experience (park, customer card, notice push) against the shared fixtures. */
class CallExpContractTest {
    private val json = FsJson.default
    private val covered = mutableSetOf<String>()

    private fun fixture(name: String): String {
        covered += "$name.json"
        return Fixtures.text("$name.json")
    }

    /** Runs every test of this class and returns the fixtures it decoded (see [ContractRegistry]). */
    fun coverage(): Set<String> {
        val tests = CallExpContractTest()
        tests.parkRequestAndResponse()
        tests.parkedCallsOfOthersAndOutsideFullStackStudio()
        tests.anUnreadableParkTimeDoesNotLoseTheList()
        tests.parkErrors()
        tests.lookupDecodesAndMapsToTheCallerContext()
        tests.anUnknownCallerHasNoContext()
        tests.noticeFixtures()

        return tests.covered
    }

    @Test
    fun parkRequestAndResponse() {
        val request = ParkRequest("3b9c1f0e2a7d4c58@192.0.2.10")
        assertEquals(Fixtures.json("park-request.json"), json.encodeToJsonElement(ParkRequest.serializer(), request))
        covered += "park-request.json"

        val call = json.decodeFromString(ParkedCall.serializer(), fixture("park-response"))
        assertEquals(1, call.slot)
        assertEquals("*5901", call.retrieveNumber)
        assertEquals("Bakkerij Smit", call.callerName)
        assertEquals(Instant.parse("2026-10-09T08:15:30Z"), call.parkedAt)
        assertEquals(Instant.parse("2026-10-09T08:20:30Z"), call.expiresAt)
        assertEquals(ParkedBy("3f0c2b1e-8a4d-4d6f-9b7a-1c2d3e4f5a6b", "Jan (balie)"), call.parkedBy)
        assertTrue(call.mine)
    }

    @Test
    fun parkedCallsOfOthersAndOutsideFullStackStudio() {
        val page = json.decodeFromString(ParkedCallsPage.serializer(), fixture("parked-calls"))
        assertTrue(page.available)
        assertEquals(listOf(1, 2, 3), page.calls.map { it.slot })
        assertNull("parked from another phone", page.calls[1].parkedBy?.deviceId)
        assertEquals("Receptie", page.calls[1].parkedBy?.name)
        assertNull(page.calls[1].callerNumber)
        assertFalse(page.calls[1].mine)
        assertNull("parked outside FullStack Studio", page.calls[2].parkedBy)
        assertNull(page.calls[2].parkedAt)
        assertNull(page.calls[2].retrieveNumber)

        val unavailable = json.decodeFromString(ParkedCallsPage.serializer(), fixture("parked-calls-unavailable"))
        assertFalse(unavailable.available)
        assertTrue(unavailable.calls.isEmpty())
    }

    @Test
    fun anUnreadableParkTimeDoesNotLoseTheList() {
        val text = """{"available":true,"calls":[{"id":"x","slot":4,"parkedAt":"gisteren","expiresAt":12,"parkedBy":"niemand","mine":false}]}"""
        val page = json.decodeFromString(ParkedCallsPage.serializer(), text)

        assertEquals(1, page.calls.size)
        assertNull(page.calls[0].parkedAt)
        assertNull(page.calls[0].expiresAt)
        assertNull(page.calls[0].parkedBy)
        assertEquals(4, page.calls[0].slot)

        // A missing flag is the restrictive choice.
        assertFalse(json.decodeFromString(ParkedCallsPage.serializer(), """{"calls":[]}""").available)
    }

    @Test
    fun parkErrors() {
        assertTrue(FsVoipApiClient.errorFor(response(404, fixture("error-call-not-found"))) is ApiException.CallNotFound)
        assertTrue(FsVoipApiClient.errorFor(response(409, fixture("error-no-free-slot"))) is ApiException.NoFreeSlot)
        assertTrue(FsVoipApiClient.errorFor(response(409, fixture("error-park-unavailable"))) is ApiException.ParkUnavailable)
        assertTrue(FsVoipApiClient.errorFor(response(503, fixture("error-park-uncertain"))) is ApiException.ParkUncertain)
        // The name wins over the status: the same names come with another status on another route.
        assertTrue(FsVoipApiClient.errorFor(response(500, """{"error":"park_uncertain"}""")) is ApiException.ParkUncertain)
    }

    @Test
    fun lookupDecodesAndMapsToTheCallerContext() {
        val lookup = json.decodeFromString(CallerLookup.serializer(), fixture("contact-lookup"))

        assertEquals("Bakkerij Smit", lookup.contact?.name)
        assertEquals(CallerCounters(openRequests = 1, openOrders = 2), lookup.counters)
        assertEquals(3, lookup.timeline.size)
        assertEquals("W-1042", lookup.timeline[0].ref)
        assertNotNull(lookup.timeline[0].occurredAtInstant)
        assertNull(lookup.timeline[2].summary)

        val context = CallerContext.from(lookup)!!
        assertEquals("6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c", context.contactId)
        assertEquals("Bakkerij Smit", context.displayName)
        assertEquals(2, context.openOrders)
        assertEquals(1, context.openRequests)
    }

    @Test
    fun anUnknownCallerHasNoContext() {
        val lookup = json.decodeFromString(CallerLookup.serializer(), fixture("contact-lookup-unknown"))

        assertNull(lookup.contact)
        assertNull(CallerContext.from(lookup))
    }

    @Test
    fun noticeFixtures() {
        val bare = PushMessage.decode(fixture("push-notice")) as PushMessage.Notice
        assertEquals("Nieuwe bestelling", bare.notice.title)
        assertEquals("3f0c2b1e-8a4d-4d6f-9b7a-1c2d3e4f5a6b", bare.notice.accountId)
        assertEquals("Er is een nieuwe bestelling binnengekomen.", bare.notice.body)
        assertTrue(bare.notice.href.contains("/orders/"))

        // The APNs envelope carries the same object under `fsvoip`; FCM carries it as a JSON string in `data.fsvoip`.
        val envelope = Fixtures.json("apns-notice-body.json").jsonObject
        covered += "apns-notice-body.json"
        assertEquals(bare, PushMessage.decode(envelope["fsvoip"]!!.jsonObject))
        assertEquals("Nieuwe bestelling", envelope["aps"]!!.jsonObject["alert"]!!.jsonObject["title"]!!.jsonPrimitive.contentOrNull)

        val fcm = PushMessage.decodeFcm(mapOf("fsvoip" to Fixtures.text("push-notice.json")))
        assertEquals(bare, fcm)
    }
}
