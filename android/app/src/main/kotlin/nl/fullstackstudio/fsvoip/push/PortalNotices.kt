// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Notices from the customer portal (a new order, a missed call, ...). They come as an FCM data message (`fsvoip.type = notice`), so
// the app shows the notification itself; a tap opens the screen the link maps to ([nl.fullstackstudio.fsvoip.core.NoticeRoute]).
// The text is the server's one sentence (no names or amounts); the link is never opened as a URL.
//
// Without a Firebase project (`google-services.json`) no notice ever arrives; this code then simply never runs.

package nl.fullstackstudio.fsvoip.push

import androidx.core.content.ContextCompat
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.NoticePush
import nl.fullstackstudio.fsvoip.core.NoticeText
import nl.fullstackstudio.fsvoip.ui.MainActivity

object PortalNotices {
    const val CHANNEL = "fsvoip.notices"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notification_channel_notices), NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Shows the notice. Does nothing (and breaks nothing) when notifications are switched off. */
    fun show(context: Context, notice: NoticePush) {
        val manager = NotificationManagerCompat.from(context)

        if (!manager.areNotificationsEnabled()) return

        createChannel(context)

        val title = NoticeText.clean(notice.title, NoticeText.MAX_TITLE).ifEmpty { context.getString(R.string.app_name) }
        val body = NoticeText.clean(notice.body, NoticeText.MAX_BODY)
        val id = (notice.accountId + "|" + notice.href).hashCode()
        val open = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_NOTICE)
            .putExtra(MainActivity.EXTRA_NOTICE_ACCOUNT, notice.accountId)
            .putExtra(MainActivity.EXTRA_NOTICE_HREF, notice.href)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val built = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

        // Android 13+: without POST_NOTIFICATIONS the notice is dropped, never shown.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { manager.notify(id, built) }
    }
}
