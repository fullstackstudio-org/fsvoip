// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.callcontroller

import nl.fullstackstudio.fsvoip.callcontroller.Harness.Companion.ACCOUNT
import nl.fullstackstudio.fsvoip.callcontroller.ImmediateCallSystem.Event
import nl.fullstackstudio.fsvoip.core.AccountPreferences
import nl.fullstackstudio.fsvoip.core.RecentCall
import nl.fullstackstudio.fsvoip.core.ServerTransport
import nl.fullstackstudio.fsvoip.sipengine.CallDirection
import nl.fullstackstudio.fsvoip.sipengine.CallEndReason
import nl.fullstackstudio.fsvoip.sipengine.CallState
import nl.fullstackstudio.fsvoip.sipengine.RegistrationState
import nl.fullstackstudio.fsvoip.sipengine.SipAccountId
import nl.fullstackstudio.fsvoip.sipengine.SipTransport
import nl.fullstackstudio.fsvoip.sipengine.SrtpMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PhoneControllerTest {
    @Test
    fun syncRegistersNewChangedAndRemovedAccounts() {
        val h = Harness(listOf("a", "b"))
        assertTrue(h.engine.started)
        assertEquals(setOf(SipAccountId("a"), SipAccountId("b")), h.engine.registered.keys)
        assertEquals(RegistrationState.Registering, h.phone.registrationState("a"))

        // Unchanged: no new REGISTER. Removed: unregistered.
        h.phone.sync(listOf(storedAccount("a", 0, "Line a")))
        assertEquals(2, h.engine.registerCount)
        assertTrue("unregister b" in h.engine.log)
        assertNull(h.phone.registrations.value["b"])

        // Changed: registered again.
        h.phone.sync(listOf(storedAccount("a", 0, "Line a").let { it.copy(sip = it.sip.copy(port = 5070)) }))
        assertEquals(3, h.engine.registerCount)
    }

    @Test
    fun registrationEventsOfUnknownAccountsAreIgnored() {
        val h = Harness()
        h.engine.emitRegistration(RegistrationState.Registered, "nobody")
        assertNull(h.phone.registrations.value["nobody"])
        h.registerAll()
        assertEquals(RegistrationState.Registered, h.phone.registrationState(ACCOUNT))
    }

    @Test
    fun outgoingCallGoesThroughTheSystemAndConnects() {
        val h = Harness()
        h.registerAll()
        h.phone.startCall("+31 (0)70-123 45 67", ACCOUNT)

        val session = h.phone.activeSession!!
        assertEquals("+31701234567", session.remoteNumber)
        assertEquals(CallSession.Phase.Ringing, session.phase)
        assertTrue("call +31701234567 from $ACCOUNT" in h.engine.log)
        assertTrue(Event.StartedConnecting(session.id) in h.system.events)
        assertTrue("activate" in h.engine.audioLog)

        h.engine.emitState(CallState.Active, "out-1", CallDirection.OUTGOING, ACCOUNT)
        assertEquals(CallSession.Phase.Active, h.phone.activeSession!!.phase)
        assertTrue(Event.Connected(session.id) in h.system.events)

        h.clock.advance(65_000)
        h.engine.emitState(CallState.Ended(CallEndReason.RemoteHangup), "out-1", CallDirection.OUTGOING, ACCOUNT)
        assertTrue(h.phone.sessions.value.isEmpty())
        assertTrue(Event.Ended(session.id, CallSystemEndReason.REMOTE_ENDED) in h.system.events)
        assertEquals(RecentCall.Outcome.ANSWERED, h.recents.single().outcome)
        assertEquals(65, h.recents.single().durationSeconds)
    }

    @Test
    fun userHangUpIsNotReportedBackToTheSystem() {
        val h = Harness()
        h.registerAll()
        h.phone.startCall("0701234567", ACCOUNT)
        val uuid = h.phone.activeSession!!.id

        h.phone.hangUp(uuid)
        assertTrue("hangup out-1" in h.engine.log)
        h.engine.emitState(CallState.Ended(CallEndReason.LocalHangup), "out-1", CallDirection.OUTGOING, ACCOUNT)
        assertFalse(h.system.events.any { it is Event.Ended })
        assertEquals(RecentCall.Outcome.NOT_ANSWERED, h.recents.single().outcome)
    }

    @Test
    fun callsAreRefusedWithAReason() {
        val h = Harness()
        expect<PhoneException.LineNotConnected> { h.phone.startCall("100", ACCOUNT) }
        h.registerAll()
        expect<PhoneException.InvalidNumber> { h.phone.startCall("abc", ACCOUNT) }
        expect<PhoneException.UnknownAccount> { h.phone.startCall("100", "nobody") }
        h.phone.startCall("100", ACCOUNT)
        expect<PhoneException.CallInProgress> { h.phone.startCall("101", ACCOUNT) }
    }

    @Test
    fun engineFailureFailsTheSystemActionAndCleansUp() {
        val h = Harness()
        h.registerAll()
        h.engine.failNextCall = true
        h.phone.startCall("100", ACCOUNT)
        assertTrue(h.phone.sessions.value.isEmpty())
        assertEquals(RecentCall.Outcome.FAILED, h.recents.single().outcome)
    }

    @Test
    fun incomingCallIsReportedAnsweredAndEnded() {
        val h = Harness()
        h.engine.emitIncoming("in-1", "0612345678", "Bakkerij Smit", ACCOUNT)

        val session = h.phone.activeSession!!
        val reported = h.system.events.filterIsInstance<Event.ReportedIncoming>().single()
        assertEquals("Bakkerij Smit", reported.display.callerTitle)
        assertEquals("Bakkerij Smit", reported.display.displayName)
        assertEquals("Line $ACCOUNT", reported.display.accountLabel)

        h.phone.answer(session.id)
        assertTrue("answer in-1" in h.engine.log)
        h.engine.emitState(CallState.Active, "in-1", CallDirection.INCOMING, ACCOUNT)
        h.engine.emitState(CallState.Ended(CallEndReason.RemoteHangup), "in-1", CallDirection.INCOMING, ACCOUNT)
        assertEquals(RecentCall.Outcome.ANSWERED, h.recents.single().outcome)
    }

    @Test
    fun calledAccountIsShownWhenSwitchedOn() {
        val h = Harness()
        h.preferences.setPreferences(AccountPreferences(showCalledAccount = true), ACCOUNT)
        h.engine.emitIncoming("in-1", "0612345678", null, ACCOUNT)
        val reported = h.system.events.filterIsInstance<Event.ReportedIncoming>().single()
        assertEquals("0612345678 → Line $ACCOUNT", reported.display.displayName)
    }

    @Test
    fun decliningAndMissing() {
        val h = Harness()
        h.engine.emitIncoming("in-1", "0612345678", null, ACCOUNT)
        h.phone.hangUp(h.phone.activeSession!!.id)
        assertTrue("decline in-1" in h.engine.log)
        h.engine.emitState(CallState.Ended(CallEndReason.Declined), "in-1", CallDirection.INCOMING, ACCOUNT)
        assertEquals(RecentCall.Outcome.DECLINED, h.recents.last().outcome)

        h.engine.emitIncoming("in-2", "0612345678", null, ACCOUNT)
        h.engine.emitState(CallState.Ended(CallEndReason.Unanswered), "in-2", CallDirection.INCOMING, ACCOUNT)
        assertEquals(RecentCall.Outcome.MISSED, h.recents.last().outcome)
        assertTrue(Event.Ended(h.recents.last().id.let(java.util.UUID::fromString), CallSystemEndReason.UNANSWERED) in h.system.events)
    }

    @Test
    fun systemRefusalDeclinesTheSipCall() {
        val h = Harness()
        h.system.refuseIncoming = true
        h.engine.emitIncoming("in-1", "0612345678", null, ACCOUNT)
        assertTrue("decline in-1" in h.engine.log)
        assertTrue(h.phone.sessions.value.isEmpty())
    }

    @Test
    fun secondCallerIsBusyAndUnknownAccountIsDeclined() {
        val h = Harness()
        h.engine.emitIncoming("in-1", "0612345678", null, ACCOUNT)
        h.engine.emitIncoming("in-2", "0687654321", null, ACCOUNT)
        assertTrue("busy in-2" in h.engine.log)
        assertEquals(RecentCall.Outcome.MISSED, h.recents.single().outcome)

        h.engine.emitIncoming("in-3", "0687654321", null, "nobody")
        assertTrue("decline in-3" in h.engine.log)
    }

    @Test
    fun muteHoldDtmfAndSpeaker() {
        val h = Harness()
        h.registerAll()
        h.phone.startCall("100", ACCOUNT)
        val uuid = h.phone.activeSession!!.id
        h.engine.emitState(CallState.Active, "out-1", CallDirection.OUTGOING, ACCOUNT)

        h.phone.setMuted(uuid, true)
        assertTrue(h.engine.micMuted)
        assertTrue(h.phone.activeSession!!.isMuted)

        h.phone.setHeld(uuid, true)
        assertTrue("hold true" in h.engine.log)

        h.phone.sendDtmf(uuid, "1#x")
        assertTrue("dtmf 1" in h.engine.log && "dtmf #" in h.engine.log)

        h.phone.setSpeaker(true)
        assertTrue(h.phone.isSpeakerOn.value)

        // The call ends: unmuted and back on the earpiece for the next one.
        h.engine.emitState(CallState.Ended(CallEndReason.RemoteHangup), "out-1", CallDirection.OUTGOING, ACCOUNT)
        assertFalse(h.engine.micMuted)
        assertFalse(h.phone.isSpeakerOn.value)
    }

    @Test
    fun systemResetEndsEverything() {
        val h = Harness()
        h.engine.emitIncoming("in-1", "0612345678", null, ACCOUNT)
        h.phone.systemDidReset()
        assertTrue(h.phone.sessions.value.isEmpty())
        assertTrue("hangup in-1" in h.engine.log)
    }

    @Test
    fun accountMapping() {
        val tls = storedAccount("a").let { it.copy(sip = it.sip.copy(transport = ServerTransport.TLS, port = 5061)) }
        val tlsConfig = SipAccountMapping.config(tls)
        assertEquals(SipTransport.TLS, tlsConfig.transport)
        assertEquals(SrtpMode.OPTIONAL, tlsConfig.srtp)
        assertEquals("sip:sip.powervoip.nl;transport=tls", tlsConfig.route)
        assertEquals("fss-dev=a1b2c3d4e5f60718", tlsConfig.contactMarker)
        assertEquals(120, tlsConfig.expiresSeconds)

        val udp = storedAccount("a").let { it.copy(sip = it.sip.copy(transport = ServerTransport.UDP, port = 0, srv = false)) }
        val udpConfig = SipAccountMapping.config(udp)
        assertEquals(SipTransport.TCP, udpConfig.transport)
        assertEquals(SrtpMode.DISABLED, udpConfig.srtp)
        assertEquals(5060, udpConfig.port)
        assertEquals("sip:sip.powervoip.nl:5060;transport=tcp", udpConfig.route)
    }

    @Test
    fun dialNumbers() {
        assertEquals("+31701234567", DialNumber.sanitize("+31 (0)70-123 45 67"))
        assertEquals("0612345678", DialNumber.sanitize("06-12 34 56 78"))
        assertEquals("123", DialNumber.sanitize("１２３"))
        assertEquals("1+2".filter { it != '+' }, DialNumber.sanitize("1+2"))
        assertEquals(32, DialNumber.sanitize("1".repeat(40)).length)

        assertTrue(DialNumber.isDialable("+31701234567"))
        assertTrue(DialNumber.isDialable("*21#"))
        assertFalse(DialNumber.isDialable("+"))
        assertFalse(DialNumber.isDialable(""))
        assertFalse(DialNumber.isDialable("12+3"))
        assertFalse(DialNumber.isDialable("100@evil"))
        assertFalse(DialNumber.isDialable("1".repeat(33)))
    }

    @Test
    fun callDisplay() {
        assertEquals("Bakkerij Smit", CallDisplay.callerText("Bakkerij Smit", "0612", "Jan", showAccount = false))
        assertEquals("Bakkerij Smit → Jan", CallDisplay.callerText("Bakkerij Smit", "0612", "Jan", showAccount = true))
        assertEquals("0612", CallDisplay.callerText(" ", "0612", "Jan", showAccount = false))
        assertEquals("Onbekend", CallDisplay.callerText(null, null, "Jan", showAccount = false))
        assertTrue(CallDisplay.shouldShowAccount(null, 2))
        assertFalse(CallDisplay.shouldShowAccount(null, 1))
        assertFalse(CallDisplay.shouldShowAccount(false, 3))
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw AssertionError("Expected ${T::class.simpleName}, got $error")
        }
    }
}
