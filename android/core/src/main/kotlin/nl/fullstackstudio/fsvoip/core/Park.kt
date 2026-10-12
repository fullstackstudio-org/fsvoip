// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Parking: put the own running call in a numbered slot ("Parkeren") and pick it up again from any phone of the PBX by dialling the
// `retrieveNumber`. Android port of the iOS `ParkModel`. Plain Kotlin; the screens observe the state flows.

package nl.fullstackstudio.fsvoip.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ParkServicing {
    suspend fun park(account: StoredAccount, callId: String): ParkedCall

    suspend fun parked(account: StoredAccount): ParkedCallsPage

    suspend fun hangup(account: StoredAccount, id: String)
}

class LiveParkService(private val api: FsVoipApiClient) : ParkServicing {
    private fun client(account: StoredAccount) = api.authenticated(account.deviceToken)

    override suspend fun park(account: StoredAccount, callId: String): ParkedCall = client(account).park(callId)

    override suspend fun parked(account: StoredAccount): ParkedCallsPage = client(account).parked()

    override suspend fun hangup(account: StoredAccount, id: String) {
        client(account).hangupParked(id)
    }
}

/** What parking a call gave. */
sealed interface ParkOutcome {
    data class Parked(val call: ParkedCall) : ParkOutcome

    /** 🚨 The call MAY be parked: the list was refreshed, and the request is never repeated. */
    data object Uncertain : ParkOutcome

    data class Failed(val failure: ParkFailure) : ParkOutcome
}

class ParkModel(private val service: ParkServicing) {
    private val _calls = MutableStateFlow<Map<String, List<ParkedCall>>>(emptyMap())
    val calls: StateFlow<Map<String, List<ParkedCall>>> = _calls.asStateFlow()

    private val _available = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val available: StateFlow<Map<String, Boolean>> = _available.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _failure = MutableStateFlow<ParkFailure?>(null)
    val failure: StateFlow<ParkFailure?> = _failure.asStateFlow()

    /** A parking request is on its way: the button waits (one request at a time, never a second one). */
    private val _parking = MutableStateFlow(false)
    val isParking: StateFlow<Boolean> = _parking.asStateFlow()

    /** SIP Call-IDs whose parking outcome is unknown: the call may already be parked, so it is never requested a second time. */
    private val _uncertainCallIds = MutableStateFlow<Set<String>>(emptySet())
    val uncertainCallIds: StateFlow<Set<String>> = _uncertainCallIds.asStateFlow()

    var onRevoked: () -> Unit = {}

    suspend fun refresh(account: StoredAccount) {
        _loading.value = true
        try {
            val page = service.parked(account)
            _calls.value = _calls.value + (account.id to page.calls)
            _available.value = _available.value + (account.id to page.available)
            _failure.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = ParkFailure.classify(e)
            if (failure == ParkFailure.Revoked) onRevoked()
            _failure.value = failure
        } finally {
            _loading.value = false
        }
    }

    /** Parks the call with this SIP `Call-ID` (never the app's internal id). */
    suspend fun park(account: StoredAccount, callId: String): ParkOutcome {
        if (_parking.value) return ParkOutcome.Failed(ParkFailure.Other)

        // An earlier attempt for this very call did not say whether it worked: look, do not repeat.
        if (callId in _uncertainCallIds.value) {
            try {
                refresh(account)
            } catch (_: CancellationException) {
            }
            return ParkOutcome.Uncertain
        }

        _parking.value = true
        try {
            val call = service.park(account, callId)
            _calls.value = _calls.value + (account.id to (_calls.value[account.id].orEmpty().filterNot { it.id == call.id } + call))
            return ParkOutcome.Parked(call)
        } catch (e: Exception) {
            if (ParkFailure.outcomeIsUncertain(e)) {
                _uncertainCallIds.value = _uncertainCallIds.value + callId
                // Look, do not repeat.
                try {
                    refresh(account)
                } catch (_: CancellationException) {
                }
                if (e is CancellationException) throw e
                return ParkOutcome.Uncertain
            }
            val failure = ParkFailure.classify(e)
            if (failure == ParkFailure.Revoked) onRevoked()
            return ParkOutcome.Failed(failure)
        } finally {
            _parking.value = false
        }
    }

    suspend fun hangup(account: StoredAccount, call: ParkedCall): ParkFailure? = try {
        service.hangup(account, call.id)
        _calls.value = _calls.value + (account.id to _calls.value[account.id].orEmpty().filterNot { it.id == call.id })
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val failure = ParkFailure.classify(e)
        // Gone already (picked up or rung back): the list was out of date.
        if (failure == ParkFailure.NotFound) {
            _calls.value = _calls.value + (account.id to _calls.value[account.id].orEmpty().filterNot { it.id == call.id })
            null
        } else {
            failure
        }
    }

    fun forget(accountId: String) {
        _calls.value = _calls.value - accountId
        _available.value = _available.value - accountId
        _uncertainCallIds.value = emptySet()
    }
}
