// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import nl.fullstackstudio.fsvoip.core.ApiException

/** Why a change to the address book did not happen. Never the server's own words; the screen picks the text. */
enum class ContactFailure {
    PHONE,
    EMAIL,
    FORBIDDEN,
    LIMIT,

    /** Someone changed the contact in the meantime; the caller reloads it. */
    STALE,
    RATE,
    NETWORK,
    GONE,

    /** The pairing was revoked. */
    REVOKED,
    OTHER,
    ;

    companion object {
        fun of(error: Throwable): ContactFailure = when (error) {
            is ApiException.InvalidRequest -> when (error.code) {
                "invalid_phone" -> PHONE
                "invalid_email" -> EMAIL
                else -> OTHER
            }
            is ApiException.Forbidden -> FORBIDDEN
            is ApiException.Conflict -> when {
                error.error == "stale" || error.code == "stale" -> STALE
                error.code == "limit_reached" -> LIMIT
                else -> OTHER
            }
            is ApiException.RateLimited -> RATE
            is ApiException.Unavailable, is ApiException.Transport -> NETWORK
            is ApiException.NotFound -> GONE
            is ApiException.Unauthorized -> REVOKED
            else -> OTHER
        }
    }
}

/** The outcome of a write: the contact as the server has it now, or the reason it failed. */
sealed interface ContactWrite {
    data class Done(val contact: nl.fullstackstudio.fsvoip.core.Contact) : ContactWrite

    data class Failed(val failure: ContactFailure) : ContactWrite
}
