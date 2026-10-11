// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Calls (the team history) and voicemail of the PBX, and playing audio. Android port of the iOS `MediaService` / `HistoryModel` /
// `VoicemailModel` / `AudioStreamer`. Everything here is plain Kotlin: the screens observe the state flows, the audio backend
// (ExoPlayer) lives in the app module.

package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why calls, voicemail or audio did not work, in the words the screens need. */
sealed interface MediaFailure {
    /** 410: past its retention period (recordings 90 days, voicemail 30 days). */
    data object Gone : MediaFailure

    data object NotFound : MediaFailure

    /** 403: this pairing may not (any more). */
    data object AccessDenied : MediaFailure

    /** 401: the pairing is gone. */
    data object Revoked : MediaFailure

    data class RateLimited(val retryAfterSeconds: Int?) : MediaFailure

    data object Offline : MediaFailure

    /** The PBX did not answer (503, 5xx). */
    data object Unavailable : MediaFailure

    data object ReadOnly : MediaFailure

    data object Other : MediaFailure

    companion object {
        fun classify(error: Throwable): MediaFailure = when (error) {
            is ApiException.Gone -> Gone
            is ApiException.NotFound -> NotFound
            is ApiException.Forbidden -> AccessDenied
            is ApiException.Unauthorized -> Revoked
            is ApiException.RateLimited -> RateLimited(error.retryAfterSeconds)
            is ApiException.Transport -> Offline
            is ApiException.Unavailable, is ApiException.UnexpectedStatus -> Unavailable
            is ApiException.Conflict -> if (error.error == "read_only") ReadOnly else Other
            else -> Other
        }

        /** The failure for an HTTP status a media player saw (it does not give us an [ApiException]). */
        fun forHttpStatus(status: Int, retryAfterSeconds: Int? = null): MediaFailure = when (status) {
            401 -> Revoked
            403 -> AccessDenied
            404 -> NotFound
            410 -> Gone
            429 -> RateLimited(retryAfterSeconds)
            else -> if (status >= 500) Unavailable else Other
        }
    }
}

/** What the screens need from the API. One implementation talks to the server; tests bring their own. Every method throws [ApiException]. */
interface MediaServicing {
    /** `GET /calls?month=`; `null` = the current month. */
    suspend fun calls(account: StoredAccount, month: String?): PbxCallsPage

    suspend fun voicemail(account: StoredAccount, boxId: String?): VoicemailPage

    suspend fun deleteVoicemail(account: StoredAccount, boxId: String, ref: String)

    /** URL and headers for a player. */
    fun voicemailSource(account: StoredAccount, boxId: String, ref: String): MediaSource
}

class LiveMediaService(private val api: FsVoipApiClient, private val locale: () -> String = { "nl" }) : MediaServicing {
    private fun client(account: StoredAccount) = api.authenticated(account.deviceToken)

    override suspend fun calls(account: StoredAccount, month: String?): PbxCallsPage = client(account).calls(month, locale())

    override suspend fun voicemail(account: StoredAccount, boxId: String?): VoicemailPage = client(account).voicemail(boxId, locale())

    override suspend fun deleteVoicemail(account: StoredAccount, boxId: String, ref: String) {
        client(account).deleteVoicemail(boxId, ref)
    }

    override fun voicemailSource(account: StoredAccount, boxId: String, ref: String): MediaSource = client(account).voicemailAudio(boxId, ref)
}

// MARK: History

/** One line of "Geschiedenis": a call of the PBX (the team history) or, while the server cannot say, one of this phone. */
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
    /** Who took (or made) the call. */
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

    enum class Filter { ALL, MISSED }

    companion object {
        fun of(item: PbxCall, accountId: String): HistoryEntry = HistoryEntry(
            id = "$accountId/${item.id}",
            accountId = accountId,
            kind = when (item.direction) {
                PbxCallDirection.OUTBOUND -> Kind.OUTGOING
                PbxCallDirection.INTERNAL -> Kind.INTERNAL
                PbxCallDirection.INBOUND, PbxCallDirection.UNKNOWN -> Kind.INCOMING
            },
            number = item.number?.takeIf { it.isNotEmpty() },
            knownName = null,
            isMissed = item.direction == PbxCallDirection.INBOUND && (item.outcome == PbxCallOutcome.MISSED || item.outcome == PbxCallOutcome.VOICEMAIL),
            startedAt = item.startedAt,
            whenLabel = item.startedLabel,
            durationLabel = if (item.outcome == PbxCallOutcome.ANSWERED) item.durationLabel else "",
            extensionName = item.extensionName,
            ourNumber = item.ourNumber,
            countryName = item.countryName,
            hasRecording = item.hasRecording,
            recordingExpired = item.recordingExpired,
            isLocal = false,
        )

        fun of(call: RecentCall, extensionName: String?, whenLabel: String, durationLabel: String): HistoryEntry = HistoryEntry(
            id = "local/${call.id}",
            accountId = call.accountId,
            kind = if (call.direction == RecentCall.Direction.INCOMING) Kind.INCOMING else Kind.OUTGOING,
            number = call.number.takeIf { it.isNotEmpty() },
            knownName = call.name,
            isMissed = call.direction == RecentCall.Direction.INCOMING && call.outcome == RecentCall.Outcome.MISSED,
            startedAt = Instant.ofEpochMilli(call.startedAtMillis),
            whenLabel = whenLabel,
            durationLabel = if (call.outcome == RecentCall.Outcome.ANSWERED) durationLabel else "",
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
 * The calls of the PBX per account, newest first, a month at a time. What a pairing sees is decided by the server
 * (`capabilities.calls`: an admin everything, a `user` the team without recordings); this model never widens it. Without an answer
 * from the server the list is the calls made on this phone.
 */
class HistoryModel(private val service: MediaServicing) {
    enum class LoadState { IDLE, LOADING, LOADED, UNAVAILABLE }

    private val _pages = MutableStateFlow<Map<String, List<PbxCallsPage>>>(emptyMap())
    private val _states = MutableStateFlow<Map<String, LoadState>>(emptyMap())
    private val _isLoadingMore = MutableStateFlow(false)
    private val _failure = MutableStateFlow<MediaFailure?>(null)

    val pages: StateFlow<Map<String, List<PbxCallsPage>>> = _pages.asStateFlow()
    val states: StateFlow<Map<String, LoadState>> = _states.asStateFlow()
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    /** A failure of "load more" that the screen shows as a notice (the list stays). */
    val failure: StateFlow<MediaFailure?> = _failure.asStateFlow()

    /** A 401: the pairing is gone. */
    var onRevoked: (() -> Unit)? = null

    fun state(accountId: String): LoadState = _states.value[accountId] ?: LoadState.IDLE

    /** Everything the screen lists for this account: the PBX's calls, plus the calls of this phone the PBX has not caught up with. */
    fun entries(accountId: String, local: List<HistoryEntry>): List<HistoryEntry> {
        val server = _pages.value[accountId].orEmpty().flatMap { it.calls }.map { HistoryEntry.of(it, accountId) }
        val newest = server.mapNotNull { it.startedAt }.maxOrNull()
        val phone = local.filter { it.accountId == accountId && (newest == null || (it.startedAt != null && it.startedAt > newest)) }

        // Without anything from the server, every call of this phone is shown.
        return (phone + server).sortedByDescending { it.startedAt ?: Instant.EPOCH }
    }

    /** The month after the ones shown, if the server lists one. */
    fun nextMonth(accountId: String): String? {
        val loaded = _pages.value[accountId] ?: return null
        val months = loaded.firstOrNull()?.months ?: return null
        val shown = loaded.map { it.month }.toSet()

        return months.firstOrNull { it !in shown }
    }

    fun isTruncated(accountId: String): Boolean = _pages.value[accountId].orEmpty().any { it.truncated }

    suspend fun load(account: StoredAccount) {
        if (_pages.value[account.id] == null) {
            _states.value = _states.value + (account.id to LoadState.LOADING)
        }

        try {
            val page = service.calls(account, null)
            // A refresh replaces the newest month and keeps the older ones the user already opened.
            val older = _pages.value[account.id].orEmpty().filter { it.month != page.month }
            _pages.value = _pages.value + (account.id to listOf(page) + older)
            _states.value = _states.value + (account.id to LoadState.LOADED)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (MediaFailure.classify(error) == MediaFailure.Revoked) onRevoked?.invoke()

            _states.value = _states.value + (account.id to if (_pages.value[account.id] == null) LoadState.UNAVAILABLE else LoadState.LOADED)
        }
    }

    suspend fun loadMore(account: StoredAccount) {
        val month = nextMonth(account.id)

        if (month == null || _isLoadingMore.value) return

        _isLoadingMore.value = true

        try {
            val page = service.calls(account, month)
            _pages.value = _pages.value + (account.id to _pages.value[account.id].orEmpty() + page)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _failure.value = MediaFailure.classify(error)
        } finally {
            _isLoadingMore.value = false
        }
    }

    fun clearFailure() {
        _failure.value = null
    }

    fun forget(accountId: String) {
        _pages.value = _pages.value - accountId
        _states.value = _states.value - accountId
    }

    companion object {
        fun filtered(entries: List<HistoryEntry>, filter: HistoryEntry.Filter, kind: HistoryEntry.Kind? = null): List<HistoryEntry> =
            entries.filter { (filter == HistoryEntry.Filter.ALL || it.isMissed) && (kind == null || it.kind == kind) }
    }
}

// MARK: Voicemail

/**
 * The "Voicemail" screen of ONE paired account. A `user` pairing sees its own box only (the server decides). An admin's page lists
 * every box; this part of the app shows the own box only (the box of the own extension), "Alle voicemail" comes with the local
 * check in part 2.
 */
class VoicemailModel(private val account: StoredAccount, private val service: MediaServicing, private val canSeeVoicemail: () -> Boolean) {
    private val _page = MutableStateFlow<VoicemailPage?>(null)
    private val _isLoading = MutableStateFlow(false)
    private val _failure = MutableStateFlow<MediaFailure?>(null)
    private val _banner = MutableStateFlow<MediaFailure?>(null)
    private val _goneIds = MutableStateFlow<Set<String>>(emptySet())

    val page: StateFlow<VoicemailPage?> = _page.asStateFlow()
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** The list could not be loaded at all (nothing to show). */
    val failure: StateFlow<MediaFailure?> = _failure.asStateFlow()

    /** Something went wrong while a good list is on screen: say so without taking the list away. */
    val banner: StateFlow<MediaFailure?> = _banner.asStateFlow()
    val goneIds: StateFlow<Set<String>> = _goneIds.asStateFlow()

    var onRevoked: (() -> Unit)? = null

    /** The box of the extension of this phone. A `user` page lists that box only; an admin's page lists every box, the number tells which one is ours. */
    fun ownBoxId(page: VoicemailPage): String? {
        val boxes = page.boxes

        if (boxes.size == 1) return boxes.first().id

        val number = account.extensionNumber ?: return boxes.firstOrNull { !it.shared }?.id

        return boxes.firstOrNull { !it.shared && it.number == number }?.id
    }

    /** The messages the screen may show: the own box, newest first. */
    fun messages(page: VoicemailPage): List<VoicemailMessage> {
        val own = ownBoxId(page)

        return page.messages.filter { own == null || it.boxId == own }.sortedByDescending { it.receivedSort.orEmpty() }
    }

    fun isGone(message: VoicemailMessage): Boolean = message.key in _goneIds.value || (message.daysLeft ?: 0) < 0

    fun markGone(message: VoicemailMessage) {
        _goneIds.value = _goneIds.value + message.key
    }

    suspend fun load() {
        if (!canSeeVoicemail()) {
            _page.value = VoicemailPage(available = true)
            return
        }

        _isLoading.value = _page.value == null

        try {
            _page.value = service.voicemail(account, null)
            _failure.value = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            handle(error)
        } finally {
            _isLoading.value = false
        }
    }

    /** Delete one message (the own box). A 404 means it was gone already: the list was out of date. */
    suspend fun delete(message: VoicemailMessage): Boolean {
        _banner.value = null

        return try {
            service.deleteVoicemail(account, message.boxId, message.ref)
            remove(message)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            when (MediaFailure.classify(error)) {
                MediaFailure.NotFound -> {
                    remove(message)
                    true
                }
                else -> {
                    handle(error)
                    false
                }
            }
        }
    }

    fun source(message: VoicemailMessage): MediaSource = service.voicemailSource(account, message.boxId, message.ref)

    fun clearBanner() {
        _banner.value = null
    }

    private fun remove(message: VoicemailMessage) {
        _page.value = _page.value?.let { page -> page.copy(messages = page.messages.filterNot { it.key == message.key }) }
    }

    private fun handle(error: Exception) {
        val result = MediaFailure.classify(error)

        if (result == MediaFailure.Revoked) onRevoked?.invoke()

        if (_page.value != null && result != MediaFailure.Revoked) {
            _banner.value = result
        } else {
            _failure.value = result
        }
    }
}

// MARK: Audio

/** What a backend (ExoPlayer) tells the model. Called on the main thread. */
interface AudioBackendListener {
    fun onReady(durationMs: Long)
    fun onPosition(positionMs: Long)
    fun onPlayingChanged(playing: Boolean)
    fun onEnded()
    fun onError(failure: MediaFailure)
}

/** Plays one audio source with the headers of its [MediaSource] (the device token goes in the `Authorization` header, never in the URL). */
interface AudioBackend {
    var listener: AudioBackendListener?

    /** Load the source and start playing as soon as there is audio. */
    fun prepare(source: MediaSource, playWhenReady: Boolean)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)

    /** Stop and let go of everything. */
    fun stop()
}

data class PlayerSnapshot(
    val id: String? = null,
    val status: Status = Status.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val failure: MediaFailure? = null,
) {
    enum class Status { IDLE, LOADING, PLAYING, PAUSED, ENDED, FAILED }

    val isActive: Boolean
        get() = id != null && status != Status.IDLE
}

/** One thing plays at a time. Starting another stops the first; the model keeps the position so the screen can show a bar. */
class AudioPlayerModel(private val backend: AudioBackend) : AudioBackendListener {
    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    init {
        backend.listener = this
    }

    fun play(id: String, source: MediaSource) {
        val speed = _snapshot.value.speed
        _snapshot.value = PlayerSnapshot(id = id, status = PlayerSnapshot.Status.LOADING, speed = speed)
        backend.prepare(source, playWhenReady = true)
        backend.setSpeed(speed)
    }

    fun toggle() {
        val current = _snapshot.value

        when (current.status) {
            PlayerSnapshot.Status.PLAYING -> backend.pause()
            PlayerSnapshot.Status.PAUSED -> backend.play()
            PlayerSnapshot.Status.ENDED -> {
                backend.seekTo(0)
                backend.play()
            }
            else -> Unit
        }
    }

    fun seekTo(positionMs: Long) {
        val current = _snapshot.value

        if (!current.isActive || current.status == PlayerSnapshot.Status.FAILED) return

        val target = positionMs.coerceIn(0, current.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        backend.seekTo(target)
        _snapshot.value = current.copy(positionMs = target)
    }

    fun skip(deltaMs: Long) = seekTo(_snapshot.value.positionMs + deltaMs)

    fun setSpeed(speed: Float) {
        backend.setSpeed(speed)
        _snapshot.value = _snapshot.value.copy(speed = speed)
    }

    fun stop() {
        backend.stop()
        _snapshot.value = PlayerSnapshot(speed = _snapshot.value.speed)
    }

    override fun onReady(durationMs: Long) {
        _snapshot.value = _snapshot.value.copy(durationMs = durationMs.coerceAtLeast(0))
    }

    override fun onPosition(positionMs: Long) {
        _snapshot.value = _snapshot.value.copy(positionMs = positionMs)
    }

    override fun onPlayingChanged(playing: Boolean) {
        val current = _snapshot.value

        if (current.id == null || current.status == PlayerSnapshot.Status.FAILED) return

        val next = when {
            playing -> PlayerSnapshot.Status.PLAYING
            current.status == PlayerSnapshot.Status.PLAYING -> PlayerSnapshot.Status.PAUSED
            else -> current.status
        }

        _snapshot.value = current.copy(status = next)
    }

    override fun onEnded() {
        val current = _snapshot.value
        _snapshot.value = current.copy(status = PlayerSnapshot.Status.ENDED, positionMs = current.durationMs)
    }

    override fun onError(failure: MediaFailure) {
        _snapshot.value = _snapshot.value.copy(status = PlayerSnapshot.Status.FAILED, failure = failure)
    }
}

object MediaFormat {
    /** `0:42`, `5:12`, `1:02:03`. */
    fun clock(milliseconds: Long): String {
        val total = (milliseconds / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60

        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }
}
