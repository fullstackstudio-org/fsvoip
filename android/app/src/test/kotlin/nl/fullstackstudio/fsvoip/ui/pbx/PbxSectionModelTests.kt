// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.pbx

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.ApiErrorPlace
import nl.fullstackstudio.fsvoip.core.ChainNameStep
import nl.fullstackstudio.fsvoip.core.Change
import nl.fullstackstudio.fsvoip.core.NumberRecordingPatch
import nl.fullstackstudio.fsvoip.core.PbxHolidayInput
import nl.fullstackstudio.fsvoip.core.PbxTarget
import nl.fullstackstudio.fsvoip.core.RingStrategy
import nl.fullstackstudio.fsvoip.core.SyncState
import nl.fullstackstudio.fsvoip.core.TargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PbxSectionModelTests {
    private fun model(service: FakePbxService, readOnly: Boolean = false, sleeps: MutableList<Long>? = null) =
        PbxSectionModel("a", "Centrale", readOnly, service, testScope(), sleep = { sleeps?.add(it) })

    // MARK: Drafts become patches

    @Test fun aDevicePatchHasOnlyTheChangedKeysAndTheVersion() {
        val device = Fx.devices().devices.first()
        val draft = DeviceDraft(device).copy(dnd = true, noAnswerSeconds = 40)
        val patch = draft.patch(device)!!

        assertEquals(device.version, patch.version)
        assertEquals(true, patch.dnd)
        assertEquals(40, patch.noAnswerSeconds)
        assertNull(patch.voicemailEnabled)
        assertEquals(Change.Keep, patch.busyTarget)
        assertNull(DeviceDraft(device).patch(device))
    }

    @Test fun clearingATargetIsAnExplicitNull() {
        val device = Fx.devices().devices.first { it.noAnswerTarget != null }
        val patch = DeviceDraft(device).copy(noAnswerTarget = null).patch(device)!!

        assertEquals(Change.Clear, patch.noAnswerTarget)
    }

    @Test fun aRingGroupNeedsANameAndAMember() {
        assertFalse(RingGroupDraft().isFilledIn)
        assertFalse(RingGroupDraft(name = "  ").copy(members = Fx.ringGroups().ringGroups.first().members).isFilledIn)
        assertTrue(RingGroupDraft(name = "Support").copy(members = Fx.ringGroups().ringGroups.first().members).isFilledIn)
    }

    @Test fun aRingGroupPatchSendsOnlyWhatChanged() {
        val group = Fx.ringGroups().ringGroups.first()
        val patch = RingGroupDraft(group).copy(name = " Allemaal ", strategy = RingStrategy.ROUND).patch(group)!!

        assertEquals(group.version, patch.version)
        assertEquals("Allemaal", patch.name)
        assertEquals(RingStrategy.ROUND, patch.strategy)
        assertNull(patch.members)
        assertNull(RingGroupDraft(group).patch(group))
    }

    @Test fun anHoursPatchSendsTheWeekAndTheVersion() {
        val hours = Fx.hours().hours.first()
        val draft = HoursDraft(hours)
        val changed = draft.copy(days = draft.days + (6 to listOf(HoursInterval("10:00", "12:00"))))
        val patch = changed.patch(hours)!!

        assertEquals(hours.version, patch.version)
        assertTrue(patch.week!!.any { it.day == 6 })
        assertNull(HoursDraft(hours).patch(hours))
    }

    // MARK: Temporary closing

    @Test fun aRangeBecomesOneDatePerDay() {
        assertEquals(listOf("2026-12-24", "2026-12-25", "2026-12-26"), TemporaryClosure.dates(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 26)))
        assertTrue(TemporaryClosure.dates(LocalDate.of(2026, 12, 26), LocalDate.of(2026, 12, 24)).isEmpty())
    }

    @Test fun closingKeepsTheExistingHolidaysAndAddsOwnDates() {
        val hours = Fx.hours().hours.first()
        val plan = TemporaryClosure.closing(hours, "Verbouwing", listOf("2026-09-01"), "Gesloten") as TemporaryClosure.Plan.Success

        assertTrue(plan.holidays.any { it is PbxHolidayInput.Rule })
        assertTrue(plan.holidays.any { it == PbxHolidayInput.Custom("Verbouwing", "2026-09-01") })
        assertTrue(plan.holidays.any { it == PbxHolidayInput.Custom("Bouwvak", "2026-08-03") })
    }

    @Test fun anEmptyRangeCannotBeSaved() {
        val plan = TemporaryClosure.closing(Fx.hours().hours.first(), "", emptyList(), "Gesloten")

        assertEquals(TemporaryClosure.Plan.Failure(TemporaryClosure.PlanError.EmptyRange), plan)
    }

    @Test fun tooManyOwnDatesIsRefusedWithTheRoomThatIsLeft() {
        val hours = Fx.hours().hours.first()
        val many = (1..25).map { "2027-01-%02d".format(it) }
        val plan = TemporaryClosure.closing(hours, "", many, "Gesloten") as TemporaryClosure.Plan.Failure

        assertEquals(TemporaryClosure.PlanError.TooMany(TemporaryClosure.CUSTOM_DATE_LIMIT - 1), plan.error)
    }

    @Test fun reopeningRemovesOnlyThatDate() {
        val hours = Fx.hours().hours.first()
        val list = TemporaryClosure.reopening(hours, "2026-08-03")

        assertTrue(list.none { it is PbxHolidayInput.Custom })
        assertTrue(list.any { it is PbxHolidayInput.Rule })
    }

    // MARK: Saving

    @Test fun savingADeviceSendsItAndReloads() = runBlocking {
        val service = FakePbxService()
        val model = model(service)
        model.load(PbxPart.OVERVIEW)
        val device = service.devicesValue.devices.first()

        val outcome = model.saveDevice(DeviceDraft(device).copy(dnd = true), device)

        assertEquals(PbxSaveOutcome.Saved, outcome)
        assertEquals(listOf("updateDevice:${device.id}"), service.calls)
        assertTrue(service.overviewLoads >= 2)
    }

    @Test fun nothingChangedSendsNothing() = runBlocking {
        val service = FakePbxService()
        val device = service.devicesValue.devices.first()

        assertEquals(PbxSaveOutcome.Unchanged, model(service).saveDevice(DeviceDraft(device), device))
        assertTrue(service.calls.isEmpty())
    }

    @Test fun aFrozenCentraleSendsNothing() = runBlocking {
        val service = FakePbxService()
        val device = service.devicesValue.devices.first()
        val model = model(service, readOnly = true)

        assertEquals(PbxSaveOutcome.Failed(PbxFailure.ReadOnly), model.saveDevice(DeviceDraft(device).copy(dnd = true), device))
        assertEquals(ChainSaveOutcome.Failed(PbxFailure.ReadOnly), model.saveChainStep("n", ChainNameStep("x")))
        assertTrue(service.calls.isEmpty())
    }

    @Test fun readOnlyFromTheServerLocksTheScreens() = runBlocking {
        val service = FakePbxService()
        val device = service.devicesValue.devices.first()
        val model = model(service)
        service.failure = ApiException.ReadOnly()

        assertEquals(PbxSaveOutcome.Failed(PbxFailure.ReadOnly), model.saveDevice(DeviceDraft(device).copy(dnd = true), device))
        assertTrue(model.state.value.isReadOnly)
    }

    @Test fun aStaleSaveClosesTheFormAndLoadsTheNewState() = runBlocking {
        val service = FakePbxService()
        val device = service.devicesValue.devices.first()
        val model = model(service)
        model.load(PbxPart.DEVICES)
        service.failure = null
        service.calls.clear()

        // The update itself is stale; reading must keep working.
        val failing = object : PbxServicing by service {
            override suspend fun updateDevice(id: String, patch: nl.fullstackstudio.fsvoip.core.PbxDevicePatch) = throw ApiException.Stale(9)
        }
        val staleModel = PbxSectionModel("a", "Centrale", false, failing, testScope())
        staleModel.load(PbxPart.DEVICES)
        val outcome = staleModel.saveDevice(DeviceDraft(device).copy(dnd = true), device)

        assertEquals(PbxSaveOutcome.Stale, outcome)
        assertTrue(outcome.closesForm)
        assertNotNull(staleModel.state.value.devices)
    }

    @Test fun inUseListsThePlaces() {
        val failure = PbxFailure.classify(ApiException.InUse(listOf(ApiErrorPlace("business_hours", "Kantoortijden"), ApiErrorPlace("ivr", "Keuzemenu"))))

        assertEquals(PbxFailure.InUse(listOf("Kantoortijden", "Keuzemenu")), failure)
        assertTrue(failure.message(DutchText()).contains("Kantoortijden, Keuzemenu"))
    }

    @Test fun blockedDestinationShowsTheNumbers() {
        val failure = PbxFailure.classify(ApiException.BlockedDestination(listOf("+44123")))

        assertTrue(failure.message(DutchText()).contains("+44123"))
    }

    @Test fun rateLimitedSaysHowLong() {
        val text = PbxFailure.classify(ApiException.RateLimited(30)).message(DutchText())

        assertTrue(text.contains("30"))
    }

    @Test fun errorsMapToTheirFailure() {
        assertEquals(PbxFailure.AccessLost, PbxFailure.classify(ApiException.Forbidden("x")))
        assertEquals(PbxFailure.Revoked, PbxFailure.classify(ApiException.Unauthorized()))
        assertEquals(PbxFailure.Offline, PbxFailure.classify(ApiException.Transport("down")))
        assertEquals(PbxFailure.Advanced, PbxFailure.classify(ApiException.Advanced()))
        assertEquals(PbxFailure.GreetingRequired, PbxFailure.classify(ApiException.GreetingRequired()))
        assertEquals(PbxFailure.Invalid, PbxFailure.classify(ApiException.Invalid("x", "name")))
        assertEquals(PbxFailure.Other, PbxFailure.classify(IllegalStateException()))
    }

    @Test fun losingTheRoleAndTheRevokedPairingAreReported() = runBlocking {
        val service = FakePbxService()
        val model = model(service)
        var lost = 0
        var revoked = 0
        model.onAccessLost = { lost += 1 }
        model.onRevoked = { revoked += 1 }

        service.failure = ApiException.Forbidden()
        model.load(PbxPart.OVERVIEW)
        service.failure = ApiException.Unauthorized()
        model.load(PbxPart.OVERVIEW)

        assertEquals(1, lost)
        assertEquals(1, revoked)
    }

    @Test fun offlineKeepsTheLastStateAndMarksItOutdated() = runBlocking {
        val service = FakePbxService()
        val model = model(service)
        model.load(PbxPart.OVERVIEW)
        service.failure = ApiException.Transport("offline")
        model.load(PbxPart.OVERVIEW)

        assertNotNull(model.state.value.overview)
        assertTrue(model.state.value.isOutdated)
    }

    // MARK: Chains

    @Test fun staleChainShowsTheFreshChainAndKeepsTheFormOpen() = runBlocking {
        val service = FakePbxService()
        val model = model(service)
        model.loadChain(service.chain.id)
        service.onSaveChain = { throw staleChain() }

        val outcome = model.saveChainStep(service.chain.id, ChainNameStep("Nieuw"))

        assertEquals(ChainSaveOutcome.Stale, outcome)
        assertEquals(5, model.state.value.chains[service.chain.id]?.version)
    }

    @Test fun costNotAcceptedKeepsTheFormAndCarriesThePrice() = runBlocking {
        val service = FakePbxService()
        val failing = object : PbxServicing by service {
            override suspend fun setNumberRecording(numberId: String, patch: NumberRecordingPatch) = throw ApiException.CostNotAccepted(
                kotlinx.serialization.json.Json.parseToJsonElement(Fx.text("error-cost-not-accepted")).let { (it as kotlinx.serialization.json.JsonObject)["cost"] },
            )
        }
        val model = PbxSectionModel("a", "Centrale", false, failing, testScope())

        val outcome = model.saveRecording(service.chain.id, NumberRecordingPatch(true, version = 4))

        val required = outcome as ChainSaveOutcome.CostRequired
        assertEquals(20000, required.cost?.priceE4)
    }

    @Test fun anAdvancedAnswerReloadsTheChain() = runBlocking {
        val service = FakePbxService()
        val model = model(service)
        service.onSaveChain = { throw ApiException.Advanced() }

        val outcome = model.saveChainStep(service.chain.id, ChainNameStep("x"))

        assertEquals(ChainSaveOutcome.Failed(PbxFailure.Advanced), outcome)
        assertNotNull(model.state.value.chains[service.chain.id])
    }

    @Test fun aSavedStepRefreshesTheListAndTheChain() = runBlocking {
        val service = FakePbxService()
        val model = model(service)
        model.load(PbxPart.NUMBERS)
        service.onSaveChain = { service.chain }

        val outcome = model.saveChainStep(service.chain.id, ChainNameStep("Nieuw"))

        assertEquals(ChainSaveOutcome.Saved, outcome)
        assertNotNull(model.state.value.chains[service.chain.id])
    }

    @Test fun nothingToSendIsUnchangedForAChain() = runBlocking {
        val model = model(FakePbxService())

        assertEquals(ChainSaveOutcome.Unchanged, model.saveChainStep("n", null))
        assertEquals(ChainSaveOutcome.Unchanged, model.saveRecording("n", null))
    }

    // MARK: Pending sync

    @Test fun pollingStopsWhenTheSyncIsDone() = runBlocking {
        val service = FakePbxService()
        val pending = service.overviewValue.numbers.map { it.copy(sync = SyncState.PENDING) }
        service.overviewValue = service.overviewValue.copy(numbers = pending)
        val sleeps = mutableListOf<Long>()
        val model = model(service, sleeps = sleeps)
        model.load(PbxPart.OVERVIEW)
        assertTrue(model.state.value.hasPendingSync)

        // After the first round the PBX is done.
        val done = service.overviewValue.numbers.map { it.copy(sync = SyncState.OK) }
        val original = service.overviewValue
        service.overviewValue = original
        model.startPollingIfNeeded()
        service.overviewValue = original.copy(numbers = done)

        // The unconfined scope runs the poll to its first sleep and on: give it a reload.
        model.reloadLoaded()

        assertFalse(model.state.value.hasPendingSync)
    }

    @Test fun pollingGivesUpAfterThreeMinutes() = runBlocking {
        val service = FakePbxService()
        service.overviewValue = service.overviewValue.copy(numbers = service.overviewValue.numbers.map { it.copy(sync = SyncState.PENDING) })
        val sleeps = mutableListOf<Long>()
        val model = model(service, sleeps = sleeps)
        model.load(PbxPart.OVERVIEW)
        model.startPollingIfNeeded()

        assertEquals(PbxSectionModel.MAX_POLL_ROUNDS, sleeps.size)
        assertTrue(sleeps.all { it == PbxSectionModel.POLL_INTERVAL_MILLIS })
        assertTrue(model.state.value.syncTimedOut)
    }

    // MARK: Routing

    @Test fun routingToTheSameTargetSendsNothing() = runBlocking {
        val service = FakePbxService()
        val number = service.overviewValue.numbers.first { it.routing != null }

        assertEquals(PbxSaveOutcome.Unchanged, model(service).saveRouting(number, number.routing))
        assertTrue(service.calls.isEmpty())

        assertEquals(PbxSaveOutcome.Saved, model(service).saveRouting(number, PbxTarget.obj(TargetType.DEVICE, "x")))
        assertEquals(listOf("routing:${number.id}"), service.calls)
    }
}
