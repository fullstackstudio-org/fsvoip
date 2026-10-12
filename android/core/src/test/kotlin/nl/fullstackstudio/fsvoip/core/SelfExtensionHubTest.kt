// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun account(id: String = "acc") = StoredAccount(
    id = id, label = "L", pbxName = "P", extensionName = "E", customerName = "C", deviceToken = Secret("t"), installId = "i",
    sip = SipCredentials("102", Secret("p"), "d", "x", 5060, ServerTransport.TCP, true), pairedAtMillis = 0,
)

private fun fixture(version: Int = 4, dnd: Boolean = false): SelfExtension =
    FsJson.default.decodeFromString(SelfExtension.serializer(), Fixtures.text("self-extension.json")).copy(version = version, dnd = dnd)

private class FakeService(var state: SelfExtension) : SelfExtensionServicing {
    val patches = mutableListOf<SelfExtensionPatch>()
    var failWith: Exception? = null
    var staleTimes = 0
    var withoutFreshState = false

    override suspend fun load(account: StoredAccount): SelfExtension = state

    override suspend fun patch(patch: SelfExtensionPatch, account: StoredAccount): SelfExtension? {
        patches += patch
        failWith?.let { throw it }

        if (staleTimes > 0) {
            staleTimes--
            // Someone else changed it: the version moves on and our patch is refused.
            state = state.copy(version = state.version + 1)
            throw ApiException.Conflict("stale", null, state.version)
        }

        state = state.copy(
            version = state.version + 1,
            dnd = patch.dnd ?: state.dnd,
            voicemailEnabled = patch.voicemailEnabled ?: state.voicemailEnabled,
            voicemailToEmail = patch.voicemailToEmail ?: state.voicemailToEmail,
            forwardAlways = when (val change = patch.forwardAlways) {
                Change.Keep -> state.forwardAlways
                Change.Clear -> null
                is Change.Set -> change.value
            },
        )

        return if (withoutFreshState) null else state
    }
}

class SelfExtensionHubTest {
    @Test
    fun availabilityMovesAtOnceAndKeepsTheServerAnswer() = runTest {
        val service = FakeService(fixture())
        val hub = SelfExtensionHub(service)
        assertTrue(hub.load(account()))

        hub.setAvailable(false, account())

        assertTrue(hub.state("acc")!!.dnd)
        assertEquals(5, hub.state("acc")!!.version)
        assertEquals(true, service.patches.single().dnd)
        assertEquals(4, service.patches.single().version)
        assertTrue(hub.saving.value.isEmpty())
    }

    @Test
    fun aStaleAnswerGetsOneRetryOnTheFreshVersion() = runTest {
        val service = FakeService(fixture()).apply { staleTimes = 1 }
        val hub = SelfExtensionHub(service)
        hub.load(account())

        hub.setAvailable(false, account())

        assertEquals(listOf(4, 5), service.patches.map { it.version })
        assertTrue(hub.state("acc")!!.dnd)
        assertNull(hub.failedFor.value)
    }

    @Test
    fun aRefusalGoesBackAndIsReported() = runTest {
        val service = FakeService(fixture()).apply { failWith = ApiException.Transport("Timeout") }
        val hub = SelfExtensionHub(service)
        hub.load(account())

        hub.setAvailable(false, account())

        assertFalse(hub.state("acc")!!.dnd)
        assertEquals("acc", hub.failedFor.value)
        hub.clearFailure()
        assertNull(hub.failedFor.value)
    }

    @Test
    fun saveSendsOnlyWhatChanged() = runTest {
        val service = FakeService(fixture())
        val hub = SelfExtensionHub(service)
        hub.load(account())
        val original = SelfExtensionDraft.of(hub.state("acc")!!)

        assertEquals(SelfExtensionSaveOutcome.Unchanged, hub.save(original, original, account()))
        assertTrue(service.patches.isEmpty())

        val draft = original.copy(forwardAlways = PbxTarget.external("+31612345678"))
        val outcome = hub.save(draft, original, account()) as SelfExtensionSaveOutcome.Saved

        val sent = service.patches.single().toJson()
        assertEquals(setOf("version", "forwardAlways"), sent.keys)
        assertEquals("+31612345678", outcome.state!!.forwardAlways!!.number)
    }

    @Test
    fun aDifferenceOfOnlyWhitespaceIsNoChange() {
        val original = SelfExtensionDraft(email = "jan@voorbeeld.example")

        assertNull(original.copy(email = " jan@voorbeeld.example ").patch(original, 1))
        assertTrue(original.copy(email = "").patch(original, 1)!!.toJson().containsKey("email"))
    }

    @Test
    fun staleTwiceShowsTheFreshStateAndKeepsTheDraft() = runTest {
        val service = FakeService(fixture()).apply { staleTimes = 2 }
        val hub = SelfExtensionHub(service)
        hub.load(account())
        val original = SelfExtensionDraft.of(hub.state("acc")!!)
        val draft = original.copy(voicemailEnabled = !original.voicemailEnabled)

        val outcome = hub.save(draft, original, account())

        assertTrue(outcome is SelfExtensionSaveOutcome.Stale)
        assertEquals(2, service.patches.size)

        // The fields the user did not touch follow the server; the one they changed stays as typed.
        val fresh = (outcome as SelfExtensionSaveOutcome.Stale).fresh.copy(noAnswerSeconds = 40)
        val (rebased, newBaseline) = draft.rebase(fresh, original)
        assertEquals(40, rebased.noAnswerSeconds)
        assertEquals(draft.voicemailEnabled, rebased.voicemailEnabled)
        assertEquals(40, newBaseline.noAnswerSeconds)
    }

    @Test
    fun failuresAreClassified() {
        assertEquals(SelfExtensionFailure.AccessDenied, SelfExtensionFailure.classify(ApiException.Forbidden("admin", null)))
        assertEquals(SelfExtensionFailure.Revoked, SelfExtensionFailure.classify(ApiException.Unauthorized()))
        assertEquals(SelfExtensionFailure.ReadOnly, SelfExtensionFailure.classify(ApiException.Conflict("read_only", null, null)))
        assertEquals(SelfExtensionFailure.Invalid, SelfExtensionFailure.classify(ApiException.InvalidRequest("x", "invalid_email", "email")))
        assertEquals(SelfExtensionFailure.Offline, SelfExtensionFailure.classify(ApiException.Transport("x")))
        assertEquals(SelfExtensionFailure.RateLimited(30), SelfExtensionFailure.classify(ApiException.RateLimited(30)))
    }

    @Test
    fun outboundChoiceOnlyOffersWhatThePbxLists() {
        val state = fixture()
        val on = AppCapabilities(callerChoice = true)

        assertTrue(OutboundChoice.canChoose(on, state))
        assertFalse(OutboundChoice.canChoose(AppCapabilities(callerChoice = false), state))
        assertFalse(OutboundChoice.canChoose(on, state.copy(numbers = state.numbers.take(1))))

        // The default of the extension shows as selected until the user chooses; a stale choice falls back.
        assertEquals("0850607848", OutboundChoice.selected(null, state)!!.number)
        assertEquals("0850607849", OutboundChoice.selected("0850607849", state)!!.number)
        assertEquals("0850607848", OutboundChoice.selected("0201234567", state)!!.number)

        // The header: only for a chosen number, only with the capability, and not for the default (nothing chosen).
        assertEquals("0850607849", OutboundChoice.headerNumber("0850607849", on, state))
        assertNull(OutboundChoice.headerNumber(null, on, state))
        assertNull(OutboundChoice.headerNumber("0850607849", AppCapabilities(), state))
        assertNull(OutboundChoice.headerNumber("0201234567", on, state))
    }
}
