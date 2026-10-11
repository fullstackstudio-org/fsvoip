// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Local access control with the system prompt (fingerprint, face or the screen lock), for the sensitive screens: "Beheer", the
// recordings and the voicemail of a colleague. The rules (five minutes of inactivity, one shared prompt) live in
// `core/LocalAuth.kt`; this file is the Android side: the `BiometricPrompt`, and the screen that waits for it.

package nl.fullstackstudio.fsvoip.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NoEncryption
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.AppServices
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.LocalAccessGate
import nl.fullstackstudio.fsvoip.core.LocalAccessOutcome
import nl.fullstackstudio.fsvoip.core.LocalAuthAvailability
import nl.fullstackstudio.fsvoip.core.LocalAuthResult
import nl.fullstackstudio.fsvoip.core.LocalAuthenticating

/** The activity that can show a `BiometricPrompt` (set by `MainActivity` while it is on the screen). */
object LocalAuthHost {
    @Volatile
    var activity: FragmentActivity? = null
}

/** `BiometricPrompt` with fingerprint/face first and the screen lock (PIN, pattern, password) as the fallback. */
class BiometricAuthenticator(private val context: Context) : LocalAuthenticating {
    private val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    override fun availability(): LocalAuthAvailability = when (BiometricManager.from(context).canAuthenticate(authenticators)) {
        BiometricManager.BIOMETRIC_SUCCESS -> LocalAuthAvailability.AVAILABLE
        // Nothing enrolled and no screen lock: there is nothing to check.
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> LocalAuthAvailability.NO_PASSCODE
        else -> LocalAuthAvailability.UNAVAILABLE
    }

    override suspend fun evaluate(reason: String): LocalAuthResult {
        val activity = LocalAuthHost.activity ?: return LocalAuthResult.FAILED

        if (availability() != LocalAuthAvailability.AVAILABLE) return LocalAuthResult.UNAVAILABLE

        return suspendCancellableCoroutine { continuation ->
            val executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(LocalAuthResult.SUCCESS)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        val result = when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON -> LocalAuthResult.CANCELLED
                            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, BiometricPrompt.ERROR_HW_NOT_PRESENT, BiometricPrompt.ERROR_HW_UNAVAILABLE -> LocalAuthResult.UNAVAILABLE
                            else -> LocalAuthResult.FAILED
                        }
                        if (continuation.isActive) continuation.resume(result)
                    }
                    // A wrong attempt (onAuthenticationFailed) keeps the prompt open: nothing to do.
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.app_name))
                .setSubtitle(reason)
                .setAllowedAuthenticators(authenticators)
                .build()

            prompt.authenticate(info)
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }
}

/** Demo mode has no biometrics: the gate is always open there (unless [forceRealPrompt], used to look at the real prompt). */
private class AlwaysGranting : LocalAuthenticating {
    override fun availability() = LocalAuthAvailability.AVAILABLE

    override suspend fun evaluate(reason: String) = LocalAuthResult.SUCCESS
}

/**
 * The one local access gate of the app (shared by "Beheer", the recordings and the voicemail of colleagues: one prompt, one five
 * minute window). `val gate = LocalAccess.gate(context, model)`.
 */
object LocalAccess {
    /** Debug only: in demo mode use the real prompt (to see how it looks). */
    @Volatile
    var forceRealPrompt = false

    fun gate(context: Context, model: AppModel): LocalAccessGate {
        val app = context.applicationContext
        val demo = AppServices.get(app).demo != null

        return model.feature { LocalAccessGate(if (demo && !forceRealPrompt) AlwaysGranting() else BiometricAuthenticator(app)) }
    }
}

/**
 * The door of a protected screen: the system prompt first, valid for five minutes together with all other protected screens. A phone
 * without a screen lock cannot open it. When the right goes away ([allowed] false) while the screen is open, it closes. [content] is
 * only composed while the gate is open.
 */
@Composable
fun LocalAccessGateScreen(
    model: AppModel,
    title: String,
    reason: String,
    message: String,
    allowed: Boolean,
    back: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val gate = remember { LocalAccess.gate(context, model) }
    var lock by remember { mutableStateOf(GateLock.CHECKING) }
    val scope = rememberCoroutineScope()

    suspend fun unlock() {
        if (!allowed) return

        lock = when (gate.ensureUnlocked(reason)) {
            LocalAccessOutcome.Unlocked -> GateLock.OPEN
            LocalAccessOutcome.Cancelled, LocalAccessOutcome.Failed -> GateLock.LOCKED
            is LocalAccessOutcome.Unavailable -> GateLock.NO_PASSCODE
        }
    }

    LaunchedEffect(allowed) {
        if (allowed) unlock() else back()
    }

    // Coming back after five idle minutes: ask again before showing anything.
    LifecycleResumeEffect(lock) {
        if (lock == GateLock.OPEN && !gate.isUnlocked) {
            lock = GateLock.CHECKING
            scope.launch { unlock() }
        }
        onPauseOrDispose {}
    }

    when (lock) {
        GateLock.OPEN -> content()
        else -> {
            BackHandler { back() }
            nl.fullstackstudio.fsvoip.ui.SubpageScaffold(title, back) {
                when (lock) {
                    GateLock.CHECKING -> Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.snd_pbx_lock_checking), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    GateLock.LOCKED -> LockCard(Icons.Filled.Lock, stringResource(R.string.snd_media_lock_title), message, stringResource(R.string.snd_pbx_lock_unlock)) { scope.launch { unlock() } }
                    else -> LockCard(Icons.Filled.NoEncryption, stringResource(R.string.snd_pbx_lock_noPasscode_title), stringResource(R.string.snd_media_lock_noPasscode), null) {}
                }
            }
        }
    }
}

private enum class GateLock { CHECKING, LOCKED, OPEN, NO_PASSCODE }

/** The lock picture (also used inside a list for the voicemail of a colleague). */
@Composable
fun LockCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String, buttonTitle: String?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp).testTag("lock-card"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)

        if (buttonTitle != null) {
            PrimaryButton(buttonTitle, onClick = onClick, modifier = Modifier.padding(top = 8.dp).testTag("unlock-button"))
        }
    }
}
