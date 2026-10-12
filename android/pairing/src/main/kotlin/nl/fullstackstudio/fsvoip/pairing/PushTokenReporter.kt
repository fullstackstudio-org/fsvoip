// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Keeps the server's push token of every account up to date (`PUT /push-token`, plan D9/D11).
//
// Android has ONE token (FCM) for calls and notices alike; it is sent as `pushToken` with `pushKind: fcm`, without an
// environment or an alert token. One request per account (the device token is per account).
//
// When: the first time in every app launch, and whenever the token changes. A token that did not change is not sent
// again (a fingerprint per account is kept; the token itself is not stored).

package nl.fullstackstudio.fsvoip.pairing

import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import nl.fullstackstudio.fsvoip.core.AccountStore
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.FsJson
import nl.fullstackstudio.fsvoip.core.FsLogger
import nl.fullstackstudio.fsvoip.core.FsVoipApiClient
import nl.fullstackstudio.fsvoip.core.KeyValueStore
import nl.fullstackstudio.fsvoip.core.PushKind
import nl.fullstackstudio.fsvoip.core.PushTokenUpdate

/** What one `report()` did, by account id. */
data class PushTokenReport(
    val sent: List<String> = emptyList(),
    /** 401: the pairing was revoked on the server. The app must remove these accounts (a `GET /me` does it). */
    val revoked: List<String> = emptyList(),
    /** Not sent now (offline, server trouble); tried again at the next report. */
    val failed: List<String> = emptyList(),
)

interface PushTokenReporting {
    /** The FCM token (`null` = none / deleted). */
    suspend fun setToken(token: String?)

    /** Send the token to every account that does not have the current one yet (or not yet in this launch). */
    suspend fun report(): PushTokenReport
}

/** The fingerprint of what each account last received. Not secret (a hash). */
class PushTokenLedger(private val store: KeyValueStore, private val key: String = "fsvoip.push-token-fingerprints") {
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    fun fingerprints(): Map<String, String> =
        store.getString(key)?.let { runCatching { FsJson.default.decodeFromString(serializer, it) }.getOrNull() } ?: emptyMap()

    fun setFingerprints(values: Map<String, String>) = store.putString(key, FsJson.default.encodeToString(serializer, values))
}

class PushTokenReporter(
    private val api: FsVoipApiClient,
    private val accounts: AccountStore,
    private val ledger: PushTokenLedger,
    private val logger: FsLogger = FsLogger("push"),
) : PushTokenReporting {
    private val mutex = Mutex()
    private var token: String? = null

    /**
     * FCM answered at least once (with a token or "none"). Until then nothing is sent: a report at app start must not
     * clear the server's token just because Firebase has not answered yet.
     */
    private var tokenKnown = false

    /** Accounts that got the token in this launch (the first report of a launch is always sent). */
    private val reportedThisLaunch = mutableSetOf<String>()

    override suspend fun setToken(token: String?) = mutex.withLock {
        this.token = token?.takeIf { it.isNotBlank() }
        tokenKnown = true
    }

    /** What the server is sent: the FCM token, or a "clear" when there is none. */
    fun update(): PushTokenUpdate = token?.let { PushTokenUpdate(pushKind = PushKind.FCM, pushToken = it) } ?: PushTokenUpdate.CLEAR

    override suspend fun report(): PushTokenReport = mutex.withLock {
        if (!tokenKnown) {
            return@withLock PushTokenReport()
        }

        val list = try {
            accounts.accounts()
        } catch (error: Exception) {
            logger.error("Accounts could not be read for the push token: ${error.javaClass.simpleName}")
            return@withLock PushTokenReport()
        }

        val update = update()
        val fingerprint = fingerprint(update)
        val known = ledger.fingerprints().filterKeys { id -> list.any { it.id == id } }.toMutableMap()
        val sent = mutableListOf<String>()
        val revoked = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for (account in list) {
            val unchanged = known[account.id] == fingerprint && account.id in reportedThisLaunch

            // Nothing to clear for an account that never had a token.
            if (unchanged || (token == null && known[account.id] == null)) {
                continue
            }

            try {
                api.authenticated(account.deviceToken).updatePushToken(update)
                // After a "clear" nothing is registered any more: nothing to clear next time either.
                if (token == null) known.remove(account.id) else known[account.id] = fingerprint
                reportedThisLaunch += account.id
                sent += account.id
            } catch (_: ApiException.Unauthorized) {
                known.remove(account.id)
                revoked += account.id
            } catch (error: Exception) {
                logger.notice("Push token of account ${account.id} not sent: ${error.javaClass.simpleName}")
                failed += account.id
            }
        }

        ledger.setFingerprints(known)

        if (sent.isNotEmpty()) {
            logger.notice("Push token sent for ${sent.size} account(s)")
        }

        PushTokenReport(sent, revoked, failed)
    }

    companion object {
        fun fingerprint(update: PushTokenUpdate): String {
            val text = listOf(update.pushKind?.wire ?: "-", update.pushToken ?: "-", update.pushEnv?.wire ?: "-", update.alertPushToken ?: "-").joinToString("|")

            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
