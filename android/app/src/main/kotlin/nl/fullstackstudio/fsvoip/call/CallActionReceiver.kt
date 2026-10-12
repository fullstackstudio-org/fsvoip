// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.UUID
import nl.fullstackstudio.fsvoip.AppServices
import nl.fullstackstudio.fsvoip.ui.MainActivity

/** Decline / hang up from the call notification (answering opens the call screen instead). */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uuid = intent.getStringExtra(MainActivity.EXTRA_CALL_UUID)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        val phone = AppServices.get(context).phone

        when (intent.action) {
            ACTION_DECLINE, ACTION_HANG_UP -> phone.hangUp(uuid)
        }
    }

    companion object {
        const val ACTION_DECLINE = "nl.fullstackstudio.fsvoip.DECLINE"
        const val ACTION_HANG_UP = "nl.fullstackstudio.fsvoip.HANG_UP"
    }
}
