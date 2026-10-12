// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.call

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Rings for an incoming call. A self-managed Telecom app rings itself (the SDK's own ringing is off: the call can be on
 * the screen from a push before its INVITE exists). The phone's ringtone, its volume and its ringer mode apply; Do Not
 * Disturb (any filter other than "all") keeps it silent.
 */
class Ringer(context: Context) {
    private val context = context.applicationContext
    private var ringtone: Ringtone? = null
    private var vibrating = false

    val isRinging: Boolean
        get() = ringtone != null || vibrating

    fun start() {
        if (isRinging) {
            return
        }

        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val filter = notifications?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL

        if (filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            return
        }

        val mode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            ringtone = runCatching {
                RingtoneManager.getRingtone(context, RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE))?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                    play()
                }
            }.getOrNull()
        }

        if (mode != AudioManager.RINGER_MODE_SILENT) {
            vibrator()?.let { vibrator ->
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
                vibrating = true
            }
        }
    }

    fun stop() {
        ringtone?.stop()
        ringtone = null

        if (vibrating) {
            vibrator()?.cancel()
            vibrating = false
        }
    }

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
}
