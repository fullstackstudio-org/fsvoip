// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The "Contacten" tab: the address book of the customer (synced from the portal) with the colleagues of the PBX, and the phone's own
// contacts (read only, with permission). Android port of the iOS `ContactsView`: adding, editing and (administrators) deleting contacts of the address book, favourites, and
// copying a contact of the phone into the address book.

package nl.fullstackstudio.fsvoip.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.contacts.ContactDraft
import nl.fullstackstudio.fsvoip.contacts.ContactEntry
import nl.fullstackstudio.fsvoip.contacts.ContactFailure
import nl.fullstackstudio.fsvoip.contacts.ContactWrite
import nl.fullstackstudio.fsvoip.contacts.ContactEntryPhone
import nl.fullstackstudio.fsvoip.contacts.ContactSource
import nl.fullstackstudio.fsvoip.contacts.ContactsBrowsing
import nl.fullstackstudio.fsvoip.contacts.ContactsFilter
import nl.fullstackstudio.fsvoip.core.Contact
import nl.fullstackstudio.fsvoip.core.ContactPhoneLabel

private enum class ContactsSegment { CENTRALE, PHONE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(model: AppModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val entries by model.contacts.entries.collectAsStateWithLifecycle()
    val states by model.contacts.accountStates.collectAsStateWithLifecycle()
    val syncing by model.contacts.syncingAccountIds.collectAsStateWithLifecycle()
    model.settingsRevision.collectAsStateWithLifecycle().value

    var segment by remember { mutableStateOf(ContactsSegment.CENTRALE) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<ContactsFilter>(ContactsFilter.All) }
    var selected by remember { mutableStateOf<ContactEntry?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val favorites by model.favorites.ids.collectAsStateWithLifecycle()
    val writable = remember(accounts, states) { model.writableContactAccounts() }

    // The phone's own contacts: read when the permission is there AND the user switched it on; never stored.
    var deviceEntries by remember { mutableStateOf<List<ContactEntry>>(emptyList()) }
    var deviceDenied by remember { mutableStateOf(false) }
    val deviceOn = model.useDeviceContacts && model.deviceContacts.hasPermission

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.useDeviceContacts = granted
        deviceDenied = !granted
    }

    LaunchedEffect(deviceOn, segment) {
        deviceEntries = if (deviceOn && segment == ContactsSegment.PHONE) withContext(Dispatchers.IO) { model.deviceContacts.readAll() } else emptyList()
    }

    // The address book is also synced when the tab opens (never more than once a minute).
    LaunchedEffect(accounts.size) {
        for (account in accounts) {
            model.contacts.sync(account.id, canRead = model.capabilities(account.id)?.contacts?.read ?: true)
        }
    }

    val centrale = remember(entries, filter, query, favorites) { ContactsBrowsing.visible(entries, filter, query, model.contacts, favorites) }
    val phone = remember(deviceEntries, query) { ContactsBrowsing.visible(deviceEntries, ContactsFilter.All, query, model.contacts) }
    val shown = if (segment == ContactsSegment.CENTRALE) centrale else phone
    val isSyncing = syncing.isNotEmpty()
    val failed = states.values.any { it.lastSyncFailed }
    val allLists = states.flatMap { (accountId, state) -> state.lists.filter { it.isEnabled }.map { accountId to it } }

    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            SegmentedButton(
                selected = segment == ContactsSegment.CENTRALE,
                onClick = { segment = ContactsSegment.CENTRALE },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                modifier = Modifier.testTag("contacts-segment-centrale"),
            ) { Text(stringResource(R.string.contacts_segment_centrale)) }
            SegmentedButton(
                selected = segment == ContactsSegment.PHONE,
                onClick = { segment = ContactsSegment.PHONE },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                modifier = Modifier.testTag("contacts-segment-phone"),
            ) { Text(stringResource(R.string.contacts_segment_phone)) }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.contacts_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.contacts_search_clear)) } }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f).testTag("contacts-search"),
            )

            if (writable.isNotEmpty()) {
                IconButton(onClick = { adding = true }, modifier = Modifier.testTag("contacts-add")) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.contacts_add)) }
            }
        }

        if (segment == ContactsSegment.CENTRALE) {
            FilterRow(filter, allLists.map { (accountId, list) -> ContactsFilter.OfList(accountId, list.id) to list.name }, accounts.size > 1, { id -> accounts.firstOrNull { it.id == id }?.displayLabel.orEmpty() }) { filter = it }
        }

        if (segment == ContactsSegment.CENTRALE && isSyncing && entries.isNotEmpty()) {
            Text(stringResource(R.string.contacts_syncing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        } else if (segment == ContactsSegment.CENTRALE && failed) {
            Text(stringResource(R.string.contacts_syncFailed), style = MaterialTheme.typography.bodySmall, color = Brand.hangUp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("contacts-sync-failed"))
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    for (account in accounts) model.contacts.sync(account.id, canRead = model.capabilities(account.id)?.contacts?.read ?: true, force = true)
                    refreshing = false
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when {
                segment == ContactsSegment.PHONE && !deviceOn -> DevicePrompt(
                    denied = deviceDenied,
                    onEnable = { permission.launch(Manifest.permission.READ_CONTACTS) },
                    onOpenSettings = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                )
                shown.isEmpty() && query.isNotEmpty() -> ContactsEmpty(stringResource(R.string.contacts_noResults_title), stringResource(R.string.contacts_noResults_body, query))
                shown.isEmpty() && filter == ContactsFilter.Favorites -> ContactsEmpty(stringResource(R.string.contacts_filter_favorites), stringResource(R.string.contacts_favorites_empty))
                shown.isEmpty() && isSyncing -> ContactsEmpty(stringResource(R.string.contacts_syncing), null)
                shown.isEmpty() -> ContactsEmpty(stringResource(R.string.contacts_empty_title), stringResource(R.string.contacts_empty_body))
                else -> LazyColumn(Modifier.fillMaxSize().testTag("contacts-list")) {
                    items(shown, key = { it.id }) { entry ->
                        ContactRow(entry, isFavorite = entry.id in favorites) { selected = entry }
                        HorizontalDivider(Modifier.padding(start = 66.dp))
                    }

                    item {
                        Text(
                            if (shown.size == 1) stringResource(R.string.contacts_count_one) else stringResource(R.string.contacts_count_many, shown.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    selected?.let { entry -> ContactDetailSheet(model, entry, onDismiss = { selected = null }) }

    if (adding) {
        ContactEditSheet(model, writable.first().id, existing = null, initial = ContactDraft(), onDismiss = { adding = false }, onSaved = { adding = false })
    }
}

@Composable
private fun FilterRow(
    filter: ContactsFilter,
    lists: List<Pair<ContactsFilter.OfList, String>>,
    several: Boolean,
    accountLabel: (String) -> String,
    select: (ContactsFilter) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val pickedList = (filter as? ContactsFilter.OfList)?.let { chosen -> lists.firstOrNull { it.first == chosen }?.second }

    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = filter == ContactsFilter.All, onClick = { select(ContactsFilter.All) }, label = { Text(stringResource(R.string.contacts_filter_pillAll)) })
        FilterChip(selected = filter == ContactsFilter.Favorites, onClick = { select(ContactsFilter.Favorites) }, label = { Text(stringResource(R.string.contacts_filter_favorites)) }, modifier = Modifier.testTag("contacts-filter-favorites"))
        FilterChip(selected = filter == ContactsFilter.AddressBook, onClick = { select(ContactsFilter.AddressBook) }, label = { Text(stringResource(R.string.contacts_filter_customer)) })
        FilterChip(selected = filter == ContactsFilter.Colleagues, onClick = { select(ContactsFilter.Colleagues) }, label = { Text(stringResource(R.string.contacts_filter_internal)) })

        if (lists.isNotEmpty()) {
            Box {
                FilterChip(
                    selected = pickedList != null,
                    onClick = { open = true },
                    label = { Text(pickedList ?: stringResource(R.string.contacts_filter_lists)) },
                    leadingIcon = { Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("contacts-filter-lists"),
                )

                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for ((choice, name) in lists) {
                        DropdownMenuItem(
                            text = { Text(if (several) "$name · ${accountLabel(choice.accountId)}" else name) },
                            onClick = {
                                open = false
                                select(choice)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactsEmpty(title: String, message: String?) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Contacts, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(34.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))

        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun DevicePrompt(denied: Boolean, onEnable: () -> Unit, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Contacts, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(34.dp))
        Text(stringResource(if (denied) R.string.contacts_phone_denied_title else R.string.contacts_phone_off_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        Text(stringResource(if (denied) R.string.contacts_sources_device_denied else R.string.contacts_phone_off_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))

        if (denied) {
            SecondaryButton(stringResource(R.string.contacts_sources_device_openSettings), onClick = onOpenSettings)
        } else {
            PrimaryButton(stringResource(R.string.contacts_phone_off_enable), onClick = onEnable, modifier = Modifier.testTag("contacts-enable-device"))
        }
    }
}

@Composable
private fun ContactRow(entry: ContactEntry, isFavorite: Boolean = false, onClick: () -> Unit) {
    val subtitle = entry.company?.takeIf { it.isNotBlank() } ?: entry.phones.firstOrNull()?.number.orEmpty()
    val tag = when (entry.source) {
        ContactSource.InternalExtensions -> stringResource(R.string.contacts_source_colleague)
        ContactSource.Device -> stringResource(R.string.contacts_source_device)
        else -> null
    }

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp).testTag("contact-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        InitialsAvatar(entry.name, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(entry.name.ifBlank { subtitle }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)

            if (subtitle.isNotEmpty() && entry.name.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (isFavorite) {
            Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.contacts_favorite_label), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }

        if (tag != null) {
            Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun contactLabel(label: ContactPhoneLabel): String = stringResource(
    when (label) {
        ContactPhoneLabel.MOBILE -> R.string.contacts_label_mobile
        ContactPhoneLabel.WORK -> R.string.contacts_label_work
        ContactPhoneLabel.HOME -> R.string.contacts_label_home
        ContactPhoneLabel.MAIN -> R.string.contacts_label_main
        ContactPhoneLabel.FAX -> R.string.contacts_label_fax
        ContactPhoneLabel.OTHER -> R.string.contacts_label_other
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactDetailSheet(model: AppModel, entry: ContactEntry, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val states by model.contacts.accountStates.collectAsStateWithLifecycle()
    val favorites by model.favorites.ids.collectAsStateWithLifecycle()
    val contactId = entry.id.substringAfter('/')
    val accountId = entry.accountId
    val isBook = entry.source == ContactSource.AddressBook && accountId != null
    val listNames = accountId?.let { id ->
        states[id]?.lists.orEmpty().filter { it.isEnabled && contactId in it.memberIds }.map { it.name }
    }.orEmpty()

    // Notes and list memberships only come with the detail of one contact; it is fetched when the sheet opens.
    var detail by remember { mutableStateOf<Contact?>(null) }
    var editing by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<ContactFailure?>(null) }

    LaunchedEffect(entry.id) {
        if (isBook && accountId != null) {
            (model.contacts.detail(accountId, contactId) as? ContactWrite.Done)?.let { detail = it.contact }
        }
    }

    val canEdit = isBook && accountId != null && (model.capabilities(accountId)?.contacts?.write ?: false)
    val canDelete = isBook && accountId != null && model.canDeleteContact(accountId)
    val canImport = entry.source == ContactSource.Device && model.writableContactAccounts().isNotEmpty()
    val favorite = entry.id in favorites

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState()).testTag("contact-detail")) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                InitialsAvatar(entry.name, size = 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    entry.company?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                IconButton(onClick = { model.favorites.toggle(entry.id) }, modifier = Modifier.testTag("contact-favorite")) {
                    Icon(
                        if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = stringResource(if (favorite) R.string.contacts_favorite_remove else R.string.contacts_favorite_add),
                        tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            failure?.let { NoteCard(contactFailureText(it), isError = true) }

            section(stringResource(R.string.contacts_detail_numbers), null) {
                if (entry.phones.isEmpty()) {
                    InfoRow(stringResource(R.string.contacts_detail_noNumbers))
                }

                for (phone in entry.phones) {
                    PhoneRow(phone) {
                        onDismiss()
                        // A contact of the address book calls with its own line; the rest with the default one.
                        model.call(phone.number, entry.accountId)
                    }
                }
            }

            entry.email?.takeIf { it.isNotBlank() }?.let { email ->
                section(stringResource(R.string.contacts_detail_email), null) { ValueRow(stringResource(R.string.contacts_detail_email), email) }
            }

            detail?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                section(stringResource(R.string.contacts_detail_notes), null) { InfoRow(notes) }
            }

            if (listNames.isNotEmpty()) {
                section(stringResource(R.string.contacts_detail_lists), null) { InfoRow(listNames.joinToString(", ")) }
            }

            if (isBook && accountId != null) {
                model.account(accountId)?.let { ContactTimelineSection(model, it, contactId) }
            }

            if (canEdit || canDelete || canImport) {
                section(null, if (canImport) stringResource(R.string.contacts_import_note) else null) {
                    if (canEdit) ActionRow(stringResource(R.string.contacts_detail_edit), Icons.Filled.Edit, Modifier.testTag("contact-edit-action")) { editing = true }
                    if (canImport) ActionRow(stringResource(R.string.contacts_import_action), Icons.Filled.Add, Modifier.testTag("contact-import")) { importing = true }
                    if (canDelete) ActionRow(stringResource(R.string.contacts_detail_delete), Icons.Filled.Delete, Modifier.testTag("contact-delete"), color = Brand.hangUp) { confirmDelete = true }
                }
            }

            when (entry.source) {
                ContactSource.Device -> Text(stringResource(R.string.contacts_detail_deviceNote), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                ContactSource.InternalExtensions -> Text(stringResource(R.string.contacts_detail_colleagueNote), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                else -> Unit
            }
        }
    }

    if (editing && accountId != null) {
        // Edit what the server last said (the detail), else what the list holds.
        val current = detail ?: model.contacts.contact(accountId, contactId)?.let { Contact(it.id, it.name, it.firstName, it.lastName, it.company, it.email, it.phones, it.tags, it.updatedAt) }

        if (current != null) {
            ContactEditSheet(model, accountId, existing = current, initial = ContactDraft.from(current), onDismiss = { editing = false }, onSaved = { detail = it; editing = false })
        }
    }

    if (importing) {
        val target = model.writableContactAccounts().first().id

        ContactEditSheet(model, target, existing = null, initial = ContactDraft.from(entry), onDismiss = { importing = false }, onSaved = { importing = false; onDismiss() })
    }

    if (confirmDelete && accountId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.contacts_detail_delete_title, entry.name)) },
            text = { Text(stringResource(R.string.contacts_detail_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false

                    scope.launch {
                        val result = model.contacts.delete(contactId, accountId)

                        if (result == null) onDismiss() else failure = result
                    }
                }) { Text(stringResource(R.string.contacts_detail_delete_confirm), color = Brand.hangUp) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PhoneRow(phone: ContactEntryPhone, onCall: () -> Unit) {
    val label = contactLabel(phone.label)
    val description = stringResource(R.string.contacts_call_number, label, phone.number)

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onCall).padding(horizontal = 20.dp, vertical = 12.dp).testTag("contact-number"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(phone.number, style = MaterialTheme.typography.bodyLarge)
        }
        Icon(Icons.Filled.Phone, contentDescription = description, tint = MaterialTheme.colorScheme.onSurface)
    }
}
