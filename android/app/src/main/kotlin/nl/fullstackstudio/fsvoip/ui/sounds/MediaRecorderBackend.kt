// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.sounds

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.core.RecorderBackend

/**
 * Records with `MediaRecorder`: AAC, 44,1 kHz, mono, 64 kbps, in an `.m4a` file (the same as iOS; the server reads the real type from
 * the first bytes). The microphone belongs to a call: the session refuses to start during one. Audio focus is asked while recording
 * and losing it (a call, an alarm) stops the recording and keeps what was recorded.
 */
class MediaRecorderBackend(context: Context, private val model: AppModel) : RecorderBackend {
    private val context = context.applicationContext
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private var recorder: MediaRecorder? = null
    private var startedAt = 0L
    private var focus: AudioFocusRequest? = null
    private val onFocusChange = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onInterrupted?.invoke()
    }

    override var onInterrupted: (() -> Unit)? = null

    override suspend fun requestPermission(): Boolean = model.permissions?.requestMicrophone() ?: false

    override fun start(file: File) {
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()

        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44_100)
            mr.setAudioChannels(1)
            mr.setAudioEncodingBitRate(64_000)
            mr.setOutputFile(file.path)
            mr.prepare()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(onFocusChange)
                .build()
            focus = request
            audio.requestAudioFocus(request)

            mr.start()
        } catch (error: Exception) {
            release(mr)
            throw error
        }

        recorder = mr
        startedAt = SystemClock.elapsedRealtime()
    }

    override fun currentTime(): Double = if (recorder == null) 0.0 else (SystemClock.elapsedRealtime() - startedAt) / 1000.0

    override fun stop(): Double {
        val seconds = currentTime()
        val mr = recorder ?: return seconds

        recorder = null
        // `stop()` throws when nothing was recorded (stopped at once): the file is then useless, which the session sees as "too short".
        runCatching { mr.stop() }
        release(mr)

        return seconds
    }

    private fun release(mr: MediaRecorder) {
        runCatching { mr.release() }
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }
}
