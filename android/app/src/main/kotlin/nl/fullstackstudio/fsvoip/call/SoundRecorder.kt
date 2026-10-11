// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Records a sound with the microphone for "Geluiden" (AAC in an MPEG-4 file, which the PBX accepts as M4A). The rules (five minutes at
// most, one second at least, never during a call) are in [nl.fullstackstudio.fsvoip.core.SoundRecordingRules].

package nl.fullstackstudio.fsvoip.call

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

class SoundRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var startedAt = 0L

    var file: File? = null
        private set

    val isRecording: Boolean
        get() = recorder != null

    /** Seconds recorded so far. */
    val elapsedSeconds: Double
        get() = if (recorder == null) 0.0 else (SystemClock.elapsedRealtime() - startedAt) / 1000.0

    /** Starts a new recording in the cache folder. `false` when the microphone could not be opened. */
    fun start(): Boolean {
        cancel()

        val target = File(context.cacheDir, "fsvoip-sound-${System.currentTimeMillis()}.m4a")

        @Suppress("DEPRECATION")
        val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

        return try {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioChannels(1)
            next.setAudioSamplingRate(44_100)
            next.setAudioEncodingBitRate(96_000)
            next.setOutputFile(target.absolutePath)
            next.prepare()
            next.start()

            recorder = next
            file = target
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (_: Exception) {
            runCatching { next.release() }
            target.delete()
            false
        }
    }

    /** Stops and returns the seconds recorded (the file is in [file]); `0.0` when nothing usable came out. */
    fun stop(): Double {
        val active = recorder ?: return 0.0
        val seconds = elapsedSeconds
        recorder = null

        return try {
            active.stop()
            seconds
        } catch (_: RuntimeException) {
            // Stopped too soon: the file holds nothing.
            file?.delete()
            file = null
            0.0
        } finally {
            runCatching { active.release() }
        }
    }

    /** Hands the file over: the recorder forgets it and no longer deletes it. */
    fun detach() {
        file = null
    }

    /** Lets go of everything and removes the file. */
    fun cancel() {
        recorder?.let { active ->
            recorder = null
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        file?.delete()
        file = null
    }
}
