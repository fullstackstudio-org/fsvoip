// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip

import android.app.Application

class FsVoipApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 🚨 The services exist before anything else runs: an FCM `ring` that started the process must find the SIP
        // engine, Telecom and the phone ready (the Android twin of iOS creating them in didFinishLaunching).
        AppServices.init(this)
    }
}
