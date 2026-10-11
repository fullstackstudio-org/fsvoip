// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.media

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import nl.fullstackstudio.fsvoip.core.AudioSource
import nl.fullstackstudio.fsvoip.core.CallDirection
import nl.fullstackstudio.fsvoip.core.CallItem
import nl.fullstackstudio.fsvoip.core.CallOutcome
import nl.fullstackstudio.fsvoip.core.CallsPage
import nl.fullstackstudio.fsvoip.core.FsJson
import nl.fullstackstudio.fsvoip.core.MediaFailure
import nl.fullstackstudio.fsvoip.core.PlayerState
import nl.fullstackstudio.fsvoip.core.RecentCall

/** One line of "Recent": a call of the PBX (the team history) or, while the server cannot say, one of this phone. */
data class HistoryEntry(
    val id: String,
    val accountId: String,
    val kind: Kind,
    /** The other party's number; `null` = anonymous. */
    val number: String?,
    /** A name that came with the call itself (the phone's own record). */
    val knownName: String?,
    /** Incoming and nobody picked up (a voicemail counts: the caller did not reach anyone). */
    val isMissed: Boolean,
    val startedAt: Instant?,
    val whenLabel: String,
    val durationLabel: String,
    /** Who took (or made) the call: shown as the initials in the row. */
    val extensionName: String?,
    /** Our number that was called or used. */
    val ourNumber: String?,
    val countryName: String?,
    val hasRecording: Boolean,
    val recordingExpired: Boolean,
    /** Came from this phone, not from the PBX. */
    val isLocal: Boolean,
) {
    enum class Kind { INCOMING, OUTGOING, INTERNAL }

    /** The filter of the screen. */
    enum class Filter { ALL, MISSED }

    companion object {
        fun fromItem(item: CallItem, accountId: String): HistoryEntry = HistoryEntry(
            id = "$accountId/${item.id}",
            accountId = accountId,
            kind = when (item.direction) {
                CallDirection.OUTBOUND -> Kind.OUTGOING
                CallDirection.INTERNAL -> Kind.INTERNAL
                CallDirection.INBOUND, CallDirection.UNKNOWN -> Kind.INCOMING
            },
            number = item.number?.takeIf { it.isNotEmpty() },
            knownName = null,
            isMissed = item.direction == CallDirection.INBOUND && (item.outcome == CallOutcome.MISSED || item.outcome == CallOutcome.VOICEMAIL),
            startedAt = item.startedSort?.let { FsJson.parseTimestamp(it) },
            whenLabel = item.startedLabel,
            durationLabel = if (item.outcome == CallOutcome.ANSWERED) item.durationLabel else "",
            extensionName = item.extensionName,
            ourNumber = item.ourNumber,
            countryName = item.countryName,
            hasRecording = item.hasRecording,
            recordingExpired = item.recordingExpired,
            isLocal = false,
        )

        fun fromRecent(call: RecentCall, extensionName: String?, whenLabel: String): HistoryEntry = HistoryEntry(
            id = "local/${call.id}",
            accountId = call.accountId,
            kind = if (call.direction == RecentCall.Direction.INCOMING) Kind.INCOMING else Kind.OUTGOING,
            number = call.number.takeIf { it.isNotEmpty() },
            knownName = call.name,
            isMissed = call.direction == RecentCall.Direction.INCOMING && call.outcome == RecentCall.Outcome.MISSED,
            startedAt = Instant.ofEpochMilli(call.startedAtMillis),
            whenLabel = whenLabel,
            durationLabel = if (call.outcome == RecentCall.Outcome.ANSWERED) MediaFormat.duration(call.durationSeconds) else "",
            extensionName = extensionName,
            ourNumber = null,
            countryName = null,
            hasRecording = false,
            recordingExpired = false,
            isLocal = true,
        )
    }
}

/**
 * The calls of the PBX per account, newest first, a month at a time; and the recording that plays from a detail sheet.
 *
 * What a pairing sees is decided by the server (`capabilities.calls`: an admin everything, a `user` the team without recordings).
 * This model never widens it. Without an answer from the server the list is the calls made on this phone.
 */
class HistoryModel(
    private val media: MediaHub?,
    /** The time of a call made on this phone ("14:05", "Gisteren", "9 okt"). */
    private val localWhen: (Instant) -> String = { it.toString() },
    /** The sentence for an anonymous call. */
    var anonymousTitle: String = "",
) {
    enum class LoadState { IDLE, LOADING, LOADED, UNAVAILABLE }

    private val _pages = MutableStateFlow<Map<String, List<CallsPage>>>(emptyMap())
    private val _states = MutableStateFlow<Map<String, LoadState>>(emptyMap())
    private val _isLoadingMore = MutableStateFlow(false)
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    private val _banner = MutableStateFlow<MediaBanner?>(null)
    private val _goneIds = MutableStateFlow<Set<String>>(emptySet())

    val pages: StateFlow<Map<String, List<CallsPage>>> = _pages.asStateFlow()
    val states: StateFlow<Map<String, LoadState>> = _states.asStateFlow()
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()
    val banner: StateFlow<MediaBanner?> = _banner.asStateFlow()

    /** Recordings the server said are past their retention. */
    val goneIds: StateFlow<Set<String>> = _goneIds.asStateFlow()

    /** A name for a number (contacts, colleagues). */
    var nameLookup: (String) -> String? = { null }

    init {
        media?.player?.let { player ->
            player.itemObservers += { id -> if (id == null) _nowPlaying.value = null }
            player.stateObservers += { state -> playerStateChanged(state) }
        }
    }

    // MARK: Reading

    fun state(accountId: String): LoadState = _states.value[accountId] ?: LoadState.IDLE

    /** Everything the screen lists for this account: the PBX's calls, plus the calls of this phone the PBX has not caught up with. */
    fun entries(accountId: String, local: List<RecentCall>, extensionName: String?): List<HistoryEntry> {
        val server = (_pages.value[accountId] ?: emptyList())
            .flatMap { it.calls }
            .map { HistoryEntry.fromItem(it, accountId) }
            .sortedWith(NEWEST_FIRST)

        val newest = server.mapNotNull { it.startedAt }.maxOrNull()

        val phone = local
            .filter { call -> call.accountId == accountId && (newest == null || Instant.ofEpochMilli(call.startedAtMillis) > newest) }
            .map { HistoryEntry.fromRecent(it, extensionName, localWhen(Instant.ofEpochMilli(it.startedAtMillis))) }

        // Without anything from the server, every call of this phone is shown.
        return (phone + server).sortedWith(NEWEST_FIRST)
    }

    fun title(entry: HistoryEntry): String {
        val number = entry.number

        if (number != null) return nameLookup(number) ?: entry.knownName ?: number

        return entry.knownName ?: anonymousTitle
    }

    /** The month after the ones shown, if the server lists one. */
    fun nextMonth(accountId: String): String? {
        val loaded = _pages.value[accountId] ?: return null
        val months = loaded.firstOrNull()?.months ?: return null
        val shown = loaded.map { it.month }.toSet()

        return months.firstOrNull { it !in shown }
    }

    fun isTruncated(accountId: String): Boolean = _pages.value[accountId]?.any { it.truncated } == true

    // MARK: Loading

    suspend fun load(accountId: String) {
        val hub = media

        if (hub == null) {
            setState(accountId, LoadState.UNAVAILABLE)

            return
        }

        if (_pages.value[accountId] == null) setState(accountId, LoadState.LOADING)

        try {
            val page = hub.service.calls(accountId, null)
            // A refresh replaces the newest month and keeps the older ones the user already opened.
            val older = (_pages.value[accountId] ?: emptyList()).filter { it.month != page.month }
            _pages.update { it + (accountId to (listOf(page) + older)) }
            setState(accountId, LoadState.LOADED)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (MediaFailure.classify(error) == MediaFailure.Revoked) hub.onRevoked?.invoke()

            setState(accountId, if (_pages.value[accountId] == null) LoadState.UNAVAILABLE else LoadState.LOADED)
        }
    }

    suspend fun loadMore(accountId: String) {
        val hub = media
        val month = nextMonth(accountId)

        if (hub == null || month == null || _isLoadingMore.value) return

        _isLoadingMore.value = true

        try {
            val page = hub.service.calls(accountId, month)
            _pages.update { it + (accountId to ((it[accountId] ?: emptyList()) + page)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _banner.value = MediaBanner(MediaMessage.Failure(MediaFailure.classify(error), MediaKind.RECORDING))
        } finally {
            _isLoadingMore.value = false
        }
    }

    fun forget(accountId: String) {
        _pages.update { it - accountId }
        _states.update { it - accountId }
    }

    fun clearBanner() {
        _banner.value = null
    }

    private fun setState(accountId: String, state: LoadState) {
        _states.update { it + (accountId to state) }
    }

    // MARK: Recordings (admin)

    /** Only an admin pairing hears recordings; the hub knows (`recordings` comes from `capabilities.recordings` AND the admin role). */
    fun canPlayRecording(entry: HistoryEntry): Boolean = !entry.isLocal && entry.hasRecording && media?.hasRecordings(entry.accountId) == true

    /** The recording exists once but is not kept any more (shown to admins only). */
    fun hasExpiredRecording(entry: HistoryEntry): Boolean = !entry.isLocal && entry.recordingExpired && media?.hasRecordings(entry.accountId) == true

    fun isRecordingGone(entry: HistoryEntry): Boolean = entry.recordingExpired || entry.id in _goneIds.value

    /** Plays the recording after the local check (screen lock), like the "Opnames" screen. */
    suspend fun play(entry: HistoryEntry) {
        _banner.value = null

        val hub = media

        if (hub == null || !canPlayRecording(entry) || isRecordingGone(entry)) return

        when (hub.gate.ensureUnlocked(GateReason.RECORDINGS)) {
            GateResult.UNLOCKED -> Unit
            GateResult.CANCELLED, GateResult.FAILED -> return
            GateResult.UNAVAILABLE -> {
                _banner.value = MediaBanner(MediaMessage.NoScreenLock)

                return
            }
        }

        val callId = entry.id.removePrefix("${entry.accountId}/")

        try {
            val service = hub.service
            val accountId = entry.accountId
            val source: AudioSource = service.recordingSource(accountId, callId)
            _nowPlaying.value = NowPlaying(entry.id, title(entry), entry.whenLabel)
            hub.player.play(entry.id, source) { request -> service.download(accountId, request) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _banner.value = MediaBanner(MediaMessage.Failure(MediaFailure.classify(error), MediaKind.RECORDING))
        }
    }

    suspend fun retryPlaying(entries: List<HistoryEntry>) {
        val id = _nowPlaying.value?.id ?: return
        val entry = entries.firstOrNull { it.id == id } ?: return

        play(entry)
    }

    fun stopPlayer() {
        media?.player?.stop()
    }

    private fun playerStateChanged(state: PlayerState) {
        val failed = state as? PlayerState.Failed ?: return
        val id = _nowPlaying.value?.id ?: return

        when (failed.failure) {
            MediaFailure.Gone, MediaFailure.NotFound -> _goneIds.update { it + id }
            MediaFailure.AccessDenied -> id.substringBefore('/').let { media?.partDenied(MediaPart.RECORDINGS, it) }
            MediaFailure.Revoked -> media?.onRevoked?.invoke()
            else -> Unit
        }
    }

    private companion object {
        val NEWEST_FIRST: Comparator<HistoryEntry> = Comparator { a, b ->
            (b.startedAt ?: Instant.MIN).compareTo(a.startedAt ?: Instant.MIN)
        }
    }
}

object HistoryFilter {
    fun apply(entries: List<HistoryEntry>, filter: HistoryEntry.Filter, kind: HistoryEntry.Kind? = null): List<HistoryEntry> =
        entries.filter { entry -> (filter == HistoryEntry.Filter.ALL || entry.isMissed) && (kind == null || entry.kind == kind) }
}
