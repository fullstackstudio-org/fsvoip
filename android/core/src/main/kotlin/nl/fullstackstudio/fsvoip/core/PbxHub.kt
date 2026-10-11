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

    suspend fun overview(account: StoredAccount): PbxOverview

    suspend fun hours(account: StoredAccount): PbxHours

    suspend fun patchHours(account: StoredAccount, id: String, patch: PbxHoursPatch)

    suspend fun patchRouting(account: StoredAccount, numberId: String, patch: PbxRoutingPatch)

    suspend fun numbers(account: StoredAccount): PbxNumbersPage

    suspend fun chain(account: StoredAccount, numberId: String): NumberChain

    suspend fun putChainStep(account: StoredAccount, numberId: String, step: ChainStep, basedOn: NumberChain): NumberChain

    suspend fun patchRecording(account: StoredAccount, numberId: String, patch: NumberRecordingPatch): NumberChain

    suspend fun sounds(account: StoredAccount): PbxSoundsPage

    suspend fun uploadSound(account: StoredAccount, name: String, fileName: String, contentType: String, bytes: ByteArray): String

    suspend fun renameSound(account: StoredAccount, id: String, name: String)

    suspend fun deleteSound(account: StoredAccount, id: String)

    fun soundSource(account: StoredAccount, id: String): MediaSource

    suspend fun invite(account: StoredAccount, deviceId: String): AppPairingResponse
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

    override suspend fun overview(account: StoredAccount): PbxOverview = client(account).pbxOverview()

    override suspend fun hours(account: StoredAccount): PbxHours = client(account).pbxHours()

    override suspend fun patchHours(account: StoredAccount, id: String, patch: PbxHoursPatch) {
        client(account).patchPbxHours(id, patch)
    }

    override suspend fun patchRouting(account: StoredAccount, numberId: String, patch: PbxRoutingPatch) {
        client(account).patchPbxNumberRouting(numberId, patch)
    }

    override suspend fun numbers(account: StoredAccount): PbxNumbersPage = client(account).pbxNumbers()

    override suspend fun chain(account: StoredAccount, numberId: String): NumberChain = client(account).pbxNumberChain(numberId)

    override suspend fun putChainStep(account: StoredAccount, numberId: String, step: ChainStep, basedOn: NumberChain): NumberChain =
        client(account).putPbxNumberChainStep(numberId, step, basedOn)

    override suspend fun patchRecording(account: StoredAccount, numberId: String, patch: NumberRecordingPatch): NumberChain =
        client(account).patchPbxNumberRecording(numberId, patch)

    override suspend fun sounds(account: StoredAccount): PbxSoundsPage = client(account).pbxSounds()

    override suspend fun uploadSound(account: StoredAccount, name: String, fileName: String, contentType: String, bytes: ByteArray): String =
        client(account).uploadPbxSound(name, fileName, contentType, bytes).soundId

    override suspend fun renameSound(account: StoredAccount, id: String, name: String) {
        client(account).renamePbxSound(id, name)
    }

    override suspend fun deleteSound(account: StoredAccount, id: String) {
        client(account).deletePbxSound(id)
    }

    override fun soundSource(account: StoredAccount, id: String): MediaSource = client(account).soundAudio(id)

    override suspend fun invite(account: StoredAccount, deviceId: String): AppPairingResponse = client(account).createPbxDeviceAppPairing(deviceId)
}

/** What saving a step of a number chain gave. */
sealed interface ChainSaveOutcome {
    data class Saved(val chain: NumberChain) : ChainSaveOutcome

    /** Changed in the meantime: [fresh] is the new state (also in the hub); what the user typed has not been sent. */
    data class Stale(val fresh: NumberChain?) : ChainSaveOutcome

    data class Failed(val failure: PbxFailure) : ChainSaveOutcome
}

/** The PBX objects per account id. Every `suspend` function returns `null` when it worked, otherwise the reason. */
class PbxHub(private val service: PbxServicing) {
    private val _devices = MutableStateFlow<Map<String, PbxDevices>>(emptyMap())
    private val _ringGroups = MutableStateFlow<Map<String, PbxRingGroups>>(emptyMap())

    private val _overview = MutableStateFlow<Map<String, PbxOverview>>(emptyMap())
    private val _hours = MutableStateFlow<Map<String, PbxHours>>(emptyMap())
    private val _numbers = MutableStateFlow<Map<String, PbxNumbersPage>>(emptyMap())
    private val _chains = MutableStateFlow<Map<String, NumberChain>>(emptyMap())
    private val _sounds = MutableStateFlow<Map<String, List<PbxSound>>>(emptyMap())

    val overview: StateFlow<Map<String, PbxOverview>> = _overview.asStateFlow()
    val hours: StateFlow<Map<String, PbxHours>> = _hours.asStateFlow()
    val numbers: StateFlow<Map<String, PbxNumbersPage>> = _numbers.asStateFlow()

    /** Keyed `<accountId>/<numberId>`. */
    val chains: StateFlow<Map<String, NumberChain>> = _chains.asStateFlow()
    val sounds: StateFlow<Map<String, List<PbxSound>>> = _sounds.asStateFlow()

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

    // MARK: Belstroom and opening hours

    suspend fun loadOverview(account: StoredAccount): PbxFailure? = guarded { _overview.value = _overview.value + (account.id to service.overview(account)) }

    suspend fun changeRouting(account: StoredAccount, numberId: String, patch: PbxRoutingPatch): PbxFailure? = guarded {
        service.patchRouting(account, numberId, patch)
    }.also { refreshAfterSave(it) { loadOverview(account) } }

    suspend fun loadHours(account: StoredAccount): PbxFailure? = guarded { _hours.value = _hours.value + (account.id to service.hours(account)) }

    suspend fun saveHours(account: StoredAccount, id: String, patch: PbxHoursPatch): PbxFailure? = guarded {
        service.patchHours(account, id, patch)
    }.also { refreshAfterSave(it) { loadHours(account) } }

    // MARK: Numbers

    suspend fun loadNumbers(account: StoredAccount): PbxFailure? = guarded { _numbers.value = _numbers.value + (account.id to service.numbers(account)) }

    fun chain(accountId: String, numberId: String): NumberChain? = _chains.value["$accountId/$numberId"]

    suspend fun loadChain(account: StoredAccount, numberId: String): PbxFailure? = guarded { putChain(account.id, service.chain(account, numberId)) }

    /** One step of a chain, based on the chain the screen showed ([basedOn]: its `versions` go back, so a change in between is `stale`). */
    suspend fun saveStep(account: StoredAccount, numberId: String, step: ChainStep, basedOn: NumberChain): ChainSaveOutcome =
        chainCall(account, numberId) { service.putChainStep(account, numberId, step, basedOn) }

    suspend fun saveRecording(account: StoredAccount, numberId: String, patch: NumberRecordingPatch): ChainSaveOutcome =
        chainCall(account, numberId) { service.patchRecording(account, numberId, patch) }

    private suspend fun chainCall(account: StoredAccount, numberId: String, block: suspend () -> NumberChain): ChainSaveOutcome = try {
        val fresh = block()
        putChain(account.id, fresh)
        refreshNumbers(account)
        ChainSaveOutcome.Saved(fresh)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val failure = classified(error)

        if (failure is PbxFailure.Stale) {
            // The fresh chain rides along in the error; if it cannot be read, ask for it.
            val fresh = failure.chain?.let { runCatching { FsJson.default.decodeFromJsonElement(NumberChain.serializer(), it) }.getOrNull() }
                ?: runCatching { service.chain(account, numberId) }.getOrNull()
            fresh?.let { putChain(account.id, it) }
            ChainSaveOutcome.Stale(fresh)
        } else {
            ChainSaveOutcome.Failed(failure)
        }
    }

    private suspend fun refreshNumbers(account: StoredAccount) {
        if (_numbers.value.containsKey(account.id)) loadNumbers(account)
    }

    private fun putChain(accountId: String, chain: NumberChain) {
        _chains.value = _chains.value + ("$accountId/${chain.id}" to chain)
    }

    // MARK: Sounds

    suspend fun loadSounds(account: StoredAccount): PbxFailure? = guarded { _sounds.value = _sounds.value + (account.id to service.sounds(account).sounds) }

    suspend fun uploadSound(account: StoredAccount, name: String, fileName: String, contentType: String, bytes: ByteArray): PbxFailure? = guarded {
        service.uploadSound(account, name, fileName, contentType, bytes)
    }.also { if (it == null) loadSounds(account) }

    suspend fun renameSound(account: StoredAccount, id: String, name: String): PbxFailure? = guarded { service.renameSound(account, id, name) }.also { if (it == null) loadSounds(account) }

    /** A sound that is still used is `PbxFailure.InUse` with the places; nothing is deleted then. */
    suspend fun deleteSound(account: StoredAccount, id: String): PbxFailure? = guarded { service.deleteSound(account, id) }.also {
        if (it == null || it is PbxFailure.NotFound) loadSounds(account)
    }

    fun soundSource(account: StoredAccount, id: String): MediaSource = service.soundSource(account, id)

    // MARK: Inviting a colleague

    /** The link is a credential: the caller shows it once and never keeps or logs it. */
    suspend fun invite(account: StoredAccount, deviceId: String): Pair<AppPairingResponse?, PbxFailure?> = try {
        service.invite(account, deviceId) to null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null to classified(error)
    }

    fun forget(accountId: String) {
        _devices.value = _devices.value - accountId
        _ringGroups.value = _ringGroups.value - accountId
        _overview.value = _overview.value - accountId
        _hours.value = _hours.value - accountId
        _numbers.value = _numbers.value - accountId
        _sounds.value = _sounds.value - accountId
        _chains.value = _chains.value.filterKeys { !it.startsWith("$accountId/") }
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
        classified(error)
    }

    /** The failure for an error, and the side effect of a revoked pairing or a lost role. */
    private fun classified(error: Exception): PbxFailure {
        val failure = PbxFailure.classify(error)

        when (failure) {
            PbxFailure.Revoked -> onRevoked()
            PbxFailure.AccessDenied -> onDenied()
            else -> Unit
        }

        return failure
    }
}
