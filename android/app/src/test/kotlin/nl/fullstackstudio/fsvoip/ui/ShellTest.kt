// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui

import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.sipengine.RegistrationFailure
import nl.fullstackstudio.fsvoip.sipengine.RegistrationState
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellTest {
    @Test
    fun theDotFollowsTheLineAndDoNotDisturb() {
        assertEquals(AvailabilityDot.AVAILABLE, AvailabilityDot.of(RegistrationState.Registered, doNotDisturb = false))
        assertEquals(AvailabilityDot.DO_NOT_DISTURB, AvailabilityDot.of(RegistrationState.Registered, doNotDisturb = true))
        assertEquals(AvailabilityDot.CONNECTING, AvailabilityDot.of(RegistrationState.Registering, doNotDisturb = false))
        assertEquals(AvailabilityDot.OFFLINE, AvailabilityDot.of(RegistrationState.Unregistered, doNotDisturb = false))
        // Do not disturb is the user's own choice: it shows whatever the line does.
        assertEquals(AvailabilityDot.DO_NOT_DISTURB, AvailabilityDot.of(RegistrationState.Failed(RegistrationFailure.Network), doNotDisturb = true))
        assertEquals(AvailabilityDot.DO_NOT_DISTURB, AvailabilityDot.of(RegistrationState.Registering, doNotDisturb = true))
    }

    @Test
    fun initialsOfAName() {
        assertEquals("JV", initials("Jan de Vries"))
        assertEquals("R", initials("Receptie"))
        assertEquals("AB", initials("  Anna   Bakker "))
        assertEquals("•", initials(null))
        assertEquals("•", initials("  "))
    }

    @Test
    fun theTabsFollowTheIosOrder() {
        assertEquals(
            listOf(AppModel.Tab.DIALER, AppModel.Tab.ON_HOLD, AppModel.Tab.RECENTS, AppModel.Tab.VOICEMAIL, AppModel.Tab.CONTACTS),
            AppModel.Tab.entries,
        )
    }
}
