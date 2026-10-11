// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The own extension per paired account: the state the "Profiel" and "Oproepvoorkeuren" pages edit, "Beschikbaar" (do not disturb)
// and the invitation of a colleague. The Android port of the iOS `SelfExtensionHub`, `AvailabilityHub` and `InviteModel`.

package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What "Profiel", "Oproepvoorkeuren" and "Gebruiker uitnodigen" need from the API for the own extension. Every method throws [ApiException]. */
interface SelfExtensionServicing {
    suspend fun load(accountId: String): SelfExtension

    /** `PATCH /me/extension`: the fresh state, or null when the server could not read it right now. */
    suspend fun patch(patch: SelfExtensionPatch, accountId: String): SelfExtension?
}

interface InviteServicing {
    /** `POST /pbx/devices/{id}/app-pairing`. */
    suspend fun invite(deviceId: String, accountId: String): AppPairingResponse

    /** The extensions of the PBX (id, name, number). */
    suspend fun devices(accountId: String): List<InviteDevice>
}

/** The live implementation: [clientFor] is the authenticated client of an account (null when the account is gone). */
class LiveSelfExtensionService(private val clientFor: (String) -> FsVoipApiClient?) : SelfExtensionServicing, InviteServicing, AvailabilityServicing {
    private fun client(accountId: String) = clientFor(accountId) ?: throw ApiException.MissingDeviceToken()

    override suspend fun load(accountId: String): SelfExtension = client(accountId).selfExtension()

    override suspend fun patch(patch: SelfExtensionPatch, accountId: String): SelfExtension? = client(accountId).updateSelfExtension(patch).extensionState

    override suspend fun invite(deviceId: String, accountId: String): AppPairingResponse = client(accountId).createAppPairing(deviceId)

    override suspend fun devices(accountId: String): List<InviteDevice> = client(accountId).inviteDevices().devices

    override suspend fun loadAvailability(accountId: String): AvailabilityHub.State {
        val state = client(accountId).selfExtension()

        return AvailabilityHub.State(state.dnd, state.version)
    }

    override suspend fun setDoNotDisturb(dnd: Boolean, version: Int, accountId: String): AvailabilityHub.State {
        val client = client(accountId)
        val fresh = client.updateSelfExtension(SelfExtensionPatch(version = version, dnd = dnd)).extensionState
            // The server could not read the fresh state: ask once more.
            ?: client.selfExtension()

        return AvailabilityHub.State(fresh.dnd, fresh.version)
    }
}

// MARK: - The form

/** What the two pages show of the own extension, as one value, and the translation to a PATCH body that holds only what changed. */
data class SelfExtensionDraft(
    val forwardAlways: PbxTarget? = null,
    val noAnswerSeconds: Int = 25,
    val noAnswerTarget: PbxTarget? = null,
    val voicemailEnabled: Boolean = false,
    val voicemailToEmail: Boolean = false,
    val email: String = "",
) {
    constructor(state: SelfExtension) : this(
        forwardAlways = state.forwardAlways,
        noAnswerSeconds = state.noAnswerSeconds,
        noAnswerTarget = state.noAnswerTarget,
        voicemailEnabled = state.voicemailEnabled,
        voicemailToEmail = state.voicemailToEmail,
        email = state.email ?: "",
    )

    val cleanEmail: String get() = email.trim()

    /**
     * Only the keys that differ from [original], with [version]. Null = nothing changed. The number to call out with is not a setting
     * and "dnd" belongs to the switch on the avatar, so neither is ever sent from here.
     */
    fun patch(original: SelfExtensionDraft, version: Int): SelfExtensionPatch? {
        if (this == original) return null

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

    /** The result of [rebased]: the draft that follows the server where the user did not touch it, and the new baseline. */
    data class Rebase(val draft: SelfExtensionDraft, val baseline: SelfExtensionDraft)

    /** The server has a newer state: every field the user did not touch follows it, every field they did touch stays as typed. */
    fun rebased(fresh: SelfExtension, baseline: SelfExtensionDraft): Rebase {
        val freshDraft = SelfExtensionDraft(fresh)

        return Rebase(
            draft = copy(
                forwardAlways = if (forwardAlways == baseline.forwardAlways) freshDraft.forwardAlways else forwardAlways,
                noAnswerSeconds = if (noAnswerSeconds == baseline.noAnswerSeconds) freshDraft.noAnswerSeconds else noAnswerSeconds,
                noAnswerTarget = if (noAnswerTarget == baseline.noAnswerTarget) freshDraft.noAnswerTarget else noAnswerTarget,
                voicemailEnabled = if (voicemailEnabled == baseline.voicemailEnabled) freshDraft.voicemailEnabled else voicemailEnabled,
                voicemailToEmail = if (voicemailToEmail == baseline.voicemailToEmail) freshDraft.voicemailToEmail else voicemailToEmail,
                email = if (cleanEmail == baseline.cleanEmail) freshDraft.email else email,
            ),
            baseline = freshDraft,
        )
    }

    companion object {
        val noAnswerRange: IntRange = 5..120
    }
}

// MARK: - Failures

/** Why the own extension or an invitation did not work, in the user's terms. */
sealed interface SelfExtensionFailure {
    /** 403: this pairing may not do this (any more). */
    data object AccessDenied : SelfExtensionFailure

    /** 401: the pairing is gone. */
    data object Revoked : SelfExtensionFailure

    data object ReadOnly : SelfExtensionFailure

    /** 409 `conflict` on an invitation: the own extension cannot be invited. */
    data object OwnDevice : SelfExtensionFailure

    data object Conflict : SelfExtensionFailure

    /** 400 / 422: something the server will not accept (a bad e-mail address, a blocked number). */
    data object Invalid : SelfExtensionFailure

    data class RateLimited(val retryAfterSeconds: Int?) : SelfExtensionFailure

    data object Offline : SelfExtensionFailure

    data object Unavailable : SelfExtensionFailure

    data object Other : SelfExtensionFailure

    companion object {
        /** [forInvite]: a 409 on the invitation call means "you cannot invite your own extension"; anywhere else it is a generic conflict. */
        fun classify(error: Throwable, forInvite: Boolean = false): SelfExtensionFailure = when (error) {
            is ApiException.Forbidden -> AccessDenied
            is ApiException.Unauthorized -> Revoked
            is ApiException.ReadOnly -> ReadOnly
            is ApiException.Conflict -> when {
                error.code == "read_only" -> ReadOnly
                forInvite -> OwnDevice
                else -> Conflict
            }
            is ApiException.Stale, is ApiException.StaleChain -> Conflict
            is ApiException.Invalid, is ApiException.InvalidRequest, is ApiException.BlockedDestination, is ApiException.PayloadTooLarge -> Invalid
            is ApiException.RateLimited -> RateLimited(error.retryAfterSeconds)
            is ApiException.Transport -> Offline
            is ApiException.Unavailable, is ApiException.UnexpectedStatus -> Unavailable
            else -> Other
        }
    }
}

// MARK: - The hub

class SelfExtensionHub(private val service: SelfExtensionServicing, private val invites: InviteServicing) {
    sealed interface SaveOutcome {
        data class Saved(val state: SelfExtension?) : SaveOutcome

        /** Nothing differed: nothing was sent. */
        data object Unchanged : SaveOutcome

        /** Changed again while retrying: the fresh state is in [states]; what the user typed has not been sent. */
        data class Stale(val state: SelfExtension) : SaveOutcome

        data class Failed(val failure: SelfExtensionFailure) : SaveOutcome
    }

    sealed interface InviteOutcome {
        data class Created(val response: AppPairingResponse) : InviteOutcome

        data class Failed(val failure: SelfExtensionFailure) : InviteOutcome
    }

    private val _states = MutableStateFlow<Map<String, SelfExtension>>(emptyMap())
    private val _saving = MutableStateFlow<Set<String>>(emptySet())

    val states: StateFlow<Map<String, SelfExtension>> = _states.asStateFlow()
    val saving: StateFlow<Set<String>> = _saving.asStateFlow()

    /** Set when the role/pairing was taken away (401), so the app can react. */
    var onRevoked: (() -> Unit)? = null

    fun state(accountId: String): SelfExtension? = _states.value[accountId]

    private fun put(accountId: String, state: SelfExtension) {
        _states.value = _states.value + (accountId to state)
    }

    /** Loads the state; false when the server did not answer (the old state, if any, stays). */
    suspend fun load(accountId: String): Boolean = try {
        put(accountId, service.load(accountId))
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (SelfExtensionFailure.classify(error) == SelfExtensionFailure.Revoked) onRevoked?.invoke()
        false
    }

    /**
     * Saves what differs between [draft] and [original], with the version of the state. A `stale` answer gets ONE retry on the fresh
     * version: only the touched fields go again, so nothing the user did not touch is overwritten. When that is stale as well the fresh
     * state is shown and the draft is kept by the caller (see [SelfExtensionDraft.rebased]).
     */
    suspend fun save(draft: SelfExtensionDraft, original: SelfExtensionDraft, accountId: String): SaveOutcome {
        val current = state(accountId)

        if (current == null || accountId in _saving.value) return SaveOutcome.Failed(SelfExtensionFailure.Other)

        val first = draft.patch(original, current.version) ?: return SaveOutcome.Unchanged

        _saving.value = _saving.value + accountId

        try {
            return apply(first, accountId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiException.Stale) {
            return retry(draft, original, accountId)
        } catch (error: Exception) {
            val failure = SelfExtensionFailure.classify(error)
            if (failure == SelfExtensionFailure.Revoked) onRevoked?.invoke()

            return SaveOutcome.Failed(failure)
        } finally {
            _saving.value = _saving.value - accountId
        }
    }

    private suspend fun retry(draft: SelfExtensionDraft, original: SelfExtensionDraft, accountId: String): SaveOutcome {
        try {
            val fresh = service.load(accountId)
            put(accountId, fresh)

            val patch = draft.patch(original, fresh.version) ?: return SaveOutcome.Unchanged

            return try {
                apply(patch, accountId)
            } catch (error: ApiException.Stale) {
                val newest = try {
                    service.load(accountId)
                } catch (inner: CancellationException) {
                    throw inner
                } catch (inner: Exception) {
                    fresh
                }
                put(accountId, newest)

                SaveOutcome.Stale(newest)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = SelfExtensionFailure.classify(error)
            if (failure == SelfExtensionFailure.Revoked) onRevoked?.invoke()

            return SaveOutcome.Failed(failure)
        }
    }

    private suspend fun apply(patch: SelfExtensionPatch, accountId: String): SaveOutcome {
        service.patch(patch, accountId)?.let { fresh ->
            put(accountId, fresh)
            return SaveOutcome.Saved(fresh)
        }

        // The server could not read the fresh state: ask once more.
        val fresh = try {
            service.load(accountId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
        fresh?.let { put(accountId, it) }

        return SaveOutcome.Saved(fresh)
    }

    /** An invitation link for a colleague's extension. The link is a credential: it is returned to the caller and kept nowhere. */
    suspend fun invite(deviceId: String, accountId: String): InviteOutcome = try {
        InviteOutcome.Created(invites.invite(deviceId, accountId))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val failure = SelfExtensionFailure.classify(error, forInvite = true)
        if (failure == SelfExtensionFailure.Revoked) onRevoked?.invoke()

        InviteOutcome.Failed(failure)
    }

    suspend fun devices(accountId: String): List<InviteDevice>? = try {
        invites.devices(accountId)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }

    fun forget(accountId: String) {
        _states.value = _states.value - accountId
    }
}

// MARK: - Beschikbaar

/** "Beschikbaar": do-not-disturb of the own extension (`GET`/`PATCH /me/extension`, every role). */
interface AvailabilityServicing {
    suspend fun loadAvailability(accountId: String): AvailabilityHub.State

    /** Returns the new state (the `version` moves on). */
    suspend fun setDoNotDisturb(dnd: Boolean, version: Int, accountId: String): AvailabilityHub.State
}

/** The state of "Beschikbaar" per account, shown as the dot on the avatar and as the switch in the settings. */
class AvailabilityHub(private val service: AvailabilityServicing) {
    data class State(val doNotDisturb: Boolean, val version: Int)

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    private val _saving = MutableStateFlow<Set<String>>(emptySet())
    private val _failedFor = MutableStateFlow<String?>(null)

    val states: StateFlow<Map<String, State>> = _states.asStateFlow()
    val saving: StateFlow<Set<String>> = _saving.asStateFlow()

    /** Set when a change did not work; the app shows it and clears it ([clearFailure]). */
    val failedFor: StateFlow<String?> = _failedFor.asStateFlow()

    fun state(accountId: String): State? = _states.value[accountId]

    /** Null = unknown (not loaded). */
    fun isAvailable(accountId: String): Boolean? = state(accountId)?.let { !it.doNotDisturb }

    fun clearFailure() {
        _failedFor.value = null
    }

    private fun put(accountId: String, state: State) {
        _states.value = _states.value + (accountId to state)
    }

    suspend fun load(accountId: String) {
        val state = try {
            service.loadAvailability(accountId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return
        }

        put(accountId, state)
    }

    /** Switch availability. Optimistic: the switch moves at once and goes back when the server says no. */
    suspend fun setAvailable(available: Boolean, accountId: String) {
        val current = state(accountId)

        if (current == null || accountId in _saving.value) return

        put(accountId, State(doNotDisturb = !available, version = current.version))
        _saving.value = _saving.value + accountId
        val wanted = !available

        try {
            put(accountId, service.setDoNotDisturb(wanted, current.version, accountId))
        } catch (error: CancellationException) {
            put(accountId, current)
            throw error
        } catch (error: ApiException.Stale) {
            // Changed somewhere else in the meantime (the portal, another phone): ONE retry on the fresh version. The patch is a
            // single field, so there is nothing to merge.
            retryOnFreshVersion(wanted, current, accountId)
        } catch (error: Exception) {
            // Offline or refused: show what the server has now.
            put(accountId, current)
            _failedFor.value = accountId
            load(accountId)
        } finally {
            _saving.value = _saving.value - accountId
        }
    }

    private suspend fun retryOnFreshVersion(wanted: Boolean, previous: State, accountId: String) {
        try {
            val fresh = service.loadAvailability(accountId)

            // Someone already switched it to what was asked: done.
            if (fresh.doNotDisturb == wanted) {
                put(accountId, fresh)
                return
            }

            put(accountId, service.setDoNotDisturb(wanted, fresh.version, accountId))
        } catch (error: CancellationException) {
            put(accountId, previous)
            throw error
        } catch (error: Exception) {
            put(accountId, previous)
            _failedFor.value = accountId
            load(accountId)
        }
    }

    fun forget(accountId: String) {
        _states.value = _states.value - accountId
    }
}

/** The colour of the dot on the avatar. */
object AvailabilityDot {
    enum class Kind { AVAILABLE, DO_NOT_DISTURB, CONNECTING, OFFLINE }

    fun kind(registered: Boolean, connecting: Boolean, doNotDisturb: Boolean): Kind = when {
        doNotDisturb -> Kind.DO_NOT_DISTURB
        registered -> Kind.AVAILABLE
        connecting -> Kind.CONNECTING
        else -> Kind.OFFLINE
    }
}

// MARK: - Gebruiker uitnodigen

/**
 * The state of "Gebruiker uitnodigen": pick a colleague's extension, ask for a link, show it once until it expires.
 *
 * 🚨 The link is a credential (it pairs a phone with the extension). It lives only in [phase] while the page is open: never in a log, a
 * preference or a file. [reset] drops it.
 */
class InviteModel(
    private val hub: SelfExtensionHub,
    private val accountId: String,
    private val ownExtensionNumber: String?,
    private val now: () -> Instant = Instant::now,
) {
    data class Invitation(val deviceId: String, val deviceName: String, val url: Secret, val expiresAt: Instant)

    sealed interface Phase {
        data object Choosing : Phase

        data object Creating : Phase

        data class Shown(val invitation: Invitation) : Phase

        data class Failed(val failure: SelfExtensionFailure) : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Choosing)
    private val _selectedId = MutableStateFlow<String?>(null)

    val phase: StateFlow<Phase> = _phase.asStateFlow()
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    fun select(id: String?) {
        _selectedId.value = id
    }

    /** The extensions that can be invited: all except the one of this very app (the server refuses it: it would replace this app). */
    fun candidates(devices: List<InviteDevice>): List<InviteDevice> {
        val ownId = hub.state(accountId)?.id

        return devices.filter { device ->
            when {
                ownId != null -> device.id != ownId
                else -> ownExtensionNumber == null || device.extensionNumber != ownExtensionNumber
            }
        }
    }

    suspend fun create(deviceName: (String) -> String) {
        val id = _selectedId.value ?: return

        if (_phase.value == Phase.Creating) return

        _phase.value = Phase.Creating

        _phase.value = when (val outcome = hub.invite(id, accountId)) {
            is SelfExtensionHub.InviteOutcome.Created -> Phase.Shown(Invitation(id, deviceName(id), outcome.response.url, outcome.response.expiresAt))
            is SelfExtensionHub.InviteOutcome.Failed -> Phase.Failed(outcome.failure)
        }
    }

    /** Back to the list; the link is gone. */
    fun reset() {
        _phase.value = Phase.Choosing
    }

    /** Seconds left, never negative. */
    fun remaining(invitation: Invitation, at: Instant? = null): Double =
        maxOf(0.0, (invitation.expiresAt.toEpochMilli() - (at ?: now()).toEpochMilli()) / 1000.0)

    fun isExpired(invitation: Invitation, at: Instant? = null): Boolean = remaining(invitation, at) <= 0

    companion object {
        /** `9:42`, never negative. */
        fun countdown(seconds: Double): String {
            val total = maxOf(0, kotlin.math.ceil(seconds).toInt())

            return "%d:%02d".format(total / 60, total % 60)
        }
    }
}
