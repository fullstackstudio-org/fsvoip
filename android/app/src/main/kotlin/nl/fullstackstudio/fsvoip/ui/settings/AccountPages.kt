// SPDX-License-Identifier: AGPL-3.0-or-later
//
// "Mijn account": Profiel, Oproepvoorkeuren and Koppeling for one paired line (the Android port of the iOS `ProfilePage`,
// `CallPreferencesPage` and `LinkPage`), plus the unpair question that Profiel and the settings list share.

package nl.fullstackstudio.fsvoip.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.ServerTransport
import nl.fullstackstudio.fsvoip.core.StoredAccount
import nl.fullstackstudio.fsvoip.pairing.AccountService
import nl.fullstackstudio.fsvoip.sipengine.RegistrationState
import nl.fullstackstudio.fsvoip.ui.ActionRow
import nl.fullstackstudio.fsvoip.ui.Brand
import nl.fullstackstudio.fsvoip.ui.CheckRow
import nl.fullstackstudio.fsvoip.ui.InitialsAvatar
import nl.fullstackstudio.fsvoip.ui.StatusLight
import nl.fullstackstudio.fsvoip.ui.SubpageScaffold
import nl.fullstackstudio.fsvoip.ui.SwitchRow
import nl.fullstackstudio.fsvoip.ui.ValueRow
import nl.fullstackstudio.fsvoip.ui.label
import nl.fullstackstudio.fsvoip.ui.section

// MARK: Unpair

/** The question "unpair this line?" and its follow-up when the server could not be reached. */
@Stable
class UnpairFlow {
    var confirms by mutableStateOf(false)
    var unpairing by mutableStateOf(false)
    var offersForget by mutableStateOf(false)

    fun ask() {
        if (!unpairing) confirms = true
    }
}

@Composable
fun rememberUnpairFlow(): UnpairFlow = remember { UnpairFlow() }

@Composable
fun UnpairDialogs(flow: UnpairFlow, model: AppModel, account: StoredAccount) {
    val scope = rememberCoroutineScope()

    if (flow.confirms) {
        AlertDialog(
            onDismissRequest = { flow.confirms = false },
            title = { Text(stringResource(R.string.account_unpair_title, account.displayLabel)) },
            text = { Text(stringResource(R.string.account_unpair_message)) },
            confirmButton = {
                TextButton(onClick = {
                    flow.confirms = false
                    flow.unpairing = true
                    scope.launch {
                        val result = model.unpair(account.id)
                        flow.unpairing = false
                        if (result is AppModel.UnpairResult.Failed) flow.offersForget = true
                    }
                }) { Text(stringResource(R.string.account_unpair_confirm), color = Brand.hangUp) }
            },
            dismissButton = { TextButton(onClick = { flow.confirms = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (flow.offersForget) {
        AlertDialog(
            onDismissRequest = { flow.offersForget = false },
            title = { Text(stringResource(R.string.account_forget_title)) },
            text = { Text(stringResource(R.string.account_forget_message)) },
            confirmButton = {
                TextButton(onClick = {
                    flow.offersForget = false
                    model.forget(account.id)
                }) { Text(stringResource(R.string.account_forget_confirm), color = Brand.hangUp) }
            },
            dismissButton = { TextButton(onClick = { flow.offersForget = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

// MARK: Profiel

/** "Profiel": who this phone is on the centrale. The name in the app is local; the e-mail address for voicemail is the own extension's. */
@Composable
fun ProfileScreen(model: AppModel, accountId: String, back: () -> Unit, openLink: () -> Unit) {
    val context = LocalContext.current
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val registrations by model.phone.registrations.collectAsStateWithLifecycle()
    model.settingsRevision.collectAsStateWithLifecycle().value
    val meByAccount by model.meByAccount.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }

    if (account == null) {
        LaunchedEffect(Unit) { back() }
        return
    }

    val hubs = remember { model.settingsHubs(context) }
    val scope = rememberCoroutineScope()
    val form = remember(account.id) { SelfExtensionForm() }
    val unpair = rememberUnpairFlow()
    var alias by remember(account.id) { mutableStateOf(account.labelOverride ?: "") }
    var isSaving by remember { mutableStateOf(false) }
    val selfAllowed = meByAccount[account.id]?.canEditOwnExtension == true
    val offersServerFields = selfAllowed && form.isLoaded
    val aliasChanged = AccountService.cleanAlias(alias) != AccountService.cleanAlias(account.labelOverride)
    val isDirty = aliasChanged || (offersServerFields && form.isDirty)
    val leave = rememberDiscardGuard(isDirty, isSaving, back)
    val state = registrations[account.id] ?: RegistrationState.Unregistered

    LaunchedEffect(account.id, selfAllowed) {
        if (!selfAllowed) return@LaunchedEffect

        hubs.selfExtension.load(account.id)
        form.adopt(hubs.selfExtension.state(account.id))
    }

    fun save() {
        if (!isDirty || isSaving) return

        isSaving = true

        scope.launch {
            try {
                val baseline = form.baseline

                if (offersServerFields && form.isDirty && baseline != null) {
                    val outcome = hubs.selfExtension.save(form.draft, baseline, account.id)

                    if (!form.finish(outcome)) return@launch

                    hubs.availability.load(account.id)
                }

                if (aliasChanged && !model.rename(account.id, alias)) return@launch

                back()
            } finally {
                isSaving = false
            }
        }
    }

    SubpageScaffold(
        stringResource(R.string.snd_settings_profile),
        leave,
        footer = { SaveFooter(canSave = isDirty, onCancel = leave, onSave = ::save, isSaving = isSaving) },
    ) {
        SelfExtensionNotice(form.message)

        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            InitialsAvatar(account.extensionName, 56)
            Column {
                Text(account.extensionName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(account.pbxName, account.extensionNumber?.let { stringResource(R.string.account_extension, it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        section(stringResource(R.string.account_alias), stringResource(R.string.account_alias_footer, "${account.pbxName} · ${account.extensionName}")) {
            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it.take(AccountService.ALIAS_MAX_LENGTH) },
                placeholder = { Text(account.label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("alias-field"),
            )
        }

        section(stringResource(R.string.snd_profile_device), stringResource(R.string.snd_profile_device_footer)) {
            ValueRow(stringResource(R.string.snd_profile_device_name), account.extensionName)
            account.extensionNumber?.let { ValueRow(stringResource(R.string.snd_profile_device_number), it) }
        }

        if (offersServerFields) {
            section(stringResource(R.string.snd_profile_email), stringResource(R.string.snd_profile_email_footer)) {
                OutlinedTextField(
                    value = form.draft.email,
                    onValueChange = { form.draft = form.draft.copy(email = it) },
                    placeholder = { Text(stringResource(R.string.snd_profile_email_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("profile-email-field"),
                )
            }
        }

        section(stringResource(R.string.snd_settings_link), null) {
            ValueLinkRow(state.label(), null, Modifier.testTag("profile-link-status"), onClick = openLink)
            ActionRow(stringResource(R.string.snd_profile_relink), Icons.Filled.QrCodeScanner, Modifier.testTag("profile-relink")) {
                model.presentScanner()
                model.closeSettings()
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionRow(stringResource(R.string.account_unpair), Icons.Filled.LinkOff, Modifier.testTag("profile-unpair"), color = Brand.hangUp) { unpair.ask() }
                if (unpair.unpairing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }

    UnpairDialogs(unpair, model, account)
}

// MARK: Koppeling

/** "Koppeling": the state of the line and the details of the pairing. */
@Composable
fun LinkScreen(model: AppModel, accountId: String, back: () -> Unit) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val registrations by model.phone.registrations.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }

    if (account == null) {
        LaunchedEffect(Unit) { back() }
        return
    }

    val state = registrations[account.id] ?: RegistrationState.Unregistered

    SubpageScaffold(stringResource(R.string.snd_settings_link), back) {
        section(null, null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusLight(state, 10.dp)
                Text(state.label(), style = MaterialTheme.typography.bodyLarge)
            }

            if (state is RegistrationState.Failed) {
                ActionRow(stringResource(R.string.account_reconnect), Icons.Filled.Refresh, Modifier.testTag("reconnect-button")) { model.phone.refreshRegistrations() }
            }
        }

        section(stringResource(R.string.account_details), stringResource(R.string.account_unpair_footer)) {
            ValueRow(stringResource(R.string.account_pbx), account.pbxName)
            ValueRow(stringResource(R.string.account_device), listOfNotNull(account.extensionName, account.extensionNumber).joinToString(" · "))
            ValueRow(stringResource(R.string.account_customer), account.customerName)
            ValueRow(stringResource(R.string.account_connection), stringResource(if (account.sip.transport == ServerTransport.TLS) R.string.account_connection_tls else R.string.account_connection_plain))
            ValueRow(stringResource(R.string.account_domain), account.sip.domain)
            ValueRow(stringResource(R.string.account_paired), account.pairedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
        }
    }
}

// MARK: Oproepvoorkeuren

private enum class TargetField { FORWARD, NO_ANSWER }

/**
 * "Oproepvoorkeuren": what happens to a call for the own extension (always forward, when nobody picks up, voicemail), plus the settings
 * that only live on this phone. The number a call goes out with is chosen per call on the dial screen, not here.
 */
@Composable
fun CallPreferencesScreen(model: AppModel, accountId: String, back: () -> Unit) {
    val context = LocalContext.current
    val accounts by model.accounts.collectAsStateWithLifecycle()
    model.settingsRevision.collectAsStateWithLifecycle().value
    val meByAccount by model.meByAccount.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }

    if (account == null) {
        LaunchedEffect(Unit) { back() }
        return
    }

    val hubs = remember { model.settingsHubs(context) }
    val states by hubs.selfExtension.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val form = remember(account.id) { SelfExtensionForm() }
    var isSaving by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<TargetField?>(null) }
    val selfAllowed = meByAccount[account.id]?.canEditOwnExtension == true
    val state = if (selfAllowed) states[account.id] else null
    val leave = rememberDiscardGuard(form.isDirty, isSaving, back)

    LaunchedEffect(account.id, selfAllowed) {
        if (!selfAllowed) return@LaunchedEffect

        hubs.selfExtension.load(account.id)
        form.adopt(hubs.selfExtension.state(account.id))
    }

    fun save() {
        val baseline = form.baseline ?: return

        if (isSaving) return

        isSaving = true

        scope.launch {
            try {
                val outcome = hubs.selfExtension.save(form.draft, baseline, account.id)

                if (form.finish(outcome)) {
                    hubs.availability.load(account.id)
                    back()
                }
            } finally {
                isSaving = false
            }
        }
    }

    // The choice of a destination is a page of its own on top of this one; the draft stays here.
    picking?.let { field ->
        val options = state?.targets.orEmpty()

        when (field) {
            TargetField.FORWARD -> TargetChoicePage(
                title = stringResource(R.string.snd_pbx_device_forward_title),
                selection = form.draft.forwardAlways,
                options = options,
                noneLabel = stringResource(R.string.snd_pbx_device_forward_off),
                allowsNone = true,
                allowsHangup = false,
                footer = null,
                onChoose = { form.draft = form.draft.copy(forwardAlways = it); picking = null },
                back = { picking = null },
            )
            TargetField.NO_ANSWER -> TargetChoicePage(
                title = stringResource(R.string.snd_pbx_device_noAnswer_then),
                selection = form.draft.noAnswerTarget,
                options = options,
                noneLabel = stringResource(if (form.draft.voicemailEnabled) R.string.snd_pbx_device_noAnswer_default_voicemail else R.string.snd_pbx_device_noAnswer_default_ring),
                allowsNone = true,
                allowsHangup = false,
                footer = null,
                onChoose = { form.draft = form.draft.copy(noAnswerTarget = it); picking = null },
                back = { picking = null },
            )
        }

        return
    }

    SubpageScaffold(
        stringResource(R.string.snd_settings_callPreferences),
        leave,
        footer = if (form.isLoaded) ({ SaveFooter(canSave = form.isDirty, onCancel = leave, onSave = ::save, isSaving = isSaving) }) else null,
    ) {
        SelfExtensionNotice(form.message)

        if (state != null && form.isLoaded) {
            val options = state.targets

            section(stringResource(R.string.snd_prefs_forward), stringResource(R.string.snd_prefs_forward_footer)) {
                ValueLinkRow(
                    stringResource(R.string.snd_pbx_device_forward_title),
                    form.draft.forwardAlways?.let { describeTarget(it, options) } ?: stringResource(R.string.snd_pbx_device_forward_off),
                    Modifier.testTag("prefs-forward-row"),
                ) { picking = TargetField.FORWARD }
            }

            section(stringResource(R.string.snd_pbx_device_noAnswer_title), stringResource(R.string.snd_pbx_device_noAnswer_footer)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.snd_pbx_device_noAnswer_after, form.draft.noAnswerSeconds), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).testTag("prefs-noanswer-seconds"))
                    val range = SelfExtensionDraftRange
                    StepButton("−", enabled = form.draft.noAnswerSeconds - 5 >= range.first, description = stringResource(R.string.snd_prefs_less)) { form.draft = form.draft.copy(noAnswerSeconds = (form.draft.noAnswerSeconds - 5).coerceAtLeast(range.first)) }
                    StepButton("+", enabled = form.draft.noAnswerSeconds + 5 <= range.last, description = stringResource(R.string.snd_prefs_more)) { form.draft = form.draft.copy(noAnswerSeconds = (form.draft.noAnswerSeconds + 5).coerceAtMost(range.last)) }
                }

                ValueLinkRow(
                    stringResource(R.string.snd_pbx_device_noAnswer_then),
                    form.draft.noAnswerTarget?.let { describeTarget(it, options) }
                        ?: stringResource(if (form.draft.voicemailEnabled) R.string.snd_pbx_device_noAnswer_default_voicemail else R.string.snd_pbx_device_noAnswer_default_ring),
                    Modifier.testTag("prefs-noanswer-target"),
                ) { picking = TargetField.NO_ANSWER }
            }

            section(
                stringResource(R.string.snd_prefs_voicemail),
                if (form.draft.cleanEmail.isEmpty()) stringResource(R.string.snd_prefs_voicemail_email_needsAddress) else stringResource(R.string.snd_prefs_voicemail_email_to, form.draft.cleanEmail),
            ) {
                SwitchRow(stringResource(R.string.snd_pbx_device_voicemail), stringResource(R.string.snd_pbx_device_voicemail_footer), checked = form.draft.voicemailEnabled, modifier = Modifier.testTag("prefs-voicemail-toggle")) {
                    form.draft = form.draft.copy(voicemailEnabled = it)
                }
                SwitchRow(
                    stringResource(R.string.snd_prefs_voicemail_email),
                    null,
                    checked = form.draft.voicemailToEmail,
                    enabled = form.draft.voicemailEnabled && form.draft.cleanEmail.isNotEmpty(),
                    modifier = Modifier.testTag("prefs-voicemail-email-toggle"),
                ) { form.draft = form.draft.copy(voicemailToEmail = it) }
            }
        }

        section(stringResource(R.string.snd_prefs_phone), stringResource(R.string.account_showCalled_footer, account.displayLabel)) {
            SwitchRow(stringResource(R.string.account_showCalled), null, checked = model.showsCalledAccount(account.id), modifier = Modifier.testTag("show-called-toggle")) {
                model.setShowsCalledAccount(account.id, it)
            }
        }

        if (accounts.size > 1) {
            section(stringResource(R.string.settings_defaultLine), stringResource(R.string.settings_defaultLine_footer)) {
                for (item in accounts) {
                    CheckRow(item.displayLabel, checked = model.defaultOutgoingAccountId == item.id) { model.setDefaultOutgoing(item.id) }
                }
            }
        }
    }
}

private val SelfExtensionDraftRange = nl.fullstackstudio.fsvoip.core.SelfExtensionDraft.noAnswerRange

@Composable
private fun StepButton(label: String, enabled: Boolean, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description }.testTag("step-$label"),
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
    }
}
