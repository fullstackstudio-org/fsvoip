// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.demo

import android.content.Context
import java.io.File
import nl.fullstackstudio.fsvoip.BuildConfig

/**
 * Demo mode (the Android twin of the iOS `-FSVoipDemo` launch argument): the app runs against an in-memory fake of the
 * FullStack Studio API and a fake SIP engine, with example accounts. Nothing leaves the phone. Debug builds only.
 *
 * Switch it on with files in the app's private storage (works on an emulator, no intent needed):
 *
 * ```
 * adb shell "run-as nl.fullstackstudio.fsvoip sh -c 'echo full > files/demo-mode; echo recents > files/demo-screen'"
 * adb shell am force-stop nl.fullstackstudio.fsvoip && adb shell am start -n nl.fullstackstudio.fsvoip/.ui.MainActivity
 * ```
 *
 * `demo-mode`: `full` (two accounts, the first an administrator, the second a plain user) or `onboarding` (no accounts).
 * `demo-screen`: which screen to open, see [DemoScreen]. Remove both files to leave demo mode.
 */
class DemoConfig(val mode: String, val screen: String?) {
    val withAccounts: Boolean get() = mode != "onboarding"

    companion object {
        fun load(context: Context): DemoConfig? {
            if (!BuildConfig.DEBUG) return null

            val dir = context.filesDir
            val mode = File(dir, "demo-mode").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val screen = File(dir, "demo-screen").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

            return DemoConfig(mode, screen)
        }
    }
}
