// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The system check behind [nl.fullstackstudio.fsvoip.core.LocalAccessGate]: fingerprint or face first, the screen lock as the
// fallback (`BiometricPrompt`, the Android side of iOS Face ID / `deviceOwnerAuthentication`). Needs the visible activity.

package nl.fullstackstudio.fsvoip

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import nl.fullstackstudio.fsvoip.core.LocalAuthAvailability
import nl.fullstackstudio.fsvoip.core.LocalAuthResult
import nl.fullstackstudio.fsvoip.core.LocalAuthenticating

class BiometricAuthenticator(context: Context) : LocalAuthenticating {
    private val appContext = context.applicationContext
    private var activity: WeakReference<FragmentActivity>? = null

    fun attach(activity: FragmentActivity) {
        this.activity = WeakReference(activity)
    }

    fun detach(activity: FragmentActivity) {
        if (this.activity?.get() === activity) this.activity = null
    }

    override fun availability(): LocalAuthAvailability = when (BiometricManager.from(appContext).canAuthenticate(AUTHENTICATORS)) {
        BiometricManager.BIOMETRIC_SUCCESS -> LocalAuthAvailability.AVAILABLE
        // With the screen lock allowed, "none enrolled" means the phone has no PIN, pattern, password or biometric at all.
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> LocalAuthAvailability.NO_SCREEN_LOCK
        else -> LocalAuthAvailability.UNAVAILABLE
    }

    override suspend fun evaluate(reason: String): LocalAuthResult {
        val host = activity?.get() ?: return LocalAuthResult.UNAVAILABLE

        return suspendCancellableCoroutine { continuation ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(LocalAuthResult.SUCCESS)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val outcome = when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED -> LocalAuthResult.CANCELLED
                        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, BiometricPrompt.ERROR_HW_NOT_PRESENT, BiometricPrompt.ERROR_HW_UNAVAILABLE -> LocalAuthResult.UNAVAILABLE
                        else -> LocalAuthResult.FAILED
                    }

                    if (continuation.isActive) continuation.resume(outcome)
                }
            }

            val prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(appContext), callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(appContext.getString(R.string.app_name))
                .setSubtitle(reason)
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()

            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            prompt.authenticate(info)
        }
    }

    private companion object {
        // Strong biometrics with the screen lock is only supported from Android 11; weak + screen lock works on every version we support.
        const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
