// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import nl.fullstackstudio.fsvoip.core.AudioBackend
import nl.fullstackstudio.fsvoip.core.AudioBackendEvent
import nl.fullstackstudio.fsvoip.core.AudioSource
import nl.fullstackstudio.fsvoip.core.FsLogger

/**
 * Plays a downloaded recording or voicemail file with `MediaPlayer` (speech on the media stream). Needs the audio focus: another
 * app, an alarm or a call that takes it pauses the sound ([AudioBackendEvent.Interrupted]). Everything runs on the main thread.
 */
class MediaPlayerBackend(context: Context, private val logger: FsLogger = FsLogger("player")) : AudioBackend {
    override var onEvent: ((AudioBackendEvent) -> Unit)? = null

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var prepared = false
    private var focusRequest: AudioFocusRequest? = null
    private var ticking = false

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val ticker = object : Runnable {
        override fun run() {
            val current = player

            if (!ticking || current == null || !prepared) return

            runCatching { emit(AudioBackendEvent.Progress(current.currentPosition / 1000.0)) }
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun load(source: AudioSource) {
        release()

        val file = (source as? AudioSource.LocalFile)?.file

        if (file == null) {
            // This backend plays files only (the audio is downloaded through the API client first).
            handler.post { emit(AudioBackendEvent.Failed) }

            return
        }

        val next = MediaPlayer()
        player = next
        prepared = false

        try {
            next.setAudioAttributes(attributes)
            next.setOnPreparedListener {
                if (player !== next) return@setOnPreparedListener

                prepared = true
                emit(AudioBackendEvent.Ready(next.duration.takeIf { it > 0 }?.let { it / 1000.0 }))
            }
            next.setOnCompletionListener {
                if (player !== next) return@setOnCompletionListener

                stopTicker()
                emit(AudioBackendEvent.Ended)
            }
            next.setOnErrorListener { _, what, extra ->
                if (player === next) {
                    logger.notice("Player error $what/$extra")
                    stopTicker()
                    emit(AudioBackendEvent.Failed)
                }

                true
            }
            next.setDataSource(file.absolutePath)
            next.prepareAsync()
        } catch (error: Exception) {
            logger.notice("Player could not load: ${error.javaClass.simpleName}")
            handler.post { if (player === next) emit(AudioBackendEvent.Failed) }
        }
    }

    override fun play(rate: Double) {
        val current = player ?: return

        if (!prepared) return

        requestFocus()

        try {
            // Setting the speed while paused can start the sound on some versions: only do it right before `start`.
            current.playbackParams = PlaybackParams().setSpeed(rate.toFloat())
            current.start()
            startTicker()
        } catch (error: IllegalStateException) {
            emit(AudioBackendEvent.Failed)
        }
    }

    override fun pause() {
        stopTicker()
        runCatching { player?.takeIf { prepared && it.isPlaying }?.pause() }
    }

    override fun seek(seconds: Double) {
        val current = player ?: return

        if (!prepared) return

        runCatching { current.seekTo((seconds * 1000).toLong(), MediaPlayer.SEEK_CLOSEST) }
    }

    override fun setRate(rate: Double) {
        val current = player ?: return

        if (!prepared || !current.isPlaying) return

        runCatching { current.playbackParams = PlaybackParams().setSpeed(rate.toFloat()) }
    }

    override fun stop() {
        release()
    }

    // MARK: Plumbing

    private fun release() {
        stopTicker()
        handler.removeCallbacksAndMessages(null)
        val old = player
        player = null
        prepared = false

        if (old != null) {
            old.setOnPreparedListener(null)
            old.setOnCompletionListener(null)
            old.setOnErrorListener(null)
            runCatching { old.reset() }
            runCatching { old.release() }
        }

        abandonFocus()
    }

    private fun emit(event: AudioBackendEvent) {
        onEvent?.invoke(event)
    }

    private fun startTicker() {
        if (ticking) return

        ticking = true
        handler.postDelayed(ticker, TICK_MILLIS)
    }

    private fun stopTicker() {
        ticking = false
        handler.removeCallbacks(ticker)
    }

    private fun requestFocus() {
        val existing = focusRequest

        if (existing != null) {
            audioManager.requestAudioFocus(existing)

            return
        }

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    pause()
                    emit(AudioBackendEvent.Interrupted)
                }
            }
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private companion object {
        const val TICK_MILLIS = 250L
    }
}
