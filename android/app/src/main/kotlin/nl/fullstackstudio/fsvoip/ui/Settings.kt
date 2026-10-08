// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.BuildConfig
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.StoredAccount
import nl.fullstackstudio.fsvoip.push.PushSupport
import nl.fullstackstudio.fsvoip.sipengine.RegistrationState

@Composable
fun SettingsNavigation(model: AppModel, stack: SnapshotStateList<SettingsPage>) {
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }

    when (val page = stack.lastOrNull()) {
        null -> SettingsScreen(model, open = { stack.add(it) })
        is SettingsPage.Account -> AccountDetailScreen(model, page.id, back = { stack.removeAt(stack.lastIndex) })
        SettingsPage.Licenses -> LicensesScreen(back = { stack.removeAt(stack.lastIndex) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(model: AppModel, open: (SettingsPage) -> Unit) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val registrations by model.phone.registrations.collectAsStateWithLifecycle()
    model.settingsRevision.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }

    // The system settings can change while the app is away: look again when it comes back.
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }

    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.useDeviceContacts = granted
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            section(stringResource(R.string.settings_lines), stringResource(R.string.settings_lines_footer)) {
                for (account in accounts) {
                    AccountRow(account, registrations[account.id] ?: RegistrationState.Unregistered) { open(SettingsPage.Account(account.id)) }
                }
                ActionRow(stringResource(R.string.settings_addLine), Icons.Filled.Add, Modifier.testTag("add-line-button")) { model.presentScanner() }
            }

            if (accounts.size > 1) {
                section(stringResource(R.string.settings_calling), stringResource(R.string.settings_defaultLine_footer)) {
                    Text(stringResource(R.string.settings_defaultLine), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    for (account in accounts) {
                        CheckRow(account.displayLabel, checked = model.defaultOutgoingAccountId == account.id) { model.setDefaultOutgoing(account.id) }
                    }
                }
            }

            section(stringResource(R.string.settings_device), null) {
                @Suppress("UNUSED_EXPRESSION") resumes // read: recompose after every resume
                val notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()

                if (!PushSupport.isAvailable(context)) {
                    InfoRow(stringResource(R.string.settings_push_unavailable))
                }

                if (!notificationsOn) {
                    LinkRow(stringResource(R.string.settings_notifications), stringResource(R.string.settings_notifications_off)) { openNotificationSettings(context) }
                }

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
                SwitchRow(stringResource(R.string.settings_contacts), stringResource(R.string.settings_contacts_footer), checked = model.useDeviceContacts && contactsGranted) { on ->
                    if (on && !contactsGranted) contactsPermission.launch(Manifest.permission.READ_CONTACTS) else model.useDeviceContacts = on
                }
            }

            section(stringResource(R.string.settings_about), stringResource(R.string.settings_about_footer)) {
                ValueRow(stringResource(R.string.settings_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                LinkRow(stringResource(R.string.settings_licenses), null) { open(SettingsPage.Licenses) }
            }
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

// MARK: - List building blocks (shared with the account and licence screens)

@Composable
fun section(header: String?, footer: String?, content: @Composable () -> Unit) {
    run {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            if (header != null) {
                Text(header.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            content()
            if (footer != null) {
                Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
fun AccountRow(account: StoredAccount, state: RegistrationState, onClick: () -> Unit) {
    val subtitle = listOfNotNull(state.label(), account.extensionNumber?.let { stringResource(R.string.account_extension, it) }).joinToString(" · ")

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp).testTag("account-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusLight(state, 10.dp)
        Column(Modifier.weight(1f)) {
            Text(account.displayLabel, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun ActionRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, modifier: Modifier = Modifier, color: Color = Color.Unspecified, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).then(modifier).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Icon(icon, contentDescription = null, tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

@Composable
fun LinkRow(title: String, subtitle: String?, warn: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (warn) Brand.hangUp else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun ValueRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun InfoRow(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
fun CheckRow(title: String, checked: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (checked) Icon(Icons.Filled.Check, contentDescription = null)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.then(modifier).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Brand.ink, checkedThumbColor = Brand.lime))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubpageScaffold(title: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_close)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()), content = content)
    }
}
