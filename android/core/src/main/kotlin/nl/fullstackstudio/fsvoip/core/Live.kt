// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Live calls: who is calling the PBX right now, taking a ringing call over to the own extension and transferring a call (blind, internal
// only). Android port of the iOS `LiveModel`. Plain Kotlin; the screens observe the state flows.
//
// - There is no push for this: the list is polled every few seconds, only while the screen is visible (the screen starts and stops it).
// - `ref` changes with every response; the row identity (`LiveCall.id`) does not. An action sends the `ref` the row has in the latest list.
// - Picking up always lands on the own extension: the server takes it from the pairing, the request carries no extension.
// - Attended transfer (talk to the colleague first) is an app-side SIP matter on the running call; this model only does the blind one.

package nl.fullstackstudio.fsvoip.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the Live screen needs from the API, per paired account. One implementation talks to the server; tests bring their own. */
interface LiveServicing {
    suspend fun calls(account: StoredAccount): LiveCallsPage

    suspend fun targets(account: StoredAccount): LiveTargetsPage

    /** Takes the ringing call `ref` over to the OWN extension of [account]. */
    suspend fun pickup(account: StoredAccount, ref: String): LivePickupResponse

    suspend fun transfer(account: StoredAccount, ref: String, target: LiveTarget): LiveTransferResponse
}

class LiveCallService(private val api: FsVoipApiClient) : LiveServicing {
    private fun client(account: StoredAccount) = api.authenticated(account.deviceToken)

    override suspend fun calls(account: StoredAccount): LiveCallsPage = client(account).liveCalls()

    override suspend fun targets(account: StoredAccount): LiveTargetsPage = client(account).liveTargets()

    override suspend fun pickup(account: StoredAccount, ref: String): LivePickupResponse = client(account).pickupLiveCall(ref)

    override suspend fun transfer(account: StoredAccount, ref: String, target: LiveTarget): LiveTransferResponse =
        client(account).transferLiveCall(ref, target)
}

/** Why the Live screen failed, in the user's terms. */
sealed interface LiveFailure {
    /** 404 `call_not_found`: the call is gone. */
    data object CallNotFound : LiveFailure

    /** 409 `call_not_ringing`: somebody else answered, or the caller hung up. */
    data object NotRinging : LiveFailure

    /** 409 `call_not_transferable`. */
    data object NotTransferable : LiveFailure

    /** 409 `live_unavailable`: the PBX cannot do live calls now. */
    data object Unavailable : LiveFailure

    /** 403: a `user` may only transfer the call of the own extension (or take over a ring group / queue / own call). */
    data object Forbidden : LiveFailure

    /** 401: the pairing is gone. */
    data object Revoked : LiveFailure

    data object ReadOnly : LiveFailure

    data object InvalidTarget : LiveFailure

    data class RateLimited(val retryAfterSeconds: Int?) : LiveFailure

    data object Offline : LiveFailure

    /** Any other code, also one this version does not know: generic. */
    data object Other : LiveFailure

    companion object {
        fun classify(error: Throwable): LiveFailure = when (error) {
            is ApiException.CallNotFound, is ApiException.NotFound -> CallNotFound
            is ApiException.Conflict -> when (error.error) {
                "call_not_ringing" -> NotRinging
                "call_not_transferable" -> NotTransferable
                "live_unavailable" -> Unavailable
                "read_only" -> ReadOnly
                else -> Other
            }
            is ApiException.Forbidden -> Forbidden
            is ApiException.Unauthorized -> Revoked
            is ApiException.InvalidRequest -> InvalidTarget
            is ApiException.RateLimited -> RateLimited(error.retryAfterSeconds)
            is ApiException.Transport -> Offline
            else -> Other
        }
    }
}

/** What a row may offer. The server decides (`canPickup` / `canTransfer`, per role and per PBX state); the app only follows. */
object LiveRules {
    /** The "Overnemen" button: only what the server says can be taken over. */
    fun showPickup(call: LiveCall): Boolean = call.canPickup

    /** The "Doorverbinden" button: the server allows it and there is somewhere to transfer to. */
    fun showTransfer(call: LiveCall, targets: List<LiveTarget>): Boolean = call.canTransfer && targets.isNotEmpty()

    /** Should the live data be polled? Only while the screen is visible. */
    fun shouldPoll(resumed: Boolean, selectedOnHoldTab: Boolean): Boolean = resumed && selectedOnHoldTab

    /** Targets grouped by kind, in a fixed order; a search term filters on name and number. */
    fun grouped(targets: List<LiveTarget>, search: String): List<Pair<LiveTargetType, List<LiveTarget>>> {
        val needle = search.trim().lowercase()
        val visible = if (needle.isEmpty()) targets else targets.filter { it.name.lowercase().contains(needle) || it.extension.contains(needle) }
        val order = listOf(
            LiveTargetType.EXTENSION,
            LiveTargetType.RING_GROUP,
            LiveTargetType.QUEUE,
            LiveTargetType.IVR,
            LiveTargetType.BUSINESS_HOURS,
            LiveTargetType.VOICEMAIL,
        )

        return order.mapNotNull { type -> visible.filter { it.type == type }.takeIf { it.isNotEmpty() }?.let { type to it } }
    }

    /** `1:23` / `1:02:03`. */
    fun clock(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60

        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

class LiveModel(
    private val service: LiveServicing,
    private val scope: CoroutineScope,
) {
    sealed interface PickupResult {
        data object PickedUp : PickupResult

        data class Failed(val failure: LiveFailure) : PickupResult

        /** The same call is already being handled. */
        data object Ignored : PickupResult
    }

    sealed interface TransferResult {
        data class Transferred(val destination: String?) : TransferResult

        data class Failed(val failure: LiveFailure) : TransferResult

        data object Ignored : TransferResult
    }

    private val _calls = MutableStateFlow<List<LiveCall>>(emptyList())
    val calls: StateFlow<List<LiveCall>> = _calls.asStateFlow()

    /** `null` = not known yet. `false` = live calls are off for this PBX: show the explanation instead of a list. */
    private val _enabled = MutableStateFlow<Boolean?>(null)
    val enabled: StateFlow<Boolean?> = _enabled.asStateFlow()

    /** `false` = the list may lag a few seconds. */
    private val _fresh = MutableStateFlow(true)
    val fresh: StateFlow<Boolean> = _fresh.asStateFlow()

    private val _targets = MutableStateFlow<List<LiveTarget>>(emptyList())
    val targets: StateFlow<List<LiveTarget>> = _targets.asStateFlow()

    private val _readOnly = MutableStateFlow(false)
    val readOnly: StateFlow<Boolean> = _readOnly.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _loading.asStateFlow()

    /** The last refresh failed (the old list stays). */
    private val _failure = MutableStateFlow<LiveFailure?>(null)
    val failure: StateFlow<LiveFailure?> = _failure.asStateFlow()

    private val _polling = MutableStateFlow(false)
    val isPolling: StateFlow<Boolean> = _polling.asStateFlow()

    private val _busyIds = MutableStateFlow<Set<String>>(emptySet())
    val busyIds: StateFlow<Set<String>> = _busyIds.asStateFlow()

    /** A 401: the pairing is gone. */
    var onRevoked: () -> Unit = {}

    var refreshCount = 0
        private set

    private var currentAccountId: String? = null
    private var targetsAccountId: String? = null
    private var pollJob: Job? = null

    val ringing: List<LiveCall> get() = _calls.value.filter { it.isRinging }
    val running: List<LiveCall> get() = _calls.value.filter { it.isRunning }
    val parked: List<LiveCall> get() = _calls.value.filter { it.isParked }

    fun forget(accountId: String) {
        if (currentAccountId != accountId) return

        stopPolling()
        _calls.value = emptyList()
        _enabled.value = null
        _failure.value = null
        _targets.value = emptyList()
        targetsAccountId = null
        currentAccountId = null
    }

    /** Reads the list of this account. Switching account starts from an empty list. */
    suspend fun refresh(account: StoredAccount): Boolean {
        if (currentAccountId != account.id) {
            currentAccountId = account.id
            _calls.value = emptyList()
            _enabled.value = null
            _failure.value = null
        }

        _loading.value = true
        refreshCount++

        try {
            val page = service.calls(account)

            if (currentAccountId != account.id) return false

            // Longest running first: a stable order between polls.
            _calls.value = page.calls.sortedBy { it.startedAt }
            _enabled.value = page.enabled
            _fresh.value = page.fresh
            _failure.value = null

            if (page.enabled && targetsAccountId != account.id) loadTargets(account)

            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (currentAccountId != account.id) return false

            val classified = LiveFailure.classify(e)

            if (classified == LiveFailure.Unavailable) {
                // The module cannot do it now: the same as "off".
                _calls.value = emptyList()
                _enabled.value = false
                _failure.value = null
            } else {
                _failure.value = classified
            }

            if (classified == LiveFailure.Revoked) onRevoked()

            return false
        } finally {
            _loading.value = false
        }
    }

    /** The transfer targets of this PBX, once per account (they rarely change; a transfer that fails with a stale target reloads them). */
    suspend fun loadTargets(account: StoredAccount) {
        try {
            val page = service.targets(account)

            if (currentAccountId != null && currentAccountId != account.id) return

            _targets.value = page.choosable
            _readOnly.value = page.readOnly
            targetsAccountId = account.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (LiveFailure.classify(e) == LiveFailure.Revoked) onRevoked()
            // Without targets the transfer button stays off; the next poll tries again.
        }
    }

    /** Polls while the screen is visible. Calling it again for the same account does nothing; another account restarts it. */
    fun startPolling(account: StoredAccount, intervalMillis: Long = 3_000) {
        if (_polling.value && currentAccountId == account.id) return

        stopPolling()
        _polling.value = true

        pollJob = scope.launch {
            while (isActive) {
                refresh(account)
                delay(intervalMillis)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        _polling.value = false
    }

    /** Takes the ringing call over to the own extension and says what happened. The call then rings on the own phone. */
    suspend fun pickup(call: LiveCall, account: StoredAccount): PickupResult {
        if (call.id in _busyIds.value) return PickupResult.Ignored

        _busyIds.value = _busyIds.value + call.id

        try {
            service.pickup(account, call.ref)
            refresh(account)

            return PickupResult.PickedUp
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val classified = LiveFailure.classify(e)

            if (classified == LiveFailure.Revoked) onRevoked()

            // The call changed in the meantime: show the truth.
            if (classified == LiveFailure.NotRinging || classified == LiveFailure.CallNotFound) refresh(account)

            return PickupResult.Failed(classified)
        } finally {
            _busyIds.value = _busyIds.value - call.id
        }
    }

    /** Blind transfer to an internal target. */
    suspend fun transfer(call: LiveCall, target: LiveTarget, account: StoredAccount): TransferResult {
        if (call.id in _busyIds.value) return TransferResult.Ignored

        _busyIds.value = _busyIds.value + call.id

        try {
            val response = service.transfer(account, call.ref, target)
            refresh(account)

            return TransferResult.Transferred(response.destinationType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val classified = LiveFailure.classify(e)

            if (classified == LiveFailure.Revoked) onRevoked()

            if (classified == LiveFailure.NotTransferable || classified == LiveFailure.CallNotFound) refresh(account)

            if (classified == LiveFailure.InvalidTarget) {
                // The list of targets was stale: read it again.
                targetsAccountId = null
                loadTargets(account)
            }

            return TransferResult.Failed(classified)
        } finally {
            _busyIds.value = _busyIds.value - call.id
        }
    }
}
