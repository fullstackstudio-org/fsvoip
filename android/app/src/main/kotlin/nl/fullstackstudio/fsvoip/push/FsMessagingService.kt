// SPDX-License-Identifier: AGPL-3.0-or-later
//
// FCM data messages (plan D9/D11). The PBX push gate → FSS → FCM (priority HIGH, ttl 0) → here:
//   `ring`    : start the phone-call foreground service at once, report the call to Telecom, wake the pushed account
//               (a fresh REGISTER the gate waits for); the INVITE with X-FSS-Call joins the call.
//   `revoked` : remove the account.
//   `refresh` : re-read `GET /me`.
// The payload never contains a secret; it is never logged.

package nl.fullstackstudio.fsvoip.push

import android.os.Handler
import android.os.Looper
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import nl.fullstackstudio.fsvoip.AppServices
import nl.fullstackstudio.fsvoip.call.CallService
import nl.fullstackstudio.fsvoip.core.FsLogger
import nl.fullstackstudio.fsvoip.core.PushMessage

class FsMessagingService : FirebaseMessagingService() {
    private val logger = FsLogger("push")
    private val main = Handler(Looper.getMainLooper())

    override fun onNewToken(token: String) {
        main.post { AppServices.get(this).pushTokenChanged(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val decoded = runCatching { PushMessage.decodeFcm(message.data) }
            .onFailure { logger.notice("Push without a readable payload: ${it.javaClass.simpleName}") }
            .getOrNull()

        when (decoded) {
            is PushMessage.Ring, null -> {
                if (decoded == null && message.data["fsvoip"] == null) {
                    return
                }

                // 🚨 First the foreground service (allowed right now thanks to the high-priority message), then the call.
                CallService.startForIncoming(this)
                main.post { AppServices.get(this).phone.handleRingPush(decoded) }
            }
            is PushMessage.Revoked, is PushMessage.Refresh -> main.post { AppServices.get(this).model.handleNotice(decoded) }
        }
    }
}
