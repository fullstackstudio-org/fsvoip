// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The own extension per paired account (`GET`/`PATCH /me/extension`): the state "Beschikbaar" (do not disturb) and "Oproepvoorkeuren"
// (forwarding, no-answer, voicemail) edit. Android port of the iOS `SelfExtensionHub` / `AvailabilityHub`.

package nl.fullstackstudio.fsvoip.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the hub needs from the API. One implementation talks to the server; tests bring their own. Every method throws [ApiException]. */
interface SelfExtensionServicing {
    suspend fun load(account: StoredAccount): SelfExtension

    /** `PATCH /me/extension`: the fresh state, or `null` when the server could not read it right now. */
    suspend fun patch(patch: SelfExtensionPatch, account: StoredAccount): SelfExtension?
}

class LiveSelfExtensionService(private val api: FsVoipApiClient = FsVoipApiClient()) : SelfExtensionServicing {
    override suspend fun load(account: StoredAccount): SelfExtension = api.authenticated(account.deviceToken).selfExtension()

    override suspend fun patch(patch: SelfExtensionPatch, account: StoredAccount): SelfExtension? =
        api.authenticated(account.deviceToken).patchSelfExtension(patch).state()
}

/** What "Oproepvoorkeuren" shows of the own extension, as one value, and the translation to a PATCH body that holds only what changed. */
data class SelfExtensionDraft(
    val forwardAlways: PbxTarget? = null,
    val noAnswerSeconds: Int = 25,
    val noAnswerTarget: PbxTarget? = null,
    val voicemailEnabled: Boolean = false,
    val voicemailToEmail: Boolean = false,
    val email: String = "",
) {
    val cleanEmail: String
        get() = email.trim()

    /**
     * Only the keys that differ from [original], with [version]. `null` = nothing changed. The number to call out with is not a
     * setting and "dnd" belongs to the switch on the avatar, so neither is ever sent from here.
     */
    fun patch(original: SelfExtensionDraft, version: Int): SelfExtensionPatch? {
        fun change(value: PbxTarget?, initial: PbxTarget?): Change<PbxTarget> = if (value == initial) Change.Keep else Change.of(value)

        val emailChange: Change<String> = when {
            cleanEmail == original.cleanEmail -> Change.Keep
            cleanEmail.isEmpty() -> Change.Clear
            else -> Change.Set(cleanEmail)
        }

        val patch = SelfExtensionPatch(
            version = version,
            forwardAlways = change(forwardAlways, original.forwardAlways),
            noAnswerSeconds = noAnswerSeconds.takeIf { it != original.noAnswerSeconds },
            noAnswerTarget = change(noAnswerTarget, original.noAnswerTarget),
            voicemailEnabled = voicemailEnabled.takeIf { it != original.voicemailEnabled },
            voicemailToEmail = voicemailToEmail.takeIf { it != original.voicemailToEmail },
            email = emailChange,
        )

        // A difference that only the trimming removes ("a@b.nl " against "a@b.nl") is no change.
        return patch.takeUnless { it.isEmpty }
    }

    /** The server has a newer state: every field the user did not touch follows it, every field they did touch stays as typed. */
    fun rebase(fresh: SelfExtension, baseline: SelfExtensionDraft): Pair<SelfExtensionDraft, SelfExtensionDraft> {
        val freshDraft = of(fresh)
        val rebased = SelfExtensionDraft(
            forwardAlways = if (forwardAlways == baseline.forwardAlways) freshDraft.forwardAlways else forwardAlways,
            noAnswerSeconds = if (noAnswerSeconds == baseline.noAnswerSeconds) freshDraft.noAnswerSeconds else noAnswerSeconds,
            noAnswerTarget = if (noAnswerTarget == baseline.noAnswerTarget) freshDraft.noAnswerTarget else noAnswerTarget,
            voicemailEnabled = if (voicemailEnabled == baseline.voicemailEnabled) freshDraft.voicemailEnabled else voicemailEnabled,
            voicemailToEmail = if (voicemailToEmail == baseline.voicemailToEmail) freshDraft.voicemailToEmail else voicemailToEmail,
            email = if (cleanEmail == baseline.cleanEmail) freshDraft.email else email,
        )

        return rebased to freshDraft
    }

    companion object {
        val NO_ANSWER_RANGE = 5..120

        fun of(state: SelfExtension) = SelfExtensionDraft(
            forwardAlways = state.forwardAlways,
            noAnswerSeconds = state.noAnswerSeconds,
            noAnswerTarget = state.noAnswerTarget,
            voicemailEnabled = state.voicemailEnabled,
            voicemailToEmail = state.voicemailToEmail,
            email = state.email.orEmpty(),
        )
    }
}

/** No key besides `version`. */
val SelfExtensionPatch.isEmpty: Boolean
    get() = dnd == null && forwardAlways == Change.Keep && noAnswerSeconds == null && noAnswerTarget == Change.Keep &&
        voicemailEnabled == null && voicemailToEmail == null && email == Change.Keep

/** Why the own extension did not work, in the user's terms. */
sealed interface SelfExtensionFailure {
    /** 403: this pairing may not do this (any more). */
    data object AccessDenied : SelfExtensionFailure

    /** 401: the pairing is gone. */
    data object Revoked : SelfExtensionFailure

    data object ReadOnly : SelfExtensionFailure

    data object Conflict : SelfExtensionFailure

    /** 400 / 422: something the server will not accept (a bad e-mail address, a blocked number). */
    data object Invalid : SelfExtensionFailure

    data class RateLimited(val retryAfterSeconds: Int?) : SelfExtensionFailure

    data object Offline : SelfExtensionFailure

    data object Unavailable : SelfExtensionFailure

    data object Other : SelfExtensionFailure

    companion object {
        fun classify(error: Throwable): SelfExtensionFailure = when (error) {
            is ApiException.Forbidden -> AccessDenied
            is ApiException.Unauthorized -> Revoked
            is ApiException.Conflict -> if (error.error == "read_only") ReadOnly else Conflict
            is ApiException.InvalidRequest, is ApiException.PayloadTooLarge -> Invalid
            is ApiException.RateLimited -> RateLimited(error.retryAfterSeconds)
            is ApiException.Transport -> Offline
            is ApiException.Unavailable, is ApiException.UnexpectedStatus -> Unavailable
            else -> Other
        }

        fun isStale(error: Throwable): Boolean = error is ApiException.Conflict && error.error == "stale"
    }
}

/** The result of saving the draft of "Oproepvoorkeuren". */
sealed interface SelfExtensionSaveOutcome {
    data class Saved(val state: SelfExtension?) : SelfExtensionSaveOutcome

    /** Nothing differed: nothing was sent. */
    data object Unchanged : SelfExtensionSaveOutcome

    /** Changed again while retrying: the fresh state is in the hub; what the user typed has not been sent. */
    data class Stale(val fresh: SelfExtension) : SelfExtensionSaveOutcome

    data class Failed(val failure: SelfExtensionFailure) : SelfExtensionSaveOutcome
}

class SelfExtensionHub(private val service: SelfExtensionServicing) {
    private val _states = MutableStateFlow<Map<String, SelfExtension>>(emptyMap())
    private val _saving = MutableStateFlow<Set<String>>(emptySet())
    private val _failedFor = MutableStateFlow<String?>(null)

    val states: StateFlow<Map<String, SelfExtension>> = _states.asStateFlow()
    val saving: StateFlow<Set<String>> = _saving.asStateFlow()

    /** Set when a change of "Beschikbaar" did not work; the app shows it and clears it. */
    val failedFor: StateFlow<String?> = _failedFor.asStateFlow()

    /** A 401/403 on the own extension: the pairing or the role is gone. */
    var onRevoked: (() -> Unit)? = null

    fun state(accountId: String): SelfExtension? = _states.value[accountId]

    fun clearFailure() {
        _failedFor.value = null
    }

    /** Loads the state; `false` when the server did not answer (the old state, if any, stays). */
    suspend fun load(account: StoredAccount): Boolean = try {
        put(account.id, service.load(account))
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (SelfExtensionFailure.classify(error) == SelfExtensionFailure.Revoked) onRevoked?.invoke()
        false
    }

    /**
     * Switch availability (`dnd`). Optimistic: the switch moves at once and goes back when the server says no. A `stale` answer gets
     * ONE retry on the fresh version (the patch is a single field, so there is nothing to merge).
     */
    suspend fun setAvailable(available: Boolean, account: StoredAccount) {
        val current = state(account.id) ?: return

        if (account.id in _saving.value) return

        val wanted = !available
        put(account.id, current.copy(dnd = wanted))
        _saving.value = _saving.value + account.id

        try {
            put(account.id, apply(SelfExtensionPatch(version = current.version, dnd = wanted), account))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (SelfExtensionFailure.isStale(error)) {
                retryDnd(wanted, current, account)
            } else {
                // Offline or refused: show what the server has now.
                put(account.id, current)
                _failedFor.value = account.id
                load(account)
            }
        } finally {
            _saving.value = _saving.value - account.id
        }
    }

    private suspend fun retryDnd(wanted: Boolean, previous: SelfExtension, account: StoredAccount) {
        try {
            val fresh = service.load(account)

            // Someone already switched it to what was asked: done.
            if (fresh.dnd == wanted) {
                put(account.id, fresh)
                return
            }

            put(account.id, apply(SelfExtensionPatch(version = fresh.version, dnd = wanted), account))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            put(account.id, previous)
            _failedFor.value = account.id
            load(account)
        }
    }

    /**
     * Saves what differs between [draft] and [original], with the version of the state. A `stale` answer gets ONE retry on the fresh
     * version: only the touched fields go again, so nothing the user did not touch is overwritten. When that is stale as well the
     * fresh state is shown and the draft is kept by the caller ([SelfExtensionDraft.rebase]).
     */
    suspend fun save(draft: SelfExtensionDraft, original: SelfExtensionDraft, account: StoredAccount): SelfExtensionSaveOutcome {
        val current = state(account.id)

        if (current == null || account.id in _saving.value) return SelfExtensionSaveOutcome.Failed(SelfExtensionFailure.Other)

        val first = draft.patch(original, current.version) ?: return SelfExtensionSaveOutcome.Unchanged

        _saving.value = _saving.value + account.id

        try {
            val fresh = apply(first, account)
            put(account.id, fresh)

            return SelfExtensionSaveOutcome.Saved(fresh)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return if (SelfExtensionFailure.isStale(error)) retry(draft, original, account) else failed(error)
        } finally {
            _saving.value = _saving.value - account.id
        }
    }

    private suspend fun retry(draft: SelfExtensionDraft, original: SelfExtensionDraft, account: StoredAccount): SelfExtensionSaveOutcome {
        try {
            val fresh = service.load(account)
            put(account.id, fresh)

            val patch = draft.patch(original, fresh.version) ?: return SelfExtensionSaveOutcome.Unchanged

            return try {
                val saved = apply(patch, account)
                put(account.id, saved)
                SelfExtensionSaveOutcome.Saved(saved)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!SelfExtensionFailure.isStale(error)) return failed(error)

                val latest = try {
                    service.load(account)
                } catch (_: Exception) {
                    fresh
                }
                put(account.id, latest)
                SelfExtensionSaveOutcome.Stale(latest)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return failed(error)
        }
    }

    /** The fresh state after a PATCH; when the server could not read it, ask once more (a failure of that is no failure of the save). */
    private suspend fun apply(patch: SelfExtensionPatch, account: StoredAccount): SelfExtension {
        service.patch(patch, account)?.let { return it }

        return service.load(account)
    }

    private fun failed(error: Exception): SelfExtensionSaveOutcome {
        val failure = SelfExtensionFailure.classify(error)

        if (failure == SelfExtensionFailure.Revoked || failure == SelfExtensionFailure.AccessDenied) onRevoked?.invoke()

        return SelfExtensionSaveOutcome.Failed(failure)
    }

    private fun put(accountId: String, state: SelfExtension) {
        _states.value = _states.value + (accountId to state)
    }

    fun forget(accountId: String) {
        _states.value = _states.value - accountId
    }
}

/** The numbers of the PBX an extension may call out with, and the one the user chose ("Bellen met"). Pure rules; the choice is kept per account. */
object OutboundChoice {
    /** What the user chose and the PBX still lists. */
    fun chosen(stored: String?, numbers: List<SelfNumber>): String? = stored?.takeIf { value -> numbers.any { it.number == value } }

    /** The number shown as selected: the choice, else the default of the extension, else the default flag, else none. */
    fun selected(stored: String?, state: SelfExtension?): SelfNumber? {
        val numbers = state?.numbers.orEmpty()

        chosen(stored, numbers)?.let { value -> return numbers.first { it.number == value } }

        return numbers.firstOrNull { it.number == state?.defaultNumber } ?: numbers.firstOrNull { it.isDefault }
    }

    /** The chooser is only worth opening with something to choose from. */
    fun canChoose(capabilities: AppCapabilities?, state: SelfExtension?): Boolean = capabilities?.callerChoice == true && (state?.numbers?.size ?: 0) > 1

    /** The number for the `X-FSS-From` header of the NEXT outgoing call, or `null` (no header). */
    fun headerNumber(stored: String?, capabilities: AppCapabilities?, state: SelfExtension?): String? {
        val numbers = state?.numbers.orEmpty()

        return CallerChoice.header(chosen(stored, numbers), capabilities, numbers)
    }
}
