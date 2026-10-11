// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The bar under the dialler keypad ("Bellen via") and the sheet behind it: with several accounts first the account (line), then the
// number the call goes out with. Choosing a number sets it for the next call of that account and closes the sheet; no network, no
// waiting.

package nl.fullstackstudio.fsvoip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.StoredAccount
import nl.fullstackstudio.fsvoip.sipengine.RegistrationState

/**
 * Always there: the number this call goes out with (`[icon] [Name · number ⌄]`). Tapping it opens the chooser. Without the capability,
 * or with a single number and a single account, it is a label; there is no chevron then and nothing to open.
 */
@Composable
internal fun OutboundBar(model: AppModel, account: StoredAccount, state: RegistrationState, opensChooser: () -> Unit) {
    // Redraw when the numbers or the choice change.
    model.outbound.loaded.collectAsStateWithLifecycle().value
    model.outbound.revision.collectAsStateWithLifecycle().value
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val outbound = model.outbound
    val label = outbound.label(account.id, stringResource(R.string.outbound_default))
    val choosable = outbound.canChoose(account.id) || accounts.size > 1
    val description = stringResource(R.string.outbound_bar_accessibility, label)
    val hint = stringResource(R.string.outbound_bar_hint)

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .let { if (choosable) it.clickable(onClick = opensChooser) else it }
                .heightIn(min = 52.dp)
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    if (choosable) onClick(label = hint) { opensChooser(); true }
                }
                .testTag("outbound-bar"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.CallMade, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))

            Column(Modifier.weight(1f)) {
                if (accounts.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusLight(state, size = 7.dp)
                        Text(account.displayLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            if (choosable) {
                Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }

        if (state != RegistrationState.Registered) {
            Text(state.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OutboundChooserSheet(model: AppModel, accountId: String?, onSelectAccount: (String) -> Unit, onDismiss: () -> Unit) {
    model.outbound.loaded.collectAsStateWithLifecycle().value
    model.outbound.revision.collectAsStateWithLifecycle().value
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val registrations by model.phone.registrations.collectAsStateWithLifecycle()
    val outbound = model.outbound
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = Modifier.testTag("outbound-sheet")) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.outbound_sheet_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            if (accounts.size > 1) {
                SectionTitle(stringResource(R.string.outbound_sheet_account))

                for (account in accounts) {
                    val state = registrations[account.id] ?: RegistrationState.Unregistered
                    ChoiceRow(account.displayLabel, state.label(), account.id == accountId, tag = "outbound-account-${account.id}") {
                        onSelectAccount(account.id)
                        scope.launch { outbound.load(account) }
                    }
                }
            }

            if (accountId != null && outbound.canChoose(accountId)) {
                SectionTitle(stringResource(R.string.outbound_sheet_number))

                for (number in outbound.numbers(accountId)) {
                    ChoiceRow(OutboundChoiceModel.title(number), null, outbound.selected(accountId)?.number == number.number, tag = "outbound-number-${number.number}") {
                        outbound.select(number.number, accountId)
                        close()
                    }
                }

                Text(stringResource(R.string.outbound_sheet_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
}

@Composable
private fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) { role = Role.RadioButton }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        } else {
            Spacer(Modifier.size(24.dp))
        }
    }
}
