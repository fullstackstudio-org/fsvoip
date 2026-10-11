// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppServices
import nl.fullstackstudio.fsvoip.PermissionRequester

// A FragmentActivity (still a ComponentActivity) because `BiometricPrompt` needs one.
class MainActivity : FragmentActivity(), PermissionRequester {
    private val services by lazy { AppServices.get(this) }
    private var pendingPermission: CompletableDeferred<Boolean>? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingPermission?.complete(granted)
        pendingPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        services.model.permissions = this
        LocalAuthHost.activity = this
        nl.fullstackstudio.fsvoip.ui.settings.Appearance.load(this)

        setContent {
            FsVoipTheme {
                RootScreen(services.model)
            }
        }

        // The system bars follow the chosen appearance (light/dark), not only the system setting.
        lifecycleScope.launch {
            nl.fullstackstudio.fsvoip.ui.settings.Appearance.current.collect { preference ->
                val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (preference.resolveDark(systemDark)) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
        }

        // A call on the screen may show over the lock screen and turn the screen on; otherwise this is a normal app.
        lifecycleScope.launch {
            combine(services.phone.sessions, services.phone.lastEnded) { sessions, ended -> sessions.isNotEmpty() || ended != null }.collect { inCall ->
                setShowWhenLocked(inCall)
                setTurnScreenOn(inCall)
            }
        }

        handle(intent)

        // Demo mode (debug builds): open the screen the demo config asks for, once per process.
        services.demo?.let { demo ->
            val engine = services.demoEngine
            if (engine != null && !demoScreenOpened) {
                demoScreenOpened = true
                nl.fullstackstudio.fsvoip.demo.DemoMode.openScreen(demo.screen, services.model, engine)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        if (services.model.permissions === this) services.model.permissions = null
        if (LocalAuthHost.activity === this) LocalAuthHost.activity = null
        super.onDestroy()
    }

    private fun handle(intent: Intent?) {
        intent ?: return

        when (intent.action) {
            // https://fullstackstudio.nl/fsvoip/pair?t=… (App Link) and fsvoip://pair?t=…
            Intent.ACTION_VIEW -> intent.dataString?.let { services.model.handleIncoming(it) }
            ACTION_ANSWER -> callUuid(intent)?.let { services.phone.answer(it) }
            ACTION_SHOW_CALL -> Unit // The call screen is on top already.
        }

        // Handled once: a configuration change must not answer or pair again.
        setIntent(Intent(this, MainActivity::class.java))
    }

    private fun callUuid(intent: Intent): UUID? = intent.getStringExtra(EXTRA_CALL_UUID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    // MARK: PermissionRequester

    override suspend fun requestMicrophone(): Boolean = request(Manifest.permission.RECORD_AUDIO)

    override suspend fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            request(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private suspend fun request(permission: String): Boolean {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            return true
        }

        pendingPermission?.complete(false)
        val deferred = CompletableDeferred<Boolean>()
        pendingPermission = deferred
        permissionLauncher.launch(permission)

        return deferred.await()
    }

    companion object {
        private var demoScreenOpened = false

        const val ACTION_ANSWER = "nl.fullstackstudio.fsvoip.ANSWER"
        const val ACTION_SHOW_CALL = "nl.fullstackstudio.fsvoip.SHOW_CALL"
        const val EXTRA_CALL_UUID = "nl.fullstackstudio.fsvoip.CALL_UUID"
    }
}
