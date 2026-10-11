// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Parking (plan `fsvoip-app-v2`, D7, Task 16): the park button in a call and the "In de wacht" tab. The Android port of the iOS
// `ParkModel`; the rules are the same.
//
// - Parking is `POST /calls/park` with the SIP `Call-ID` of the running call. 🚨 A `park_uncertain` answer means the call MAY be
//   parked: this model never sends that `callId` again, it refreshes the list and says so.
// - Picking a call up is NOT an API call: the list is refreshed first (the slot may be gone), then the call's `retrieveNumber`
//   (`*5901`) is dialled as a normal call. There is no time-out logic here: the PBX rings the extension that parked the call.
// - The list is polled every few seconds, but only while the tab is visible (the screen runs [poll] while it is composed and the
//   app is in the foreground); there is no push for this.

package nl.fullstackstudio.fsvoip.ui.park

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import nl.fullstackstudio.fsvoip.core.ParkFailure
import nl.fullstackstudio.fsvoip.core.ParkServicing
import nl.fullstackstudio.fsvoip.core.ParkedCall
import nl.fullstackstudio.fsvoip.core.RecentCall
import nl.fullstackstudio.fsvoip.core.StoredAccount

class ParkModel(private val service: ParkServicing) {
    enum class Scope { ALL, MINE }

    sealed interface ParkResult {
        data class Parked(val slot: Int) : ParkResult

        data class Failed(val failure: ParkFailure) : ParkResult

        /** A park request is already running (or this call is already being parked). */
        data object Ignored : ParkResult
    }

    sealed interface RetrieveResult {
        /** The retrieve number is being dialled. */
        data object Dialing : RetrieveResult

        /** The call is not parked any more ("Niet meer geparkeerd"). */
        data object Gone : RetrieveResult

        /** Dialling refused; the app model already told the user why (do not overwrite that with a generic message). */
        data object DialFailed : RetrieveResult

        data class Failed(val failure: ParkFailure) : RetrieveResult
    }

    sealed interface HangupResult {
        data object Done : HangupResult

        data object Gone : HangupResult

        data class Failed(val failure: ParkFailure) : HangupResult
    }

    private val _calls = MutableStateFlow<List<ParkedCall>>(emptyList())
    private val _available = MutableStateFlow<Boolean?>(null)
    private val _isLoading = MutableStateFlow(false)
    private val _failure = MutableStateFlow<ParkFailure?>(null)
    private val _isParking = MutableStateFlow(false)
    private val _busyIds = MutableStateFlow<Set<String>>(emptySet())

    val calls: StateFlow<List<ParkedCall>> = _calls.asStateFlow()

    /** `null` = not known yet. `false` = this PBX cannot park right now: show the explanation instead of an empty list. */
    val available: StateFlow<Boolean?> = _available.asStateFlow()
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** The last refresh failed (the old list stays). */
    val failure: StateFlow<ParkFailure?> = _failure.asStateFlow()
    val isParking: StateFlow<Boolean> = _isParking.asStateFlow()
    val busyIds: StateFlow<Set<String>> = _busyIds.asStateFlow()

    /** Dials a number on an account as a normal call. Wired by the app model. */
    var dial: (number: String, accountId: String) -> Boolean = { _, _ -> false }

    /** Tells the user the call is no longer parked. Wired by the app model. */
    var notifyGone: () -> Unit = {}

    /** May this pairing hang up calls of colleagues (an admin)? A `user` may only hang up their own. */
    var isAdmin: (accountId: String) -> Boolean = { false }

    /** A 401: the pairing is gone. */
    var onRevoked: (() -> Unit)? = null

    /** How many list requests were made (tests). */
    var refreshCount = 0
        private set

    private var currentAccountId: String? = null

    /** Call-IDs whose parking ended `park_uncertain`: never parked again. */
    private val uncertainCallIds = mutableSetOf<String>()
    private var pendingRetrieve: PendingRetrieve? = null

    private data class PendingRetrieve(val number: String, val accountId: String)

    // MARK: Reading

    fun calls(scope: Scope): List<ParkedCall> = if (scope == Scope.MINE) _calls.value.filter { it.mine } else _calls.value

    val mineCount: Int get() = _calls.value.count { it.mine }

    /** May the user hang this call up? A `user` only a call that is theirs. */
    fun canHangUp(call: ParkedCall, accountId: String): Boolean = call.mine || isAdmin(accountId)

    fun forget(accountId: String) {
        if (currentAccountId != accountId) return

        _calls.value = emptyList()
        _available.value = null
        _failure.value = null
        currentAccountId = null
    }

    // MARK: Refreshing

    /** Reads the list of this account. Switching account starts from an empty list. */
    suspend fun refresh(account: StoredAccount): Boolean {
        if (currentAccountId != account.id) {
            currentAccountId = account.id
            _calls.value = emptyList()
            _available.value = null
            _failure.value = null
        }

        _isLoading.value = true
        refreshCount++

        try {
            val page = service.parked(account)

            // The user may have switched account while the request ran.
            if (currentAccountId != account.id) return false

            _calls.value = page.calls.sortedBy { it.parkedAt ?: Instant.EPOCH }
            _available.value = page.available
            _failure.value = null

            return true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            if (currentAccountId != account.id) return false

            val classified = ParkFailure.classify(error)
            _failure.value = classified

            if (classified == ParkFailure.Revoked) onRevoked?.invoke()

            return false
        } finally {
            _isLoading.value = false
        }
    }

    /** Refreshes every [intervalMillis] until the calling coroutine is cancelled (the tab left the screen or the app went away). */
    suspend fun poll(account: StoredAccount, intervalMillis: Long = 5_000) {
        while (currentCoroutineContext().isActive) {
            refresh(account)
            delay(intervalMillis)
        }
    }

    // MARK: Parking

    suspend fun park(callId: String, account: StoredAccount): ParkResult {
        if (_isParking.value || callId in uncertainCallIds) {
            return ParkResult.Ignored
        }

        _isParking.value = true

        try {
            val parked = service.park(callId, account)
            refreshIfShowing(account)

            return ParkResult.Parked(parked.slot)
        } catch (error: Throwable) {
            var classified = ParkFailure.classify(error)

            // A time-out, a transport error or a gateway error after the request left: the PBX may have parked the call anyway.
            if (classified != ParkFailure.Uncertain && ParkFailure.outcomeIsUncertain(error)) {
                classified = ParkFailure.Uncertain
            }

            if (classified == ParkFailure.Uncertain) {
                // Look, never repeat.
                uncertainCallIds += callId
            }

            if (error is CancellationException) throw error

            if (classified == ParkFailure.Uncertain) {
                refreshIfShowing(account)
            } else if (classified == ParkFailure.Revoked) {
                onRevoked?.invoke()
            }

            return ParkResult.Failed(classified)
        } finally {
            _isParking.value = false
        }
    }

    /** Refreshes only when the list on screen belongs to [account]: parking a call of another account must not switch the list. */
    private suspend fun refreshIfShowing(account: StoredAccount) {
        if (currentAccountId != null && currentAccountId != account.id) return

        refresh(account)
    }

    // MARK: Picking up

    /** Refreshes, then dials the retrieve number of the fresh entry. A call that is gone from the list is not dialled. */
    suspend fun retrieve(call: ParkedCall, account: StoredAccount): RetrieveResult {
        if (call.id in _busyIds.value) return RetrieveResult.Failed(ParkFailure.Other)

        _busyIds.value += call.id

        try {
            if (!refresh(account)) {
                return RetrieveResult.Failed(_failure.value ?: ParkFailure.Other)
            }

            val number = _calls.value.firstOrNull { it.id == call.id }?.retrieveNumber?.takeIf { it.isNotEmpty() } ?: return RetrieveResult.Gone

            pendingRetrieve = PendingRetrieve(number, account.id)

            if (!dial(number, account.id)) {
                pendingRetrieve = null

                return RetrieveResult.DialFailed
            }

            return RetrieveResult.Dialing
        } finally {
            _busyIds.value -= call.id
        }
    }

    /**
     * A call ended. If it was our pick-up and it never connected, the slot was gone: say so. The caller refreshes the list
     * afterwards when this returns `true`.
     */
    fun callFinished(call: RecentCall): Boolean {
        val pending = pendingRetrieve ?: return false

        if (pending.accountId != call.accountId || pending.number != call.number) return false

        pendingRetrieve = null

        if (call.outcome == RecentCall.Outcome.ANSWERED) return false

        notifyGone()

        return true
    }

    // MARK: Hanging up

    suspend fun hangUp(call: ParkedCall, account: StoredAccount): HangupResult {
        if (!canHangUp(call, account.id)) {
            return HangupResult.Failed(ParkFailure.Forbidden)
        }

        if (call.id in _busyIds.value) return HangupResult.Failed(ParkFailure.Other)

        _busyIds.value += call.id

        try {
            service.hangup(call.id, account)
            _calls.value = _calls.value.filter { it.id != call.id }
            refresh(account)

            return HangupResult.Done
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            val classified = ParkFailure.classify(error)

            if (classified == ParkFailure.NotFound) {
                refresh(account)

                return HangupResult.Gone
            }

            if (classified == ParkFailure.Revoked) onRevoked?.invoke()

            return HangupResult.Failed(classified)
        } finally {
            _busyIds.value -= call.id
        }
    }
}

object ParkFormat {
    /** `1:23`, `0:37`, `1:02:03`. */
    fun clock(totalSeconds: Long): String {
        val total = totalSeconds.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60

        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    /** `1:23` since the call was parked; `null` when unknown. */
    fun elapsed(since: Instant?, now: Instant): String? = since?.let { clock(Duration.between(it, now).seconds) }

    /** `0:37` until the call rings back; `null` when unknown or passed. */
    fun remaining(until: Instant?, now: Instant): String? {
        if (until == null || !until.isAfter(now)) return null

        return clock(Math.ceil(Duration.between(now, until).toMillis() / 1000.0).toLong())
    }

    /** `01` for slot 1 (the PBX shows `*5901`). */
    fun slot(slot: Int): String = "%02d".format(slot)
}
