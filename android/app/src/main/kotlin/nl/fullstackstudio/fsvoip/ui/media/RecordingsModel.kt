// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import nl.fullstackstudio.fsvoip.core.CallDirection
import nl.fullstackstudio.fsvoip.core.CallItem
import nl.fullstackstudio.fsvoip.core.CallsPage
import nl.fullstackstudio.fsvoip.core.MediaFailure
import nl.fullstackstudio.fsvoip.core.PlayerState

data class RecordingsUi(
    val page: CallsPage? = null,
    val month: String? = null,
    val isLoading: Boolean = false,
    val failure: MediaFailure? = null,
    val nowPlaying: NowPlaying? = null,
    val goneIds: Set<String> = emptySet(),
    val banner: MediaBanner? = null,
)

/** Words the list needs; the screen fills them from its resources. */
data class RecordingWords(val anonymous: String, val inbound: String, val outbound: String, val internal: String)

/**
 * The "Opnames" screen of ONE paired account (an admin only; the whole screen sits behind the local check).
 * The calls of one month come from `GET /calls?month=`; the ones with `hasRecording` can be played.
 */
class RecordingsModel(val accountId: String, private val hub: MediaHub) {
    private val _state = MutableStateFlow(RecordingsUi())
    val state: StateFlow<RecordingsUi> = _state.asStateFlow()

    private val ui: RecordingsUi get() = _state.value
    private var loadGeneration = 0

    init {
        hub.player.itemObservers += { id -> if (id == null) update { copy(nowPlaying = null) } }
        hub.player.stateObservers += { state -> playerStateChanged(state) }
    }

    private fun update(change: RecordingsUi.() -> RecordingsUi) {
        _state.update { it.change() }
    }

    // MARK: Reading

    /** The calls that have (or had) a recording, newest first. */
    val rows: List<CallItem>
        get() = (ui.page?.calls ?: emptyList()).filter { it.hasRecording || it.recordingExpired }.sortedByDescending { it.startedSort ?: "" }

    /** The months to choose from; the shown month is always in the list. */
    val months: List<String>
        get() {
            val list = (ui.page?.months ?: emptyList()).toMutableList()
            val current = ui.page?.month

            if (current != null && current !in list) list.add(0, current)

            return list
        }

    val monthTitle: String get() = (ui.page?.month ?: ui.month)?.let { MediaFormat.month(it) } ?: ""

    val isTruncated: Boolean get() = ui.page?.truncated == true

    val nowPlayingId: String? get() = ui.nowPlaying?.id

    fun isGone(call: CallItem): Boolean = !call.hasRecording || call.id in ui.goneIds

    fun title(call: CallItem, words: RecordingWords): String {
        call.number?.takeIf { it.isNotEmpty() }?.let { return hub.nameLookup(it) ?: it }
        call.extensionName?.takeIf { it.isNotEmpty() }?.let { return it }

        return words.anonymous
    }

    fun subtitle(call: CallItem, words: RecordingWords): String {
        val parts = mutableListOf(call.startedLabel)

        if (call.durationLabel.isNotEmpty()) parts += call.durationLabel

        when (call.direction) {
            CallDirection.INBOUND -> parts += words.inbound
            CallDirection.OUTBOUND -> parts += words.outbound
            CallDirection.INTERNAL -> parts += words.internal
            CallDirection.UNKNOWN -> Unit
        }

        call.extensionName?.takeIf { it.isNotEmpty() && call.number != null }?.let { parts += it }

        return parts.filter { it.isNotEmpty() }.joinToString(" · ")
    }

    // MARK: Loading

    suspend fun load(month: String? = null) {
        val wanted = month ?: ui.month
        loadGeneration++
        val token = loadGeneration
        update { copy(isLoading = page == null || month != null) }

        try {
            val result = hub.service.calls(accountId, wanted)

            if (token != loadGeneration) return

            update { copy(page = result, month = result.month, failure = null) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (token != loadGeneration) return

            handle(error)
        }

        update { copy(isLoading = false) }
    }

    suspend fun select(month: String) {
        if (month == ui.page?.month) return

        hub.player.stop()
        hub.gate.touch()
        load(month)
    }

    // MARK: Playing

    fun play(call: CallItem, words: RecordingWords) {
        update { copy(banner = null) }

        if (!call.hasRecording || call.id in ui.goneIds) return

        hub.gate.touch()

        try {
            val accountId = accountId
            val source = hub.service.recordingSource(accountId, call.id)
            update { copy(nowPlaying = NowPlaying(call.id, title(call, words), subtitle(call, words))) }
            hub.player.play(call.id, source) { request -> hub.service.download(accountId, request) }
        } catch (error: Exception) {
            handle(error)
        }
    }

    fun retryPlaying(words: RecordingWords) {
        val id = ui.nowPlaying?.id ?: return
        val call = ui.page?.calls?.firstOrNull { it.id == id } ?: return

        play(call, words)
    }

    fun closePlayer() {
        hub.player.stop()
    }

    private fun playerStateChanged(state: PlayerState) {
        val failed = state as? PlayerState.Failed ?: return
        val id = ui.nowPlaying?.id ?: return

        when (failed.failure) {
            MediaFailure.Gone, MediaFailure.NotFound -> update { copy(goneIds = goneIds + id) }
            MediaFailure.AccessDenied -> hub.partDenied(MediaPart.RECORDINGS, accountId)
            MediaFailure.Revoked -> hub.onRevoked?.invoke()
            else -> Unit
        }
    }

    private fun handle(error: Exception) {
        val result = MediaFailure.classify(error)

        when (result) {
            MediaFailure.AccessDenied -> hub.partDenied(MediaPart.RECORDINGS, accountId)
            MediaFailure.Revoked -> hub.onRevoked?.invoke()
            else -> Unit
        }

        if (ui.page != null && result != MediaFailure.AccessDenied && result != MediaFailure.Revoked) {
            update { copy(banner = MediaBanner(MediaMessage.Failure(result, MediaKind.RECORDING))) }
        } else {
            update { copy(failure = result) }
        }
    }

    val page: CallsPage? get() = ui.page
    val failure: MediaFailure? get() = ui.failure
    val banner: MediaBanner? get() = ui.banner
    val nowPlaying: NowPlaying? get() = ui.nowPlaying
}
