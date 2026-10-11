// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Kotlin mirror of `/calls/park` and `/parked` in `shared/openapi.yaml`. Parking puts the own running call on hold in a numbered
// slot; picking it up is dialling `retrieveNumber` (`*5901`) as a normal call. After the park time-out the call rings back at the
// extension that parked it.
//
// 🚨 `ApiException.ParkUncertain`: the PBX failed while parking and the call MAY be parked. Never park again: refresh the list.

package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer

/** `POST /calls/park`. [callId] is the SIP `Call-ID` of the leg of the app; the extension that parks comes from the pairing. */
@Serializable
data class ParkRequest(val callId: String)

@Serializable
data class ParkedBy(
    /** The app pairing that parked it; `null` = parked from another phone of the PBX. */
    val deviceId: String? = null,
    val name: String = "",
)

@Serializable
data class ParkedCall(
    /** Opaque, sealed per PBX (never a channel id). Goes into `DELETE /parked/{id}`. */
    val id: String,
    val slot: Int,
    /** The number that picks the call up when dialled as a normal call (`*5901`); `null` = unknown. */
    val retrieveNumber: String? = null,
    val callerNumber: String? = null,
    val callerName: String? = null,
    @Serializable(with = LenientInstantSerializer::class) val parkedAt: Instant? = null,
    /** After this the call rings back at the extension that parked it. */
    @Serializable(with = LenientInstantSerializer::class) val expiresAt: Instant? = null,
    /** `null` = parked outside FullStack Studio. */
    @Serializable(with = LenientParkedBySerializer::class) val parkedBy: ParkedBy? = null,
    /** Parked by this pairing. */
    val mine: Boolean = false,
)

object LenientParkedBySerializer : KSerializer<ParkedBy?> by LenientSerializer(ParkedBy.serializer())

/** `GET /parked`. */
@Serializable
data class ParkedCallsPage(
    val calls: List<ParkedCall> = emptyList(),
    /** `false` = parking does not work on this PBX right now: hide the section. Missing = `false` (restrictive). */
    val available: Boolean = false,
)

private fun safeSegment(id: String): String {
    if (id.isEmpty() || id == "." || id == ".." || id.contains('/') || id.contains('\\') || id.contains('?') || id.contains('#')) {
        throw ApiException.NotFound()
    }

    return id
}

/**
 * Parks the own running call. [callId] = the SIP `Call-ID` of the app's leg. Only when `AppCapabilities.park`.
 * Failures: [ApiException.CallNotFound], [ApiException.NoFreeSlot], [ApiException.ParkUnavailable], [ApiException.ParkUncertain],
 * [ApiException.ReadOnly].
 */
suspend fun FsVoipApiClient.parkCall(callId: String): ParkedCall =
    perform("POST", "calls/park", FsJson.default.encodeToJsonElement(ParkRequest.serializer(), ParkRequest(callId)), authenticated = true, ParkedCall.serializer())

suspend fun FsVoipApiClient.parkedCalls(): ParkedCallsPage = get("parked", ParkedCallsPage.serializer())

/** Hangs up a parked call. A `user` may only hang up a call that is `mine` ([ApiException.Forbidden] otherwise). */
suspend fun FsVoipApiClient.hangupParkedCall(id: String): OkResponse =
    perform("DELETE", "parked/${safeSegment(id)}", null, authenticated = true, OkResponse.serializer())

/** Why parking, listing or hanging up failed, in the user's terms. */
sealed interface ParkFailure {
    /** 404: the call is no longer running (or it is not a call of this extension). */
    data object CallNotFound : ParkFailure

    /** 409 `no_free_slot` / `park_busy`. */
    data object NoFreeSlot : ParkFailure

    /** 409 `park_unavailable`. */
    data object Unavailable : ParkFailure

    /** 503 `park_uncertain`: the call MAY be parked. NEVER try again; refresh the list. */
    data object Uncertain : ParkFailure

    /** 403: a `user` may only hang up a call that is theirs, or the pairing lost its rights. */
    data object Forbidden : ParkFailure

    /** 401: the pairing is gone. */
    data object Revoked : ParkFailure

    data object ReadOnly : ParkFailure

    /** 404 when hanging up: already gone. */
    data object NotFound : ParkFailure

    data class RateLimited(val retryAfterSeconds: Int?) : ParkFailure

    data object Offline : ParkFailure

    data object Other : ParkFailure

    companion object {
        fun classify(error: Throwable): ParkFailure = when (error) {
            is ApiException.CallNotFound -> CallNotFound
            is ApiException.NoFreeSlot -> NoFreeSlot
            is ApiException.Conflict -> if (error.code == "park_busy") NoFreeSlot else if (error.code == "read_only") ReadOnly else Other
            is ApiException.ParkUnavailable -> Unavailable
            is ApiException.ParkUncertain -> Uncertain
            is ApiException.Forbidden -> Forbidden
            is ApiException.Unauthorized -> Revoked
            is ApiException.ReadOnly -> ReadOnly
            is ApiException.NotFound, is ApiException.Gone -> NotFound
            is ApiException.RateLimited -> RateLimited(error.retryAfterSeconds)
            is ApiException.Transport -> Offline
            else -> Other
        }

        /**
         * Does this error of the park POST leave open whether the call was parked? (No answer, or a 5xx from a proxy in
         * between.) The request may have left before a cancellation.
         */
        fun outcomeIsUncertain(error: Throwable): Boolean = when (error) {
            is kotlinx.coroutines.CancellationException -> true
            is ApiException.Transport, is ApiException.Unavailable -> true
            is ApiException.UnexpectedStatus -> error.status == 502 || error.status == 504 || error.status == 500
            else -> false
        }
    }
}

/** What the "On hold" tab and the park button need from the API, per paired account. Every method throws [ApiException]. */
interface ParkServicing {
    /** `POST /calls/park`. [callId] = the SIP `Call-ID` of the running call. */
    suspend fun park(callId: String, account: StoredAccount): ParkedCall

    suspend fun parked(account: StoredAccount): ParkedCallsPage

    suspend fun hangup(id: String, account: StoredAccount)
}

class LiveParkService(private val api: FsVoipApiClient = FsVoipApiClient()) : ParkServicing {
    private fun client(account: StoredAccount) = api.authenticated(account.deviceToken)

    override suspend fun park(callId: String, account: StoredAccount): ParkedCall = client(account).parkCall(callId)

    override suspend fun parked(account: StoredAccount): ParkedCallsPage = client(account).parkedCalls()

    override suspend fun hangup(id: String, account: StoredAccount) {
        client(account).hangupParkedCall(id)
    }
}
