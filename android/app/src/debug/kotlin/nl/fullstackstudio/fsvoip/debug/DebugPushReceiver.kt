// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import nl.fullstackstudio.fsvoip.AppServices
import nl.fullstackstudio.fsvoip.call.CallService
import nl.fullstackstudio.fsvoip.core.PushMessage

/**
 * DEBUG builds only: handles an FSVoip push exactly like [nl.fullstackstudio.fsvoip.push.FsMessagingService] does, so
 * the Telecom / foreground service / full-screen notification path can be exercised on an emulator without Firebase.
 */
class DebugPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val json = intent.getStringExtra("fsvoip") ?: return
        val message = runCatching { PushMessage.decode(json) }.getOrNull()
        val services = AppServices.get(context)

        when (message) {
            is PushMessage.Ring, null -> {
                CallService.startForIncoming(context)
                services.phone.handleRingPush(message)
            }
            is PushMessage.Revoked, is PushMessage.Refresh -> services.model.handleNotice(message)
        }
    }
}
