// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.settings

/**
 * Which parts of the settings exist for one account. Pure, so a test can prove that a `user` never sees "Beheer" (the iOS
 * `SettingsOutline`).
 */
data class SettingsOutline(
    val showsAccountSwitcher: Boolean,
    val showsAvailability: Boolean,
    val admin: List<Admin>,
) {
    enum class Admin { NUMBERS, OVERVIEW, HOURS, DEVICES, RING_GROUPS, SOUNDS, INVITE, RECORDINGS }

    val showsAdmin: Boolean get() = admin.isNotEmpty()

    companion object {
        /** All inputs are what `GET /me` said about the account (see [nl.fullstackstudio.fsvoip.AppModel]); the server enforces the rest. */
        fun of(
            accountCount: Int,
            hasAccount: Boolean,
            canOfferAvailability: Boolean,
            canManagePbx: Boolean,
            canManageSounds: Boolean,
            canInvite: Boolean,
            hasRecordings: Boolean,
        ): SettingsOutline {
            val availability = hasAccount && canOfferAvailability

            if (!hasAccount || !canManagePbx) return SettingsOutline(accountCount > 1, availability, emptyList())

            val items = mutableListOf(Admin.NUMBERS, Admin.DEVICES, Admin.RING_GROUPS, Admin.OVERVIEW, Admin.HOURS)

            if (canManageSounds) items += Admin.SOUNDS
            if (canInvite) items += Admin.INVITE
            if (hasRecordings) items += Admin.RECORDINGS

            return SettingsOutline(accountCount > 1, availability, items)
        }
    }
}
