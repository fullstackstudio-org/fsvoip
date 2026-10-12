// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun account(id: String = "acc") = StoredAccount(
    id = id, label = "L", pbxName = "P", extensionName = "Jan", extensionNumber = "102", customerName = "C", deviceToken = Secret("t"), installId = "i",
    sip = SipCredentials("102", Secret("p"), "d", "x", 5060, ServerTransport.TCP, true), pairedAtMillis = 0,
)

private fun call(
    key: String,
    state: LiveState,
    startedAt: String = "2026-10-12T09:00:00.000Z",
    canPickup: Boolean = false,
    canTransfer: Boolean = false,
    mine: Boolean = false,
) = LiveCall(
    ref = "ref-$key",
    state = state,
    callerNumber = "0701234$key",
    calleeNumber = "0850000001",
    startedAt = Instant.parse(startedAt),
    canPickup = canPickup,
    canTransfer = canTransfer,
    mine = mine,
)

private class FakeLive : LiveServicing {
    var page = LiveCallsPage(enabled = true, fresh = true, calls = emptyList())
    var targetsPage = LiveTargetsPage(false, listOf(LiveTarget(LiveTargetType.EXTENSION, "t1", "Receptie", "101")))
    var callsError: Throwable? = null
    var pickupError: Throwable? = null
    var transferError: Throwable? = null
    var callsCount = 0
    var targetsCount = 0
    val pickups = mutableListOf<String>()
    val transfers = mutableListOf<Pair<String, String>>()

    override suspend fun calls(account: StoredAccount): LiveCallsPage {
        callsCount++
        callsError?.let { throw it }
        return page
    }

    override suspend fun targets(account: StoredAccount): LiveTargetsPage {
        targetsCount++
        return targetsPage
    }

    override suspend fun pickup(account: StoredAccount, ref: String): LivePickupResponse {
        pickups += ref
        pickupError?.let { throw it }
        return LivePickupResponse(LiveParty("102", "Jan"))
    }

    override suspend fun transfer(account: StoredAccount, ref: String, target: LiveTarget): LiveTransferResponse {
        transfers += ref to target.id
        transferError?.let { throw it }
        return LiveTransferResponse("internal")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTest {
    private val acc = account()

    @Test
    fun refreshFillsTheListOldestFirstAndLoadsTargetsOnce() = runTest {
        val service = FakeLive().apply {
            page = LiveCallsPage(true, true, listOf(call("2", LiveState.ACTIVE, "2026-10-12T09:10:00.000Z"), call("1", LiveState.RINGING, "2026-10-12T09:05:00.000Z"), call("3", LiveState.PARKED, "2026-10-12T09:20:00.000Z")))
        }
        val model = LiveModel(service, backgroundScope)

        assertTrue(model.refresh(acc))
        assertTrue(model.refresh(acc))

        assertEquals(listOf("ref-1", "ref-2", "ref-3"), model.calls.value.map { it.ref })
        assertEquals(1, model.ringing.size)
        assertEquals(1, model.running.size)
        assertEquals(1, model.parked.size)
        assertEquals(true, model.enabled.value)
        assertEquals(1, service.targetsCount)
        assertEquals(listOf("t1"), model.targets.value.map { it.id })
    }

    @Test
    fun aDisabledPbxHidesTheListAndSkipsTargets() = runTest {
        val service = FakeLive().apply { page = LiveCallsPage(enabled = false, fresh = false) }
        val model = LiveModel(service, backgroundScope)

        model.refresh(acc)

        assertEquals(false, model.enabled.value)
        assertTrue(model.calls.value.isEmpty())
        assertEquals(0, service.targetsCount)
    }

    @Test
    fun aServerThatCannotDoLiveCallsIsTheSameAsOff() = runTest {
        val service = FakeLive().apply { callsError = ApiException.Conflict("live_unavailable", null, null) }
        val model = LiveModel(service, backgroundScope)

        assertFalse(model.refresh(acc))

        assertEquals(false, model.enabled.value)
        assertNull(model.failure.value)
    }

    @Test
    fun aFailedPollKeepsTheOldListAndRemembersTheFailure() = runTest {
        val service = FakeLive().apply { page = LiveCallsPage(true, true, listOf(call("1", LiveState.RINGING))) }
        val model = LiveModel(service, backgroundScope)

        model.refresh(acc)
        service.callsError = ApiException.Transport("timeout")
        assertFalse(model.refresh(acc))

        assertEquals(1, model.calls.value.size)
        assertEquals(LiveFailure.Offline, model.failure.value)

        service.callsError = null
        model.refresh(acc)
        assertNull(model.failure.value)
    }

    @Test
    fun aRevokedPairingIsReported() = runTest {
        val service = FakeLive().apply { callsError = ApiException.Unauthorized() }
        val model = LiveModel(service, backgroundScope)
        var revoked = 0
        model.onRevoked = { revoked++ }

        model.refresh(acc)

        assertEquals(1, revoked)
        assertEquals(LiveFailure.Revoked, model.failure.value)
    }

    @Test
    fun switchingAccountStartsFromAnEmptyList() = runTest {
        val service = FakeLive().apply { page = LiveCallsPage(true, true, listOf(call("1", LiveState.RINGING))) }
        val model = LiveModel(service, backgroundScope)

        model.refresh(acc)
        service.callsError = ApiException.Transport("x")
        model.refresh(account("other"))

        assertTrue(model.calls.value.isEmpty())
        assertNull(model.enabled.value)
        assertEquals(LiveFailure.Offline, model.failure.value)
    }

    @Test
    fun pollingRunsEveryThreeSecondsAndStopsWhenTheScreenIsLeft() = runTest {
        val service = FakeLive()
        val model = LiveModel(service, backgroundScope)

        model.startPolling(acc)
        runCurrent()
        assertTrue(model.isPolling.value)
        assertEquals(1, service.callsCount)

        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(2, service.callsCount)

        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(4, service.callsCount)

        model.stopPolling()
        assertFalse(model.isPolling.value)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(4, service.callsCount)
    }

    @Test
    fun startingTwiceForTheSameAccountDoesNothing() = runTest {
        val service = FakeLive()
        val model = LiveModel(service, backgroundScope)

        model.startPolling(acc)
        model.startPolling(acc)
        runCurrent()

        assertEquals(1, service.callsCount)
        model.stopPolling()
    }

    @Test
    fun pollsOnlyWhenTheOnHoldTabIsVisibleAndTheAppResumed() {
        assertTrue(LiveRules.shouldPoll(resumed = true, selectedOnHoldTab = true))
        assertFalse(LiveRules.shouldPoll(resumed = false, selectedOnHoldTab = true))
        assertFalse(LiveRules.shouldPoll(resumed = true, selectedOnHoldTab = false))
    }

    @Test
    fun pickupSendsTheRefOfTheRowAndRefreshes() = runTest {
        val service = FakeLive()
        val model = LiveModel(service, backgroundScope)
        val row = call("1", LiveState.RINGING, canPickup = true)

        assertEquals(LiveModel.PickupResult.PickedUp, model.pickup(row, acc))

        assertEquals(listOf("ref-1"), service.pickups)
        assertEquals(1, service.callsCount)
        assertTrue(model.busyIds.value.isEmpty())
    }

    @Test
    fun pickupOfACallSomebodyElseAnsweredRefreshesAndExplains() = runTest {
        val service = FakeLive().apply { pickupError = ApiException.Conflict("call_not_ringing", null, null) }
        val model = LiveModel(service, backgroundScope)

        val result = model.pickup(call("1", LiveState.RINGING, canPickup = true), acc)

        assertEquals(LiveModel.PickupResult.Failed(LiveFailure.NotRinging), result)
        assertEquals(1, service.callsCount)
    }

    @Test
    fun transferSendsRefAndTargetId() = runTest {
        val service = FakeLive()
        val model = LiveModel(service, backgroundScope)
        val target = LiveTarget(LiveTargetType.RING_GROUP, "g1", "Iedereen", "200")

        val result = model.transfer(call("2", LiveState.ACTIVE, canTransfer = true, mine = true), target, acc)

        assertEquals(LiveModel.TransferResult.Transferred("internal"), result)
        assertEquals(listOf("ref-2" to "g1"), service.transfers)
    }

    @Test
    fun transferFailuresAreClassified() = runTest {
        val service = FakeLive()
        val model = LiveModel(service, backgroundScope)
        val row = call("2", LiveState.ACTIVE, canTransfer = true)
        val target = LiveTarget(LiveTargetType.EXTENSION, "t1", "Receptie", "101")

        service.transferError = ApiException.Forbidden("admin", null)
        assertEquals(LiveModel.TransferResult.Failed(LiveFailure.Forbidden), model.transfer(row, target, acc))

        service.transferError = ApiException.Conflict("call_not_transferable", null, null)
        assertEquals(LiveModel.TransferResult.Failed(LiveFailure.NotTransferable), model.transfer(row, target, acc))
        // The list is read again after this one.
        assertEquals(1, service.callsCount)

        service.transferError = ApiException.CallNotFound()
        assertEquals(LiveModel.TransferResult.Failed(LiveFailure.CallNotFound), model.transfer(row, target, acc))

        service.transferError = ApiException.Conflict("read_only", null, null)
        assertEquals(LiveModel.TransferResult.Failed(LiveFailure.ReadOnly), model.transfer(row, target, acc))
    }

    @Test
    fun anInvalidTargetReloadsTheTargets() = runTest {
        val service = FakeLive().apply { transferError = ApiException.InvalidRequest(null) }
        val model = LiveModel(service, backgroundScope)
        model.loadTargets(acc)

        val result = model.transfer(call("2", LiveState.ACTIVE, canTransfer = true), LiveTarget(LiveTargetType.EXTENSION, "gone", "X", "1"), acc)

        assertEquals(LiveModel.TransferResult.Failed(LiveFailure.InvalidTarget), result)
        assertEquals(2, service.targetsCount)
    }

    @Test
    fun errorsMapToFailures() {
        assertEquals(LiveFailure.CallNotFound, LiveFailure.classify(ApiException.NotFound()))
        assertEquals(LiveFailure.Unavailable, LiveFailure.classify(ApiException.Conflict("live_unavailable", null, null)))
        assertEquals(LiveFailure.Revoked, LiveFailure.classify(ApiException.Unauthorized()))
        assertEquals(LiveFailure.RateLimited(7), LiveFailure.classify(ApiException.RateLimited(7)))
        // An error code this version does not know is generic.
        assertEquals(LiveFailure.Other, LiveFailure.classify(ApiException.Conflict("something_new", null, null)))
        assertEquals(LiveFailure.Other, LiveFailure.classify(IllegalStateException()))
        assertEquals(LiveFailure.Other, LiveFailure.classify(ApiException.UnexpectedStatus(500)))
    }

    @Test
    fun theServerDecidesWhoMayPickUpAndTransfer() {
        val targets = listOf(LiveTarget(LiveTargetType.EXTENSION, "t1", "Receptie", "101"))
        // A user: a call that rings only at a colleague's personal extension has canPickup=false, a ring group call true.
        assertTrue(LiveRules.showPickup(call("1", LiveState.RINGING, canPickup = true)))
        assertFalse(LiveRules.showPickup(call("2", LiveState.RINGING, canPickup = false)))
        assertFalse(LiveRules.showPickup(call("3", LiveState.ACTIVE)))

        // A user transfers the own answered call only (canTransfer follows `mine`), an admin every answered call.
        assertTrue(LiveRules.showTransfer(call("4", LiveState.ACTIVE, canTransfer = true, mine = true), targets))
        assertFalse(LiveRules.showTransfer(call("5", LiveState.ACTIVE, canTransfer = false), targets))
        // Nowhere to transfer to: no button.
        assertFalse(LiveRules.showTransfer(call("6", LiveState.ACTIVE, canTransfer = true), emptyList()))
    }

    @Test
    fun targetsAreGroupedInAFixedOrderAndSearchable() {
        val targets = listOf(
            LiveTarget(LiveTargetType.VOICEMAIL, "v", "Voicemail Algemeen", "900"),
            LiveTarget(LiveTargetType.RING_GROUP, "g", "Iedereen", "200"),
            LiveTarget(LiveTargetType.EXTENSION, "e1", "Receptie", "101"),
            LiveTarget(LiveTargetType.EXTENSION, "e2", "Jan (balie)", "102"),
        )

        assertEquals(listOf(LiveTargetType.EXTENSION, LiveTargetType.RING_GROUP, LiveTargetType.VOICEMAIL), LiveRules.grouped(targets, "").map { it.first })
        assertEquals(listOf("e2"), LiveRules.grouped(targets, " jan ").single().second.map { it.id })
        assertEquals(listOf("v"), LiveRules.grouped(targets, "900").single().second.map { it.id })
        assertTrue(LiveRules.grouped(targets, "zzz").isEmpty())
    }

    @Test
    fun theClockFormat() {
        assertEquals("0:00", LiveRules.clock(-4))
        assertEquals("1:23", LiveRules.clock(83))
        assertEquals("1:02:03", LiveRules.clock(3723))
    }
}
