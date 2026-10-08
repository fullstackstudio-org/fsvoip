// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.pairing

import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.SecretStoreException

/** Why pairing (or another account request) did not work, in the terms the user can act on. */
sealed interface PairingFailure {
    /** The code is unknown, expired (10 minutes) or already used: make a new one in the portal. */
    data object CodeExpiredOrUsed : PairingFailure

    /** Too many attempts from this network. */
    data class TooManyAttempts(val retryAfterSeconds: Int?) : PairingFailure

    /** The server or the PBX could not finish right now; the code was NOT used, so trying again is fine. */
    data object TemporarilyUnavailable : PairingFailure

    /** No connection to the server. */
    data object Network : PairingFailure

    /** The pairing was revoked (portal, admin, or another phone took over the extension). */
    data object Revoked : PairingFailure

    /** The secure storage of the phone refused to save the account. */
    data object Storage : PairingFailure

    /** Anything else (an app bug or an unexpected answer). */
    data object Other : PairingFailure

    /** Whether repeating the same request may work. */
    val isRetryable: Boolean
        get() = when (this) {
            TemporarilyUnavailable, Network, is TooManyAttempts, Storage -> true
            CodeExpiredOrUsed, Revoked, Other -> false
        }

    companion object {
        fun from(error: Throwable): PairingFailure = when (error) {
            is ApiException.NotFound -> CodeExpiredOrUsed
            is ApiException.Unauthorized, is ApiException.MissingDeviceToken -> Revoked
            is ApiException.RateLimited -> TooManyAttempts(error.retryAfterSeconds)
            is ApiException.Unavailable -> TemporarilyUnavailable
            is ApiException.Transport -> Network
            is ApiException -> Other
            is SecretStoreException -> Storage
            is java.io.IOException -> Network
            else -> Other
        }
    }
}
