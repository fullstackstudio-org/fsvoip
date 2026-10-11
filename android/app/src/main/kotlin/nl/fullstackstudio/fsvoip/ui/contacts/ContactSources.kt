// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.contacts

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.contacts.ContactsAccountState
import nl.fullstackstudio.fsvoip.contacts.DeviceContactsAccess
import nl.fullstackstudio.fsvoip.ui.SubpageScaffold
import nl.fullstackstudio.fsvoip.ui.SwitchRow
import nl.fullstackstudio.fsvoip.ui.section

/** Which contacts the app uses: the phone's own, and per account the address book and its lists. */
@Composable
fun ContactSourcesScreen(model: AppModel, kit: ContactsKit) {
    val hub = kit.hub
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val states by hub.accountStates.collectAsStateWithLifecycle()
    val usesDevice by hub.usesDeviceContacts.collectAsStateWithLifecycle()
    val deviceAccess by hub.deviceAccess.collectAsStateWithLifecycle()
    val switchDevice = rememberDeviceContactsSwitch(hub)

    SubpageScaffold(stringResource(R.string.contacts_sources_title), back = { kit.nav.home() }) {
        section(null, stringResource(R.string.contacts_sources_device_footer)) {
            SwitchRow(stringResource(R.string.contacts_sources_device), null, checked = usesDevice, modifier = Modifier.testTag("contacts-device-toggle")) { switchDevice(it) }

            if (deviceAccess == DeviceContactsAccess.DENIED) {
                Text(stringResource(R.string.contacts_sources_device_denied), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                TextButton(onClick = { openAppSettings(context) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.contacts_sources_device_openSettings)) }
            }
        }

        for (account in accounts) {
            val state = states[account.id] ?: continue

            section(account.displayLabel, if (state.isEnabled && state.lists.isNotEmpty()) stringResource(R.string.contacts_sources_lists_footer) else null) {
                SwitchRow(
                    stringResource(R.string.contacts_sources_book),
                    stringResource(R.string.contacts_sources_book_footer, countText(state.contactCount), updatedText(state)),
                    checked = state.isEnabled,
                    modifier = Modifier.testTag("contacts-book-toggle"),
                ) { enabled -> scope.launch { hub.setAddressBookEnabled(enabled, account.id) } }

                if (state.isEnabled) {
                    state.lists.forEach { list ->
                        SwitchRow(list.name, countText(list.contactCount), checked = list.isEnabled, modifier = Modifier.testTag("contacts-list-toggle")) { enabled ->
                            scope.launch { hub.setListEnabled(enabled, list.id, account.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun updatedText(state: ContactsAccountState): String {
    val at = state.lastSyncedAtMillis ?: return stringResource(R.string.contacts_sources_neverSynced)

    return DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}
