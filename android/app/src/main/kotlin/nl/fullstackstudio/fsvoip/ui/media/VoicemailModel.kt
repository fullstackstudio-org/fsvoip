// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import nl.fullstackstudio.fsvoip.core.MediaFailure
import nl.fullstackstudio.fsvoip.core.PlayerState
import nl.fullstackstudio.fsvoip.core.VoicemailAccess
import nl.fullstackstudio.fsvoip.core.VoicemailBox
import nl.fullstackstudio.fsvoip.core.VoicemailMessage
import nl.fullstackstudio.fsvoip.core.VoicemailPage

/** The state the "Voicemail" screen draws. Read the derived values on [VoicemailModel] after every change of this. */
data class VoicemailUi(
    val page: VoicemailPage? = null,
    val scope: VoicemailModel.Scope = VoicemailModel.Scope.Own,
    val isLoading: Boolean = false,
    val isDeleting: Boolean = false,
    val failure: MediaFailure? = null,
    val othersUnlocked: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    /** Messages the server said are past their retention. */
    val goneIds: Set<String> = emptySet(),
    val noPasscode: Boolean = false,
    val banner: MediaBanner? = null,
    /** A delete came back `read_only`: the phone system is locked. */
    val serverReadOnly: Boolean = false,
)

/**
 * The "Voicemail" screen of ONE paired account.
 *
 * - A `user` pairing (`access.voicemail == OWN`) sees its own box only; nothing here asks for the screen lock.
 * - An admin starts on the own box (found by the extension number). "Alle voicemail" and the box of a colleague are open only
 *   after the local check ([MediaAccessGate], five minutes); when it expires, the screen falls back to the own box and stops the
 *   sound of a colleague's message.
 * - Deleting is possible in the own box (everyone) and, after the check, in the others (admin); a frozen phone system blocks it.
 */
class VoicemailModel(
    val accountId: String,
    /** The extension number of this pairing: it tells which box is the own one. */
    private val extensionNumber: String?,
    private val hub: MediaHub,
) {
    sealed interface Scope {
        data object Own : Scope

        data object All : Scope

        data class Box(val id: String) : Scope
    }

    private val _state = MutableStateFlow(VoicemailUi())
    val state: StateFlow<VoicemailUi> = _state.asStateFlow()

    private val ui: VoicemailUi get() = _state.value
    private val service get() = hub.service
    private val gate get() = hub.gate
    private val player get() = hub.player

    private val itemObserver: (String?) -> Unit = { id -> if (id == null) update { copy(nowPlaying = null) } }
    private val stateObserver: (PlayerState) -> Unit = { state -> playerStateChanged(state) }

    init {
        player.itemObservers += itemObserver
        player.stateObservers += stateObserver
    }

    /** The screen is gone: stop listening to the shared player. */
    fun close() {
        player.itemObservers -= itemObserver
        player.stateObservers -= stateObserver
    }

    private fun update(change: VoicemailUi.() -> VoicemailUi) {
        _state.update { it.change() }
    }

    // MARK: Rights

    val access: MediaHub.Access
        get() = hub.access(accountId) ?: MediaHub.Access(VoicemailAccess.NONE, recordings = false, readOnly = true)

    /** An admin sees every box and can choose. */
    val canChooseBox: Boolean get() = access.voicemail == VoicemailAccess.ALL

    val isReadOnly: Boolean get() = access.readOnly || ui.serverReadOnly

    // MARK: Reading

    val scope: Scope get() = ui.scope

    val boxes: List<VoicemailBox> get() = ui.page?.boxes ?: emptyList()

    /** The box of the extension of this phone (an admin's page lists every box; the number tells which one is ours). */
    val ownBoxId: String?
        get() {
            val page = ui.page ?: return null

            if (access.voicemail != VoicemailAccess.ALL) return page.boxes.firstOrNull()?.id

            val number = extensionNumber ?: return null

            return page.boxes.firstOrNull { !it.shared && it.number == number }?.id
        }

    fun isOwn(message: VoicemailMessage): Boolean = access.voicemail != VoicemailAccess.ALL || message.boxId == ownBoxId

    /** The messages the screen may show right now (never a colleague's while locked). */
    val messages: List<VoicemailMessage>
        get() {
            val page = ui.page ?: return emptyList()
            val sorted = page.messages.sortedByDescending { it.receivedSort ?: "" }

            if (access.voicemail != VoicemailAccess.ALL) return sorted

            return when (val scope = ui.scope) {
                Scope.Own -> sorted.filter(::isOwn)
                Scope.All -> if (ui.othersUnlocked) sorted else emptyList()
                is Scope.Box -> if (ui.othersUnlocked) sorted.filter { it.boxId == scope.id } else emptyList()
            }
        }

    /** The admin asked for more than the own box and has not unlocked (yet): show the lock instead of a list. */
    val needsUnlock: Boolean get() = canChooseBox && ui.scope != Scope.Own && !ui.othersUnlocked

    fun boxTitle(box: VoicemailBox): String = listOfNotNull(box.name, box.number).filter { it.isNotEmpty() }.joinToString(" · ")

    /** The page says the phone system did not answer: a notice, not an empty list. */
    val isUnavailable: Boolean get() = ui.page?.available == false

    /** [anonymous] is the word for a caller without a number. */
    fun title(message: VoicemailMessage, anonymous: String): String {
        message.callerName?.takeIf { it.isNotEmpty() }?.let { return it }
        message.caller?.takeIf { it.isNotEmpty() }?.let { return hub.nameLookup(it) ?: it }

        return anonymous
    }

    fun subtitle(message: VoicemailMessage): String {
        val parts = mutableListOf(message.receivedLabel)

        if (message.durationLabel.isNotEmpty()) parts += message.durationLabel

        if (canChooseBox && (!isOwn(message) || ui.scope != Scope.Own)) parts += message.boxName

        return parts.filter { it.isNotEmpty() }.joinToString(" · ")
    }

    fun isGone(message: VoicemailMessage): Boolean = message.id in ui.goneIds || (message.daysLeft ?: 0) < 0

    val nowPlayingId: String? get() = ui.nowPlaying?.id

    // MARK: Loading

    suspend fun load() {
        update { copy(isLoading = page == null) }

        try {
            val result = service.voicemail(accountId, null)
            update { copy(page = result, failure = null, serverReadOnly = false) }

            // An admin without a box of the own extension has only the locked "all" to show.
            if (canChooseBox && ownBoxId == null && ui.scope == Scope.Own) update { copy(scope = Scope.All) }

            dropVanishedSelection()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            handle(error)
        }

        update { copy(isLoading = false) }
    }

    suspend fun reload() {
        refreshLock()
        load()
    }

    private fun dropVanishedSelection() {
        val scope = ui.scope

        if (scope is Scope.Box && boxes.none { it.id == scope.id }) {
            update { copy(scope = if (ownBoxId != null) Scope.Own else Scope.All) }
        }
    }

    // MARK: The local check

    /** Choose another box; anything but the own box asks for the screen lock first. */
    suspend fun select(newScope: Scope) {
        update { copy(banner = null) }

        if (newScope == Scope.Own) {
            update { copy(scope = Scope.Own) }

            return
        }

        if (!canChooseBox) return

        when (gate.ensureUnlocked(GateReason.VOICEMAIL)) {
            GateResult.UNLOCKED -> update { copy(othersUnlocked = true, noPasscode = false, scope = newScope) }
            GateResult.CANCELLED, GateResult.FAILED -> Unit
            GateResult.UNAVAILABLE -> update { copy(noPasscode = true) }
        }
    }

    /** The lock card asks to open what is chosen. */
    suspend fun unlock() {
        select(if (ui.scope == Scope.Own) Scope.All else ui.scope)
    }

    /** The five minutes are over (or the app came back): fall back to the own box and stop a colleague's message. */
    fun refreshLock() {
        if (!ui.othersUnlocked || gate.isUnlocked) return

        update { copy(othersUnlocked = false) }

        if (ui.scope != Scope.Own) update { copy(scope = if (ownBoxId != null) Scope.Own else Scope.All) }

        val id = ui.nowPlaying?.id ?: return
        val message = ui.page?.messages?.firstOrNull { it.id == id } ?: return

        if (!isOwn(message)) player.stop()
    }

    // MARK: Playing

    fun play(message: VoicemailMessage, anonymous: String = "") {
        update { copy(banner = null) }

        if (messages.none { it.id == message.id } || isGone(message)) return

        if (!isOwn(message)) gate.touch()

        try {
            val source = service.voicemailSource(accountId, message.boxId, message.ref)
            val accountId = accountId
            update { copy(nowPlaying = NowPlaying(message.id, title(message, anonymous), subtitle(message))) }
            player.play(message.id, source) { request -> service.download(accountId, request) }
        } catch (error: Exception) {
            handle(error)
        }
    }

    fun retryPlaying(anonymous: String = "") {
        val id = ui.nowPlaying?.id ?: return
        val message = ui.page?.messages?.firstOrNull { it.id == id } ?: return

        play(message, anonymous)
    }

    fun closePlayer() {
        player.stop()
    }

    private fun playerStateChanged(state: PlayerState) {
        val failed = state as? PlayerState.Failed ?: return
        val id = ui.nowPlaying?.id ?: return

        when (failed.failure) {
            MediaFailure.Gone, MediaFailure.NotFound -> update { copy(goneIds = goneIds + id) }
            MediaFailure.AccessDenied -> hub.partDenied(MediaPart.VOICEMAIL, accountId)
            MediaFailure.Revoked -> hub.onRevoked?.invoke()
            else -> Unit
        }
    }

    // MARK: Deleting

    suspend fun delete(message: VoicemailMessage) {
        update { copy(banner = null) }

        if (isReadOnly) {
            update { copy(banner = MediaBanner(MediaMessage.DeleteReadOnly)) }

            return
        }

        if (!isOwn(message)) {
            // A colleague's message: the check has to be open now.
            if (!gate.isUnlocked) {
                refreshLock()
                select(Scope.All)

                return
            }

            gate.touch()
        }

        if (ui.nowPlaying?.id == message.id) player.stop()

        update { copy(isDeleting = true) }

        try {
            service.deleteVoicemail(accountId, message.boxId, message.ref)
            remove(message)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            when (MediaFailure.classify(error)) {
                // Gone already (deleted on another phone): the list was out of date.
                MediaFailure.NotFound -> remove(message)
                MediaFailure.ReadOnly -> update { copy(serverReadOnly = true, banner = MediaBanner(MediaMessage.DeleteReadOnly)) }
                else -> handle(error)
            }
        } finally {
            update { copy(isDeleting = false) }
        }
    }

    private fun remove(message: VoicemailMessage) {
        update { copy(page = page?.copy(messages = page.messages.filter { it.id != message.id })) }
    }

    // MARK: Failures

    private fun handle(error: Exception) {
        val result = MediaFailure.classify(error)

        when (result) {
            MediaFailure.AccessDenied -> hub.partDenied(MediaPart.VOICEMAIL, accountId)
            MediaFailure.Revoked -> hub.onRevoked?.invoke()
            else -> Unit
        }

        if (ui.page != null && result != MediaFailure.AccessDenied && result != MediaFailure.Revoked) {
            // The list on screen is still good: say what went wrong without taking it away.
            update { copy(banner = MediaBanner(MediaMessage.Failure(result, MediaKind.VOICEMAIL))) }
        } else {
            update { copy(failure = result) }
        }
    }

    val noPasscode: Boolean get() = ui.noPasscode
    val failure: MediaFailure? get() = ui.failure
    val page: VoicemailPage? get() = ui.page
    val banner: MediaBanner? get() = ui.banner
    val nowPlaying: NowPlaying? get() = ui.nowPlaying
}
