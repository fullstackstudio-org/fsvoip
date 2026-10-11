// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The "App" group of the settings: Weergave, Meldingen and Over FSVoip.

package nl.fullstackstudio.fsvoip.ui.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.BuildConfig
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.push.PushSupport
import nl.fullstackstudio.fsvoip.ui.InfoRow
import nl.fullstackstudio.fsvoip.ui.LinkRow
import nl.fullstackstudio.fsvoip.ui.SubpageScaffold
import nl.fullstackstudio.fsvoip.ui.SwitchRow
import nl.fullstackstudio.fsvoip.ui.ValueRow
import nl.fullstackstudio.fsvoip.ui.section

@Composable
fun AppearanceScreen(back: () -> Unit) {
    val context = LocalContext.current
    val current by Appearance.current.collectAsStateWithLifecycle()

    SubpageScaffold(stringResource(R.string.snd_settings_appearance), back) {
        section(null, stringResource(R.string.snd_settings_appearance_footer)) {
            for (option in listOf(AppearancePreference.SYSTEM, AppearancePreference.LIGHT, AppearancePreference.DARK)) {
                ChoiceRow(stringResource(option.titleRes), null, selected = option == current, modifier = Modifier.testTag("appearance-${option.key}")) { Appearance.set(context, option) }
            }
        }
    }
}

/** Meldingen: what Android needs to ring with the app closed (notifications, full screen, battery) and the names from the contacts. */
@Composable
fun NotificationsScreen(model: AppModel, back: () -> Unit) {
    val context = LocalContext.current
    model.settingsRevision.collectAsStateWithLifecycle().value
    var resumes by remember { mutableIntStateOf(0) }

    // The system settings can change while the app is away: look again when it comes back.
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }

    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.useDeviceContacts = granted
    }

    SubpageScaffold(stringResource(R.string.settings_notifications), back) {
        section(stringResource(R.string.settings_device), stringResource(R.string.snd_settings_notifications_footer)) {
            @Suppress("UNUSED_EXPRESSION") resumes // read: recompose after every resume
            val notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()

            if (!PushSupport.isAvailable(context)) {
                InfoRow(stringResource(R.string.settings_push_unavailable))
            }

            LinkRow(
                stringResource(R.string.settings_notifications),
                if (notificationsOn) stringResource(R.string.snd_settings_notifications_open) else stringResource(R.string.settings_notifications_off),
                warn = !notificationsOn,
            ) { openNotificationSettings(context) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val fullScreen = context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true
                LinkRow(
                    stringResource(R.string.settings_fullScreen),
                    stringResource(if (fullScreen) R.string.settings_fullScreen_on else R.string.settings_fullScreen_off),
                    warn = !fullScreen,
                ) { openFullScreenSettings(context) }
            }

            val unrestricted = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            LinkRow(
                stringResource(R.string.settings_battery),
                stringResource(if (unrestricted) R.string.settings_battery_ok else R.string.settings_battery_body),
                warn = !unrestricted,
            ) { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

            val contactsGranted = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            SwitchRow(stringResource(R.string.settings_contacts), stringResource(R.string.settings_contacts_footer), checked = model.useDeviceContacts && contactsGranted, modifier = Modifier.testTag("device-contacts-toggle")) { on ->
                if (on && !contactsGranted) contactsPermission.launch(Manifest.permission.READ_CONTACTS) else model.useDeviceContacts = on
            }
        }
    }
}

@Composable
fun AboutScreen(back: () -> Unit, openLicenses: () -> Unit) {
    SubpageScaffold(stringResource(R.string.snd_settings_aboutRow), back) {
        section(stringResource(R.string.settings_about), stringResource(R.string.settings_about_footer)) {
            ValueRow(stringResource(R.string.settings_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            LinkRow(stringResource(R.string.settings_licenses), null, onClick = openLicenses)
        }
    }
}

private fun openNotificationSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun openFullScreenSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { openNotificationSettings(context) }
    }
}

