// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.callcontroller

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import nl.fullstackstudio.fsvoip.callcontroller.Harness.Companion.ACCOUNT
import nl.fullstackstudio.fsvoip.callcontroller.ImmediateCallSystem.Event
import nl.fullstackstudio.fsvoip.core.CallerContext
import nl.fullstackstudio.fsvoip.sipengine.CallDirection
import nl.fullstackstudio.fsvoip.sipengine.CallEndReason
import nl.fullstackstudio.fsvoip.sipengine.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The customer card of an incoming call: best effort, never in the way of the call. */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomerCardTest {
    private val card = CallerContext(contactId = "c", name = "Jansen Bakkerij", company = "Bakkerij", openRequests = 1, openOrders = 2)

    @Test
    fun aKnownCallerGetsTheNameAndTheCounters() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(lookupScope = backgroundScope)
        h.phone.lookupCaller = { number, _ -> if (number == "0612345678") card else null }
        h.registerAll()

        h.engine.emitIncoming("c1", "0612345678", "0612345678", ACCOUNT)

        val session = h.phone.activeSession!!

        assertEquals(card, session.callerContext)
        assertEquals("Jansen Bakkerij", session.remoteName)
        // The system (lock screen, car, watch) is told the better name.
        assertTrue(h.system.events.any { it is Event.Updated && it.display.callerTitle == "Jansen Bakkerij" })
    }

    @Test
    fun aSlowLookupChangesNothing() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(lookupScope = backgroundScope)
        h.phone.lookupCaller = { _, _ ->
            delay(5_000)
            card
        }
        h.registerAll()

        h.engine.emitIncoming("c1", "0612345678", null, ACCOUNT)
        advanceTimeBy(1_500)

        val session = h.phone.activeSession!!

        assertNull(session.callerContext)
        assertEquals(CallSession.Phase.Incoming, session.phase)
    }

    @Test
    fun aFailingLookupChangesNothing() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(lookupScope = backgroundScope)
        h.phone.lookupCaller = { _, _ -> error("offline") }
        h.registerAll()

        h.engine.emitIncoming("c1", "0612345678", null, ACCOUNT)

        assertNull(h.phone.activeSession!!.callerContext)
    }

    @Test
    fun aColleagueAndAnAnonymousCallerAreNeverLookedUp() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(lookupScope = backgroundScope)
        var asked = 0
        h.phone.lookupCaller = { _, _ ->
            asked++
            card
        }
        h.registerAll()

        h.engine.emitIncoming("c1", "100", null, ACCOUNT)
        assertEquals(0, asked)
    }

    @Test
    fun anAnswerAfterTheCallEndedIsDropped() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(lookupScope = backgroundScope)
        h.phone.lookupCaller = { _, _ ->
            delay(500)
            card
        }
        h.registerAll()

        h.engine.emitIncoming("c1", "0612345678", null, ACCOUNT)
        val id = h.phone.activeSession!!.id

        // The caller hangs up before the website answers.
        h.engine.emitState(CallState.Ended(CallEndReason.Unanswered), "c1", CallDirection.INCOMING, ACCOUNT)
        advanceTimeBy(900)

        assertNull(h.phone.session(id))
        assertTrue(h.phone.sessions.value.isEmpty())
        // Nothing was told to the system about a call that is over.
        assertTrue(h.system.events.none { it is Event.Updated })
    }
}
