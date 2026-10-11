// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Decodes the pbx and chain fixtures with the Kotlin models and round-trips the request fixtures. */
class PbxContractTest {
    private val json = FsJson.default
    private val covered = mutableSetOf<String>()

    private fun fixture(name: String): String {
        covered += "$name.json"
        return Fixtures.text("$name.json")
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, name: String): T = json.decodeFromString(serializer, fixture(name))

    private fun assertJsonEqual(actual: JsonObject, name: String) {
        covered += "$name.json"
        assertEquals(Fixtures.json("$name.json"), actual)
    }

    private fun mutated(name: String, edit: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit): String {
        covered += "$name.json"
        val map = Fixtures.json("$name.json").jsonObject.toMutableMap()
        edit(map)

        return JsonObject(map).toString()
    }

    /** Runs every test of this class (for [ContractRegistry]) and returns the fixtures they used. */
    fun coverage(): Set<String> {
        overview()
        devices()
        devicePatch()
        ringGroups()
        ringGroupRequests()
        hours()
        hoursAndRoutingPatches()
        unknownEnumValuesDecodeAndCanNotBeSent()
        responsesIgnoreUnknownFields()
        numbersPage()
        simpleChain()
        menuChain()
        advancedChain()
        fallbackLeniencyAndStrictness()
        unknownForwardingKindMakesTheChainReadOnly()
        unknownModeAndBrokenOptionsDoNotBreakTheChain()
        everyStepEncodesToItsFixture()
        stepsKnowTheirPathSegment()
        leftOutPartsStayOutOfTheBody()
        blankNameIsSentAsNull()
        errorBodies()
        errorsMapToTheirExceptions()
        brokenChainInAStaleErrorIsNotLost()

        return covered
    }

    // MARK: /pbx

    @Test
    fun overview() {
        val overview = decode(PbxOverview.serializer(), "pbx-overview")

        assertEquals(PbxState.ACTIVE, overview.pbx.state)
        assertEquals(2, overview.numbers.size)
        assertEquals(3, overview.deviceCount)
        assertEquals(2, overview.connectedCount)
        assertFalse(overview.outboundBlocked)

        val first = overview.numbers[0]
        assertEquals("0850607848", first.number)
        assertEquals(PbxTarget(TargetType.BUSINESS_HOURS, id = "c3b2a1f0-e9d8-4c7b-a6f5-e4d3c2b1a0f9"), first.routing)
        assertEquals(3, first.version)
        assertEquals(SyncState.OK, first.sync)

        val flow = first.flow!!
        assertEquals(FlowKind.BUSINESS_HOURS, flow.kind)
        assertEquals("500", flow.extensionNumber)
        assertEquals(listOf(FlowWhenKind.OPEN, FlowWhenKind.CLOSED), flow.branches.map { it.`when`.kind })
        assertEquals(FlowKind.RING_GROUP, flow.branches[0].node.kind)
        assertEquals(FlowWhen(FlowWhenKind.NO_ANSWER, seconds = 25), flow.branches[0].node.branches.first().`when`)
        assertEquals(FlowKind.VOICEMAIL, flow.branches[0].node.branches.first().node.kind)

        val second = overview.numbers[1]
        assertNull(second.routing)
        assertEquals(SyncState.PENDING, second.sync)
        assertEquals(FlowKind.NO_DESTINATION, second.flow?.kind)
        assertNull(overview.entryFlow)
    }

    @Test
    fun devices() {
        val page = decode(PbxDevicesResponse.serializer(), "pbx-devices")

        assertEquals(listOf("Receptie", "Pieter Jansen", "Jan de Vries"), page.devices.map { it.name })
        assertEquals(listOf("0850607848", "0850607849"), page.numbers.map { it.number })

        val pieter = page.devices[1]
        assertTrue(pieter.dnd)
        assertEquals(5, pieter.version)
        assertEquals(PbxTarget.external("+31612345678"), pieter.forwardAlways)
        assertEquals(DeviceRegistration(connected = false, count = 0, agent = null), pieter.registration)

        val jan = page.devices[2]
        assertNull("null = the PBX did not answer", jan.registration)
        assertEquals(SyncState.PENDING, jan.sync)
        assertEquals(listOf(FollowMeStep(PbxTarget.external("+31687654321"), delaySeconds = 10, timeoutSeconds = 20)), jan.followMe)

        assertEquals(7, page.targets.size)
        assertTrue(page.targets[5].ofDevice)
        assertFalse(page.targets[0].ofDevice)
        assertEquals(TargetType.EXTERNAL, page.targets[6].type)
        assertNull(page.targets[6].id)
    }

    @Test
    fun devicePatch() {
        val patch = PbxDevicePatch(
            version = 4,
            outboundNumberId = Change.Clear,
            dnd = true,
            noAnswerSeconds = 20,
            noAnswerTarget = Change.Set(PbxTarget.obj(TargetType.VOICEMAIL, "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57")),
            busyTarget = Change.Clear,
            forwardAlways = Change.Set(PbxTarget.external("+31612345678")),
            followMe = listOf(FollowMeStep(PbxTarget.obj(TargetType.RING_GROUP, "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4"), 0, 25)),
        )
        assertJsonEqual(patch.toJson(), "pbx-device-patch")

        val body = patch.toJson()
        assertFalse("an untouched field is left out", body.containsKey("notRegisteredTarget"))
        assertFalse(body.containsKey("voicemailEnabled"))
        assertEquals("clear is an explicit null", JsonNull, body["busyTarget"])
        assertEquals(JsonNull, body["outboundNumberId"])
        assertEquals(JsonPrimitive(4), body["version"])
    }

    @Test
    fun ringGroups() {
        val page = decode(PbxRingGroupsResponse.serializer(), "pbx-ring-groups")

        assertEquals(1, page.ringGroups.size)
        assertEquals(RingStrategy.ALL, page.ringGroups[0].strategy)
        assertEquals(2, page.ringGroups[0].members.size)
        assertEquals(TargetType.VOICEMAIL, page.ringGroups[0].timeoutTarget?.type)
        assertEquals(listOf("100", "101", "102"), page.devices.map { it.extensionNumber })
        assertEquals("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", decode(PbxRingGroupCreated.serializer(), "pbx-ring-group-created").ringGroupId)
    }

    @Test
    fun ringGroupRequests() {
        val create = PbxRingGroupCreate(
            name = "Support",
            strategy = RingStrategy.SEQUENCE,
            members = listOf(
                RingGroupMember("1b2c3d4e-0a1b-4c2d-8e3f-5a6b7c8d9e01", 0, 20),
                RingGroupMember("7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57", 20, 20),
            ),
            timeoutTarget = Change.Set(PbxTarget.obj(TargetType.VOICEMAIL, "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f")),
        )
        assertJsonEqual(create.toJson(), "pbx-ring-group-create")
        assertJsonEqual(PbxRingGroupPatch(version = 2, name = "Alle collega's", strategy = RingStrategy.ROUND, timeoutTarget = Change.Clear).toJson(), "pbx-ring-group-patch")
    }

    @Test
    fun hours() {
        val page = decode(PbxHoursResponse.serializer(), "pbx-hours")
        val hours = page.hours.first()

        assertEquals(5, hours.week.size)
        assertEquals(HoursWeekEntry(1, "09:00", "17:00"), hours.week[0])
        assertEquals(TargetType.RING_GROUP, hours.openTarget?.type)
        assertEquals(listOf("christmas_day", null), hours.holidays.map { it.rule })
        assertEquals("2026-08-03", hours.holidays[1].date)
        assertEquals(TargetType.VOICEMAIL, hours.holidays[1].target?.type)
        assertEquals(6, hours.version)
        assertEquals(listOf("new_year", "kings_day", "christmas_day", "boxing_day"), page.holidayRules.map { it.rule })
    }

    @Test
    fun hoursAndRoutingPatches() {
        val patch = PbxHoursPatch(
            version = 6,
            week = listOf(HoursWeekEntry(1, "08:30", "12:00"), HoursWeekEntry(1, "13:00", "17:30"), HoursWeekEntry(5, "09:00", "24:00")),
            openTarget = Change.Set(PbxTarget.obj(TargetType.RING_GROUP, "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4")),
            closedTarget = Change.Clear,
            holidays = listOf(PbxHolidayInput.Rule("christmas_day"), PbxHolidayInput.Custom("Bedrijfsuitje", "2026-11-13")),
            holidayTarget = Change.Set(PbxTarget.obj(TargetType.VOICEMAIL, "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f")),
        )
        assertJsonEqual(patch.toJson(), "pbx-hours-patch")
        assertJsonEqual(PbxRoutingPatch(PbxTarget.obj(TargetType.RING_GROUP, "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4"), 3).toJson(), "pbx-routing-patch")
        assertJsonEqual(PbxRoutingPatch(null, 3).toJson(), "pbx-routing-patch-entry")
    }

    @Test
    fun unknownEnumValuesDecodeAndCanNotBeSent() {
        val text = mutated("pbx-ring-groups") { map ->
            val groups = (map["ringGroups"] as JsonArray).map { it.jsonObject.toMutableMap() }
            groups[0]["strategy"] = JsonPrimitive("random")
            groups[0]["sync"] = JsonPrimitive("rebooting")
            groups[0]["timeoutTarget"] = buildJsonObject {
                put("type", JsonPrimitive("teleport"))
                put("id", JsonPrimitive("x"))
            }
            map["ringGroups"] = JsonArray(groups.map { JsonObject(it) })
        }
        val page = json.decodeFromString(PbxRingGroupsResponse.serializer(), text)

        assertEquals(RingStrategy.UNKNOWN, page.ringGroups[0].strategy)
        assertEquals(SyncState.UNKNOWN, page.ringGroups[0].sync)
        assertEquals(TargetType.UNKNOWN, page.ringGroups[0].timeoutTarget?.type)

        // UNKNOWN is a decoding fallback: sending it back is an app bug, so encoding refuses.
        try {
            PbxRingGroupPatch(version = 1, strategy = RingStrategy.UNKNOWN).toJson()
            fail("expected a refusal")
        } catch (_: IllegalArgumentException) {
        }

        try {
            PbxRoutingPatch(PbxTarget(TargetType.UNKNOWN, id = "x"), 1).toJson()
            fail("expected a refusal")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun responsesIgnoreUnknownFields() {
        fun withFuture(name: String): String = mutated(name) { it["futureField"] = buildJsonObject { put("a", JsonPrimitive(1)) } }

        json.decodeFromString(PbxOverview.serializer(), withFuture("pbx-overview"))
        json.decodeFromString(PbxDevicesResponse.serializer(), withFuture("pbx-devices"))
        json.decodeFromString(PbxRingGroupsResponse.serializer(), withFuture("pbx-ring-groups"))
        json.decodeFromString(PbxHoursResponse.serializer(), withFuture("pbx-hours"))
    }

    // MARK: Numbers as a chain

    @Test
    fun numbersPage() {
        val page = decode(PbxNumbersPage.serializer(), "numbers-page")

        assertEquals(2, page.numbers.size)
        assertEquals("Hoofdnummer", page.numbers[0].name)
        assertEquals(ChainMode.SIMPLE, page.numbers[0].mode)
        assertEquals(PbxNumberEntry.Summary("ma-vr 9:00-17:00", welcome = true, forwarding = ForwardingKind.STANDARD, recording = false), page.numbers[0].summary)
        assertEquals(ChainMode.ADVANCED, page.numbers[1].mode)
        assertEquals(ForwardingKind.ADVANCED, page.numbers[1].summary.forwarding)
        assertEquals(SyncState.PENDING, page.numbers[1].sync)
        assertNull(page.numbers[1].name)
    }

    private val hoursId = "c3b2a1f0-e9d8-4c7b-a6f5-e4d3c2b1a0f9"
    private val box = "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f"
    private val sound1 = "a1b2c3d4-1111-4a2b-8c3d-4e5f6a7b8c9d"
    private val sound2 = "a1b2c3d4-2222-4a2b-8c3d-4e5f6a7b8c9d"
    private val receptie = "5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50"
    private val pieter = "1b2c3d4e-0a1b-4c2d-8e3f-5a6b7c8d9e01"

    @Test
    fun simpleChain() {
        val chain = decode(NumberChain.serializer(), "number-chain-simple")

        assertTrue(chain.isEditable)
        assertEquals(4, chain.version)

        val hours = chain.hours!!
        assertEquals(hoursId, hours.id)
        assertEquals(5, hours.week.size)
        assertTrue(hours.holidays.national)
        assertEquals(listOf(ChainHolidayDate("Bouwvak", "2026-08-03")), hours.holidays.dates)
        assertEquals(Fallback.Voicemail(box, false), hours.closed)
        assertNull(hours.holiday)

        assertEquals(sound1, chain.welcome?.soundId)

        val forwarding = (chain.forwarding as ChainForwarding.Standard).value
        assertEquals("9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4", forwarding.groupId)
        assertEquals(RingStrategy.ALL, forwarding.strategy)
        assertEquals(listOf(receptie, pieter, "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57"), forwarding.members.map { it.deviceId })
        assertEquals(ChainMember("7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57", 5, 20), forwarding.members.last())
        assertEquals(listOf("Servicenummer"), forwarding.sharedWith)

        assertFalse(chain.recording.enabled)
        assertEquals(RecordingCost(20000, false), chain.recording.cost)
        assertEquals(listOf("100", "101", "102"), chain.options.devices.map { it.extensionNumber })
        assertEquals(listOf("Welkom", "Buiten kantoortijd"), chain.options.sounds.map { it.name })
        assertEquals(listOf("Iedereen"), chain.options.groups.map { it.name })
        assertNull(chain.advancedSummary)
    }

    @Test
    fun menuChain() {
        val chain = decode(NumberChain.serializer(), "number-chain-menu")

        assertEquals(SyncState.PENDING, chain.sync)
        assertNull(chain.welcome)

        val hours = chain.hours!!
        assertEquals("two periods on one day", listOf(1, 1), hours.week.map { it.day })
        assertEquals(Fallback.Message(sound2), hours.closed)
        assertEquals(Fallback.Other(PbxTarget(TargetType.QUEUE, id = "8c9d0e1f-2a3b-4c4d-9e5f-6a7b8c9d0e1f")), hours.holiday)
        assertEquals(false, hours.holiday?.isSelectable)
        assertTrue(hours.closed.isSelectable)

        val menu = (chain.forwarding as ChainForwarding.Menu).value
        assertEquals(2, menu.repeats)
        assertEquals("1", menu.defaultKey)
        assertEquals(Fallback.Hangup, menu.noChoice)
        assertEquals(listOf("1", "2", "3"), menu.keys.map { it.digit })
        assertEquals(listOf(true, true, false), menu.keys.map { it.editable })
        assertEquals(PbxTarget.external("0612345678"), menu.keys[1].target)

        assertTrue(chain.recording.enabled)
        assertTrue(chain.recording.billingActive)
        assertNull("free for this customer", chain.recording.cost)
    }

    @Test
    fun advancedChain() {
        val chain = decode(NumberChain.serializer(), "number-chain-advanced")

        assertEquals(ChainMode.ADVANCED, chain.mode)
        assertFalse(chain.isEditable)
        assertNull(chain.hours)
        assertNull(chain.welcome)
        assertNull(chain.forwarding)
        assertEquals(3, chain.advancedSummary?.size)
        assertEquals("Support", chain.name)
    }

    @Test
    fun fallbackLeniencyAndStrictness() {
        fun fallback(text: String) = json.decodeFromString(Fallback.serializer(), text)

        assertEquals(Fallback.Voicemail("b", true), fallback("""{"mode":"voicemail","boxId":"b","ofDevice":true}"""))
        assertEquals(Fallback.Voicemail(null, false), fallback("""{"mode":"voicemail"}"""))
        assertEquals(Fallback.Device("d"), fallback("""{"mode":"device","deviceId":"d"}"""))
        assertEquals(Fallback.Unknown("teleport"), fallback("""{"mode":"teleport"}"""))
        assertEquals(Fallback.Other(null), fallback("""{"mode":"other"}"""))
        assertFalse(fallback("""{"mode":"teleport"}""").isSelectable)

        // What the app may send: voicemail without a box = the shared box; `ofDevice` is display only.
        assertEquals(buildJsonObject { put("mode", JsonPrimitive("voicemail")) }, Fallback.Voicemail(null, true).toJson())

        // `other` and an unknown mode can never be sent back.
        for (readOnly in listOf(Fallback.Other(null), Fallback.Unknown("teleport"))) {
            try {
                readOnly.toJson()
                fail("expected a refusal")
            } catch (_: IllegalArgumentException) {
            }
        }

        try {
            ChainClosedStep(closed = Fallback.Other(null)).toJson()
            fail("expected a refusal")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun unknownForwardingKindMakesTheChainReadOnly() {
        val text = mutated("number-chain-simple") {
            it["forwarding"] = buildJsonObject {
                put("kind", JsonPrimitive("teleport"))
                put("futureField", JsonPrimitive(1))
            }
        }
        val chain = json.decodeFromString(NumberChain.serializer(), text)

        assertEquals(ChainForwarding.Unknown("teleport"), chain.forwarding)
        assertFalse(chain.isEditable)
    }

    @Test
    fun unknownModeAndBrokenOptionsDoNotBreakTheChain() {
        val text = mutated("number-chain-simple") {
            it["mode"] = JsonPrimitive("quantum")
            it["futureField"] = buildJsonObject { put("a", JsonPrimitive(1)) }
            it["options"] = JsonPrimitive("garbage")
        }
        val chain = json.decodeFromString(NumberChain.serializer(), text)

        assertEquals(ChainMode.UNKNOWN, chain.mode)
        assertFalse(chain.isEditable)
        assertEquals(ChainOptions(), chain.options)
    }

    // MARK: Chain steps (requests)

    @Test
    fun everyStepEncodesToItsFixture() {
        val versions = ChainVersions(mapOf(hoursId to 6), 4)

        assertJsonEqual(ChainNameStep("Hoofdnummer", ChainVersions(number = 4)).toJson(), "chain-step-name")
        assertJsonEqual(
            ChainHoursStep(
                enabled = true,
                week = listOf(HoursWeekEntry(1, "09:00", "17:00"), HoursWeekEntry(2, "09:00", "17:00")),
                holidays = ChainHolidaysInput(national = true, dates = listOf(ChainHolidayDate("Bouwvak", "2026-08-03"))),
                closed = Fallback.Voicemail(box, false),
                holiday = Change.Clear,
                versions = versions,
            ).toJson(),
            "chain-step-hours",
        )
        assertJsonEqual(ChainWelcomeStep(true, Change.Set(sound1), ChainVersions(mapOf("4a5b6c7d-8e9f-4a0b-9c1d-2e3f4a5b6c7d" to 3), 4)).toJson(), "chain-step-welcome")
        assertJsonEqual(
            ChainStandardForwardingStep(
                strategy = RingStrategy.SEQUENCE,
                members = listOf(ChainMember(receptie, 0, 20), ChainMember(pieter, 20, 20)),
                unanswered = Fallback.Message(sound2),
                versions = ChainVersions(mapOf("9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4" to 3), 4),
            ).toJson(),
            "chain-step-forwarding-standard",
        )
        assertJsonEqual(
            ChainMenuForwardingStep(
                greetingSoundId = Change.Set(sound1),
                repeats = 2,
                timeoutSeconds = 8,
                defaultKey = Change.Set("1"),
                noChoice = Fallback.Hangup,
                keys = listOf(ChainKey("1", PbxTarget.obj(TargetType.DEVICE, receptie)), ChainKey("2", PbxTarget.external("0612345678"))),
                versions = ChainVersions(mapOf("6b7c8d9e-0f1a-4b2c-8d3e-4f5a6b7c8d9e" to 2), 2),
            ).toJson(),
            "chain-step-forwarding-menu",
        )
        assertJsonEqual(ChainClosedStep(Fallback.Forward("0612345678"), Change.Clear, versions).toJson(), "chain-step-closed")
        assertJsonEqual(NumberRecordingPatch(enabled = true, announcementSoundId = Change.Set(sound2), costAccepted = true, version = 4).toJson(), "number-recording-patch")
    }

    @Test
    fun stepsKnowTheirPathSegment() {
        assertEquals("name", ChainNameStep(null).step.wire)
        assertEquals("hours", ChainHoursStep(false).step.wire)
        assertEquals("welcome", ChainWelcomeStep(false).step.wire)
        assertEquals("forwarding", ChainStandardForwardingStep().step.wire)
        assertEquals("forwarding", ChainMenuForwardingStep().step.wire)
        assertEquals("closed", ChainClosedStep().step.wire)
    }

    @Test
    fun leftOutPartsStayOutOfTheBody() {
        assertEquals(setOf("enabled"), ChainHoursStep(false).toJson().keys)
        assertEquals(setOf("enabled"), ChainWelcomeStep(false).toJson().keys)
        assertEquals(emptySet<String>(), ChainClosedStep().toJson().keys)
        assertEquals(setOf("kind"), ChainStandardForwardingStep().toJson().keys)
        assertEquals(setOf("kind"), ChainMenuForwardingStep().toJson().keys)
        assertEquals(setOf("enabled", "version"), NumberRecordingPatch(enabled = false, version = 2).toJson().keys)
    }

    @Test
    fun blankNameIsSentAsNull() {
        assertEquals(JsonNull, ChainNameStep("   ").toJson()["name"])
    }

    // MARK: Error bodies

    @Test
    fun errorBodies() {
        fun body(name: String) = decode(ApiErrorBody.serializer(), name)

        assertEquals("advanced", body("error-advanced").error)
        assertEquals("greeting_required", body("error-greeting-required").code)
        assertEquals(RecordingCost(20000, false), json.decodeFromJsonElement(RecordingCost.serializer(), body("error-cost-not-accepted").cost!!))
        assertEquals("suspended", body("error-forbidden-field").field)
        assertEquals(5, json.decodeFromJsonElement(NumberChain.serializer(), body("error-stale-chain").chain!!).version)
        assertEquals("admin", body("error-forbidden").requiredRole)
        assertEquals(5, body("error-stale").version)
        assertEquals(listOf("+449000000000"), body("error-blocked-destination").blocked)
        assertEquals("phones", body("error-invalid-field").field)
        assertEquals(listOf(ApiErrorPlace("object", "Kantoortijden")), body("error-in-use").places)
        assertEquals("read_only", body("error-read-only").error)
    }

    private fun mapped(status: Int, name: String): ApiException {
        covered += "$name.json"

        return FsVoipApiClient.errorFor(HttpResponse(status, emptyMap(), Fixtures.text("$name.json").toByteArray()))
    }

    @Test
    fun errorsMapToTheirExceptions() {
        assertTrue(mapped(409, "error-advanced") is ApiException.Advanced)
        assertTrue(mapped(400, "error-greeting-required") is ApiException.GreetingRequired)

        val cost = mapped(422, "error-cost-not-accepted") as ApiException.CostNotAccepted
        assertEquals(RecordingCost(20000, false), cost.recordingCost())
        // The same answer on a 409 maps the same (the name decides).
        assertTrue(mapped(409, "error-cost-not-accepted") is ApiException.CostNotAccepted)

        val stale = mapped(409, "error-stale-chain") as ApiException.StaleChain
        assertEquals(5, stale.freshChain()?.version)
        assertEquals("Hoofdnummer", stale.freshChain()?.name)

        assertEquals(5, (mapped(409, "error-stale") as ApiException.Stale).version)
        assertTrue(mapped(409, "error-read-only") is ApiException.ReadOnly)
        assertEquals(listOf(ApiErrorPlace("object", "Kantoortijden")), (mapped(409, "error-in-use") as ApiException.InUse).places)
        assertEquals(listOf("+449000000000"), (mapped(422, "error-blocked-destination") as ApiException.BlockedDestination).numbers)
        assertNull((mapped(403, "error-forbidden") as ApiException.Forbidden).field)
        assertEquals("suspended", (mapped(403, "error-forbidden-field") as ApiException.Forbidden).field)

        val invalid = mapped(400, "error-invalid-field") as ApiException.Invalid
        assertEquals("invalid_phone", invalid.code)
        assertEquals("phones", invalid.field)
    }

    @Test
    fun brokenChainInAStaleErrorIsNotLost() {
        val response = HttpResponse(409, emptyMap(), """{"error":"stale","chain":{"broken":true},"cost":"free"}""".toByteArray())
        val error = FsVoipApiClient.errorFor(response) as ApiException.StaleChain

        // The error is still a stale answer; the chain just cannot be read, so the caller reloads.
        assertNull(error.freshChain())
    }
}
