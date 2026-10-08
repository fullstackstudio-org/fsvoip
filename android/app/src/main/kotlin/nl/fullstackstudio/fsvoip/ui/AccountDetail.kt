// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
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

@Composable
fun AccountDetailScreen(model: AppModel, accountId: String, back: () -> Unit) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val registrations by model.phone.registrations.collectAsStateWithLifecycle()
    model.settingsRevision.collectAsStateWithLifecycle().value
    val account = accounts.firstOrNull { it.id == accountId }

    if (account == null) {
        LaunchedEffect(Unit) { back() }
        return
    }

    val state = registrations[account.id] ?: RegistrationState.Unregistered
    val scope = rememberCoroutineScope()
    var alias by remember(account.id) { mutableStateOf(account.labelOverride ?: "") }
    var savingAlias by remember { mutableStateOf(false) }
    var confirmsUnpair by remember { mutableStateOf(false) }
    var unpairing by remember { mutableStateOf(false) }
    var offersForget by remember { mutableStateOf(false) }
    val aliasChanged = AccountService.cleanAlias(alias) != AccountService.cleanAlias(account.labelOverride)

    fun saveAlias() {
        if (!aliasChanged || savingAlias) return
        savingAlias = true
        scope.launch {
            if (model.rename(account.id, alias)) alias = model.account(account.id)?.labelOverride ?: ""
            savingAlias = false
        }
    }

    SubpageScaffold(account.displayLabel, back) {
        Column {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(account.displayLabel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusLight(state, 10.dp)
                    Text(state.label(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state is RegistrationState.Failed) {
                ActionRow(stringResource(R.string.account_reconnect), null) { model.phone.refreshRegistrations() }
            }
        }

        section(stringResource(R.string.account_alias), stringResource(R.string.account_alias_footer, "${account.pbxName} · ${account.extensionName}")) {
            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it.take(AccountService.ALIAS_MAX_LENGTH) },
                placeholder = { Text(account.label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { saveAlias() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("alias-field"),
            )

            if (aliasChanged) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { saveAlias() }, enabled = !savingAlias) { Text(stringResource(R.string.action_save)) }
                    if (savingAlias) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }

        section(stringResource(R.string.account_calls), stringResource(R.string.account_showCalled_footer, account.displayLabel)) {
            SwitchRow(stringResource(R.string.account_showCalled), null, checked = model.showsCalledAccount(account.id), modifier = Modifier.testTag("show-called-toggle")) {
                model.setShowsCalledAccount(account.id, it)
            }
        }

        if (accounts.size > 1) {
            section(stringResource(R.string.settings_calling), null) {
                CheckRow(stringResource(R.string.account_defaultLine), checked = model.defaultOutgoingAccountId == account.id) { model.setDefaultOutgoing(account.id) }
            }
        }

        section(stringResource(R.string.account_details), null) {
            ValueRow(stringResource(R.string.account_pbx), account.pbxName)
            ValueRow(stringResource(R.string.account_device), listOfNotNull(account.extensionName, account.extensionNumber).joinToString(" · "))
            ValueRow(stringResource(R.string.account_customer), account.customerName)
            ValueRow(stringResource(R.string.account_connection), connectionText(account))
            ValueRow(stringResource(R.string.account_domain), account.sip.domain)
            ValueRow(
                stringResource(R.string.account_paired),
                account.pairedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
            )
        }

        section(null, stringResource(R.string.account_unpair_footer)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionRow(stringResource(R.string.account_unpair), null, Modifier.testTag("unpair-button"), color = Brand.hangUp) {
                    if (!unpairing) confirmsUnpair = true
                }
                if (unpairing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }

    if (confirmsUnpair) {
        AlertDialog(
            onDismissRequest = { confirmsUnpair = false },
            title = { Text(stringResource(R.string.account_unpair_title, account.displayLabel)) },
            text = { Text(stringResource(R.string.account_unpair_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmsUnpair = false
                    unpairing = true
                    scope.launch {
                        val result = model.unpair(account.id)
                        unpairing = false
                        if (result is AppModel.UnpairResult.Failed) offersForget = true
                    }
                }) { Text(stringResource(R.string.account_unpair_confirm), color = Brand.hangUp) }
            },
            dismissButton = { TextButton(onClick = { confirmsUnpair = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (offersForget) {
        AlertDialog(
            onDismissRequest = { offersForget = false },
            title = { Text(stringResource(R.string.account_forget_title)) },
            text = { Text(stringResource(R.string.account_forget_message)) },
            confirmButton = {
                TextButton(onClick = {
                    offersForget = false
                    model.forget(account.id)
                }) { Text(stringResource(R.string.account_forget_confirm), color = Brand.hangUp) }
            },
            dismissButton = { TextButton(onClick = { offersForget = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun connectionText(account: StoredAccount): String = stringResource(
    if (account.sip.transport == ServerTransport.TLS) R.string.account_connection_tls else R.string.account_connection_plain,
)
