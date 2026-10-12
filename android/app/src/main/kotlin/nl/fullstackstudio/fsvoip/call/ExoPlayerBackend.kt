// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Plays voicemail (and later recordings) with ExoPlayer. The device token travels in the request headers of the media source, never
// in the URL; ExoPlayer asks for `Range` itself when the user seeks. Everything runs on the main thread.

package nl.fullstackstudio.fsvoip.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.concurrent.TimeUnit
import nl.fullstackstudio.fsvoip.BuildConfig
import nl.fullstackstudio.fsvoip.core.AudioBackend
import nl.fullstackstudio.fsvoip.core.AudioBackendListener
import nl.fullstackstudio.fsvoip.core.MediaFailure
import nl.fullstackstudio.fsvoip.core.MediaSource
import okhttp3.OkHttpClient

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ExoPlayerBackend(context: Context, private val userAgent: String, private val apiBaseUrl: String = BuildConfig.API_BASE_URL) : AudioBackend {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var speed = 1f

    override var listener: AudioBackendListener? = null

    private val poll = object : Runnable {
        override fun run() {
            val current = player ?: return
            listener?.onPosition(current.currentPosition)
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun prepare(source: MediaSource, playWhenReady: Boolean) {
        stop()

        // The token goes to the host of the API only, also after a redirect (see AuthorizedHttpDataSource).
        val http = AuthorizedHttpDataSource.Factory(client, apiBaseUrl, source.headers, userAgent)

        val exo = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> listener?.onReady(exo.duration.takeIf { it != C.TIME_UNSET } ?: 0)
                    Player.STATE_ENDED -> listener?.onEnded()
                    else -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listener?.onPlayingChanged(isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(failure(error))
            }
        })

        player = exo
        exo.setMediaItem(MediaItem.fromUri(source.url))
        exo.setPlaybackSpeed(speed)
        exo.playWhenReady = playWhenReady
        exo.prepare()
        handler.postDelayed(poll, POLL_MS)
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        player?.setPlaybackSpeed(speed)
    }

    override fun stop() {
        handler.removeCallbacks(poll)
        player?.release()
        player = null
    }

    private fun failure(error: PlaybackException): MediaFailure {
        var cause: Throwable? = error

        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                val retryAfter = cause.headerFields.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value?.firstOrNull()?.trim()?.toIntOrNull()

                return MediaFailure.forHttpStatus(cause.responseCode, retryAfter)
            }

            cause = cause.cause
        }

        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> MediaFailure.Offline
            else -> MediaFailure.Other
        }
    }

    private companion object {
        const val POLL_MS = 250L
    }
}
