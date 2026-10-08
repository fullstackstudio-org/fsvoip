// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.Serializable

/** Settings of one account that are not secret. */
@Serializable
data class AccountPreferences(
    /**
     * Show the dialled account next to the caller on the incoming call screen. `null` = never chosen: then the default
     * applies (on when several accounts are paired, plan D10).
     */
    val showCalledAccount: Boolean? = null,
) {
    fun effectiveShowCalledAccount(accountCount: Int): Boolean = showCalledAccount ?: (accountCount > 1)
}

/** Non-secret app and account settings. */
interface PreferencesStore {
    fun preferences(accountId: String): AccountPreferences
    fun setPreferences(preferences: AccountPreferences, accountId: String)
    fun removePreferences(accountId: String)

    /** The account used for outgoing calls when nothing else was chosen. */
    var defaultOutgoingAccountId: String?

    /** Look up caller names in the phone's own contacts (needs the contacts permission). */
    var useDeviceContacts: Boolean
}

class KeyValuePreferencesStore(private val store: KeyValueStore) : PreferencesStore {
    override fun preferences(accountId: String): AccountPreferences {
        val text = store.getString(PREFIX + accountId) ?: return AccountPreferences()

        return runCatching { FsJson.default.decodeFromString(AccountPreferences.serializer(), text) }.getOrDefault(AccountPreferences())
    }

    override fun setPreferences(preferences: AccountPreferences, accountId: String) {
        store.putString(PREFIX + accountId, FsJson.default.encodeToString(AccountPreferences.serializer(), preferences))
    }

    override fun removePreferences(accountId: String) {
        store.putString(PREFIX + accountId, null)

        if (defaultOutgoingAccountId == accountId) {
            defaultOutgoingAccountId = null
        }
    }

    override var defaultOutgoingAccountId: String?
        get() = store.getString(DEFAULT_ACCOUNT_KEY)
        set(value) = store.putString(DEFAULT_ACCOUNT_KEY, value)

    override var useDeviceContacts: Boolean
        get() = store.getString(DEVICE_CONTACTS_KEY) == "1"
        set(value) = store.putString(DEVICE_CONTACTS_KEY, if (value) "1" else null)

    private companion object {
        const val PREFIX = "fsvoip.account-preferences."
        const val DEFAULT_ACCOUNT_KEY = "fsvoip.default-outgoing-account"
        const val DEVICE_CONTACTS_KEY = "fsvoip.use-device-contacts"
    }
}
