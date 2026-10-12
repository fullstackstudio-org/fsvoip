// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The number, hours, sound and overview fixtures against the Kotlin models. */
class PbxAdminContractTest {
    private val json = FsJson.default

    private fun chain(name: String) = json.decodeFromString(NumberChain.serializer(), Fixtures.text(name))

    @Test
    fun overviewDecodesWithItsFlowTree() {
        val overview = json.decodeFromString(PbxOverview.serializer(), Fixtures.text("pbx-overview.json"))

        assertEquals("Voorbeeld Bouw", overview.pbx.name)
        assertEquals(3, overview.deviceCount)
        assertEquals(2, overview.connectedCount)
        assertEquals(2, overview.numbers.size)

        val hours = overview.numbers[0].flow
        assertEquals(FlowKind.BUSINESS_HOURS, hours.kind)
        assertEquals(listOf(FlowWhenKind.OPEN, FlowWhenKind.CLOSED), hours.branches.map { it.condition.kind })
        assertEquals(FlowKind.RING_GROUP, hours.branches[0].node.kind)
        assertEquals(25, hours.branches[0].node.branches[0].condition.seconds)
        assertEquals(FlowKind.NONE, overview.numbers[1].flow.kind)
        assertNull(overview.numbers[1].routing)
    }

    @Test
    fun routingPatchEqualsTheFixtures() {
        val target = PbxTarget.obj(TargetType.RING_GROUP, "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4")

        assertEquals(Fixtures.json("pbx-routing-patch.json"), PbxRoutingPatch(Change.Set(target), 3).toJson())
        assertEquals(Fixtures.json("pbx-routing-patch-entry.json"), PbxRoutingPatch(Change.Clear, 3).toJson())
    }

    @Test
    fun hoursDecodeAndThePatchEqualsTheFixture() {
        val page = json.decodeFromString(PbxHours.serializer(), Fixtures.text("pbx-hours.json"))
        val item = page.hours.single()

        assertEquals("Kantoortijden", item.name)
        assertEquals(5, item.week.size)
        assertEquals("christmas_day", item.holidays[0].rule)
        assertEquals("2026-08-03", item.holidays[1].date)
        assertEquals(4, page.holidayRules.size)

        val patch = PbxHoursPatch(
            version = 6,
            week = listOf(HoursWeekEntry(1, "08:30", "12:00"), HoursWeekEntry(1, "13:00", "17:30"), HoursWeekEntry(5, "09:00", "24:00")),
            openTarget = Change.Set(PbxTarget.obj(TargetType.RING_GROUP, "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4")),
            closedTarget = Change.Clear,
            holidays = listOf(HolidayInput.Rule("christmas_day"), HolidayInput.Custom("Bedrijfsuitje", "2026-11-13")),
            holidayTarget = Change.Set(PbxTarget.obj(TargetType.VOICEMAIL, "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f")),
        )
        assertEquals(Fixtures.json("pbx-hours-patch.json"), patch.toJson())
    }

    @Test
    fun numbersPageDecodes() {
        val page = json.decodeFromString(PbxNumbersPage.serializer(), Fixtures.text("numbers-page.json"))

        assertEquals(ChainMode.SIMPLE, page.numbers[0].mode)
        assertEquals(ForwardingKind.STANDARD, page.numbers[0].summary.forwarding)
        assertEquals("ma-vr 9:00-17:00", page.numbers[0].summary.hours)
        assertEquals(ChainMode.ADVANCED, page.numbers[1].mode)
        assertEquals(ForwardingKind.ADVANCED, page.numbers[1].summary.forwarding)
        assertEquals(SyncState.PENDING, page.numbers[1].sync)
    }

    @Test
    fun simpleChainDecodes() {
        val chain = chain("number-chain-simple.json")

        assertTrue(chain.isEditable)
        assertEquals("Hoofdnummer", chain.name)
        assertEquals(5, chain.hours!!.week.size)
        assertTrue(chain.hours!!.holidays.national)
        assertEquals(Fallback.Voicemail("2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f", false), chain.hours!!.closed)
        assertNull(chain.hours!!.holiday)
        assertEquals("a1b2c3d4-1111-4a2b-8c3d-4e5f6a7b8c9d", chain.welcome!!.soundId)

        val forwarding = chain.forwarding as ChainForwarding.Standard
        assertEquals(3, forwarding.members.size)
        assertEquals(RingStrategy.ALL, forwarding.strategy)
        assertEquals(listOf("Servicenummer"), forwarding.sharedWith)

        assertEquals(RecordingCost(20000, false), chain.recording.cost)
        assertEquals(3, chain.options.devices.size)

        // The versions the chain shows go back with a step.
        assertEquals(
            mapOf("c3b2a1f0-e9d8-4c7b-a6f5-e4d3c2b1a0f9" to 6, "4a5b6c7d-8e9f-4a0b-9c1d-2e3f4a5b6c7d" to 3, "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4" to 3),
            chain.versions,
        )
    }

    @Test
    fun menuChainDecodesAndKeepsKeysItCannotEdit() {
        val chain = chain("number-chain-menu.json")
        val menu = chain.forwarding as ChainForwarding.Menu

        assertEquals(3, menu.keys.size)
        assertFalse(menu.keys[2].editable)
        assertEquals(TargetType.QUEUE, menu.keys[2].target.type)
        assertEquals("1", menu.defaultKey)
        assertEquals(Fallback.Hangup, menu.noChoice)
        assertTrue(chain.hours!!.closed is Fallback.Message)
        assertEquals(listOf("Hoofdnummer"), chain.hours!!.sharedWith)
        assertEquals(ChainHolidays(national = false, rules = listOf("christmas_day")), chain.hours!!.holidays)
        assertTrue(chain.hours!!.holiday is Fallback.Other)
        assertTrue(chain.recording.enabled)
    }

    @Test
    fun advancedChainIsReadOnly() {
        val chain = chain("number-chain-advanced.json")

        assertFalse(chain.isEditable)
        assertNull(chain.forwarding)
        assertEquals(3, chain.advanced!!.summary.size)
        assertTrue(chain.recording.billingActive)
    }

    @Test
    fun aFallbackWeDoNotKnowIsKeptAsOtherAndNeverSent() {
        val unknown = Fallback.fromJson(Fixtures.json("number-chain-menu.json").jsonObject["hours"]!!.jsonObject["holiday"]!!)

        assertTrue(unknown is Fallback.Other)
        assertFalse(unknown.isSelectable)
        assertNull(unknown.toJson())
        assertTrue(Fallback.fromJson(json.parseToJsonElement("""{"mode":"teleport"}""")) is Fallback.Other)
    }

    @Test
    fun stepRequestsEqualTheFixtures() {
        val chain = chain("number-chain-simple.json").copy(version = 4)
        val hoursId = "c3b2a1f0-e9d8-4c7b-a6f5-e4d3c2b1a0f9"

        assertEquals(Fixtures.json("chain-step-name.json"), ChainStep.Name("Hoofdnummer").request(chain))

        assertEquals(
            Fixtures.json("chain-step-hours.json"),
            ChainStep.Hours(
                enabled = true,
                week = listOf(HoursWeekEntry(1, "09:00", "17:00"), HoursWeekEntry(2, "09:00", "17:00")),
                nationalHolidays = true,
                holidayDates = listOf(ChainHolidayDate("Bouwvak", "2026-08-03")),
                closed = Fallback.Voicemail("2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f"),
                holiday = Change.Clear,
            ).request(chain),
        )

        assertEquals(
            Fixtures.json("chain-step-welcome.json"),
            ChainStep.Welcome(true, Change.Set("a1b2c3d4-1111-4a2b-8c3d-4e5f6a7b8c9d")).request(chain),
        )

        assertEquals(
            Fixtures.json("chain-step-closed.json"),
            ChainStep.Closed(Fallback.Forward("0612345678"), Change.Clear).request(chain),
        )

        assertEquals(
            Fixtures.json("chain-step-forwarding-standard.json"),
            ChainStep.ForwardingStandard(
                RingStrategy.SEQUENCE,
                listOf(ChainMember("5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50", 0, 20), ChainMember("1b2c3d4e-0a1b-4c2d-8e3f-5a6b7c8d9e01", 20, 20)),
                Fallback.Message("a1b2c3d4-2222-4a2b-8c3d-4e5f6a7b8c9d"),
            ).request(chain),
        )

        // A welcome step only sends the version of the welcome menu; "closed" and "hours" send the version of the hours.
        assertEquals(listOf(hoursId), ChainStep.Closed().versionIds(chain))
    }

    @Test
    fun menuStepRequestEqualsTheFixture() {
        val chain = chain("number-chain-menu.json")

        val request = ChainStep.ForwardingMenu(
            greetingSoundId = Change.Set("a1b2c3d4-1111-4a2b-8c3d-4e5f6a7b8c9d"),
            repeats = 2,
            timeoutSeconds = 8,
            defaultKey = Change.Set("1"),
            noChoice = Fallback.Hangup,
            keys = listOf(
                ChainKey("1", PbxTarget.obj(TargetType.DEVICE, "5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50")),
                ChainKey("2", PbxTarget.external("0612345678")),
            ),
        ).request(chain)

        assertEquals(Fixtures.json("chain-step-forwarding-menu.json"), request)
    }

    @Test
    fun recordingPatchEqualsTheFixture() {
        val patch = NumberRecordingPatch(true, Change.Set("a1b2c3d4-2222-4a2b-8c3d-4e5f6a7b8c9d"), costAccepted = true, version = 4)

        assertEquals(Fixtures.json("number-recording-patch.json"), patch.toJson())
    }

    @Test
    fun soundsDecodeAndRequestsEqualTheFixtures() {
        val page = json.decodeFromString(PbxSoundsPage.serializer(), Fixtures.text("sounds-page.json"))

        assertEquals(3, page.sounds.size)
        assertTrue(page.sounds[0].playable)
        assertEquals("menu_greeting", page.sounds[0].uses.single().kind)
        assertFalse(page.sounds[2].playable)
        assertNull(page.sounds[2].contentType)
        assertEquals(SyncState.PENDING, page.sounds[1].sync)

        assertEquals("a1b2c3d4-4444-4a2b-8c3d-4e5f6a7b8c9d", json.decodeFromString(SoundUploadResponse.serializer(), Fixtures.text("sound-upload-response.json")).soundId)
        assertNotNull(Fixtures.json("sound-rename-request.json"))
    }

    @Test
    fun multipartHasExactlyTheTwoPartsAndAClosedBoundary() {
        val body = Multipart.build(
            listOf(Multipart.Part.Text("name", "Welkom \"1\""), Multipart.Part.File("file", "welkom.wav", "audio/wav", byteArrayOf(1, 2, 3))),
            boundary = "B",
        )
        val text = String(body.bytes, Charsets.ISO_8859_1)

        assertEquals("multipart/form-data; boundary=B", body.contentType)
        assertEquals(2, Regex("Content-Disposition: form-data").findAll(text).count())
        assertTrue(text.startsWith("--B\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\nWelkom \"1\"\r\n--B\r\n"))
        assertTrue(text.contains("name=\"file\"; filename=\"welkom.wav\"\r\nContent-Type: audio/wav\r\n\r\n\u0001\u0002\u0003\r\n"))
        assertTrue(text.endsWith("--B--\r\n"))

        // A quote or a line break in a file name does not end the header early.
        val tricky = String(Multipart.build(listOf(Multipart.Part.File("file", "a\"\r\nb.wav", "audio/wav", ByteArray(0))), "B").bytes, Charsets.ISO_8859_1)
        assertTrue(tricky.contains("filename=\"a%22  b.wav\""))
    }

    @Test
    fun staleFailureCarriesTheFreshChain() {
        val failure = PbxFailure.classify(FsVoipApiClient.errorFor(HttpResponse(409, emptyMap(), Fixtures.text("error-stale-chain.json").toByteArray()))) as PbxFailure.Stale
        val fresh = json.decodeFromJsonElement(NumberChain.serializer(), failure.chain!!)

        assertEquals(5, fresh.version)
        assertEquals("Hoofdnummer", fresh.name)
    }

}
