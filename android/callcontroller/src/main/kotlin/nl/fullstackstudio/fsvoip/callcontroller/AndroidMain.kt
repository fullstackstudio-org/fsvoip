// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.callcontroller

import android.os.Handler
import android.os.Looper

/** The main thread of the app, as [MainExecutor] and [CallScheduler] for [PhoneController]. */
object AndroidMain {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    val executor = MainExecutor { block ->
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    val scheduler = CallScheduler { delayMillis, action ->
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMillis)
        val cancel: () -> Unit = { handler.removeCallbacks(runnable) }
        cancel
    }
}
