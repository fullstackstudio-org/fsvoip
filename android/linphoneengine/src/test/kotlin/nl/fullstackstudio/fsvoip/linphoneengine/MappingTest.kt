// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.linphoneengine

import nl.fullstackstudio.fsvoip.sipengine.CallEndReason
import nl.fullstackstudio.fsvoip.sipengine.CallState
import nl.fullstackstudio.fsvoip.sipengine.RegistrationFailure
import nl.fullstackstudio.fsvoip.sipengine.RegistrationState
import nl.fullstackstudio.fsvoip.sipengine.SipTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.core.Call
import org.linphone.core.TransportType
import org.linphone.core.RegistrationState as LinphoneRegistrationState

class MappingTest {
    @Test
    fun registrationStates() {
        assertEquals(RegistrationState.Registered, LinphoneSipEngine.registrationState(LinphoneRegistrationState.Ok, null))
        assertEquals(RegistrationState.Registering, LinphoneSipEngine.registrationState(LinphoneRegistrationState.Refreshing, null))
        assertEquals(RegistrationState.Unregistered, LinphoneSipEngine.registrationState(LinphoneRegistrationState.Cleared, null))
        assertEquals(
            RegistrationState.Failed(RegistrationFailure.Authentication),
            LinphoneSipEngine.registrationState(LinphoneRegistrationState.Failed, "Forbidden"),
        )
        assertEquals(
            RegistrationState.Failed(RegistrationFailure.Network),
            LinphoneSipEngine.registrationState(LinphoneRegistrationState.Failed, "io error"),
        )
    }

    @Test
    fun callStatesAndTransports() {
        assertEquals(CallState.Active, LinphoneSipEngine.callState(Call.State.StreamsRunning, null))
        assertEquals(CallState.HeldByRemote, LinphoneSipEngine.callState(Call.State.PausedByRemote, null))
        assertEquals(CallState.Ended(CallEndReason.Busy), LinphoneSipEngine.callState(Call.State.End, CallEndReason.Busy))
        assertEquals(TransportType.Tls, LinphoneSipEngine.transport(SipTransport.TLS))
        assertEquals(TransportType.Tcp, LinphoneSipEngine.transport(SipTransport.TCP))
    }

    @Test
    fun dialable() {
        assertTrue(LinphoneSipEngine.isDialable("+31701234567"))
        assertFalse(LinphoneSipEngine.isDialable("1@evil.example"))
        assertFalse(LinphoneSipEngine.isDialable("+"))
    }
}
