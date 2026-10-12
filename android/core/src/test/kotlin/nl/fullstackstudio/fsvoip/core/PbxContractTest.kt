// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The PBX fixtures against the Kotlin models: what the server sends decodes, what the app sends equals the fixture. */
class PbxContractTest {
    private val json = FsJson.default

    @Test
    fun devicesDecode() {
        val page = json.decodeFromString(PbxDevices.serializer(), Fixtures.text("pbx-devices.json"))

        assertEquals(3, page.devices.size)
        assertEquals("Receptie", page.devices[0].name)
        assertTrue(page.devices[0].registration!!.connected)
        assertTrue(page.devices[1].dnd)
        assertEquals(TargetType.EXTERNAL, page.devices[1].forwardAlways!!.type)
        assertEquals("+31612345678", page.devices[1].forwardAlways!!.number)
        assertNull(page.devices[2].registration)
        assertEquals(SyncState.PENDING, page.devices[2].sync)
        assertEquals(10, page.devices[2].followMe[0].delaySeconds)
        assertEquals(2, page.numbers.size)
        assertTrue(page.targets.any { it.ofDevice })
    }

    @Test
    fun devicePatchEqualsTheFixture() {
        val patch = PbxDevicePatch(
            version = 4,
            dnd = true,
            noAnswerSeconds = 20,
            noAnswerTarget = Change.Set(PbxTarget.obj(TargetType.VOICEMAIL, "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57")),
            busyTarget = Change.Clear,
            forwardAlways = Change.Set(PbxTarget.external("+31612345678")),
            outboundNumberId = Change.Clear,
            followMe = listOf(FollowMeStep(PbxTarget.obj(TargetType.RING_GROUP, "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4"), 0, 25)),
        )

        assertEquals(Fixtures.json("pbx-device-patch.json"), patch.toJson())
        assertFalse(patch.isEmpty)
        assertTrue(PbxDevicePatch(version = 4).isEmpty)
    }

    @Test
    fun ringGroupsDecodeAndRequestsEqualTheFixtures() {
        val page = json.decodeFromString(PbxRingGroups.serializer(), Fixtures.text("pbx-ring-groups.json"))

        assertEquals("Iedereen", page.ringGroups[0].name)
        assertEquals(RingStrategy.ALL, page.ringGroups[0].strategy)
        assertEquals(2, page.ringGroups[0].members.size)
        assertEquals(TargetType.VOICEMAIL, page.ringGroups[0].timeoutTarget!!.type)
        assertEquals(3, page.devices.size)

        val create = PbxRingGroupCreate(
            name = "Support",
            strategy = RingStrategy.SEQUENCE,
            members = listOf(
                RingGroupMember("1b2c3d4e-0a1b-4c2d-8e3f-5a6b7c8d9e01", 0, 20),
                RingGroupMember("7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57", 20, 20),
            ),
            timeoutTarget = Change.Set(PbxTarget.obj(TargetType.VOICEMAIL, "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f")),
        )
        assertEquals(Fixtures.json("pbx-ring-group-create.json"), create.toJson())

        assertEquals("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", json.decodeFromString(PbxRingGroupCreated.serializer(), Fixtures.text("pbx-ring-group-created.json")).ringGroupId)

        val patch = PbxRingGroupPatch(version = 2, name = "Iedereen", members = emptyList())
        assertFalse(patch.isEmpty)
        assertTrue(PbxRingGroupPatch(version = 2).isEmpty)
    }

    @Test
    fun failuresAreClassified() {
        assertEquals(PbxFailure.AccessDenied, PbxFailure.classify(ApiException.Forbidden("admin", null)))
        assertEquals(PbxFailure.Revoked, PbxFailure.classify(ApiException.Unauthorized()))
        assertEquals(PbxFailure.ReadOnly, PbxFailure.classify(ApiException.Conflict("read_only", null, null)))
        assertEquals(PbxFailure.Stale(7, null), PbxFailure.classify(ApiException.Conflict("stale", null, 7)))
        assertEquals(PbxFailure.Blocked(listOf("+449000")), PbxFailure.classify(ApiException.BlockedDestination(listOf("+449000"))))
        assertEquals(PbxFailure.InvalidAudio, PbxFailure.classify(ApiException.InvalidRequest(null, null, null, "invalid_audio")))
        assertEquals(PbxFailure.Invalid("greeting_required", null), PbxFailure.classify(ApiException.InvalidRequest(null, "greeting_required")))
        assertEquals(PbxFailure.TooLarge, PbxFailure.classify(ApiException.PayloadTooLarge()))
        assertEquals(PbxFailure.Offline, PbxFailure.classify(ApiException.Transport("x")))
    }

    @Test
    fun errorBodiesBecomeTypedFailures() {
        fun mapped(status: Int, name: String) = PbxFailure.classify(FsVoipApiClient.errorFor(HttpResponse(status, emptyMap(), Fixtures.text(name).toByteArray())))

        val inUse = mapped(409, "error-in-use.json") as PbxFailure.InUse
        assertEquals("Kantoortijden", inUse.places.single().name)

        assertEquals(listOf("+449000000000"), (mapped(422, "error-blocked-destination.json") as PbxFailure.Blocked).numbers)
        assertTrue(mapped(422, "error-cost-not-accepted.json") is PbxFailure.CostNotAccepted)
        assertEquals(PbxFailure.InvalidAudio, mapped(400, "error-invalid-audio.json"))
        assertEquals(PbxFailure.TooLarge, mapped(413, "error-too-large.json"))
        assertEquals(PbxFailure.TooMany, mapped(409, "error-too-many.json"))
        assertEquals(PbxFailure.Advanced, mapped(409, "error-advanced.json"))
        assertTrue((mapped(409, "error-stale-chain.json") as PbxFailure.Stale).chain != null)
    }
}

class PbxDraftsTest {
    private val device = PbxDevice(id = "d", name = "Receptie", version = 3, noAnswerSeconds = 25, voicemailEnabled = true)

    @Test
    fun anUntouchedDeviceDraftSendsNothing() {
        val draft = PbxDeviceDraft.of(device)

        assertNull(draft.patch(PbxDeviceDraft.of(device), device.version))
    }

    @Test
    fun onlyTheChangedKeysAreSent() {
        val original = PbxDeviceDraft.of(device)
        val patch = original.copy(dnd = true, forwardAlways = PbxTarget.external("+31612345678")).patch(original, 3)!!
        val body = patch.toJson()

        assertEquals(setOf("version", "dnd", "forwardAlways"), body.keys)
        assertEquals(3, (body["version"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())

        // Clearing a target sends an explicit null.
        val cleared = original.copy(forwardAlways = PbxTarget.external("1")).copy(forwardAlways = null).patch(original.copy(forwardAlways = PbxTarget.external("1")), 3)!!
        assertEquals(kotlinx.serialization.json.JsonNull, cleared.toJson()["forwardAlways"])
    }

    @Test
    fun ringGroupDraftValidatesAndDiffs() {
        val empty = PbxRingGroupDraft(name = "  ")
        assertFalse(empty.isValid)

        val draft = PbxRingGroupDraft(name = " Support ").toggle("a").toggle("b")
        assertTrue(draft.isValid)
        assertEquals("Support", draft.create().name)
        assertEquals(2, draft.members.size)
        assertEquals(1, draft.toggle("a").members.size)

        val original = PbxRingGroupDraft("Support", RingStrategy.ALL, draft.members, null)
        assertNull(original.patch(original, 2))
        assertEquals(setOf("version", "strategy"), original.copy(strategy = RingStrategy.ROUND).patch(original, 2)!!.toJson().keys)
    }
}
