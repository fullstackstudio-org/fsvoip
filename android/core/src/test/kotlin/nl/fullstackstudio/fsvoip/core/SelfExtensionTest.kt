// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun state(version: Int = 4, edit: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit = {}): SelfExtension {
    val map = Fixtures.json("self-extension.json").jsonObject.toMutableMap()
    map["version"] = JsonPrimitive(version)
    edit(map)

    return FsJson.default.decodeFromJsonElement(SelfExtension.serializer(), JsonObject(map))
}

private fun patchJson(patch: SelfExtensionPatch) = patch.toJson().toString()

private class FakeSelfService : SelfExtensionServicing, InviteServicing {
    val patches = mutableListOf<SelfExtensionPatch>()
    var loads = 0
    val invitedDevices = mutableListOf<String>()

    /** What `load` returns, in order (the last one repeats). */
    var loadStates: List<SelfExtension> = listOf(state())

    /** Errors for the next patches, in order. */
    val patchErrors = ArrayDeque<Exception>()

    /** Null = the server could not read the fresh state. */
    var patchAnswer: (SelfExtensionPatch) -> SelfExtension? = { state(version = it.version + 1) }
    var inviteResult: () -> AppPairingResponse = { FsJson.default.decodeFromString(AppPairingResponse.serializer(), Fixtures.text("app-pairing-response.json")) }
    var devices: List<InviteDevice> = FsJson.default.decodeFromString(InviteDevicesPage.serializer(), Fixtures.text("pbx-devices.json")).devices

    override suspend fun load(accountId: String): SelfExtension {
        loads++

        return loadStates[minOf(loads - 1, loadStates.size - 1)]
    }

    override suspend fun patch(patch: SelfExtensionPatch, accountId: String): SelfExtension? {
        patches += patch
        patchErrors.removeFirstOrNull()?.let { throw it }

        return patchAnswer(patch)
    }

    override suspend fun invite(deviceId: String, accountId: String): AppPairingResponse {
        invitedDevices += deviceId

        return inviteResult()
    }

    override suspend fun devices(accountId: String): List<InviteDevice> = devices
}

class SelfExtensionDraftTest {
    @Test
    fun onlyChangedFieldsAndTheVersionGoToTheServer() {
        val original = SelfExtensionDraft(state())
        var draft = original.copy(email = "nieuw@voorbeeld-bouw.example")

        assertEquals("""{"version":4,"email":"nieuw@voorbeeld-bouw.example"}""", patchJson(draft.patch(original, 4)!!))

        draft = original.copy(voicemailToEmail = false, noAnswerSeconds = 40, forwardAlways = PbxTarget.external("+31612345678"))

        assertEquals(
            """{"version":7,"forwardAlways":{"type":"external","number":"+31612345678"},"noAnswerSeconds":40,"voicemailToEmail":false}""",
            patchJson(draft.patch(original, 7)!!),
        )
    }

    @Test
    fun nothingChangedSendsNothing() {
        val original = SelfExtensionDraft(state())

        assertNull(original.patch(original, 4))

        // Trimming is not a change.
        assertNull(original.copy(email = " " + original.email + "  ").patch(original, 4))
    }

    @Test
    fun clearingSendsExplicitNullAndNeverDoNotDisturb() {
        val original = SelfExtensionDraft(state { it["forwardAlways"] = Fixtures.json("self-extension-patch-request.json").jsonObject["forwardAlways"]!! })
        val draft = original.copy(forwardAlways = null, noAnswerTarget = null, email = "  ")

        val text = patchJson(draft.patch(original, 4)!!)

        assertEquals("""{"version":4,"forwardAlways":null,"noAnswerTarget":null,"email":null}""", text)
        assertFalse(text.contains("dnd"))
    }

    @Test
    fun rebaseFollowsTheServerOnlyWhereTheUserDidNotTouch() {
        val original = SelfExtensionDraft(state())
        val draft = original.copy(email = "typed@voorbeeld-bouw.example")
        val fresh = state(version = 9) {
            it["noAnswerSeconds"] = JsonPrimitive(60)
            it["email"] = JsonPrimitive("elders@voorbeeld-bouw.example")
        }

        val result = draft.rebased(fresh, original)

        assertEquals("what the user typed stays", "typed@voorbeeld-bouw.example", result.draft.email)
        assertEquals("untouched fields follow the server", 60, result.draft.noAnswerSeconds)
        assertEquals("elders@voorbeeld-bouw.example", result.baseline.email)
    }
}

class SelfExtensionHubTest {
    private fun hub(service: FakeSelfService) = SelfExtensionHub(service, service)

    private suspend fun loaded(service: FakeSelfService): SelfExtensionHub = hub(service).also { it.load("a") }

    @Test
    fun savingSendsOnlyTheChangeWithTheLoadedVersion() = runTest {
        val service = FakeSelfService()
        val hub = loaded(service)
        val original = SelfExtensionDraft(hub.state("a")!!)

        val outcome = hub.save(original.copy(voicemailEnabled = false), original, "a")

        outcome as SelfExtensionHub.SaveOutcome.Saved
        assertEquals(5, outcome.state?.version)
        assertEquals(1, service.patches.size)
        assertEquals("""{"version":4,"voicemailEnabled":false}""", patchJson(service.patches[0]))
        assertEquals(5, hub.state("a")?.version)
    }

    @Test
    fun nothingToSaveSendsNothing() = runTest {
        val service = FakeSelfService()
        val hub = loaded(service)
        val original = SelfExtensionDraft(hub.state("a")!!)

        assertEquals(SelfExtensionHub.SaveOutcome.Unchanged, hub.save(original, original, "a"))
        assertTrue(service.patches.isEmpty())
    }

    @Test
    fun staleGetsOneRetryOnTheFreshVersionWithOnlyTheTouchedFields() = runTest {
        val service = FakeSelfService()
        service.loadStates = listOf(state(version = 4), state(version = 8) { it["noAnswerSeconds"] = JsonPrimitive(60) })
        service.patchErrors += ApiException.Stale(8)
        val hub = loaded(service)
        val original = SelfExtensionDraft(hub.state("a")!!)

        val outcome = hub.save(original.copy(email = "typed@voorbeeld-bouw.example"), original, "a")

        assertTrue(outcome.toString(), outcome is SelfExtensionHub.SaveOutcome.Saved)
        assertEquals(listOf(4, 8), service.patches.map { it.version })
        assertEquals(
            "the change of the other phone (noAnswerSeconds) is not overwritten",
            """{"version":8,"email":"typed@voorbeeld-bouw.example"}""",
            patchJson(service.patches[1]),
        )
    }

    @Test
    fun staleTwiceShowsTheFreshStateAndKeepsTheInput() = runTest {
        val service = FakeSelfService()
        service.loadStates = listOf(state(version = 4), state(version = 8), state(version = 9) { it["noAnswerSeconds"] = JsonPrimitive(60) })
        service.patchErrors += listOf(ApiException.Stale(8), ApiException.Stale(9))
        val hub = loaded(service)
        val original = SelfExtensionDraft(hub.state("a")!!)
        val typed = original.copy(email = "typed@voorbeeld-bouw.example")

        val outcome = hub.save(typed, original, "a")

        outcome as SelfExtensionHub.SaveOutcome.Stale
        assertEquals(9, outcome.state.version)
        assertEquals("one retry, not more", 2, service.patches.size)

        val rebased = typed.rebased(outcome.state, original)
        assertEquals("nothing typed is lost", "typed@voorbeeld-bouw.example", rebased.draft.email)
        assertEquals("the rest follows the server", 60, rebased.draft.noAnswerSeconds)
        assertNotNull(rebased.draft.patch(rebased.baseline, 9))
    }

    @Test
    fun aFieldTheUserMayNotChangeIsRefusedAndTheStateStays() = runTest {
        val service = FakeSelfService()
        service.patchErrors += ApiException.Forbidden("voicemailToEmail")
        val hub = loaded(service)
        val original = SelfExtensionDraft(hub.state("a")!!)

        val outcome = hub.save(original.copy(voicemailToEmail = false), original, "a")

        assertEquals(SelfExtensionHub.SaveOutcome.Failed(SelfExtensionFailure.AccessDenied), outcome)
        assertEquals(4, hub.state("a")?.version)
    }

    @Test
    fun failuresAreSortedIntoTheUsersTerms() {
        assertEquals(SelfExtensionFailure.Revoked, SelfExtensionFailure.classify(ApiException.Unauthorized()))
        assertEquals(SelfExtensionFailure.Invalid, SelfExtensionFailure.classify(ApiException.Invalid("invalid_request", "email")))
        assertEquals(SelfExtensionFailure.RateLimited(120), SelfExtensionFailure.classify(ApiException.RateLimited(120)))
        assertEquals(SelfExtensionFailure.Offline, SelfExtensionFailure.classify(ApiException.Transport("x")))
        assertEquals(SelfExtensionFailure.Conflict, SelfExtensionFailure.classify(ApiException.Conflict("conflict")))
        assertEquals(SelfExtensionFailure.OwnDevice, SelfExtensionFailure.classify(ApiException.Conflict("conflict"), forInvite = true))
        assertEquals(SelfExtensionFailure.ReadOnly, SelfExtensionFailure.classify(ApiException.Conflict("read_only")))
        assertEquals(SelfExtensionFailure.AccessDenied, SelfExtensionFailure.classify(ApiException.Forbidden("email")))
    }

    @Test
    fun anUnreadableAnswerAsksForTheFreshState() = runTest {
        val service = FakeSelfService()
        service.loadStates = listOf(state(version = 4), state(version = 5) { it["voicemailEnabled"] = JsonPrimitive(false) })
        service.patchAnswer = { null }
        val hub = loaded(service)
        val original = SelfExtensionDraft(hub.state("a")!!)

        val outcome = hub.save(original.copy(voicemailEnabled = false), original, "a")

        outcome as SelfExtensionHub.SaveOutcome.Saved
        assertEquals(5, outcome.state?.version)
        assertEquals(false, hub.state("a")?.voicemailEnabled)
    }

    @Test
    fun theLoadedStateOfOneAccountIsNotTheOtherOnes() = runTest {
        val service = FakeSelfService()
        val hub = loaded(service)

        assertNull(hub.state("b"))
        hub.forget("a")
        assertNull(hub.state("a"))
    }
}

class AvailabilityHubTest {
    private class FakeAvailability : AvailabilityServicing {
        var state = AvailabilityHub.State(false, 4)
        val errors = ArrayDeque<Exception>()
        val calls = mutableListOf<Pair<Boolean, Int>>()

        override suspend fun loadAvailability(accountId: String) = state

        override suspend fun setDoNotDisturb(dnd: Boolean, version: Int, accountId: String): AvailabilityHub.State {
            calls += dnd to version
            errors.removeFirstOrNull()?.let { throw it }
            state = AvailabilityHub.State(dnd, version + 1)

            return state
        }
    }

    @Test
    fun switchingOffMeansDoNotDisturbAndTheVersionMovesOn() = runTest {
        val service = FakeAvailability()
        val hub = AvailabilityHub(service)
        hub.load("a")
        assertEquals(true, hub.isAvailable("a"))

        hub.setAvailable(false, "a")

        assertEquals(false, hub.isAvailable("a"))
        assertEquals(5, hub.state("a")?.version)
        assertEquals(listOf(true to 4), service.calls)
    }

    @Test
    fun aRefusedChangeGoesBackAndIsReported() = runTest {
        val service = FakeAvailability()
        service.errors += ApiException.Transport("offline")
        val hub = AvailabilityHub(service)
        hub.load("a")

        hub.setAvailable(false, "a")

        assertEquals(true, hub.isAvailable("a"))
        assertEquals("a", hub.failedFor.value)
        hub.clearFailure()
        assertNull(hub.failedFor.value)
    }

    @Test
    fun staleGetsOneRetryOnTheFreshVersion() = runTest {
        val service = FakeAvailability()
        val hub = AvailabilityHub(service)
        hub.load("a")
        service.errors += ApiException.Stale(9)
        service.state = AvailabilityHub.State(false, 9)

        hub.setAvailable(false, "a")

        assertEquals(listOf(true to 4, true to 9), service.calls)
        assertEquals(false, hub.isAvailable("a"))
    }

    @Test
    fun nothingHappensBeforeTheStateIsKnown() = runTest {
        val service = FakeAvailability()
        val hub = AvailabilityHub(service)

        hub.setAvailable(false, "a")

        assertTrue(service.calls.isEmpty())
        assertNull(hub.isAvailable("a"))
    }

    @Test
    fun theDotFollowsDoNotDisturbFirst() {
        assertEquals(AvailabilityDot.Kind.DO_NOT_DISTURB, AvailabilityDot.kind(registered = true, connecting = false, doNotDisturb = true))
        assertEquals(AvailabilityDot.Kind.AVAILABLE, AvailabilityDot.kind(registered = true, connecting = false, doNotDisturb = false))
        assertEquals(AvailabilityDot.Kind.CONNECTING, AvailabilityDot.kind(registered = false, connecting = true, doNotDisturb = false))
        assertEquals(AvailabilityDot.Kind.OFFLINE, AvailabilityDot.kind(registered = false, connecting = false, doNotDisturb = false))
    }
}

class InviteModelTest {
    private val own = "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57"

    private fun setup(service: FakeSelfService, now: () -> Instant = Instant::now): Pair<SelfExtensionHub, InviteModel> {
        val hub = SelfExtensionHub(service, service)
        return hub to InviteModel(hub, "a", ownExtensionNumber = "102", now = now)
    }

    @Test
    fun theOwnExtensionIsNotOffered() = runTest {
        val service = FakeSelfService()
        val (hub, model) = setup(service)
        hub.load("a")
        val devices = service.devices

        assertTrue(devices.any { it.id == own })
        assertFalse(model.candidates(devices).any { it.id == own })
        assertTrue(model.candidates(devices).isNotEmpty())
    }

    @Test
    fun withoutALoadedStateTheOwnNumberDecides() {
        val service = FakeSelfService()
        val (_, model) = setup(service)

        val candidates = model.candidates(listOf(InviteDevice("x", "Ik", "102"), InviteDevice("y", "Collega", "103")))

        assertEquals(listOf("y"), candidates.map { it.id })
    }

    @Test
    fun creatingShowsTheLinkUntilItExpiresAndTheUrlIsNotInTheDescription() = runTest {
        val service = FakeSelfService()
        var clock = Instant.parse("2026-10-09T08:15:00Z")
        val (hub, model) = setup(service) { clock }
        hub.load("a")
        val colleague = service.devices.first { it.id != own }
        model.select(colleague.id)

        model.create { colleague.name }

        assertEquals(listOf(colleague.id), service.invitedDevices)
        val shown = model.phase.value as InviteModel.Phase.Shown
        val invitation = shown.invitation
        assertEquals(colleague.name, invitation.deviceName)
        assertFalse("the link must not appear in a description or log", model.phase.value.toString().contains("fss_vpair"))
        assertFalse(invitation.toString().contains("fss_vpair"))

        clock = invitation.expiresAt.minusSeconds(90)
        assertEquals(90.0, model.remaining(invitation), 0.0)
        assertFalse(model.isExpired(invitation))

        clock = invitation.expiresAt.plusSeconds(1)
        assertTrue(model.isExpired(invitation))
        assertEquals(0.0, model.remaining(invitation), 0.0)

        model.reset()
        assertEquals(InviteModel.Phase.Choosing, model.phase.value)
    }

    @Test
    fun invitingYourselfIsTheServersConflictAndShowsAClearMessage() = runTest {
        val service = FakeSelfService()
        service.inviteResult = { throw ApiException.Conflict("conflict") }
        val (_, model) = setup(service)
        model.select(own)

        model.create { "" }

        assertEquals(InviteModel.Phase.Failed(SelfExtensionFailure.OwnDevice), model.phase.value)
    }

    @Test
    fun nothingIsSentWithoutAChoice() = runTest {
        val service = FakeSelfService()
        val (_, model) = setup(service)

        model.create { "" }

        assertTrue(service.invitedDevices.isEmpty())
        assertEquals(InviteModel.Phase.Choosing, model.phase.value)
    }

    @Test
    fun rateLimitedReturnsToTheList() = runTest {
        val service = FakeSelfService()
        service.inviteResult = { throw ApiException.RateLimited(600) }
        val (_, model) = setup(service)
        model.select("x")

        model.create { "" }

        assertEquals(InviteModel.Phase.Failed(SelfExtensionFailure.RateLimited(600)), model.phase.value)
    }

    @Test
    fun countdownFormat() {
        assertEquals("9:42", InviteModel.countdown(582.0))
        assertEquals("1:00", InviteModel.countdown(59.2))
        assertEquals("0:00", InviteModel.countdown(0.0))
        assertEquals("0:00", InviteModel.countdown(-5.0))
    }
}
