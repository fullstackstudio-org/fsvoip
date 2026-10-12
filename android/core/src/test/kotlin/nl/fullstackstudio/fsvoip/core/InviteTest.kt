// SPDX-License-Identifier: AGPL-3.0-or-later

package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import org.junit.Test
import org.junit.Assert.assertEquals

class InviteTest {
    private fun device(id: String, number: String?) = PbxDevice(id = id, name = "Toestel $id", version = 1, extensionNumber = number)

    @Test
    fun ownDeviceIsNeverOffered() {
        val devices = listOf(device("a", "100"), device("b", "101"))

        assertEquals(listOf("b"), InviteRules.candidates(devices, "a", "100").map { it.id })
        // Without the id of this app the extension number decides.
        assertEquals(listOf("b"), InviteRules.candidates(devices, null, "100").map { it.id })
        assertEquals(listOf("a", "b"), InviteRules.candidates(devices, null, null).map { it.id })
    }

    @Test
    fun countdownIsNeverNegative() {
        val now = Instant.parse("2026-10-12T10:00:00Z")

        assertEquals(582L, InviteRules.remainingSeconds("2026-10-12T10:09:42Z", now))
        assertEquals(582L, InviteRules.remainingSeconds("2026-10-12T12:09:42+02:00", now))
        assertEquals(0L, InviteRules.remainingSeconds("2026-10-12T09:00:00Z", now))
        assertEquals(0L, InviteRules.remainingSeconds("niet een tijd", now))
        assertEquals("9:42", InviteRules.countdown(582))
        assertEquals("0:05", InviteRules.countdown(5))
        assertEquals("0:00", InviteRules.countdown(-3))
    }
}
