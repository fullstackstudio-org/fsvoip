// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.sounds

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.core.SoundFailure
import nl.fullstackstudio.fsvoip.core.SoundPlayer

/** Plays one sound at a time with `MediaPlayer`. The audio is first fetched as a local file by the caller's loader. Main thread only. */
class MediaSoundPlayer(private val scope: CoroutineScope) : SoundPlayer {
    private val _state = MutableStateFlow(SoundPlayer.PlayerState())
    private var player: MediaPlayer? = null
    private var loadJob: Job? = null
    private var ticker: Job? = null

    override val state: StateFlow<SoundPlayer.PlayerState> = _state.asStateFlow()

    override fun play(id: String, load: suspend () -> File) {
        release()
        _state.value = SoundPlayer.PlayerState(currentId = id, status = SoundPlayer.Status.LOADING)

        loadJob = scope.launch {
            val file = try {
                load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = SoundPlayer.PlayerState(currentId = id, status = SoundPlayer.Status.FAILED, failure = SoundFailure.classify(error))
                return@launch
            }

            start(id, file)
        }
    }

    private fun start(id: String, file: File) {
        val mp = MediaPlayer()

        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(file.path)
            mp.setOnPreparedListener {
                if (player !== mp) return@setOnPreparedListener
                it.start()
                _state.value = SoundPlayer.PlayerState(id, SoundPlayer.Status.PLAYING, 0, it.duration.takeIf { d -> d > 0 })
                startTicker(mp)
            }
            mp.setOnCompletionListener {
                if (player !== mp) return@setOnCompletionListener
                ticker?.cancel()
                _state.value = SoundPlayer.PlayerState(id, SoundPlayer.Status.PAUSED, 0, it.duration.takeIf { d -> d > 0 })
            }
            mp.setOnErrorListener { _, _, _ ->
                if (player === mp) {
                    ticker?.cancel()
                    _state.value = SoundPlayer.PlayerState(id, SoundPlayer.Status.FAILED)
                }
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (error: Exception) {
            mp.release()
            player = null
            _state.value = SoundPlayer.PlayerState(id, SoundPlayer.Status.FAILED)
        }
    }

    private fun startTicker(mp: MediaPlayer) {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && player === mp) {
                val current = _state.value

                if (current.status == SoundPlayer.Status.PLAYING) {
                    _state.value = current.copy(positionMs = runCatching { mp.currentPosition }.getOrDefault(current.positionMs))
                }

                delay(250)
            }
        }
    }

    override fun togglePlayPause() {
        val mp = player ?: return
        val current = _state.value

        try {
            when (current.status) {
                SoundPlayer.Status.PLAYING -> {
                    mp.pause()
                    _state.value = current.copy(status = SoundPlayer.Status.PAUSED, positionMs = mp.currentPosition)
                }
                SoundPlayer.Status.PAUSED -> {
                    if (current.positionMs == 0 || mp.currentPosition >= mp.duration - 50) mp.seekTo(0)
                    mp.start()
                    _state.value = current.copy(status = SoundPlayer.Status.PLAYING)
                    startTicker(mp)
                }
                else -> Unit
            }
        } catch (error: IllegalStateException) {
            _state.value = current.copy(status = SoundPlayer.Status.FAILED)
        }
    }

    override fun seekTo(positionMs: Int) {
        val mp = player ?: return

        runCatching { mp.seekTo(positionMs) }
        _state.value = _state.value.copy(positionMs = positionMs)
    }

    override fun stop() {
        release()
        _state.value = SoundPlayer.PlayerState()
    }

    private fun release() {
        loadJob?.cancel()
        loadJob = null
        ticker?.cancel()
        ticker = null
        player?.let { runCatching { it.release() } }
        player = null
    }
}
