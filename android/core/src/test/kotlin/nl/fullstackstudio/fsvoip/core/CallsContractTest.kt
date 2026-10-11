// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decodes the calls and voicemail fixtures of `shared/fixtures` (the same files the iOS contract tests decode). */
class CallsContractTest {
    private val json = FsJson.default
    private val covered = mutableSetOf<String>()

    private fun fixture(name: String): String {
        covered += name

        return Fixtures.text(name)
    }

    /** Runs the tests of this class and reports the fixtures they used (see [ContractRegistry]). */
    fun coverage(): Set<String> {
        callsPage()
        callsPageTeam()
        callsPageOfAUser()
        unknownCallValues()
        voicemailPages()
        goneError()

        return covered.toSet()
    }

    @Test
    fun callsPage() {
        val page = json.decodeFromString(CallsPage.serializer(), fixture("calls-page.json"))

        assertEquals("2026-10", page.month)
        assertEquals(listOf("2026-10", "2026-09"), page.months)
        assertFalse(page.truncated)
        assertEquals(4, page.calls.size)

        val inbound = page.calls[0]
        assertEquals(CallDirection.INBOUND, inbound.direction)
        assertEquals(CallOutcome.ANSWERED, inbound.outcome)
        assertEquals("070 123 45 67", inbound.number)
        assertEquals("102", inbound.extensionNumber)
        assertEquals("Jan de Vries", inbound.extensionName)
        assertEquals(CallLineType.FIXED, inbound.lineType)
        assertEquals(CallDestinationCategory.FIXED, inbound.category)
        assertEquals(125, inbound.durationSeconds)
        assertTrue(inbound.hasRecording)
        assertFalse(inbound.recordingExpired)
        assertEquals(CallLineType.MOBILE, page.calls[1].lineType)

        val expired = page.calls[2]
        assertEquals(CallOutcome.VOICEMAIL, expired.outcome)
        assertNull(expired.number)
        assertTrue(expired.recordingExpired)
        assertFalse(expired.hasRecording)
        assertEquals(CallDirection.INTERNAL, page.calls[3].direction)
        assertNull(page.calls[3].durationSeconds)
    }

    @Test
    fun callsPageTeam() {
        val page = json.decodeFromString(CallsPage.serializer(), fixture("calls-page-team.json"))

        assertEquals(3, page.calls.size)
        assertEquals(listOf("Pieter Jansen", "Receptie", "Jan de Vries"), page.calls.map { it.extensionName })
        assertTrue(page.calls.none { it.hasRecording })
        // The field is absent for a team page.
        assertFalse(page.calls.any { it.recordingExpired })
        assertEquals(CallOutcome.MISSED, page.calls[2].outcome)
        assertEquals("0850607849", page.calls[2].ourNumber)
    }

    @Test
    fun callsPageOfAUser() {
        val page = json.decodeFromString(CallsPage.serializer(), fixture("calls-page-user.json"))

        assertTrue(page.truncated)
        assertEquals(1, page.calls.size)
        assertFalse(page.calls[0].hasRecording)
        assertFalse(page.calls[0].recordingExpired)
    }

    @Test
    fun unknownCallValues() {
        val source = json.parseToJsonElement(fixture("calls-page.json")).jsonObject
        val calls = source["calls"]!!.jsonArray
        val changed = JsonObject(
            calls[0].jsonObject + mapOf(
                "direction" to JsonPrimitive("sidewards"),
                "outcome" to JsonPrimitive("teleported"),
                "category" to JsonPrimitive("premium_plus"),
                "lineType" to JsonPrimitive("voip"),
                "aFutureField" to JsonPrimitive(1),
            ),
        )
        val page = json.decodeFromString(CallsPage.serializer(), JsonObject(source + ("calls" to JsonArray(listOf(changed)))).toString())

        assertEquals(CallDirection.UNKNOWN, page.calls[0].direction)
        assertEquals(CallOutcome.UNKNOWN, page.calls[0].outcome)
        assertEquals(CallDestinationCategory.OTHER, page.calls[0].category)
        assertEquals(CallLineType.UNKNOWN, page.calls[0].lineType)

        // The server's own `unknown` category is a value of its own.
        val named = json.decodeFromString(CallDestinationCategory.serializer(), "\"unknown\"")
        assertEquals(CallDestinationCategory.UNKNOWN_DESTINATION, named)
    }

    @Test
    fun voicemailPages() {
        val page = json.decodeFromString(VoicemailPage.serializer(), fixture("voicemail-page.json"))

        assertTrue(page.available)
        assertNull(page.boxId)
        assertEquals(listOf(true, false), page.boxes.map { it.shared })
        assertEquals(2, page.messages.size)
        assertEquals("070 123 45 67", page.messages[0].caller)
        assertEquals("Bakkerij Smit", page.messages[0].callerName)
        assertTrue(page.messages[0].isNew)
        assertEquals(29, page.messages[0].daysLeft)
        assertEquals("v1.Zm9vYmFyYmF6cXV4", page.messages[0].ref)
        assertEquals("7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57/v1.Zm9vYmFyYmF6cXV4", page.messages[0].id)
        assertNull(page.messages[1].caller)
        assertEquals("Bel mij even terug.", page.messages[1].transcription)
        assertFalse(page.messages[1].isNew)

        val unavailable = json.decodeFromString(VoicemailPage.serializer(), fixture("voicemail-page-unavailable.json"))
        assertFalse(unavailable.available)
        assertTrue(unavailable.messages.isEmpty())
        assertEquals("7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57", unavailable.boxId)

        // A page without the flag counts as available (an older server); one without lists is empty, never an error.
        val bare = json.decodeFromString(VoicemailPage.serializer(), "{}")
        assertTrue(bare.available)
        assertTrue(bare.boxes.isEmpty() && bare.messages.isEmpty())
    }

    @Test
    fun goneError() {
        val body = json.decodeFromString(ApiErrorBody.serializer(), fixture("error-gone.json"))
        assertEquals("gone", body.error)

        val error = FsVoipApiClient.errorFor(response(410, Fixtures.text("error-gone.json")))
        assertTrue(error is ApiException.Gone)
        assertEquals(MediaFailure.Gone, MediaFailure.classify(error))

        assertEquals("gone", error.message)
    }

    @Test
    fun noPageEverCarriesARecordingForAUser() {
        // The server never sends `hasRecording: true` to a user pairing; the fixtures show it.
        for (name in listOf("calls-page-user.json", "calls-page-team.json")) {
            val page = json.decodeFromString(CallsPage.serializer(), fixture(name))
            assertTrue(name, page.calls.none { it.hasRecording })
        }
    }
}
