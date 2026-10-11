// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.callcontroller

import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.delay
import nl.fullstackstudio.fsvoip.callcontroller.Harness.Companion.ACCOUNT
import nl.fullstackstudio.fsvoip.callcontroller.Harness.Companion.CALL_REF
import nl.fullstackstudio.fsvoip.callcontroller.ImmediateCallSystem.Event
import nl.fullstackstudio.fsvoip.core.CallerContext
import nl.fullstackstudio.fsvoip.core.PushCaller
import nl.fullstackstudio.fsvoip.core.PushMessage
import nl.fullstackstudio.fsvoip.core.RingPush
import nl.fullstackstudio.fsvoip.sipengine.CallDirection
import nl.fullstackstudio.fsvoip.sipengine.CallId
import nl.fullstackstudio.fsvoip.sipengine.CallOptions
import nl.fullstackstudio.fsvoip.sipengine.CallState
import nl.fullstackstudio.fsvoip.sipengine.RegistrationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Park (Call-ID), caller choice (options), the customer card and the answer fix: the call experience of build 6-9. */
class CallExperienceTest {
    private val uuid = UUID.fromString(CALL_REF)
    private val card = CallerContext("6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c", "Jansen Bakkerij", null, openRequests = 1, openOrders = 2)

    private fun registered(h: Harness) = h.registerAll()

    private fun ring(h: Harness, number: String? = "+31701234567", name: String? = "Bakkerij Smit") =
        PushMessage.Ring(RingPush(CALL_REF, PushCaller(number, name), ACCOUNT, "Voorbeeld Bouw · Jan de Vries", h.clock.now.plusSeconds(12)))

    private fun asked() = Collections.synchronizedList(mutableListOf<String>())

    // MARK: Park needs the live SIP Call-ID

    @Test
    fun theSipCallIdIsReadLiveFromTheEngine() {
        val h = Harness()
        registered(h)
        h.phone.startCall("101", ACCOUNT)
        val session = h.phone.activeSession!!

        // The engine id was fixed before the INVITE existed; the dialog's Call-ID is known only later.
        h.engine.sipCallIds[CallId("out-1")] = "HSzuSitbTA"
        assertEquals("HSzuSitbTA", h.phone.sipCallId(session))
    }

    @Test
    fun noSipCallIdBeforeTheSipSideOfTheCallExists() {
        val h = Harness()
        registered(h)
        h.phone.startCall("101", ACCOUNT)
        val session = h.phone.activeSession!!.copy(engineCallId = null)

        assertNull(h.phone.sipCallId(session.copy(id = UUID.randomUUID())))
    }

    // MARK: Caller choice

    @Test
    fun theChosenNumberTravelsWithTheOutgoingCallOnly() {
        val h = Harness()
        registered(h)

        h.phone.startCall("0612345678", ACCOUNT, CallOptions(fromNumber = "0850607848"))

        assertEquals(listOf(CallOptions(fromNumber = "0850607848")), h.engine.callOptions)
        assertEquals(1, h.engine.callOptions.first().headers.count { it.name == "X-FSS-From" })
        assertEquals("0850607848", h.phone.activeSession!!.viaNumber)

        val id = h.phone.activeSession!!.id
        h.phone.hangUp(id)
        h.engine.emitState(CallState.Ended(nl.fullstackstudio.fsvoip.sipengine.CallEndReason.LocalHangup), "out-1", CallDirection.OUTGOING, ACCOUNT)

        // The next call without a choice carries nothing: the choice is never remembered by the controller.
        h.phone.startCall("0612345678", ACCOUNT)
        assertEquals(CallOptions.NONE, h.engine.callOptions.last())
        assertNull(h.phone.activeSession!!.viaNumber)
    }

    @Test
    fun anIncomingCallNeverGetsTheCallerChoice() {
        val h = Harness()
        registered(h)

        h.engine.emitIncoming("in-1", "0701234567", null, ACCOUNT)

        assertTrue(h.engine.callOptions.isEmpty())
        assertNull(h.phone.activeSession!!.viaNumber)
    }

    // MARK: Answer (TestFlight "Blijft op verbinden")

    @Test
    fun incomingCallAnsweredSynchronouslyActiveStaysActive() {
        val h = Harness()
        registered(h)
        h.engine.emitIncoming("in-1", "102", "Ipad test", ACCOUNT)
        val id = h.phone.activeSession!!.id

        h.engine.onAnswer = {
            h.engine.emitState(CallState.Connecting, "in-1", CallDirection.INCOMING, ACCOUNT)
            h.engine.emitState(CallState.Active, "in-1", CallDirection.INCOMING, ACCOUNT)
        }

        h.phone.answer(id)

        assertEquals(CallSession.Phase.Active, h.phone.activeSession!!.phase)
        assertEquals(h.clock.now, h.phone.activeSession!!.connectedAt)
    }

    @Test
    fun incomingCallAnsweredMovesToConnectingUntilTheEngineReports() {
        val h = Harness()
        registered(h)
        h.engine.emitIncoming("in-1", "102", null, ACCOUNT)
        val id = h.phone.activeSession!!.id

        h.phone.answer(id)
        assertEquals(CallSession.Phase.Connecting, h.phone.activeSession!!.phase)

        h.engine.emitState(CallState.Active, "in-1", CallDirection.INCOMING, ACCOUNT)
        assertEquals(CallSession.Phase.Active, h.phone.activeSession!!.phase)
    }

    // MARK: The customer card

    @Test
    fun theCardNamesTheCallerAndUpdatesTheCallScreen() {
        val h = Harness()
        val asked = asked()
        h.phone.lookupCaller = { number, account ->
            asked += "$number|${account.id}"
            card
        }

        h.phone.handleRingPush(ring(h))
        h.phone.finishCallerLookup(uuid)

        assertEquals(listOf("+31701234567|$ACCOUNT"), asked)
        assertEquals(card, h.phone.activeSession!!.callerContext)
        assertEquals("Jansen Bakkerij", h.phone.activeSession!!.remoteName)
        // The system call screen is updated in place, not reported again.
        assertEquals(1, h.system.events.filterIsInstance<Event.ReportedIncoming>().size)
        assertEquals("Jansen Bakkerij", h.system.events.filterIsInstance<Event.Updated>().single().display.callerTitle)
    }

    @Test
    fun aContactNameOfThePhoneWinsOverTheCard() {
        val h = Harness()
        h.phone.lookupName = { if (it == "+31701234567") "Henk Smit" else null }
        h.phone.lookupCaller = { _, _ -> card }

        h.phone.handleRingPush(ring(h))
        h.phone.finishCallerLookup(uuid)

        assertEquals("Henk Smit", h.phone.activeSession!!.remoteName)
        assertNotNull("the context still shows on the in-app screen", h.phone.activeSession!!.callerContext)
        assertTrue("no update: the name did not change", h.system.events.none { it is Event.Updated })
    }

    @Test
    fun aSlowLookupNeverBlocksTheCall() {
        val h = Harness()
        h.phone.callerLookupTimeoutMillis = 50
        h.phone.lookupCaller = { _, _ ->
            delay(5_000)
            card
        }

        // The call is reported and ringing before the lookup is even looked at.
        assertEquals(IncomingPushOutcome.Ringing(uuid), h.phone.handleRingPush(ring(h)))
        assertEquals(1, h.system.events.size)

        h.phone.finishCallerLookup(uuid)

        assertNull(h.phone.activeSession!!.callerContext)
        assertEquals("Bakkerij Smit", h.phone.activeSession!!.remoteName)
        assertEquals(1, h.system.events.size)

        // And it can still be answered.
        h.engine.emitIncoming("in-1", "0701234567", "Bakkerij Smit", ACCOUNT, CALL_REF)
        h.phone.answer(uuid)
        assertEquals("answer in-1", h.engine.log.last())
    }

    @Test
    fun aFailedOrEmptyLookupChangesNothing() {
        val h = Harness()
        h.phone.lookupCaller = { _, _ -> null }

        h.phone.handleRingPush(ring(h))
        h.phone.finishCallerLookup(uuid)

        assertNull(h.phone.activeSession!!.callerContext)
        assertEquals(1, h.system.events.size)

        val boom = Harness()
        boom.phone.lookupCaller = { _, _ -> throw IllegalStateException("website down") }
        boom.phone.handleRingPush(ring(boom))
        boom.phone.finishCallerLookup(uuid)
        assertNull(boom.phone.activeSession!!.callerContext)
    }

    @Test
    fun anAnswerAfterTheCallEndedIsDropped() {
        val h = Harness()
        h.phone.callerLookupTimeoutMillis = 2_000
        h.phone.lookupCaller = { _, _ ->
            delay(200)
            card
        }

        h.phone.handleRingPush(ring(h))
        h.clock.advance(11_000)
        assertNull("no INVITE in time: the call ended", h.phone.activeSession)
        h.phone.finishCallerLookup(uuid)
        Thread.sleep(300)

        assertTrue(h.system.events.none { it is Event.Updated })
    }

    @Test
    fun anAnonymousCallerIsNotLookedUp() {
        val h = Harness()
        val asked = asked()
        h.phone.lookupCaller = { number, _ ->
            asked += number
            card
        }

        h.phone.handleRingPush(ring(h, number = null, name = null))
        h.phone.finishCallerLookup(uuid)

        assertEquals(emptyList<String>(), asked)
        assertNull(h.phone.activeSession!!.callerContext)
    }

    @Test
    fun aColleagueIsNotLookedUp() {
        val h = Harness()
        val asked = asked()
        h.phone.lookupCaller = { number, _ ->
            asked += number
            card
        }

        // An internal extension number is a colleague, not a customer.
        h.phone.handleRingPush(ring(h, number = "103", name = "Piet"))
        h.phone.finishCallerLookup(uuid)

        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun anAnonymousPushIsLookedUpOnceTheInviteBringsTheNumber() {
        val h = Harness()
        val asked = asked()
        h.phone.lookupCaller = { number, _ ->
            asked += number
            card
        }

        h.phone.handleRingPush(ring(h, number = null, name = null))
        h.engine.emitIncoming("in-1", "0701234567", null, ACCOUNT, CALL_REF)
        h.phone.finishCallerLookup(uuid)

        assertEquals(listOf("0701234567"), asked)
        assertEquals("Jansen Bakkerij", h.phone.activeSession!!.remoteName)
    }

    @Test
    fun anInviteWithoutAPushIsLookedUpToo() {
        val h = Harness()
        h.phone.lookupCaller = { _, _ -> card }

        h.engine.emitIncoming("in-1", "0701234567", null, ACCOUNT, CALL_REF)
        h.phone.finishCallerLookup(uuid)

        assertEquals(2, h.phone.activeSession!!.callerContext!!.openOrders)
    }

    @Test
    fun anOutgoingCallIsNeverLookedUp() {
        val h = Harness()
        registered(h)
        val asked = asked()
        h.phone.lookupCaller = { number, _ ->
            asked += number
            card
        }

        h.phone.startCall("0701234567", ACCOUNT)

        assertEquals(emptyList<String>(), asked)
        assertEquals(RegistrationState.Registered, h.phone.registrationState(ACCOUNT))
        @Suppress("UNUSED_VARIABLE") val unused = Instant.EPOCH
    }
}
