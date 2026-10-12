// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.callcontroller

import java.time.Duration
import java.time.Instant
import java.util.UUID
import nl.fullstackstudio.fsvoip.callcontroller.Harness.Companion.ACCOUNT
import nl.fullstackstudio.fsvoip.callcontroller.Harness.Companion.CALL_REF
import nl.fullstackstudio.fsvoip.callcontroller.ImmediateCallSystem.Event
import nl.fullstackstudio.fsvoip.core.PushCaller
import nl.fullstackstudio.fsvoip.core.PushMessage
import nl.fullstackstudio.fsvoip.core.RecentCall
import nl.fullstackstudio.fsvoip.core.RingPush
import nl.fullstackstudio.fsvoip.sipengine.CallDirection
import nl.fullstackstudio.fsvoip.sipengine.CallEndReason
import nl.fullstackstudio.fsvoip.sipengine.CallState
import nl.fullstackstudio.fsvoip.sipengine.SipAccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingPushTest {
    private val policy = IncomingPushPolicy()
    private val now = Instant.parse("2026-10-08T12:34:56Z")
    private val uuid = UUID.fromString(CALL_REF)

    private fun ring(expires: Instant = now.plusSeconds(12), account: String = ACCOUNT, number: String? = "+31701234567", name: String? = "Bakkerij Smit") =
        PushMessage.Ring(RingPush(CALL_REF, PushCaller(number, name), account, "Voorbeeld Bouw · Jan de Vries", expires))

    private fun pushAt(h: Harness, message: PushMessage? = ring(expires = h.clock.now.plusSeconds(12))) = h.phone.handleRingPush(message)

    // MARK: Policy

    @Test
    fun theFcmFixtureDecodesAndRings() {
        val fcm = nl.fullstackstudio.fsvoip.core.FsJson.default.parseToJsonElement(fixture("fcm-message.json"))
        val text = (fcm as kotlinx.serialization.json.JsonObject)["message"].toString()
        val data = kotlinx.serialization.json.Json.parseToJsonElement(text).let { it as kotlinx.serialization.json.JsonObject }["data"] as kotlinx.serialization.json.JsonObject
        val message = PushMessage.decodeFcm(data.mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content })
        val decision = policy.decide(message, Instant.parse("2026-10-08T12:34:57Z"), setOf(ACCOUNT), busy = false) { false }
        assertTrue(decision is RingPushDecision.Ring)
    }

    @Test
    fun aFreshPushRingsAndWaitsTenSecondsForTheInvite() {
        val decision = policy.decide(ring(expires = now.plusSeconds(30)), now, setOf(ACCOUNT), busy = false) { false } as RingPushDecision.Ring
        assertEquals(now.plusSeconds(10), decision.inviteDeadline)
    }

    @Test
    fun aLatePushWaitsUntilShortlyAfterExpiryButAtLeastTwoSeconds() {
        val ring = (ring(expires = now.plusSeconds(1)) as PushMessage.Ring).ring
        assertEquals(now.plusSeconds(6), policy.inviteDeadline(ring, now))
        assertEquals(now.plusSeconds(7), policy.inviteDeadline(ring, now.plusSeconds(5)))
    }

    @Test
    fun rejections() {
        fun decide(message: PushMessage?, accounts: Set<String> = setOf(ACCOUNT), busy: Boolean = false, handled: Boolean = false) =
            (policy.decide(message, now, accounts, busy) { handled } as RingPushDecision.Reject).rejection

        assertEquals(RingPushRejection.EXPIRED, decide(ring(expires = now.minusSeconds(6))))
        assertEquals(RingPushRejection.INVALID_PAYLOAD, decide(null))
        assertEquals(RingPushRejection.INVALID_PAYLOAD, decide(PushMessage.Refresh(nl.fullstackstudio.fsvoip.core.RefreshPush(ACCOUNT))))
        assertEquals(RingPushRejection.UNKNOWN_ACCOUNT, decide(ring(), accounts = emptySet()))
        assertEquals(RingPushRejection.BUSY, decide(ring(), busy = true))
        assertEquals(RingPushRejection.ALREADY_HANDLED, decide(ring(), busy = true, handled = true))
    }

    @Test
    fun callUuidAndCallerMatch() {
        assertEquals(uuid, IncomingPushPolicy.callUuid(CALL_REF))
        assertNull(IncomingPushPolicy.callUuid("not-a-uuid"))
        assertTrue(IncomingPushPolicy.callersMatch("+31612345678", "0612345678"))
        assertTrue(IncomingPushPolicy.callersMatch(null, "0612345678"))
        assertFalse(IncomingPushPolicy.callersMatch("0612345678", "0687654321"))
    }

    // MARK: Phone

    @Test
    fun pushReportsTheCallAtOnceAndWakesThePushedAccount() {
        val h = Harness()
        val outcome = pushAt(h)

        assertEquals(IncomingPushOutcome.Ringing(uuid), outcome)
        val reported = h.system.events.filterIsInstance<Event.ReportedIncoming>().single()
        assertEquals(uuid, reported.uuid)
        assertEquals("Bakkerij Smit", reported.display.callerTitle)
        assertTrue("refresh $ACCOUNT" in h.engine.log)
        assertTrue(h.phone.activeSession!!.awaitingInvite)
    }

    @Test
    fun aLocalContactNameWinsOverThePushName() {
        val h = Harness()
        h.phone.lookupName = { if (it == "+31701234567") "Smit (contact)" else null }
        pushAt(h)
        assertEquals("Smit (contact)", h.phone.activeSession!!.remoteName)
    }

    @Test
    fun inviteWithTheCallRefJoinsTheReportedCall() {
        val h = Harness()
        pushAt(h)
        h.engine.emitIncoming("in-1", "+31701234567", "Bakkerij Smit", ACCOUNT, CALL_REF)

        val session = h.phone.activeSession!!
        assertEquals(uuid, session.id)
        assertFalse(session.awaitingInvite)
        assertEquals(1, h.system.events.filterIsInstance<Event.ReportedIncoming>().size)

        h.phone.answer(uuid)
        assertTrue("answer in-1" in h.engine.log)
    }

    @Test
    fun answeredBeforeTheInviteArrives() {
        val h = Harness()
        pushAt(h)
        h.phone.answer(uuid)
        assertTrue(h.phone.activeSession!!.answerPending)
        assertFalse(h.engine.log.any { it.startsWith("answer") })

        h.engine.emitIncoming("in-1", "+31701234567", null, ACCOUNT, CALL_REF)
        assertTrue("answer in-1" in h.engine.log)
    }

    @Test
    fun declinedBeforeTheInviteArrivesGivesTheInviteA603() {
        val h = Harness()
        pushAt(h)
        h.phone.hangUp(uuid)
        assertTrue(h.phone.sessions.value.isEmpty())
        assertEquals(RecentCall.Outcome.DECLINED, h.recents.single().outcome)

        h.engine.emitIncoming("in-1", "+31701234567", null, ACCOUNT, CALL_REF)
        assertTrue("decline in-1" in h.engine.log)
        assertTrue(h.phone.sessions.value.isEmpty())
    }

    @Test
    fun noInviteInTimeEndsTheCallAsMissed() {
        val h = Harness()
        pushAt(h)
        h.clock.advance(9_000)
        assertEquals(1, h.phone.sessions.value.size)
        h.clock.advance(2_000)
        assertTrue(h.phone.sessions.value.isEmpty())
        assertTrue(Event.Ended(uuid, CallSystemEndReason.UNANSWERED) in h.system.events)
        assertEquals(RecentCall.Outcome.MISSED, h.recents.single().outcome)

        // The INVITE after all: busy, no ring.
        h.engine.emitIncoming("in-1", "+31701234567", null, ACCOUNT, CALL_REF)
        assertTrue("busy in-1" in h.engine.log)
    }

    @Test
    fun answeredButTheInviteNeverCameEndsAsFailed() {
        val h = Harness()
        pushAt(h)
        h.phone.answer(uuid)
        h.clock.advance(11_000)
        assertTrue(Event.Ended(uuid, CallSystemEndReason.FAILED) in h.system.events)
    }

    @Test
    fun inviteWithoutHeaderJoinsByAccountAndCaller() {
        val h = Harness()
        pushAt(h)
        h.engine.emitIncoming("in-1", "0701234567", null, ACCOUNT, null)
        assertEquals(1, h.phone.sessions.value.size)
        assertFalse(h.phone.activeSession!!.awaitingInvite)
    }

    @Test
    fun anInviteFromAnotherCallerDoesNotJoinAndHearsBusy() {
        val h = Harness()
        pushAt(h)
        h.engine.emitIncoming("in-9", "0687654321", null, ACCOUNT, null)
        assertTrue("busy in-9" in h.engine.log)
        assertTrue(h.phone.activeSession!!.awaitingInvite)
    }

    @Test
    fun callerNameFromTheInviteUpdatesAnAnonymousPushCall() {
        val h = Harness()
        pushAt(h, ring(expires = h.clock.now.plusSeconds(12), number = null, name = null))
        assertEquals("Onbekend nummer", h.system.events.filterIsInstance<Event.ReportedIncoming>().single().display.callerTitle)
        h.engine.emitIncoming("in-1", "0612345678", "Piet", ACCOUNT, CALL_REF)
        assertEquals("Piet", h.system.events.filterIsInstance<Event.Updated>().single().display.callerTitle)
    }

    @Test
    fun rejectedPushesDoNotRingButAreRemembered() {
        val h = Harness()
        assertEquals(IncomingPushOutcome.Rejected(RingPushRejection.EXPIRED), pushAt(h, ring(expires = h.clock.now.minusSeconds(30))))
        assertEquals(RecentCall.Outcome.MISSED, h.recents.single().outcome)
        assertTrue(h.system.events.isEmpty())

        h.engine.emitIncoming("in-1", "+31701234567", null, ACCOUNT, CALL_REF)
        assertTrue("busy in-1" in h.engine.log)

        assertEquals(IncomingPushOutcome.Rejected(RingPushRejection.INVALID_PAYLOAD), h.phone.handleRingPush(null))
        val fresh = Harness()
        assertEquals(IncomingPushOutcome.Rejected(RingPushRejection.UNKNOWN_ACCOUNT), pushAt(fresh, ring(expires = fresh.clock.now.plusSeconds(12), account = "gone")))
    }

    @Test
    fun pushDuringACallIsBusy() {
        val h = Harness()
        h.engine.emitIncoming("in-0", "0611111111", null, ACCOUNT)
        assertEquals(IncomingPushOutcome.Rejected(RingPushRejection.BUSY), pushAt(h))
        h.engine.emitIncoming("in-1", "+31701234567", null, ACCOUNT, CALL_REF)
        assertTrue("busy in-1" in h.engine.log)
    }

    @Test
    fun repeatedPushAndPushAfterItsInvite() {
        val h = Harness()
        pushAt(h)
        assertEquals(IncomingPushOutcome.Rejected(RingPushRejection.ALREADY_HANDLED), pushAt(h))
        assertEquals(1, h.phone.sessions.value.size)

        val other = Harness()
        other.engine.emitIncoming("in-1", "+31701234567", null, ACCOUNT, CALL_REF)
        assertEquals(uuid, other.phone.activeSession!!.id)
        assertEquals(IncomingPushOutcome.Rejected(RingPushRejection.ALREADY_HANDLED), pushAt(other))
    }

    @Test
    fun systemRefusalEndsThePushCallAndRejectsItsInvite() {
        val h = Harness()
        h.system.refuseIncoming = true
        assertEquals(IncomingPushOutcome.Rejected(RingPushRejection.REFUSED_BY_SYSTEM), pushAt(h))
        h.engine.emitIncoming("in-1", "+31701234567", null, ACCOUNT, CALL_REF)
        assertTrue("decline in-1" in h.engine.log)
    }

    @Test
    fun backgroundWithoutACallUnregistersAndAPushWakesOnlyThatAccount() {
        val h = Harness(listOf(ACCOUNT, "other"))
        h.phone.enterBackground()
        assertEquals(setOf(SipAccountId(ACCOUNT), SipAccountId("other")), h.engine.disabled)

        pushAt(h)
        assertEquals(setOf(SipAccountId("other")), h.engine.disabled)

        // The call ends in the background: suspended again.
        h.engine.emitIncoming("in-1", "+31701234567", null, ACCOUNT, CALL_REF)
        h.engine.emitState(CallState.Ended(CallEndReason.RemoteHangup), "in-1", CallDirection.INCOMING, ACCOUNT)
        assertEquals(setOf(SipAccountId(ACCOUNT), SipAccountId("other")), h.engine.disabled)

        h.phone.enterForeground()
        assertTrue(h.engine.disabled.isEmpty())
    }

    @Test
    fun backgroundDuringACallKeepsRegistrationsUntilTheCallEnds() {
        val h = Harness()
        h.engine.emitIncoming("in-1", "0612345678", null, ACCOUNT)
        h.phone.enterBackground()
        assertTrue(h.engine.disabled.isEmpty())
        h.engine.emitState(CallState.Ended(CallEndReason.RemoteHangup), "in-1", CallDirection.INCOMING, ACCOUNT)
        assertEquals(setOf(SipAccountId(ACCOUNT)), h.engine.disabled)
    }

    @Test
    fun startedInTheBackgroundAddsAccountsWithoutRegistering() {
        val h = Harness(emptyList())
        h.phone.enterBackground()
        h.phone.sync(listOf(storedAccount(ACCOUNT)))
        assertTrue(SipAccountId(ACCOUNT) in h.engine.disabled)
        assertTrue(h.engine.registered.containsKey(SipAccountId(ACCOUNT)))
    }

    @Suppress("unused")
    private val unusedDuration = Duration.ZERO
}
