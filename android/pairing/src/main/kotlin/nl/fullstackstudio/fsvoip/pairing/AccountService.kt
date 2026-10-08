// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.pairing

import nl.fullstackstudio.fsvoip.core.AccountStore
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.FsLogger
import nl.fullstackstudio.fsvoip.core.FsVoipApiClient
import nl.fullstackstudio.fsvoip.core.InternalContact
import nl.fullstackstudio.fsvoip.core.StoredAccount

/** The result of `GET /me` for one account. */
sealed interface AccountRefreshResult {
    data class Updated(val account: StoredAccount, val internalContacts: List<InternalContact>) : AccountRefreshResult

    /** 401: the pairing was revoked. The account has been removed from this phone. */
    data object Revoked : AccountRefreshResult
}

/** Everything the app does with the FullStack Studio API for its accounts. */
interface AccountServicing {
    /** `POST /pair` and keep the account (SIP password and device token encrypted only). */
    suspend fun pair(link: PairingLink, device: DeviceDescriptor): StoredAccount

    /** `GET /me`: refresh labels and the SIP server. Removes the account locally on a 401. */
    suspend fun refresh(account: StoredAccount): AccountRefreshResult

    /** `PATCH /me`: set the alias (`null` or empty = back to the label from the portal). */
    suspend fun rename(account: StoredAccount, alias: String?): StoredAccount

    /** `POST /unpair`, then remove the account. A pairing the server no longer knows (401/404) counts as unpaired. */
    suspend fun unpair(account: StoredAccount)

    /** Remove the account from this phone only (when the server cannot be reached). */
    fun forget(account: StoredAccount)
}

class AccountService(
    private val api: FsVoipApiClient,
    private val accounts: AccountStore,
    private val logger: FsLogger = FsLogger("accounts"),
) : AccountServicing {
    override suspend fun pair(link: PairingLink, device: DeviceDescriptor): StoredAccount =
        PairingService(api, accounts, logger).pair(link, device)

    override suspend fun refresh(account: StoredAccount): AccountRefreshResult = try {
        val me = client(account).me()
        val updated = account.updatedWith(me)

        if (updated != account) {
            accounts.save(updated)
        }

        AccountRefreshResult.Updated(updated, me.internalContacts)
    } catch (_: ApiException.Unauthorized) {
        logger.notice("Account ${account.id} was revoked by the server")
        accounts.remove(account.id)
        AccountRefreshResult.Revoked
    }

    override suspend fun rename(account: StoredAccount, alias: String?): StoredAccount {
        val response = client(account).setLabelOverride(cleanAlias(alias))
        val updated = account.copy(label = response.label, labelOverride = response.labelOverride)
        accounts.save(updated)

        return updated
    }

    override suspend fun unpair(account: StoredAccount) {
        try {
            client(account).unpair()
        } catch (_: ApiException.Unauthorized) {
            // Already revoked on the server: nothing left to undo there.
        } catch (_: ApiException.NotFound) {
            // Idem.
        }

        accounts.remove(account.id)
        logger.notice("Account ${account.id} unpaired")
    }

    override fun forget(account: StoredAccount) {
        accounts.remove(account.id)
        logger.notice("Account ${account.id} removed from this phone only")
    }

    private fun client(account: StoredAccount) = api.authenticated(account.deviceToken)

    companion object {
        /** Maximum length of an alias (the server's limit). */
        const val ALIAS_MAX_LENGTH = 60

        /** Trimmed, single line, at most 60 characters; empty = `null`. */
        fun cleanAlias(alias: String?): String? {
            val trimmed = alias?.lines()?.joinToString(" ")?.trim() ?: return null

            return trimmed.takeIf { it.isNotEmpty() }?.take(ALIAS_MAX_LENGTH)
        }
    }
}
