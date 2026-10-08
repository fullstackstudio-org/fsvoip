// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The foreground service of a call (type `phoneCall`). It keeps the process and the microphone alive while a call is on
// the screen, rings for an incoming call and carries the call notification:
//   - incoming: `CallStyle.forIncomingCall` with a full-screen intent to the call screen (Android 14+: only when the
//     user allows full-screen notifications; otherwise a heads-up notification, see Settings in the app);
//   - in a call: `CallStyle.forOngoingCall` with a hang-up action.
// It stops itself when no call is left.

package nl.fullstackstudio.fsvoip.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppServices
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.callcontroller.CallDisplay
import nl.fullstackstudio.fsvoip.callcontroller.CallSession
import nl.fullstackstudio.fsvoip.core.FsLogger
import nl.fullstackstudio.fsvoip.ui.MainActivity

class CallService : Service() {
    private val logger = FsLogger("call-service")
    private val scope = MainScope()
    private var observer: Job? = null
    private var stopJob: Job? = null
    private lateinit var ringer: Ringer

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ringer = Ringer(this)
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 🚨 Foreground at once, before anything can fail (Android kills an app that does not).
        startInForeground(placeholder())

        if (intent?.action == ACTION_SILENCE) {
            ringer.stop()
        }

        if (observer == null) {
            observe()
        }

        return START_NOT_STICKY
    }

    private fun observe() {
        val phone = AppServices.get(this).phone

        observer = scope.launch {
            combine(phone.sessions, phone.lastEnded) { sessions, ended -> sessions to ended }.collect { (sessions, _) ->
                val session = sessions.lastOrNull()

                if (session == null) {
                    ringer.stop()
                    // A push may still be on its way to the phone (the service starts first): wait a moment.
                    if (stopJob == null) {
                        stopJob = scope.launch {
                            delay(3_000)
                            if (phone.sessions.value.isEmpty()) stopNow()
                        }
                    }
                    return@collect
                }

                stopJob?.cancel()
                stopJob = null

                if (session.phase == CallSession.Phase.Incoming) ringer.start() else ringer.stop()
                notify(session)
            }
        }
    }

    private fun notify(session: CallSession) {
        val notification = if (session.phase == CallSession.Phase.Incoming) incomingNotification(session) else ongoingNotification(session)
        startInForeground(notification)
    }

    private fun startInForeground(notification: Notification) {
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (error: Exception) {
            logger.error("Foreground service refused: ${error.javaClass.simpleName}")
        }
    }

    private fun stopNow() {
        ringer.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ringer.stop()
        scope.cancel()
        super.onDestroy()
    }

    // MARK: Notifications

    private fun placeholder(): Notification = NotificationCompat.Builder(this, CHANNEL_ONGOING)
        .setSmallIcon(R.drawable.ic_stat_call)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.notification_starting))
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setOngoing(true)
        .setSilent(true)
        .build()

    private fun caller(session: CallSession): Person = Person.Builder()
        .setName(CallDisplay.callerTitle(session.remoteName, session.remoteNumber, getString(R.string.call_anonymous)))
        .setImportant(true)
        .build()

    private fun incomingNotification(session: CallSession): Notification {
        val open = callScreenIntent(session, answer = false)
        val answer = callScreenIntent(session, answer = true)
        val decline = actionIntent(session, CallActionReceiver.ACTION_DECLINE)

        return NotificationCompat.Builder(this, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(getString(R.string.notification_incoming_title))
            .setContentText(getString(R.string.call_line_incoming, session.accountLabel))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setSilent(true) // The Ringer rings (phone ringtone, looping); the channel itself has no sound.
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller(session), decline, answer))
            .build()
    }

    private fun ongoingNotification(session: CallSession): Notification {
        val hangUp = actionIntent(session, CallActionReceiver.ACTION_HANG_UP)

        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(getString(R.string.notification_ongoing_title, CallDisplay.callerTitle(session.remoteName, session.remoteNumber, getString(R.string.call_anonymous))))
            .setContentText(getString(R.string.notification_ongoing_text, session.accountLabel))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(callScreenIntent(session, answer = false))
            .setUsesChronometer(session.connectedAt != null)
            .setWhen(session.connectedAt?.toEpochMilli() ?: System.currentTimeMillis())
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(caller(session), hangUp))
            .build()
    }

    private fun callScreenIntent(session: CallSession, answer: Boolean): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = if (answer) MainActivity.ACTION_ANSWER else MainActivity.ACTION_SHOW_CALL
            putExtra(MainActivity.EXTRA_CALL_UUID, session.id.toString())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        return PendingIntent.getActivity(this, if (answer) 2 else 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionIntent(session: CallSession, action: String): PendingIntent {
        val intent = Intent(this, CallActionReceiver::class.java).apply {
            this.action = action
            putExtra(MainActivity.EXTRA_CALL_UUID, session.id.toString())
        }

        return PendingIntent.getBroadcast(this, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val NOTIFICATION_ID = 7001
        const val CHANNEL_INCOMING = "fsvoip.incoming"
        const val CHANNEL_ONGOING = "fsvoip.ongoing"
        private const val ACTION_SILENCE = "nl.fullstackstudio.fsvoip.SILENCE"

        private val logger = FsLogger("call-service")

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val incoming = NotificationChannel(CHANNEL_INCOMING, context.getString(R.string.notification_channel_incoming), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val ongoing = NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.notification_channel_ongoing), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannels(listOf(incoming, ongoing))
        }

        /** From the FCM handler: the high-priority message allows a foreground service start right now. */
        fun startForIncoming(context: Context) = start(context, null)

        /** A call is on the screen: make sure the service runs (no-op when it does). */
        fun ensureRunning(context: Context) = start(context, null)

        /** Telecom asked to silence the ringer (volume key). */
        fun silence(context: Context) = start(context, ACTION_SILENCE)

        private fun start(context: Context, action: String?) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, CallService::class.java).setAction(action))
            } catch (error: Exception) {
                // Android refuses a foreground service start from the background outside the allowed moments.
                logger.notice("Call service not started: ${error.javaClass.simpleName}")
            }
        }
    }
}
