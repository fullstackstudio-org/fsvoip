// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.settings

import nl.fullstackstudio.fsvoip.ui.settings.SettingsOutline.Admin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsOutlineTest {
    private fun outline(
        accounts: Int = 1,
        hasAccount: Boolean = true,
        availability: Boolean = true,
        pbx: Boolean = true,
        sounds: Boolean = true,
        invite: Boolean = true,
        recordings: Boolean = true,
    ) = SettingsOutline.of(accounts, hasAccount, availability, pbx, sounds, invite, recordings)

    @Test
    fun aUserNeverSeesTheAdminGroup() {
        // Whatever else the server said: without the pbx right there is no "Beheer".
        val outline = outline(pbx = false, sounds = true, invite = true, recordings = true)

        assertFalse(outline.showsAdmin)
        assertTrue(outline.admin.isEmpty())
    }

    @Test
    fun anAdministratorSeesTheSectionsTheCapabilitiesAllow() {
        assertEquals(
            listOf(Admin.NUMBERS, Admin.DEVICES, Admin.RING_GROUPS, Admin.OVERVIEW, Admin.HOURS, Admin.SOUNDS, Admin.INVITE, Admin.RECORDINGS),
            outline().admin,
        )
        assertEquals(
            listOf(Admin.NUMBERS, Admin.DEVICES, Admin.RING_GROUPS, Admin.OVERVIEW, Admin.HOURS),
            outline(sounds = false, invite = false, recordings = false).admin,
        )
    }

    @Test
    fun theAccountSwitcherNeedsTwoAccountsAndAvailabilityAnAccount() {
        assertFalse(outline(accounts = 1).showsAccountSwitcher)
        assertTrue(outline(accounts = 2).showsAccountSwitcher)
        assertFalse(outline(hasAccount = false).showsAvailability)
        assertFalse(outline(hasAccount = false).showsAdmin)
        assertTrue(outline().showsAvailability)
    }
}
