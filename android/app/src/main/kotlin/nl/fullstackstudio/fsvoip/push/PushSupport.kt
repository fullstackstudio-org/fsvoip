// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import nl.fullstackstudio.fsvoip.BuildConfig
import nl.fullstackstudio.fsvoip.core.FsLogger

/**
 * Firebase Cloud Messaging is optional. A build without `app/google-services.json` has no Firebase project: Firebase
 * does not initialise, no token is fetched and calls only ring while the app is open (the account is registered then).
 */
object PushSupport {
    private val logger = FsLogger("push")

    fun isAvailable(context: Context): Boolean =
        BuildConfig.FIREBASE_CONFIGURED && runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    /** Ask Firebase for the current token; [onToken] runs on the main thread (only when Firebase is configured). */
    fun fetchToken(context: Context, onToken: (String?) -> Unit) {
        if (!isAvailable(context)) {
            logger.notice("Firebase is not configured in this build: no push")
            return
        }

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                onToken(task.result)
            } else {
                logger.notice("No FCM token: ${task.exception?.javaClass?.simpleName}")
            }
        }
    }
}
