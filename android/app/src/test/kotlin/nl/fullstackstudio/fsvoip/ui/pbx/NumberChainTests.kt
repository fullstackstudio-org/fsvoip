// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.pbx

import org.junit.Test
import nl.fullstackstudio.fsvoip.core.Change
import nl.fullstackstudio.fsvoip.core.ChainClosedStep
import nl.fullstackstudio.fsvoip.core.ChainHoursStep
import nl.fullstackstudio.fsvoip.core.ChainMenuForwardingStep
import nl.fullstackstudio.fsvoip.core.ChainNameStep
import nl.fullstackstudio.fsvoip.core.ChainStandardForwardingStep
import nl.fullstackstudio.fsvoip.core.ChainWelcomeStep
import nl.fullstackstudio.fsvoip.core.Fallback
import nl.fullstackstudio.fsvoip.core.PbxTarget
import nl.fullstackstudio.fsvoip.core.RingStrategy
import nl.fullstackstudio.fsvoip.core.TargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class NumberChainTests {
    private val simple = Fx.chainSimple()
    private val menu = Fx.chainMenu()

    // MARK: Name

    @Test fun nameStepSendsTheTrimmedNameWithTheVersions() {
        val baseline = NumberNameDraft(simple)
        val step = baseline.copy(name = "  Receptie  ").step(baseline, simple) as ChainNameStep

        assertEquals("Receptie", step.name)
        assertEquals(simple.stepVersions, step.versions)
    }

    @Test fun aBlankNameClearsTheName() {
        val baseline = NumberNameDraft(simple)

        assertNull((baseline.copy(name = "   ").step(baseline, simple) as ChainNameStep).name)
    }

    @Test fun nothingChangedSendsNothing() {
        assertNull(NumberNameDraft(simple).step(NumberNameDraft(simple), simple))
        // Only spaces around the same name is not a change either.
        assertNull(NumberNameDraft(" Hoofdnummer ").step(NumberNameDraft(simple), simple))
    }

    // MARK: Hours

    @Test fun onlyTheClosedBehaviourChangedIsTheClosedStep() {
        val baseline = NumberHoursDraft(simple)
        val draft = baseline.copy(closed = Fallback.Forward("0612345678"))
        val step = draft.step(baseline, simple) as ChainClosedStep

        assertEquals(Fallback.Forward("0612345678"), step.closed)
        assertEquals(Change.Keep, step.holiday)
    }

    @Test fun aChangedWeekIsTheHoursStepWithOnlyTheWeek() {
        val baseline = NumberHoursDraft(simple)
        val draft = baseline.copy(week = baseline.week.with(6, listOf(HoursInterval("10:00", "12:00"))))
        val step = draft.step(baseline, simple) as ChainHoursStep

        assertTrue(step.enabled)
        assertNotNull(step.week)
        assertTrue(step.week!!.any { it.day == 6 && it.from == "10:00" })
        assertNull(step.holidays)
        assertNull(step.closed)
    }

    @Test fun nationalAndOwnDatesTravelInTheirOwnInput() {
        val baseline = NumberHoursDraft(simple)
        val onlyNational = baseline.copy(national = false).step(baseline, simple) as ChainHoursStep

        assertEquals(false, onlyNational.holidays?.national)
        assertNull(onlyNational.holidays?.dates)
        assertNull(onlyNational.week)
    }

    @Test fun switchingTheHoursOffSendsOnlyEnabledFalse() {
        val baseline = NumberHoursDraft(simple)
        val step = baseline.copy(enabled = false).step(baseline, simple) as ChainHoursStep

        assertFalse(step.enabled)
        assertNull(step.week)
    }

    @Test fun switchingTheHoursOnSendsEverything() {
        val off = NumberHoursDraft(false, WeekPlan.preset(WeekPlan.Preset.OFFICE), true, emptyList(), Fallback.DEFAULT, null)
        val step = off.copy(enabled = true).step(off, simple) as ChainHoursStep

        assertTrue(step.enabled)
        assertNotNull(step.week)
        assertNotNull(step.holidays)
        assertNotNull(step.closed)
    }

    @Test fun aReadOnlyFallbackIsNeverSent() {
        val baseline = NumberHoursDraft(menu)
        // The holiday behaviour of the menu chain is something the portal set up (`other`): keep it, do not send it.
        val step = baseline.copy(closed = Fallback.Hangup).step(baseline, menu) as ChainClosedStep

        assertEquals(Change.Keep, step.holiday)
    }

    @Test fun clearingTheHolidayBehaviourIsAnExplicitClear() {
        val baseline = NumberHoursDraft(simple).copy(holiday = Fallback.Hangup)
        val step = baseline.copy(holiday = null).step(baseline, simple) as ChainClosedStep

        assertEquals(Change.Clear, step.holiday)
    }

    @Test fun ownDatesStopAtTheLimit() {
        val draft = NumberHoursDraft(simple)
        val many = (1..NumberHoursDraft.OWN_DATE_LIMIT + 1).map { "2027-01-%02d".format(it.coerceAtMost(28)) + "-" + it }

        assertNull(draft.adding(many, "", "Gesloten"))
    }

    @Test fun addingDatesKeepsTheNameAndSkipsTheOnesThatAreThere() {
        val draft = NumberHoursDraft(simple)
        val added = draft.adding(listOf("2026-08-03", "2026-08-04"), " Verbouwing ", "Gesloten")!!

        assertEquals(2, added.size)
        assertEquals("Bouwvak", added.first { it.date == "2026-08-03" }.name)
        assertEquals("Verbouwing", added.first { it.date == "2026-08-04" }.name)
        assertEquals("Gesloten", draft.adding(listOf("2026-09-01"), "", "Gesloten")!!.first { it.date == "2026-09-01" }.name)
    }

    // MARK: Welcome

    @Test fun aWelcomeMessageNeedsASound() {
        val baseline = NumberWelcomeDraft(false, null)

        assertFalse(baseline.copy(enabled = true).canSave)
        assertTrue(baseline.copy(enabled = true, soundId = "x").canSave)
        assertTrue(baseline.canSave)
    }

    @Test fun welcomeStepSendsTheSoundOnlyWhenItChanged() {
        val baseline = NumberWelcomeDraft(simple)
        val off = baseline.copy(enabled = false).step(baseline, simple) as ChainWelcomeStep

        assertFalse(off.enabled)
        assertEquals(Change.Keep, off.soundId)

        val other = baseline.copy(soundId = "snd-2").step(baseline, simple) as ChainWelcomeStep
        assertEquals(Change.Set("snd-2"), other.soundId)
    }

    // MARK: Forwarding

    @Test fun togglingAMemberOffRemovesIt() {
        val draft = NumberForwardingDraft(simple)
        val without = draft.withMember("5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50", false)

        assertEquals(draft.members.size - 1, without.members.size)
        assertFalse(without.isMember("5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50"))
        assertTrue(without.withMember("5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50", true).isMember("5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50"))
    }

    @Test fun aNewMemberRingsAsLongAsTheOthers() {
        val draft = NumberForwardingDraft(simple).copy(members = listOf(simple.options.devices.take(1).map { nl.fullstackstudio.fsvoip.core.ChainMember(it.id, 0, 30) }).flatten())

        assertEquals(30, draft.withMember("other", true).members.last().timeoutSeconds)
    }

    @Test fun ringTimeChangesEveryMember() {
        val changed = NumberForwardingDraft(simple).withRingSeconds(40)

        assertTrue(changed.members.all { it.timeoutSeconds == 40 })
        assertEquals(40, changed.ringSeconds)
        // The fixture has members with different times: unknown until one is chosen.
        assertNull(NumberForwardingDraft(simple).ringSeconds)
    }

    @Test fun theStandardStepNeedsAtLeastOnePhone() {
        val draft = NumberForwardingDraft(simple)

        assertFalse(draft.copy(members = emptyList()).canSave)
        assertTrue(draft.canSave)
    }

    @Test fun anUnansweredFallbackChangeIsAStandardStep() {
        val baseline = NumberForwardingDraft(simple)
        val step = baseline.copy(unanswered = Fallback.Hangup).step(baseline, simple) as ChainStandardForwardingStep

        assertEquals(Fallback.Hangup, step.unanswered)
        assertNull(step.members)
        assertNull(step.strategy)
    }

    @Test fun switchingToAMenuSendsTheWholeMenu() {
        val baseline = NumberForwardingDraft(simple)
        val draft = baseline.copy(kind = NumberForwardingDraft.Kind.MENU, greetingSoundId = "snd", repeats = 2, timeoutSeconds = 8, keys = listOf(nl.fullstackstudio.fsvoip.core.ChainKey("1", PbxTarget.obj(TargetType.DEVICE, "d"))))
        val step = draft.step(baseline, simple) as ChainMenuForwardingStep

        assertNotNull(step.keys)
        assertEquals(Change.Set("snd"), step.greetingSoundId)
    }

    @Test fun aMenuNeedsAKey() {
        val draft = NumberForwardingDraft(menu)

        assertTrue(draft.canSave)
        assertFalse(draft.copy(keys = emptyList()).canSave)
    }

    @Test fun aKeyFromThePortalIsNeverChanged() {
        val draft = NumberForwardingDraft(menu)

        // Key 3 leads to a queue (`editable = false`): a change or removal leaves it alone.
        assertEquals(draft, draft.withKey("3", null))
        assertEquals(draft, draft.withKey("3", PbxTarget.obj(TargetType.DEVICE, "x")))
    }

    @Test fun removingTheDefaultKeyClearsTheDefault() {
        val draft = NumberForwardingDraft(menu)

        assertEquals("1", draft.defaultKey)
        assertNull(draft.withKey("1", null).defaultKey)
        assertEquals("1", draft.withKey("2", null).defaultKey)
    }

    @Test fun keysAreKeptInDigitOrder() {
        val draft = NumberForwardingDraft(menu).withKey("0", PbxTarget.external("0201234567")).withKey("4", PbxTarget.external("0301234567"))

        assertEquals(listOf("1", "2", "3", "4", "0"), draft.keys.map { it.digit })
    }

    // MARK: Recording

    @Test fun switchingOnPaidRecordingNeedsConsent() {
        val draft = NumberRecordingDraft(simple)

        assertFalse(draft.needsConsent(simple.recording))
        assertTrue(draft.copy(enabled = true).needsConsent(simple.recording))
    }

    @Test fun anAlreadyBillingRecordingNeedsNoConsent() {
        val draft = NumberRecordingDraft(menu)

        assertFalse(draft.needsConsent(menu.recording))
    }

    @Test fun recordingPatchCarriesTheConsentAndTheVersion() {
        val baseline = NumberRecordingDraft(simple)
        val patch = baseline.copy(enabled = true, costAccepted = true).patch(baseline, simple)!!

        assertTrue(patch.enabled)
        assertEquals(true, patch.costAccepted)
        assertEquals(simple.version, patch.version)
        assertNull(baseline.patch(baseline, simple))
    }

    // MARK: Rebase after stale

    @Test fun staleKeepsWhatTheUserTypedAndTakesTheRest() {
        val baseline = NumberHoursDraft(simple)
        val mine = baseline.copy(closed = Fallback.Hangup)
        val fresh = NumberHoursDraft(simple).copy(national = false)
        val rebased = mine.rebase(baseline, fresh)

        assertEquals(Fallback.Hangup, rebased.closed)
        assertFalse(rebased.national)
    }

    @Test fun staleWhenSwitchingStandardToMenuKeepsTheKind() {
        val baseline = NumberForwardingDraft(simple)
        val mine = baseline.copy(kind = NumberForwardingDraft.Kind.MENU)
        val rebased = mine.rebase(baseline, NumberForwardingDraft(menu))

        assertEquals(NumberForwardingDraft.Kind.MENU, rebased.kind)
    }

    @Test fun anUntouchedFieldFollowsTheFreshChain() {
        val baseline = NumberNameDraft(simple)

        assertEquals("Nieuw", NumberNameDraft(simple).rebase(baseline, NumberNameDraft("Nieuw")).name)
        assertEquals("Mijn", NumberNameDraft("Mijn").rebase(baseline, NumberNameDraft("Nieuw")).name)
    }

    // MARK: Week plan and day bars

    @Test fun presetsAreRecognised() {
        assertEquals(WeekPlan.Preset.OFFICE, WeekPlan.preset(WeekPlan.Preset.OFFICE).matchingPreset)
        assertEquals(WeekPlan.Preset.ALWAYS, WeekPlan.preset(WeekPlan.Preset.ALWAYS).matchingPreset)
        assertEquals(WeekPlan.Preset.CLOSED, WeekPlan().matchingPreset)
        assertNull(WeekPlan.preset(WeekPlan.Preset.OFFICE).with(6, listOf(HoursInterval("09:00", "12:00"))).matchingPreset)
    }

    @Test fun aDayWithoutHoursEqualsASwitchedOffDay() {
        assertEquals(WeekPlan(), WeekPlan().with(3, emptyList()))
    }

    @Test fun timesParseAndFormat() {
        assertEquals(540, DayBarsLogic.minutes("09:00"))
        assertEquals(1440, DayBarsLogic.minutes("24:00"))
        assertNull(DayBarsLogic.minutes("25:00"))
        assertNull(DayBarsLogic.minutes("9:7x"))
        assertEquals("08:30", DayBarsLogic.format(510))
    }

    @Test fun aSlotNeedsToEndAfterItStarts() {
        assertTrue(DayBarsLogic.isValid(HoursInterval("09:00", "17:00")))
        assertTrue(DayBarsLogic.isValid(HoursInterval("00:00", "24:00")))
        assertFalse(DayBarsLogic.isValid(HoursInterval("17:00", "09:00")))
        assertFalse(DayBarsLogic.isValid(HoursInterval("09:00", "09:00")))
        assertFalse(DayBarsLogic.isValid(HoursInterval("09:00", "xx")))
    }

    @Test fun segmentsAreFractionsOfTheDay() {
        val segments = DayBarsLogic.segments(listOf(HoursInterval("06:00", "12:00"), HoursInterval("18:00", "24:00")))

        assertEquals(2, segments.size)
        assertEquals(0.25, segments[0].start, 1e-9)
        assertEquals(0.5, segments[0].endInclusive, 1e-9)
        assertEquals(1.0, segments[1].endInclusive, 1e-9)
    }

    @Test fun theNextSlotStartsAfterTheLastOne() {
        val next = DayBarsLogic.nextSlot(listOf(HoursInterval("09:00", "12:00")))

        assertTrue(DayBarsLogic.isValid(next))
        assertTrue(DayBarsLogic.minutes(next.from)!! >= 12 * 60)
        assertEquals(WeekPlan.defaultSlot.from, DayBarsLogic.nextSlot(emptyList()).from)
    }

    // MARK: Words

    @Test fun theNumberWordsReadTheChain() {
        val text = DutchText()
        val words = NumberWords(text, PbxVocabulary(text, dutchLocale))

        assertEquals("Uit", words.recording(simple))
        assertNotNull(words.hours(simple))
        assertTrue(words.fallback(Fallback.Forward("0612345678"), simple.options).contains("0612345678"))
        assertEquals("Welkom", words.soundName("a1b2c3d4-1111-4a2b-8c3d-4e5f6a7b8c9d", simple.options))
    }
}
