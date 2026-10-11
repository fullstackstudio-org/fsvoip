// SPDX-License-Identifier: AGPL-3.0-or-later
//
// A `notice` push (a message from the customer portal: a new order, a missed call, ...) is an FCM data message, so the app shows the
// notification itself. It carries a title and one sentence, never personal data. Tapping it opens the screen the link maps to
// ([nl.fullstackstudio.fsvoip.core.NoticeRoute]); the app never opens the link.

package nl.fullstackstudio.fsvoip.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.FsLogger
import nl.fullstackstudio.fsvoip.core.NoticePush
import nl.fullstackstudio.fsvoip.ui.MainActivity

object NoticeNotifications {
    const val CHANNEL = "fsvoip.notices"
    private val logger = FsLogger("push")

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notification_channel_notices), NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
    }

    /** The intent a tap on the notification starts: the main screen with the account and the link (read for its PATH only). */
    fun openIntent(context: Context, notice: NoticePush): Intent = Intent(context, MainActivity::class.java).apply {
        action = MainActivity.ACTION_OPEN_NOTICE
        putExtra(MainActivity.EXTRA_NOTICE_ACCOUNT, notice.accountId)
        putExtra(MainActivity.EXTRA_NOTICE_HREF, notice.href)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

    fun build(context: Context, notice: NoticePush): Notification {
        val open = PendingIntent.getActivity(context, notice.href.hashCode(), openIntent(context, notice), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(notice.title)
            .setContentText(notice.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
    }

    /** Shows the notification. Without the notification permission nothing shows and nothing breaks; calls still ring. */
    fun show(context: Context, notice: NoticePush) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            logger.notice("Notice not shown: no notification permission")
            return
        }

        createChannel(context)

        try {
            // One notification per link: a repeat of the same message replaces it instead of stacking.
            NotificationManagerCompat.from(context).notify("notice", notice.href.hashCode(), build(context, notice))
        } catch (error: SecurityException) {
            logger.notice("Notice not shown: ${error.javaClass.simpleName}")
        }
    }
}
