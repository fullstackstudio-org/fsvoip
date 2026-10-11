// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Recording a sound with the microphone. This file owns the rules (the state machine, the five-minute limit, no recording during a
// call, the temporary file that is always cleaned up); the Android recorder lives in the app module behind [RecorderBackend], so this
// runs in the unit tests with a fake backend.

package nl.fullstackstudio.fsvoip.core

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class RecorderFailure {
    /** The microphone is not allowed (Settings > FSVoip). */
    PERMISSION_DENIED,

    /** A call is going on: the microphone belongs to the call. */
    CALL_IN_PROGRESS,

    /** Shorter than a second: nothing worth keeping. */
    TOO_SHORT,

    COULD_NOT_START,
}

/** What records. The app module implements it with `MediaRecorder`; tests use a fake. All calls come on the main thread. */
interface RecorderBackend {
    /** Called when the system takes the microphone away (audio focus lost): the session stops what it has. */
    var onInterrupted: (() -> Unit)?

    suspend fun requestPermission(): Boolean

    /** Starts recording AAC (`.m4a`) to [file]. Throws when the recorder cannot start. */
    fun start(file: File)

    /** Seconds recorded so far. */
    fun currentTime(): Double

    /** Stops and returns the seconds recorded; lets go of the microphone. */
    fun stop(): Double
}

/**
 * One recording: idle → (permission) → recording → recorded, or failed. The file lives in the cache directory and is removed by
 * [discard], [cancel] and a new [start]; the caller that keeps it ([takeFile]) owns it from then on.
 */
class AudioRecorderSession(
    private val backend: RecorderBackend,
    private val directory: File,
    private val scope: CoroutineScope,
    private val isCallActive: () -> Boolean = { false },
    private val sleep: suspend (Double) -> Unit = { seconds -> delay((seconds * 1000).toLong()) },
) {
    sealed interface State {
        data object Idle : State

        data object RequestingPermission : State

        data object Recording : State

        data class Recorded(val duration: Double) : State

        data class Failed(val failure: RecorderFailure) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    private val _elapsed = MutableStateFlow(0.0)
    private var ticker: Job? = null
    private var generation = 0

    val state: StateFlow<State> = _state.asStateFlow()
    val elapsed: StateFlow<Double> = _elapsed.asStateFlow()

    var file: File? = null
        private set

    init {
        backend.onInterrupted = { interrupted() }
    }

    val isRecording: Boolean get() = _state.value == State.Recording

    // MARK: Control

    suspend fun start() {
        removeFile()
        _elapsed.value = 0.0

        if (isCallActive()) {
            _state.value = State.Failed(RecorderFailure.CALL_IN_PROGRESS)
            return
        }

        generation++
        val mine = generation
        _state.value = State.RequestingPermission

        if (!backend.requestPermission()) {
            if (mine == generation) _state.value = State.Failed(RecorderFailure.PERMISSION_DENIED)
            return
        }

        // Cancelled while the permission question was open, or a call started in the meantime.
        if (mine != generation || _state.value != State.RequestingPermission) return

        if (isCallActive()) {
            _state.value = State.Failed(RecorderFailure.CALL_IN_PROGRESS)
            return
        }

        try {
            directory.mkdirs()
            val target = File(directory, "rec-${UUID.randomUUID()}.m4a")
            file = target
            backend.start(target)
            _state.value = State.Recording
            startTicker(mine)
        } catch (_: Exception) {
            removeFile()
            _state.value = State.Failed(RecorderFailure.COULD_NOT_START)
        }
    }

    /** Stops recording and keeps the result (at least a second). */
    fun stop() {
        if (_state.value == State.Recording) finish()
    }

    /** Throws the recording away and goes back to the start. */
    fun cancel() {
        generation++
        ticker?.cancel()
        ticker = null

        if (_state.value == State.Recording) backend.stop()

        removeFile()
        _elapsed.value = 0.0
        _state.value = State.Idle
    }

    /** Throws away a finished recording ("Opnieuw"). */
    fun discard() = cancel()

    /** Hands the file to the caller (which removes it after the upload). The session is idle afterwards. */
    fun takeFile(): File? {
        if (_state.value !is State.Recorded) return null
        val taken = file ?: return null

        file = null
        _elapsed.value = 0.0
        _state.value = State.Idle

        return taken
    }

    /** Called by the owner when a call starts: what is recording stops (the call needs the microphone). */
    fun callActivityChanged() {
        if (_state.value == State.Recording && isCallActive()) finish()
    }

    /** One step of the clock: the limit of five minutes. Public so the tests drive it without waiting. */
    fun tick() {
        if (_state.value != State.Recording) return

        _elapsed.value = minOf(backend.currentTime(), MAX_DURATION)

        if (_elapsed.value >= MAX_DURATION) finish()
    }

    // MARK: Private

    private fun interrupted() {
        if (_state.value == State.Recording) finish()
    }

    private fun finish() {
        ticker?.cancel()
        ticker = null
        val seconds = minOf(backend.stop(), MAX_DURATION)
        _elapsed.value = seconds

        if (seconds < MIN_DURATION) {
            removeFile()
            _state.value = State.Failed(RecorderFailure.TOO_SHORT)
        } else {
            _state.value = State.Recorded(seconds)
        }
    }

    private fun startTicker(mine: Int) {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                sleep(0.25)

                if (!isActive || generation != mine) return@launch
                tick()
            }
        }
    }

    private fun removeFile() {
        file?.delete()
        file = null
    }

    companion object {
        /** Five minutes. */
        const val MAX_DURATION = 300.0
        const val MIN_DURATION = 1.0

        /** Removes recordings an earlier run left behind (a crash while recording). */
        fun purgeStaleFiles(directory: File) {
            directory.deleteRecursively()
        }
    }
}
