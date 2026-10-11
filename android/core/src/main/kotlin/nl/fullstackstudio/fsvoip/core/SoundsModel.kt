// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The sounds of the centrale of ONE paired account (an admin only): the list, listening, adding (a file or a recording), renaming and
// deleting. It is shared by "Beheer › Geluiden" and the audio picker of the number screens, so a sound added in one place is there in
// the other. The server enforces every rule; this keeps the screens honest. Main thread only.

package nl.fullstackstudio.fsvoip.core

import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Plays one sound at a time. The app module implements it with `MediaPlayer`; tests use a fake. */
interface SoundPlayer {
    enum class Status { IDLE, LOADING, PLAYING, PAUSED, FAILED }

    data class PlayerState(
        val currentId: String? = null,
        val status: Status = Status.IDLE,
        val positionMs: Int = 0,
        /** Read from the audio itself; null until known. */
        val durationMs: Int? = null,
        /** Why [Status.FAILED] (the audio could not be fetched); null = the audio itself cannot be played. */
        val failure: SoundFailure? = null,
    )

    val state: StateFlow<PlayerState>

    /** Starts [id]; [load] gives the audio as a local file (downloading it when needed). A new call replaces what plays. */
    fun play(id: String, load: suspend () -> File)

    fun togglePlayPause()

    fun seekTo(positionMs: Int)

    fun stop()
}

/** A sound that cannot be deleted because it is still used: what to tell the user. */
data class SoundDeleteBlock(val soundName: String, val places: List<ApiErrorPlace>)

/** The progress of one upload. */
data class SoundUploadState(val name: String, val fraction: Double)

/** An inline notice at the top of a list. */
data class SoundBanner(val failure: SoundFailure)

/** What the model needs from the app around it. */
class SoundsHooks(
    /** The user is still working in a protected screen: keeps the local access gate open. */
    val touchGate: () -> Unit = {},
    /** A call is going on: nothing records. */
    val isCallActive: () -> Boolean = { false },
    /** 403 on the sounds routes: this pairing lost the right. */
    val onAccessDenied: () -> Unit = {},
    /** 401: the pairing is gone. */
    val onRevoked: () -> Unit = {},
)

class SoundsModel(
    val accountId: String,
    private val service: SoundServicing,
    val player: SoundPlayer,
    val recorder: AudioRecorderSession,
    private val scope: CoroutineScope,
    private val cacheDirectory: File,
    private val hooks: SoundsHooks = SoundsHooks(),
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    private val _sounds = MutableStateFlow<List<Sound>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
    private val _hasLoaded = MutableStateFlow(false)
    private val _failure = MutableStateFlow<SoundFailure?>(null)
    private val _durations = MutableStateFlow<Map<String, Double>>(emptyMap())
    private val _upload = MutableStateFlow<SoundUploadState?>(null)
    private val _banner = MutableStateFlow<SoundBanner?>(null)
    private val _deleteBlock = MutableStateFlow<SoundDeleteBlock?>(null)
    private val _lastAddedId = MutableStateFlow<String?>(null)
    private var uploadJob: Job? = null
    private var loadGeneration = 0

    val sounds: StateFlow<List<Sound>> = _sounds.asStateFlow()
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    /** Why the list could not be loaded (only while nothing was loaded yet). */
    val failure: StateFlow<SoundFailure?> = _failure.asStateFlow()

    /** Seconds per sound id, read from the audio when it was played (or from the recording just added). Kept for the life of the model. */
    val durations: StateFlow<Map<String, Double>> = _durations.asStateFlow()
    val upload: StateFlow<SoundUploadState?> = _upload.asStateFlow()
    val banner: StateFlow<SoundBanner?> = _banner.asStateFlow()
    val deleteBlock: StateFlow<SoundDeleteBlock?> = _deleteBlock.asStateFlow()

    /** The id of the sound that was added last (the picker selects it). */
    val lastAddedId: StateFlow<String?> = _lastAddedId.asStateFlow()

    val isUploading: Boolean get() = _upload.value != null

    fun sound(id: String?): Sound? = id?.let { wanted -> _sounds.value.firstOrNull { it.id == wanted } }

    fun name(id: String?): String? = sound(id)?.name

    fun duration(sound: Sound): Double? = _durations.value[sound.id] ?: sound.durationSeconds

    fun dismissBanner() {
        _banner.value = null
    }

    fun dismissDeleteBlock() {
        _deleteBlock.value = null
    }

    suspend fun load() {
        loadGeneration++
        val mine = loadGeneration
        _isLoading.value = true

        try {
            val list = service.sounds()

            if (mine != loadGeneration) return

            _sounds.value = list.sortedWith { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
            _hasLoaded.value = true
            _failure.value = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (mine == loadGeneration) handle(error, whileLoading = true)
        } finally {
            if (mine == loadGeneration) _isLoading.value = false
        }
    }

    // MARK: Listening

    fun isPlaying(sound: Sound): Boolean = player.state.value.let { it.currentId == sound.id && it.status == SoundPlayer.Status.PLAYING }

    fun togglePlay(sound: Sound) {
        _banner.value = null
        hooks.touchGate()
        val state = player.state.value

        if (state.currentId == sound.id && state.status != SoundPlayer.Status.IDLE && state.status != SoundPlayer.Status.FAILED) {
            player.togglePlayPause()
            return
        }

        play(sound)
    }

    fun play(sound: Sound) {
        if (!sound.playable) return

        if (hooks.isCallActive()) {
            _banner.value = SoundBanner(SoundFailure.CallInProgress)
            return
        }

        player.play(sound.id) { download(sound.id) }
    }

    /** The preview of a recording that is not uploaded yet. */
    fun playLocal(file: File) {
        val state = player.state.value

        if (state.currentId == LOCAL_PREVIEW_ID && state.status != SoundPlayer.Status.IDLE && state.status != SoundPlayer.Status.FAILED) {
            player.togglePlayPause()
            return
        }

        player.play(LOCAL_PREVIEW_ID) { file }
    }

    fun stopPlaying() = player.stop()

    fun stopAll() {
        player.stop()
        recorder.cancel()
        uploadJob?.cancel()
    }

    /** A call started: what records stops, what plays pauses. */
    fun callStarted() {
        recorder.callActivityChanged()

        if (player.state.value.status == SoundPlayer.Status.PLAYING) player.togglePlayPause()
    }

    /** The duration the player read from the audio of the sound it plays (call this when the player reports it). */
    fun noteDuration(id: String, seconds: Double) {
        if (id == LOCAL_PREVIEW_ID || _durations.value[id] == seconds) return

        _durations.value = _durations.value + (id to seconds)
    }

    private suspend fun download(id: String): File {
        val bytes = try {
            service.audio(id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            when (SoundFailure.classify(error)) {
                SoundFailure.AccessDenied -> hooks.onAccessDenied()
                SoundFailure.Revoked -> hooks.onRevoked()
                else -> Unit
            }

            throw error
        }

        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            cacheDirectory.mkdirs()
            File(cacheDirectory, "sound-$accountId-$id.audio").also { it.writeBytes(bytes) }
        }
    }

    // MARK: Adding

    /**
     * Adds a file the user picked. The size is checked first: a file above the limit is never read. An unreadable size is not "0
     * bytes": refuse instead of reading a file of unknown size.
     */
    suspend fun addFile(fileName: String, size: Long?, name: String? = null, read: suspend () -> ByteArray): String? {
        if (size == null) {
            show(SoundFailure.Other)
            return null
        }

        if (size > SoundUpload.MAX_BYTES) {
            show(SoundFailure.TooLarge)
            return null
        }

        val data = try {
            read()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            show(SoundFailure.Other)
            return null
        }

        val title = cleanName(name ?: fileName.substringBeforeLast('.'))

        return add(title, fileName, data, knownDuration = null)
    }

    /** Adds the finished recording of the recorder. The temporary file stays until the upload worked (a failed upload can be tried again). */
    suspend fun addRecording(name: String): String? {
        val file = recorder.file
        val state = recorder.state.value

        if (file == null || state !is AudioRecorderSession.State.Recorded) return null

        val data = try {
            withContext(kotlinx.coroutines.Dispatchers.IO) { file.readBytes() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            show(SoundFailure.Other)
            return null
        }

        val id = add(cleanName(name), "opname.m4a", data, knownDuration = state.duration)

        if (id != null) {
            stopPlaying()
            recorder.discard()
        }

        return id
    }

    private suspend fun add(name: String, fileName: String, data: ByteArray, knownDuration: Double?): String? {
        if (_upload.value != null) return null

        _banner.value = null
        hooks.touchGate()
        _upload.value = SoundUploadState(name, 0.0)

        var id: String? = null
        val job = scope.launch {
            try {
                id = service.upload(name, fileName, data) { fraction -> _upload.value = _upload.value?.copy(fraction = fraction) }
            } catch (error: CancellationException) {
                // Cancelled by the user (or the screen): not a failure.
            } catch (error: Exception) {
                handle(error, whileLoading = false)
            }
        }
        uploadJob = job
        job.join()
        uploadJob = null
        _upload.value = null

        val added = id ?: return null

        if (knownDuration != null) _durations.value = _durations.value + (added to knownDuration)

        _lastAddedId.value = added
        load()

        return added
    }

    fun cancelUpload() {
        uploadJob?.cancel()
    }

    // MARK: Renaming and deleting

    suspend fun rename(sound: Sound, newName: String): Boolean {
        val title = cleanName(newName)

        if (title.isEmpty() || title == sound.name) return true

        hooks.touchGate()

        return try {
            service.rename(sound.id, title)
            load()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            handle(error, whileLoading = false)
            false
        }
    }

    /** Deletes a sound. A sound that is still used stays ([deleteBlock] says where). */
    suspend fun delete(sound: Sound) {
        hooks.touchGate()

        try {
            service.delete(sound.id)

            if (player.state.value.currentId == sound.id) player.stop()

            _sounds.value = _sounds.value.filter { it.id != sound.id }
            _durations.value = _durations.value - sound.id
            load()
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiException.InUse) {
            _deleteBlock.value = SoundDeleteBlock(sound.name, error.places)
        } catch (error: ApiException.NotFound) {
            _sounds.value = _sounds.value.filter { it.id != sound.id }
        } catch (error: Exception) {
            handle(error, whileLoading = false)
        }
    }

    // MARK: Errors

    private fun show(failure: SoundFailure) {
        _banner.value = SoundBanner(failure)
    }

    private fun handle(error: Exception, whileLoading: Boolean) {
        val result = SoundFailure.classify(error)

        when (result) {
            SoundFailure.AccessDenied -> hooks.onAccessDenied()
            SoundFailure.Revoked -> hooks.onRevoked()
            else -> Unit
        }

        if (whileLoading && !_hasLoaded.value) {
            _failure.value = result
        } else if (result != SoundFailure.AccessDenied && result != SoundFailure.Revoked) {
            show(result)
        }
    }

    /** "Opname 9 okt 14:32" (a name for a recording the user does not rename): the caller supplies the localized label and formatter. */
    fun defaultRecordingName(label: String, format: (LocalDateTime) -> String): String = "$label ${format(now())}"

    companion object {
        const val LOCAL_PREVIEW_ID = "local-recording"

        /** Trimmed, one line, no more than the 100 characters we send (the server has its own limit). */
        fun cleanName(value: String): String = value.lines().joinToString(" ").trim().take(100)
    }
}

/** When "Toepassen" is possible in the audio picker. */
object SoundSelection {
    /** Something other than what is set now, nothing is being added, and a choice (or "Geen" where that is allowed). */
    fun canApply(selection: String?, current: String?, allowsNone: Boolean, isUploading: Boolean): Boolean =
        selection != current && !isUploading && (selection != null || allowsNone)
}
