// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberDraftsTest {
    private val json = FsJson.default

    private fun chain(name: String) = json.decodeFromString(NumberChain.serializer(), Fixtures.text(name))

    @Test
    fun timesAreParsedAndNormalised() {
        assertEquals(540, WeekDraft.minutes("09:00"))
        assertEquals(1440, WeekDraft.minutes("24:00"))
        assertNull(WeekDraft.minutes("24:30"))
        assertNull(WeekDraft.minutes("9:75"))
        assertNull(WeekDraft.minutes("abc"))
        assertEquals("09:00", WeekDraft.normalize("900"))
        assertEquals("09:05", WeekDraft.normalize("9:05"))
        assertEquals("24:00", WeekDraft.normalize("2400"))
        assertEquals("nonsense", WeekDraft.normalize("nonsense"))
    }

    @Test
    fun weekValidityNeedsOrderedNonOverlappingIntervals() {
        assertTrue(WeekDraft.isValid(mapOf(1 to listOf(WeekSlot("08:00", "12:00"), WeekSlot("13:00", "24:00")))))
        assertFalse(WeekDraft.isValid(mapOf(1 to listOf(WeekSlot("12:00", "08:00")))))
        assertFalse(WeekDraft.isValid(mapOf(1 to listOf(WeekSlot("08:00", "12:00"), WeekSlot("11:00", "13:00")))))
        assertFalse(WeekDraft.isValid(mapOf(1 to List(5) { WeekSlot("0$it:00", "0$it:30") })))
    }

    @Test
    fun theWeekIsSummarisedInGroups() {
        val week = (1..5).map { HoursWeekEntry(it, "09:00", "17:00") } + HoursWeekEntry(6, "10:00", "12:00")

        assertEquals("ma–vr 09:00–17:00, za 10:00–12:00", WeekDraft.summary(week, "Nooit open", Locale("nl", "NL")))
        assertEquals("Nooit open", WeekDraft.summary(emptyList(), "Nooit open"))
    }

    @Test
    fun anUntouchedHoursDraftSendsNothingAndAChangeSendsOnlyThat() {
        val item = json.decodeFromString(PbxHours.serializer(), Fixtures.text("pbx-hours.json")).hours.single()
        val draft = HoursDraft.of(item)

        assertNull(draft.patch(item))

        val changed = draft.copy(days = draft.days + (6 to listOf(WeekSlot("10:00", "12:00")))).patch(item)!!
        assertEquals(setOf("version", "week"), changed.toJson().keys)
    }

    @Test
    fun closingDaysKeepsEveryHolidayAndStopsAtTheLimit() {
        val item = json.decodeFromString(PbxHours.serializer(), Fixtures.text("pbx-hours.json")).hours.single()
        val dates = TemporaryClosure.dates(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 5))

        assertEquals(listOf("2026-08-03", "2026-08-04", "2026-08-05"), dates)

        // 2026-08-03 is an own date already: it stays, two are new.
        val plan = TemporaryClosure.closing(item, "  Bouwvak ", dates, "Tijdelijk dicht") as TemporaryClosure.Plan.Holidays
        assertEquals(HolidayInput.Rule("christmas_day"), plan.holidays[0])
        assertEquals(HolidayInput.Custom("Bouwvak", "2026-08-03"), plan.holidays[1])
        assertEquals(listOf("2026-08-04", "2026-08-05"), plan.holidays.drop(2).map { (it as HolidayInput.Custom).date })
        assertTrue(plan.holidays.drop(2).all { (it as HolidayInput.Custom).name == "Bouwvak" })

        assertEquals(TemporaryClosure.Plan.EmptyRange, TemporaryClosure.closing(item, "x", emptyList(), "d"))
        assertEquals(TemporaryClosure.Plan.EmptyRange.javaClass, TemporaryClosure.closing(item, "x", TemporaryClosure.dates(LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 1)), "d").javaClass)

        val long = TemporaryClosure.dates(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31))
        assertEquals(TemporaryClosure.Plan.TooMany(19), TemporaryClosure.closing(item, "x", long, "d"))

        // Reopening drops exactly that date.
        val reopened = TemporaryClosure.reopening(item, "2026-08-03")
        assertEquals(listOf<HolidayInput>(HolidayInput.Rule("christmas_day")), reopened)
    }

    @Test
    fun hoursStepSendsOnlyWhatWasChangedAndNeverAFallbackItCannotShow() {
        val chain = chain("number-chain-menu.json")
        val original = ChainHoursDraft.of(chain)

        assertNull(original.step(original))

        val body = original.copy(days = original.days + (6 to listOf(WeekSlot("10:00", "12:00")))).step(original)!!.request(chain)
        assertEquals(chain.version, body["numberVersion"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content.toInt() })
        // The holiday fallback of this fixture is something the chain cannot send back.
        assertFalse(body.containsKey("holiday"))
        assertEquals(setOf("enabled", "week", "holidays", "closed", "versions", "numberVersion"), body.keys)

        assertEquals(false, (original.copy(enabled = false).step(original)!!.body()["enabled"] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean())
    }

    @Test
    fun welcomeNeedsASound() {
        val chain = chain("number-chain-simple.json")
        val original = ChainWelcomeDraft.of(chain)

        assertNull(original.step(original))
        assertFalse(ChainWelcomeDraft(enabled = true, soundId = null).isValid)
        assertTrue(ChainWelcomeDraft(enabled = true, soundId = "s").isValid)
        assertTrue(ChainWelcomeDraft(enabled = false).isValid)
    }

    @Test
    fun forwardingDraftSwitchesBetweenStandardAndMenu() {
        val standard = ChainForwardingDraft.of(chain("number-chain-simple.json"))
        assertEquals(ForwardingKind.STANDARD, standard.kind)
        assertEquals(3, standard.members.size)
        assertNull(standard.step(standard))
        assertTrue(standard.isValid)
        assertFalse(standard.copy(members = emptyList()).isValid)

        val menu = ChainForwardingDraft.of(chain("number-chain-menu.json"))
        assertEquals(ForwardingKind.MENU, menu.kind)
        assertTrue(menu.isValid)
        assertEquals("4", menu.nextFreeDigit())
        assertFalse(menu.copy(keys = menu.keys + ChainKey("1", PbxTarget.external("0612345678"))).isValid)

        // Switching to the menu sends the menu body.
        val step = standard.copy(kind = ForwardingKind.MENU, keys = listOf(ChainKey("1", PbxTarget.external("0612345678")))).step(standard)!!
        assertTrue(step is ChainStep.ForwardingMenu)
    }

    @Test
    fun recordingNeedsConsentOnlyWhenItStartsToCostMoney() {
        val free = chain("number-chain-simple.json")
        val off = ChainRecordingDraft.of(free)
        val on = off.copy(enabled = true)

        assertTrue(on.needsConsent(free))
        assertFalse(on.canSave(free))
        assertTrue(on.copy(costAccepted = true).canSave(free))

        val patch = on.copy(costAccepted = true).patch(off, free)!!
        assertEquals(true, (patch.toJson()["costAccepted"] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean())

        // Already billed (the announcement changes only): no consent.
        val billed = free.copy(recording = free.recording.copy(enabled = true, billingActive = true))
        val billedOff = ChainRecordingDraft.of(billed)
        assertFalse(billedOff.needsConsent(billed))
        assertNull(billedOff.patch(billedOff, billed))
        assertFalse(billedOff.copy(announcementSoundId = "s").patch(billedOff, billed)!!.toJson().containsKey("costAccepted"))

        // Not available on this PBX: cannot be switched on.
        assertFalse(on.copy(costAccepted = true).canSave(free.copy(recording = free.recording.copy(available = false))))
        assertEquals("€ 2,00", RecordingPrice.format(RecordingCost(20000), Locale("nl", "NL")).replace(' ', ' '))
    }
}

class DraftRebaseTest {
    @Test
    fun aStaleChainKeepsWhatTheUserTypedAndTakesTheRest() {
        val json = FsJson.default
        val baseline = ChainForwardingDraft.of(json.decodeFromString(NumberChain.serializer(), Fixtures.text("number-chain-simple.json")))
        val mine = baseline.copy(strategy = RingStrategy.SEQUENCE)
        val fresh = baseline.copy(unanswered = Fallback.Hangup, strategy = RingStrategy.ROUND)

        val merged = mine.rebase(fresh, baseline)

        assertEquals(RingStrategy.SEQUENCE, merged.strategy)
        assertEquals(Fallback.Hangup, merged.unanswered)
        // Against the fresh state only the touched field is a change.
        assertEquals(mine.strategy, merged.strategy)
        assertTrue(merged.step(fresh) != null)
        assertNull(merged.copy(strategy = RingStrategy.ROUND).step(fresh))
    }
}

class SoundRulesTest {
    @Test
    fun theRecordingHasLimits() {
        assertFalse(SoundRecordingRules.isLongEnough(0.4))
        assertTrue(SoundRecordingRules.isLongEnough(1.0))
        assertEquals("1:05", SoundRecordingRules.clock(65.4))
        assertEquals("5:00", SoundRecordingRules.clock(999.0))
        assertEquals("0:00", SoundRecordingRules.clock(-3.0))
    }

    @Test
    fun theUploadTypeFollowsTheExtension() {
        assertEquals("audio/wav", SoundRecordingRules.contentTypeFor("Welkom.WAV", null))
        assertEquals("audio/mpeg", SoundRecordingRules.contentTypeFor("a.mp3", "application/octet-stream"))
        assertEquals("audio/mp4", SoundRecordingRules.contentTypeFor("opname.m4a", null))
        assertEquals("audio/ogg", SoundRecordingRules.contentTypeFor("x", "audio/ogg"))
        assertEquals("application/octet-stream", SoundRecordingRules.contentTypeFor("x", null))
    }

    @Test
    fun aPlaceReadsAsKindThenName() {
        assertEquals("Keuzemenu (Support)", SoundPlaces.label(UsePlace("menu_greeting", "Support"), "Keuzemenu"))
        assertEquals("Keuzemenu", SoundPlaces.label(UsePlace("menu_greeting", ""), "Keuzemenu"))
        assertEquals("Support", SoundPlaces.label(UsePlace("new_kind", "Support"), null))
    }
}
