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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.ui.settings.AboutScreen
import nl.fullstackstudio.fsvoip.ui.settings.AppearanceScreen
import nl.fullstackstudio.fsvoip.ui.settings.CallPreferencesScreen
import nl.fullstackstudio.fsvoip.ui.settings.ChoiceRow
import nl.fullstackstudio.fsvoip.ui.settings.InvitePageGate
import nl.fullstackstudio.fsvoip.ui.settings.LinkScreen
import nl.fullstackstudio.fsvoip.ui.settings.NotificationsScreen
import nl.fullstackstudio.fsvoip.ui.settings.PbxPageGate
import nl.fullstackstudio.fsvoip.ui.settings.ProfileScreen
import nl.fullstackstudio.fsvoip.ui.settings.RecordingsPageGate
import nl.fullstackstudio.fsvoip.ui.settings.SoundsPageGate
import nl.fullstackstudio.fsvoip.ui.settings.UnpairDialogs
import nl.fullstackstudio.fsvoip.ui.settings.ValueLinkRow
import nl.fullstackstudio.fsvoip.ui.settings.rememberUnpairFlow
import nl.fullstackstudio.fsvoip.ui.settings.titleRes
import nl.fullstackstudio.fsvoip.ui.pbx.PbxPart
import nl.fullstackstudio.fsvoip.ui.settings.Appearance
import nl.fullstackstudio.fsvoip.ui.settings.SettingsOutline
import nl.fullstackstudio.fsvoip.ui.settings.settingsHubs

@Composable
fun SettingsNavigation(model: AppModel, stack: SnapshotStateList<SettingsPage>) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    var selectedId by remember { mutableStateOf(model.defaultOutgoingAccountId ?: model.accounts.value.firstOrNull()?.id) }
    val back = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    BackHandler(enabled = stack.isNotEmpty()) { back() }

    // The selected line goes away (unpaired, revoked): fall back to another one; without any, settings has nothing to show.
    LaunchedEffect(accounts) {
        if (accounts.none { it.id == selectedId }) selectedId = accounts.firstOrNull()?.id
        if (accounts.isEmpty()) model.closeSettings()
    }

    when (val page = stack.lastOrNull()) {
        null -> SettingsRoot(model, selectedId, select = { selectedId = it }, open = { stack.add(it) })
        is SettingsPage.Account -> ProfileScreen(model, page.id, back, openLink = { stack.add(SettingsPage.Link(page.id)) })
        is SettingsPage.Profile -> ProfileScreen(model, page.accountId, back, openLink = { stack.add(SettingsPage.Link(page.accountId)) })
        is SettingsPage.CallPreferences -> CallPreferencesScreen(model, page.accountId, back)
        is SettingsPage.Link -> LinkScreen(model, page.accountId, back)
        is SettingsPage.Centrale -> PbxPageGate(model, page.accountId, page.part, back)
        is SettingsPage.Sounds -> SoundsPageGate(model, page.accountId, back)
        is SettingsPage.Invite -> InvitePageGate(model, page.accountId, back)
        is SettingsPage.Recordings -> RecordingsPageGate(model, page.accountId, back)
        SettingsPage.Appearance -> AppearanceScreen(back)
        SettingsPage.Notifications -> NotificationsScreen(model, back)
        SettingsPage.About -> AboutScreen(back, openLicenses = { stack.add(SettingsPage.Licenses) })
        SettingsPage.Licenses -> LicensesScreen(back = back)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRoot(model: AppModel, selectedId: String?, select: (String) -> Unit, open: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val registrations by model.phone.registrations.collectAsStateWithLifecycle()
    val meByAccount by model.meByAccount.collectAsStateWithLifecycle()
    model.settingsRevision.collectAsStateWithLifecycle().value
    val hubs = remember { model.settingsHubs(context) }
    val availabilityStates by hubs.availability.states.collectAsStateWithLifecycle()
    val availabilitySaving by hubs.availability.saving.collectAsStateWithLifecycle()
    val soundsDenied by hubs.soundsDenied.collectAsStateWithLifecycle()
    val appearance by Appearance.current.collectAsStateWithLifecycle()
    val unpair = rememberUnpairFlow()
    val account = accounts.firstOrNull { it.id == selectedId } ?: accounts.firstOrNull()
    val accountId = account?.id
    val state = account?.let { registrations[it.id] } ?: RegistrationState.Unregistered
    val capabilities = meByAccount[accountId]?.capabilities
    val outline = SettingsOutline.of(
        accountCount = accounts.size,
        hasAccount = account != null,
        canOfferAvailability = accountId != null && meByAccount[accountId]?.canEditOwnExtension == true,
        canManagePbx = accountId != null && model.canManagePbx(accountId),
        canManageSounds = accountId != null && model.canManagePbx(accountId) && capabilities?.sounds == nl.fullstackstudio.fsvoip.core.SoundsAccess.MANAGE && accountId !in soundsDenied,
        canInvite = accountId != null && model.canInvite(accountId),
        hasRecordings = capabilities?.recordings == true,
    )

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_settings)) },
            navigationIcon = { IconButton(onClick = { model.closeSettings() }, modifier = Modifier.testTag("settings-close")) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close)) } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            // The lines: a choice when there are several, always the way to add one.
            section(
                if (outline.showsAccountSwitcher) stringResource(R.string.settings_lines) else null,
                if (outline.showsAccountSwitcher) stringResource(R.string.settings_lines_footer) else null,
            ) {
                if (outline.showsAccountSwitcher) {
                    for (item in accounts) {
                        val itemState = registrations[item.id] ?: RegistrationState.Unregistered

                        ChoiceRow(
                            item.displayLabel,
                            itemState.label(),
                            selected = item.id == account?.id,
                            leading = { StatusLight(itemState, 10.dp) },
                            modifier = Modifier.testTag("account-row"),
                        ) { select(item.id) }
                    }
                }

                ActionRow(stringResource(R.string.settings_addLine), Icons.Filled.Add, Modifier.testTag("add-line-button")) { model.presentScanner() }
            }

            if (account != null) {
                if (outline.showsAvailability) {
                    val available = availabilityStates[account.id]?.let { !it.doNotDisturb }
                    val scope = rememberCoroutineScope()

                    section(null, null) {
                        SwitchRow(
                            stringResource(R.string.snd_settings_available),
                            stringResource(if (available == false) R.string.snd_settings_available_off else R.string.snd_settings_available_on),
                            checked = available ?: true,
                            enabled = available != null && account.id !in availabilitySaving,
                            modifier = Modifier.testTag("available-toggle"),
                        ) { value -> scope.launch { hubs.availability.setAvailable(value, account.id) } }
                    }
                }

                section(stringResource(R.string.snd_settings_myAccount), null) {
                    ValueLinkRow(stringResource(R.string.snd_settings_profile), account.displayLabel, Modifier.testTag("settings-row-profile")) { open(SettingsPage.Profile(account.id)) }
                    ValueLinkRow(stringResource(R.string.snd_settings_callPreferences), null, Modifier.testTag("settings-row-callPreferences")) { open(SettingsPage.CallPreferences(account.id)) }
                    ValueLinkRow(stringResource(R.string.snd_settings_link), state.label(), Modifier.testTag("settings-row-link")) { open(SettingsPage.Link(account.id)) }
                }

                if (outline.showsAdmin) {
                    section(stringResource(R.string.snd_settings_admin), null) {
                        for (item in outline.admin) {
                            when (item) {
                                SettingsOutline.Admin.NUMBERS -> ValueLinkRow(stringResource(R.string.snd_settings_admin_numbers), null, Modifier.testTag("settings-row-numbers")) { open(SettingsPage.Centrale(account.id, PbxPart.NUMBERS)) }
                                SettingsOutline.Admin.DEVICES -> ValueLinkRow(stringResource(R.string.snd_pbx_devices_title), null, Modifier.testTag("settings-row-devices")) { open(SettingsPage.Centrale(account.id, PbxPart.DEVICES)) }
                                SettingsOutline.Admin.RING_GROUPS -> ValueLinkRow(stringResource(R.string.snd_pbx_ringGroups_title), null, Modifier.testTag("settings-row-ringGroups")) { open(SettingsPage.Centrale(account.id, PbxPart.RING_GROUPS)) }
                                SettingsOutline.Admin.OVERVIEW -> ValueLinkRow(stringResource(R.string.snd_pbx_title), null, Modifier.testTag("settings-row-overview")) { open(SettingsPage.Centrale(account.id, PbxPart.OVERVIEW)) }
                                SettingsOutline.Admin.HOURS -> ValueLinkRow(stringResource(R.string.snd_pbx_hours_title), null, Modifier.testTag("settings-row-hours")) { open(SettingsPage.Centrale(account.id, PbxPart.HOURS)) }
                                SettingsOutline.Admin.SOUNDS -> ValueLinkRow(stringResource(R.string.snd_settings_admin_sounds), null, Modifier.testTag("settings-row-sounds")) { open(SettingsPage.Sounds(account.id)) }
                                SettingsOutline.Admin.INVITE -> ValueLinkRow(stringResource(R.string.snd_settings_admin_invite), null, Modifier.testTag("settings-row-invite")) { open(SettingsPage.Invite(account.id)) }
                                SettingsOutline.Admin.RECORDINGS -> ValueLinkRow(stringResource(R.string.snd_media_recordings_title), null, Modifier.testTag("settings-row-recordings")) { open(SettingsPage.Recordings(account.id)) }
                            }
                        }
                    }
                }
            }

            section(stringResource(R.string.snd_settings_app), null) {
                ValueLinkRow(stringResource(R.string.snd_settings_appearance), stringResource(appearance.titleRes), Modifier.testTag("settings-row-appearance")) { open(SettingsPage.Appearance) }
                ValueLinkRow(stringResource(R.string.settings_notifications), null, Modifier.testTag("settings-row-notifications")) { open(SettingsPage.Notifications) }
                ValueLinkRow(stringResource(R.string.snd_settings_aboutRow), null, Modifier.testTag("settings-row-about")) { open(SettingsPage.About) }
            }

            section(null, null) {
                ActionRow(stringResource(R.string.snd_settings_feedback), Icons.Filled.Email, Modifier.testTag("feedback-link")) {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:info@fullstackstudio.nl?subject=" + Uri.encode("FSVoip feedback"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                }

                if (account != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ActionRow(stringResource(R.string.snd_settings_unpair, account.displayLabel), Icons.Filled.LinkOff, Modifier.testTag("unpair-button"), color = Brand.hangUp) { unpair.ask() }
                        if (unpair.unpairing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }

            Text(
                stringResource(R.string.snd_settings_versionLine, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("settings-version"),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }

    account?.let { UnpairDialogs(unpair, model, it) }
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
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.then(modifier).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = Brand.ink, checkedThumbColor = Brand.lime))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubpageScaffold(title: String, back: () -> Unit, footer: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        bottomBar = { footer?.invoke() },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = back, modifier = Modifier.testTag("page-back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.snd_action_back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()), content = content)
    }
}
