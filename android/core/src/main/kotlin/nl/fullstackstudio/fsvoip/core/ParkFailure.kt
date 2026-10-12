// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import kotlinx.coroutines.CancellationException

/** Why parking, picking up or hanging up a parked call did not work, in the words the screens need. */
sealed interface ParkFailure {
    /** 404 `call_not_found`: no running call of this extension has that Call-ID. */
    data object CallNotFound : ParkFailure

    data object NoFreeSlot : ParkFailure

    /** The PBX cannot park right now (`park_unavailable`, `park_busy`). */
    data object Unavailable : ParkFailure

    /** 🚨 The call MAY be parked. Look at the list; never park again. */
    data object Uncertain : ParkFailure

    data object Forbidden : ParkFailure

    /** 401: the pairing is gone. */
    data object Revoked : ParkFailure

    data object ReadOnly : ParkFailure

    /** The parked call is gone (picked up, rung back or hung up). */
    data object NotFound : ParkFailure

    data class RateLimited(val retryAfterSeconds: Int?) : ParkFailure

    data object Offline : ParkFailure

    data object Other : ParkFailure

    companion object {
        fun classify(error: Throwable): ParkFailure = when (error) {
            is ApiException.ParkUncertain -> Uncertain
            is ApiException.CallNotFound -> CallNotFound
            is ApiException.Unauthorized -> Revoked
            is ApiException.Forbidden -> Forbidden
            is ApiException.NotFound -> NotFound
            is ApiException.RateLimited -> RateLimited(error.retryAfterSeconds)
            is ApiException.Transport -> Offline
            is ApiException.Conflict -> when (error.error) {
                "no_free_slot" -> NoFreeSlot
                "park_unavailable", "park_busy" -> Unavailable
                "read_only" -> ReadOnly
                else -> Other
            }
            else -> Other
        }

        /**
         * Does this error of the park POST leave open whether the call was parked? (No answer, or a 5xx from a proxy in
         * between.) Then the app looks at the list and never repeats the request.
         */
        fun outcomeIsUncertain(error: Throwable): Boolean = when (error) {
            // The request may have left before the coroutine was cancelled.
            is CancellationException -> true
            is ApiException.ParkUncertain, is ApiException.Transport -> true
            is ApiException.Unavailable -> true
            is ApiException.UnexpectedStatus -> error.status in setOf(500, 502, 504)
            else -> false
        }
    }
}
