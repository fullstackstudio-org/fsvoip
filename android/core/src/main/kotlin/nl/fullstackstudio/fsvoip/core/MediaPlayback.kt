// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Playing recordings and voicemail. Core owns the rules (what a failure means to the user, the cache of the downloaded audio, the
// state machine of the player); the Android player (MediaPlayer) lives in the app behind [AudioBackend], so this runs in plain
// unit tests with a fake backend.

package nl.fullstackstudio.fsvoip.core

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MARK: What goes wrong, in the user's terms

/**
 * Why a recording or voicemail did not play (or could not be deleted). The server decides what is allowed; this sorts the answer
 * into what the user can do about it.
 */
sealed interface MediaFailure {
    /** 403: this pairing may not hear/delete this. The screens hide the part. */
    data object AccessDenied : MediaFailure

    /** 401: the pairing is gone. */
    data object Revoked : MediaFailure

    /** 410: past its retention period (recordings 90 days, voicemail 30). */
    data object Gone : MediaFailure

    /** 404: it is already gone (deleted somewhere else). */
    data object NotFound : MediaFailure

    data class RateLimited(val retryAfterSeconds: Int?) : MediaFailure

    data object Offline : MediaFailure

    data object Unavailable : MediaFailure

    /** 409 `read_only`: the phone system does not accept changes now. */
    data object ReadOnly : MediaFailure

    /** A call is going on: audio does not play over a call. */
    data object CallInProgress : MediaFailure

    /** The audio arrived but cannot be played. */
    data object Unplayable : MediaFailure

    data object Other : MediaFailure

    /** Trying again can help (as opposed to "gone", "no access" or "unplayable"). */
    val isRetryable: Boolean
        get() = when (this) {
            is RateLimited, Offline, Unavailable, CallInProgress, Other -> true
            else -> false
        }

    companion object {
        fun classify(error: Throwable): MediaFailure = when (error) {
            is ApiException.Forbidden -> AccessDenied
            is ApiException.Unauthorized -> Revoked
            is ApiException.Gone -> Gone
            is ApiException.NotFound -> NotFound
            is ApiException.RateLimited -> RateLimited(error.retryAfterSeconds)
            is ApiException.Transport -> Offline
            is ApiException.Unavailable, is ApiException.UnexpectedStatus -> Unavailable
            is ApiException.ReadOnly -> ReadOnly
            is ApiException.Conflict -> if (error.code == "read_only") ReadOnly else Other
            else -> Other
        }
    }
}

// MARK: Audio files

enum class AudioFileType(val extension: String) {
    WAV("wav"),
    MP3("mp3"),
    M4A("m4a"),
    ;

    companion object {
        /** From the `Content-Type` of the answer, else from the first bytes (the player wants the right extension). */
        fun detect(contentType: String?, data: ByteArray): AudioFileType {
            val type = contentType?.lowercase() ?: ""

            if (type.contains("wav")) return WAV
            if (type.contains("mpeg") || type.contains("mp3")) return MP3
            if (type.contains("mp4") || type.contains("m4a") || type.contains("aac")) return M4A

            fun at(index: Int): Int = if (index < data.size) data[index].toInt() and 0xFF else -1

            if (data.size >= 12 && at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46) return WAV
            if (data.size >= 8 && at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70) return M4A
            if (data.size >= 3 && at(0) == 0x49 && at(1) == 0x44 && at(2) == 0x33) return MP3
            if (data.size >= 2 && at(0) == 0xFF && (at(1) and 0xE0) == 0xE0) return MP3

            return M4A
        }
    }
}

class MediaDownload(val data: ByteArray, val contentType: String?)

/** Where the audio comes from. */
sealed interface AudioSource {
    /** On the server: fetched with the authenticated API client. */
    data class Stream(val request: MediaRequest) : AudioSource

    /** A file on this phone (the cache, and the demo mode). */
    data class LocalFile(val file: File) : AudioSource
}

// MARK: Cache of the downloaded audio

/**
 * The downloaded files: in the app cache directory, at most [maxBytes] in total (the least recently used go first). A file is named
 * after a hash of its key, so nothing in the name tells what it is.
 */
class MediaCache(val directory: File, val maxBytes: Long = DEFAULT_MAX_BYTES) {
    private val lock = Any()

    private fun name(key: String, type: AudioFileType): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        return "${digest.take(32)}.${type.extension}"
    }

    /** The file for this key if it is there (and counts as used just now). */
    fun cachedFile(key: String): File? = synchronized(lock) {
        for (type in AudioFileType.entries) {
            val file = File(directory, name(key, type))

            if (file.isFile) {
                file.setLastModified(System.currentTimeMillis())

                return@synchronized file
            }
        }

        null
    }

    fun store(download: MediaDownload, key: String): File = synchronized(lock) {
        directory.mkdirs()

        val type = AudioFileType.detect(download.contentType, download.data)
        val target = File(directory, name(key, type))
        val temp = File(directory, "${target.name}.part")
        temp.writeBytes(download.data)

        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }

        trim(keeping = target)

        target
    }

    /** Removes the oldest files until the total fits; the file just stored always stays. */
    private fun trim(keeping: File) {
        val entries = (directory.listFiles() ?: emptyArray()).filter { it.isFile }.sortedBy { it.lastModified() }
        var total = entries.sumOf { it.length() }

        for (entry in entries) {
            if (total <= maxBytes) break
            if (entry.name == keeping.name) continue

            val size = entry.length()

            if (entry.delete()) total -= size
        }
    }

    fun totalBytes(): Long = synchronized(lock) { (directory.listFiles() ?: emptyArray()).filter { it.isFile }.sumOf { it.length() } }

    fun clear() {
        synchronized(lock) { directory.deleteRecursively() }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 50L * 1024 * 1024
    }
}

// MARK: The player's state machine

enum class PlaybackSpeed(val rate: Double) {
    NORMAL(1.0),
    FAST(1.5),
    FASTER(2.0),
    ;

    /** 1× → 1,5× → 2× → 1×. */
    val next: PlaybackSpeed
        get() = when (this) {
            NORMAL -> FAST
            FAST -> FASTER
            FASTER -> NORMAL
        }
}

sealed interface AudioBackendEvent {
    /** The item can play; [duration] in seconds when known. */
    data class Ready(val duration: Double?) : AudioBackendEvent

    data class Progress(val seconds: Double) : AudioBackendEvent

    data object Ended : AudioBackendEvent

    data object Failed : AudioBackendEvent

    /** Something outside the app took the audio (a phone call, another app, an alarm). */
    data object Interrupted : AudioBackendEvent
}

/** What plays the sound. The app implements it with `MediaPlayer`; tests use a fake. Events arrive on the thread that owns the streamer. */
interface AudioBackend {
    var onEvent: ((AudioBackendEvent) -> Unit)?

    fun load(source: AudioSource)

    fun play(rate: Double)

    fun pause()

    fun seek(seconds: Double)

    fun setRate(rate: Double)

    /** Stops and lets go of the item and the audio focus. */
    fun stop()
}

sealed interface PlayerState {
    data object Idle : PlayerState

    data object Loading : PlayerState

    data object Paused : PlayerState

    data object Playing : PlayerState

    data object Ended : PlayerState

    data class Failed(val failure: MediaFailure) : PlayerState
}

/**
 * One item at a time: the state of what is being played and the rules around it.
 *
 * - The audio is a download (through the authenticated API client, into the [MediaCache]) played from a file. With
 *   [streamFirst] a backend that can stream is tried first and a failure is retried once as a download, which is also how the real
 *   reason (410, 403, 429, offline) comes out. The Android app downloads straight away.
 * - Nothing plays during a call, and a call that starts pauses what plays ([callActivityChanged]).
 * - All calls come from one thread (the main thread); [scope] runs on it too.
 */
class AudioStreamer(
    private val backend: AudioBackend,
    private val cache: MediaCache,
    private val scope: CoroutineScope,
    private val isCallActive: () -> Boolean = { false },
    private val streamFirst: Boolean = false,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    private val _currentId = MutableStateFlow<String?>(null)
    private val _position = MutableStateFlow(0.0)
    private val _duration = MutableStateFlow<Double?>(null)
    private val _speed = MutableStateFlow(PlaybackSpeed.NORMAL)
    private val _usesDownload = MutableStateFlow(false)

    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** The id the caller gave [play]; `null` = nothing selected. */
    val currentId: StateFlow<String?> = _currentId.asStateFlow()
    val position: StateFlow<Double> = _position.asStateFlow()
    val duration: StateFlow<Double?> = _duration.asStateFlow()
    val speed: StateFlow<PlaybackSpeed> = _speed.asStateFlow()

    /** The current item plays from the download. */
    val usesDownload: StateFlow<Boolean> = _usesDownload.asStateFlow()

    private var playerState: PlayerState = PlayerState.Idle
        set(value) {
            val changed = field != value
            field = value
            _state.value = value

            if (changed) stateObservers.toList().forEach { it(value) }
        }

    private var currentItem: String? = null
        set(value) {
            val changed = field != value
            field = value
            _currentId.value = value

            if (changed) itemObservers.toList().forEach { it(value) }
        }

    /** Called (on the owning thread) whenever the state changes / the selected item changes. */
    val stateObservers: MutableList<(PlayerState) -> Unit> = mutableListOf()
    val itemObservers: MutableList<(String?) -> Unit> = mutableListOf()

    private var generation = 0
    private var wantsToPlay = false
    private var streamRequest: MediaRequest? = null
    private var fetchFallback: (suspend (MediaRequest) -> MediaDownload)? = null
    private var fallbackJob: Job? = null

    init {
        backend.onEvent = { handle(it) }
    }

    val isActive: Boolean get() = currentItem != null

    /** Load this item and start playing it. [fetch] downloads the audio of a [AudioSource.Stream]. */
    fun play(id: String, source: AudioSource, fetch: suspend (MediaRequest) -> MediaDownload) {
        reset()
        generation++
        currentItem = id
        fetchFallback = fetch

        if (source is AudioSource.Stream) streamRequest = source.request

        if (isCallActive()) {
            playerState = PlayerState.Failed(MediaFailure.CallInProgress)

            return
        }

        playerState = PlayerState.Loading
        wantsToPlay = true

        if (source is AudioSource.Stream) {
            val cached = cache.cachedFile(id)

            when {
                cached != null -> {
                    // Heard before and still in the cache: no need to ask the server again (and it counts against the media limit).
                    _usesDownload.value = true
                    backend.load(AudioSource.LocalFile(cached))
                }
                streamFirst -> backend.load(source)
                else -> {
                    _usesDownload.value = true
                    download(source.request, id, fetch)
                }
            }
        } else {
            backend.load(source)
        }
    }

    fun togglePlayPause() {
        when (playerState) {
            PlayerState.Playing -> pause()
            PlayerState.Paused -> resume()
            PlayerState.Ended -> {
                seek(0.0)
                resume()
            }
            else -> Unit
        }
    }

    fun pause() {
        if (playerState != PlayerState.Playing) return

        wantsToPlay = false
        backend.pause()
        playerState = PlayerState.Paused
    }

    fun resume() {
        if (playerState != PlayerState.Paused && playerState != PlayerState.Ended) return

        if (isCallActive()) {
            playerState = PlayerState.Failed(MediaFailure.CallInProgress)

            return
        }

        wantsToPlay = true
        backend.play(_speed.value.rate)
        playerState = PlayerState.Playing
    }

    fun seek(seconds: Double) {
        if (currentItem == null || playerState == PlayerState.Loading) return

        val upper = _duration.value ?: maxOf(seconds, _position.value)
        val target = minOf(maxOf(0.0, seconds), upper)
        _position.value = target
        backend.seek(target)

        if (playerState == PlayerState.Ended) playerState = PlayerState.Paused
    }

    fun skip(seconds: Double) = seek(_position.value + seconds)

    fun setSpeed(value: PlaybackSpeed) {
        _speed.value = value

        if (playerState == PlayerState.Playing) backend.setRate(value.rate)
    }

    fun cycleSpeed() = setSpeed(_speed.value.next)

    /** The user left the screen or closed the player. */
    fun stop() {
        reset()
        generation++
        playerState = PlayerState.Idle
        currentItem = null
    }

    /** Call this when the phone's call state changes: a call that starts pauses (or blocks) the audio. */
    fun callActivityChanged() {
        if (!isCallActive()) return

        when (playerState) {
            PlayerState.Playing -> pause()
            PlayerState.Loading -> {
                // Let go of the item: it must not start by itself in the middle of a call.
                val id = currentItem
                reset()
                generation++
                currentItem = id
                playerState = PlayerState.Failed(MediaFailure.CallInProgress)
            }
            else -> Unit
        }
    }

    // MARK: Events of the backend

    private fun handle(event: AudioBackendEvent) {
        if (currentItem == null) return

        when (event) {
            is AudioBackendEvent.Ready -> {
                if (playerState != PlayerState.Loading) return

                _duration.value = event.duration?.takeIf { it.isFinite() && it > 0 }

                if (wantsToPlay && !isCallActive()) {
                    backend.play(_speed.value.rate)
                    playerState = PlayerState.Playing
                } else {
                    playerState = PlayerState.Paused
                }
            }
            is AudioBackendEvent.Progress -> {
                if (playerState == PlayerState.Playing || playerState == PlayerState.Paused) _position.value = event.seconds
            }
            AudioBackendEvent.Ended -> {
                if (playerState != PlayerState.Playing) return

                _duration.value?.let { _position.value = it }
                wantsToPlay = false
                playerState = PlayerState.Ended
            }
            AudioBackendEvent.Interrupted -> {
                if (playerState == PlayerState.Playing) {
                    wantsToPlay = false
                    playerState = PlayerState.Paused
                }
            }
            AudioBackendEvent.Failed -> failed()
        }
    }

    private fun failed() {
        val state = playerState

        if (state != PlayerState.Loading && state != PlayerState.Playing && state != PlayerState.Paused) return

        val request = streamRequest
        val fetch = fetchFallback

        if (!_usesDownload.value && request != null && fetch != null) {
            // The stream failed: fetch the whole file. Its answer says why (410, 403, 429, offline) and it plays when it works.
            _usesDownload.value = true
            playerState = PlayerState.Loading
            backend.stop()
            download(request, currentItem ?: "", fetch)

            return
        }

        wantsToPlay = false
        playerState = PlayerState.Failed(MediaFailure.Unplayable)
    }

    private fun download(request: MediaRequest, key: String, fetch: suspend (MediaRequest) -> MediaDownload) {
        val token = generation

        fallbackJob = scope.launch {
            try {
                val download = fetch(request)
                val file = withContext(io) { cache.store(download, key) }

                if (token != generation) return@launch

                backend.load(AudioSource.LocalFile(file))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (token != generation) return@launch

                wantsToPlay = false
                playerState = PlayerState.Failed(MediaFailure.classify(error))
            }
        }
    }

    private fun reset() {
        fallbackJob?.cancel()
        fallbackJob = null
        backend.stop()
        _position.value = 0.0
        _duration.value = null
        wantsToPlay = false
        _usesDownload.value = false
        streamRequest = null
        fetchFallback = null
    }
}
