// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Keeps the screen out of screenshots, screen recordings and the recent-apps thumbnail while it is shown. For what is a credential or
 * a private conversation: the invite link (QR) and the call recordings.
 */
@Composable
fun SecureScreen() {
    val context = LocalContext.current

    DisposableEffect(context) {
        val window = context.findActivity()?.window

        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this

    while (current is ContextWrapper) {
        if (current is Activity) return current

        current = current.baseContext
    }

    return null
}
