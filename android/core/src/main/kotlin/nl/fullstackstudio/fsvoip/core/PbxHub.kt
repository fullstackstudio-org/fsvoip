// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The "Centrale" side of the app (admin pairings): the state of the PBX objects per account, and why a change did not work. Plain
// Kotlin; the screens observe the state flows. The server enforces the role on every route; this only keeps what it answered.

package nl.fullstackstudio.fsvoip.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement

/** Why a PBX request did not work, in the words the screens need. */
sealed interface PbxFailure {
    /** 403: this pairing is not (or no longer) an administrator. */
    data object AccessDenied : PbxFailure

    /** 401: the pairing is gone. */
    data object Revoked : PbxFailure

    /** 409 `read_only`: the PBX is frozen or still being set up. */
    data object ReadOnly : PbxFailure

    /** 409 `stale`: someone else changed it; the screen shows the new state ([chain] for a number chain, best effort). */
    data class Stale(val version: Int?, val chain: JsonElement?) : PbxFailure

    /** 409 `advanced`: the flow behind a number is more than the chain can show. */
    data object Advanced : PbxFailure

    /** 409 `in_use`: nothing was deleted, it is still used here. */
    data class InUse(val places: List<UsePlace>) : PbxFailure

    /** 409 `too_many`: the PBX has the maximum number of sounds. */
    data object TooMany : PbxFailure

    /** 409 `conflict`: for example inviting the extension of this very phone. */
    data class Conflict(val code: String?) : PbxFailure

    /** 422 `blocked_destination`: a block list stops these external numbers. */
    data class Blocked(val numbers: List<String>) : PbxFailure

    /** 422 `cost_not_accepted`: show the price and ask again with `costAccepted`. */
    data class CostNotAccepted(val cost: RecordingCost?) : PbxFailure

    /** 400: the server does not accept what was sent. `code` is the portal's code (`greeting_required`, `invalid_hours`, ...). */
    data class Invalid(val code: String?, val field: String?) : PbxFailure

    /** 400 `invalid_audio`: not a WAV, MP3 or M4A file. */
    data object InvalidAudio : PbxFailure

    /** 413: the file is above 20 MB. */
    data object TooLarge : PbxFailure

    data object NotFound : PbxFailure

    data class RateLimited(val retryAfterSeconds: Int?) : PbxFailure

    data object Offline : PbxFailure

    data object Unavailable : PbxFailure

    data object Other : PbxFailure

    companion object {
        fun classify(error: Throwable): PbxFailure = when (error) {
            is ApiException.Unauthorized -> Revoked
            is ApiException.Forbidden -> AccessDenied
            is ApiException.NotFound -> NotFound
            is ApiException.RateLimited -> RateLimited(error.retryAfterSeconds)
            is ApiException.Transport -> Offline
            is ApiException.Unavailable, is ApiException.UnexpectedStatus -> Unavailable
            is ApiException.PayloadTooLarge -> TooLarge
            is ApiException.BlockedDestination -> Blocked(error.numbers)
            is ApiException.CostNotAccepted -> CostNotAccepted(error.cost)
            is ApiException.InvalidRequest -> if (error.error == "invalid_audio") InvalidAudio else Invalid(error.code, error.field)
            is ApiException.Conflict -> when (error.error) {
                "stale" -> Stale(error.version, error.chain)
                "read_only" -> ReadOnly
                "advanced" -> Advanced
                "in_use" -> InUse(error.places.asPlaces())
                "too_many" -> TooMany
                "conflict" -> Conflict(error.code)
                else -> Other
            }
            else -> Other
        }
    }
}

interface PbxServicing {
    suspend fun devices(account: StoredAccount): PbxDevices

    suspend fun patchDevice(account: StoredAccount, id: String, patch: PbxDevicePatch)

    suspend fun ringGroups(account: StoredAccount): PbxRingGroups

    suspend fun createRingGroup(account: StoredAccount, create: PbxRingGroupCreate): String

    suspend fun patchRingGroup(account: StoredAccount, id: String, patch: PbxRingGroupPatch)
}

class LivePbxService(private val api: FsVoipApiClient) : PbxServicing {
    private fun client(account: StoredAccount) = api.authenticated(account.deviceToken)

    override suspend fun devices(account: StoredAccount): PbxDevices = client(account).pbxDevices()

    override suspend fun patchDevice(account: StoredAccount, id: String, patch: PbxDevicePatch) {
        client(account).patchPbxDevice(id, patch)
    }

    override suspend fun ringGroups(account: StoredAccount): PbxRingGroups = client(account).pbxRingGroups()

    override suspend fun createRingGroup(account: StoredAccount, create: PbxRingGroupCreate): String = client(account).createPbxRingGroup(create).ringGroupId

    override suspend fun patchRingGroup(account: StoredAccount, id: String, patch: PbxRingGroupPatch) {
        client(account).patchPbxRingGroup(id, patch)
    }
}

/** The PBX objects per account id. Every `suspend` function returns `null` when it worked, otherwise the reason. */
class PbxHub(private val service: PbxServicing) {
    private val _devices = MutableStateFlow<Map<String, PbxDevices>>(emptyMap())
    private val _ringGroups = MutableStateFlow<Map<String, PbxRingGroups>>(emptyMap())

    val devices: StateFlow<Map<String, PbxDevices>> = _devices.asStateFlow()
    val ringGroups: StateFlow<Map<String, PbxRingGroups>> = _ringGroups.asStateFlow()

    /** Called when a request says the pairing is revoked (`401`); the app model reads `GET /me`. */
    var onRevoked: () -> Unit = {}

    /** Called when a request says this pairing is no administrator (any more) (`403`): `GET /me` shows the role it has now. */
    var onDenied: () -> Unit = {}

    suspend fun loadDevices(account: StoredAccount): PbxFailure? = guarded { _devices.value = _devices.value + (account.id to service.devices(account)) }

    suspend fun saveDevice(account: StoredAccount, id: String, patch: PbxDevicePatch): PbxFailure? = guarded {
        service.patchDevice(account, id, patch)
    }.also { refreshAfterSave(it) { loadDevices(account) } }

    suspend fun loadRingGroups(account: StoredAccount): PbxFailure? = guarded { _ringGroups.value = _ringGroups.value + (account.id to service.ringGroups(account)) }

    suspend fun saveRingGroup(account: StoredAccount, id: String, patch: PbxRingGroupPatch): PbxFailure? = guarded {
        service.patchRingGroup(account, id, patch)
    }.also { refreshAfterSave(it) { loadRingGroups(account) } }

    suspend fun createRingGroup(account: StoredAccount, create: PbxRingGroupCreate): PbxFailure? = guarded {
        service.createRingGroup(account, create)
    }.also { refreshAfterSave(it) { loadRingGroups(account) } }

    fun forget(accountId: String) {
        _devices.value = _devices.value - accountId
        _ringGroups.value = _ringGroups.value - accountId
    }

    /** After a save the lists follow the server ("wordt bijgewerkt"), and after a `stale` they show what changed. */
    private suspend fun refreshAfterSave(failure: PbxFailure?, reload: suspend () -> PbxFailure?) {
        if (failure == null || failure is PbxFailure.Stale) reload()
    }

    private suspend fun guarded(block: suspend () -> Unit): PbxFailure? = try {
        block()
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val failure = PbxFailure.classify(error)

        when (failure) {
            PbxFailure.Revoked -> onRevoked()
            PbxFailure.AccessDenied -> onDenied()
            else -> Unit
        }

        failure
    }
}
