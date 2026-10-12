// SPDX-License-Identifier: AGPL-3.0-or-later
//
// "Nieuw contact" / "Contact wijzigen": the form for the address book of the customer. Android port of the iOS `ContactEditView`.
// Saving a change sends `expectedUpdatedAt` (the `updatedAt` the form was opened with); `409 stale` reloads the contact and says so.

package nl.fullstackstudio.fsvoip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.contacts.ContactDraft
import nl.fullstackstudio.fsvoip.contacts.ContactFailure
import nl.fullstackstudio.fsvoip.contacts.ContactWrite
import nl.fullstackstudio.fsvoip.contacts.PhoneCountry
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import nl.fullstackstudio.fsvoip.core.Contact
import nl.fullstackstudio.fsvoip.core.ContactPhoneLabel

/** Why a contact change failed, in words for the user. */
@Composable
fun contactFailureText(failure: ContactFailure): String = stringResource(
    when (failure) {
        ContactFailure.PHONE -> R.string.contacts_error_phone
        ContactFailure.EMAIL -> R.string.contacts_error_email
        ContactFailure.FORBIDDEN -> R.string.contacts_error_forbidden
        ContactFailure.LIMIT -> R.string.contacts_error_limit
        ContactFailure.RATE -> R.string.contacts_error_rate
        ContactFailure.NETWORK -> R.string.contacts_error_network
        ContactFailure.GONE -> R.string.contacts_error_gone
        ContactFailure.STALE, ContactFailure.REVOKED, ContactFailure.OTHER -> R.string.error_generic
    },
)

/**
 * The form. [existing] `null` = a new contact (in [accountId], or in the account the user picks among [writableAccountIds]);
 * otherwise [existing] is edited in [accountId]. [initial] is what the fields start with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactEditSheet(
    model: AppModel,
    accountId: String,
    existing: Contact?,
    initial: ContactDraft,
    onDismiss: () -> Unit,
    onSaved: (Contact) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val states by model.contacts.accountStates.collectAsStateWithLifecycle()
    val writable = remember(accounts) { model.writableContactAccounts() }

    var target by remember { mutableStateOf(accountId) }
    var draft by remember { mutableStateOf(initial) }
    var base by remember { mutableStateOf(initial) }
    var updatedAt by remember { mutableStateOf(existing?.updatedAt) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<ContactFailure?>(null) }
    var staleNotice by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val lists = states[target]?.lists.orEmpty().filter { it.isEnabled }
    val isDirty = draft != base

    fun tryDismiss() {
        if (isDirty) confirmDiscard = true else onDismiss()
    }

    ModalBottomSheet(onDismissRequest = { tryDismiss() }, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp).testTag("contact-edit")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { tryDismiss() }) { Text(stringResource(R.string.action_cancel)) }
                Text(
                    stringResource(if (existing == null) R.string.contacts_edit_titleNew else R.string.contacts_edit_titleEdit),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                if (saving) {
                    CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).then(Modifier), strokeWidth = 2.dp)
                } else {
                    TextButton(
                        enabled = draft.canSave,
                        modifier = Modifier.testTag("contact-save"),
                        onClick = {
                            saving = true
                            failure = null

                            scope.launch {
                                val result = if (existing == null) model.contacts.create(draft, target) else model.contacts.update(draft, existing.id, updatedAt ?: existing.updatedAt, target)
                                saving = false

                                when (result) {
                                    is ContactWrite.Done -> onSaved(result.contact)
                                    is ContactWrite.Failed -> if (result.failure == ContactFailure.STALE && existing != null) {
                                        // Someone changed it in the meantime: show the newest version and let the user look again.
                                        val fresh = model.contacts.detail(target, existing.id)

                                        if (fresh is ContactWrite.Done) {
                                            draft = ContactDraft.from(fresh.contact)
                                            base = draft
                                            updatedAt = fresh.contact.updatedAt
                                            staleNotice = true
                                        } else {
                                            failure = result.failure
                                        }
                                    } else {
                                        failure = result.failure
                                    }
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.action_save)) }
                }
            }

            failure?.let { NoteCard(contactFailureText(it), isError = true) }

            if (!draft.canSave && (draft.displayName.isNotEmpty() || draft.filledPhones.isNotEmpty() || draft.hasEmail)) {
                Text(stringResource(R.string.contacts_edit_needs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }

            if (existing == null && writable.size > 1) {
                section(stringResource(R.string.contacts_edit_account), stringResource(R.string.contacts_edit_account_footer)) {
                    for (account in writable) {
                        CheckRow(account.displayLabel, target == account.id) {
                            if (target != account.id) {
                                target = account.id
                                // The lists belong to the account: what was picked no longer exists in the other one.
                                draft = draft.copy(listIds = emptySet())
                                base = base.copy(listIds = emptySet())
                            }
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(draft.firstName, stringResource(R.string.contacts_edit_firstName), "contact-first", KeyboardCapitalization.Words) { draft = draft.copy(firstName = it) }
                Field(draft.lastName, stringResource(R.string.contacts_edit_lastName), "contact-last", KeyboardCapitalization.Words) { draft = draft.copy(lastName = it) }
                Field(draft.company, stringResource(R.string.contacts_edit_company), "contact-company", KeyboardCapitalization.Words) { draft = draft.copy(company = it) }
            }

            section(stringResource(R.string.contacts_edit_numbers), null) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    draft.phones.forEachIndexed { index, phone ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CountryPicker(phone.country) { picked -> draft = draft.withPhone(index) { it.copy(countryRegion = picked.region) } }
                            LabelPicker(phone.label) { label -> draft = draft.withPhone(index) { it.copy(label = label) } }
                            OutlinedTextField(
                                value = phone.number,
                                onValueChange = { value -> draft = draft.withPhone(index) { it.copy(number = value) } },
                                label = { Text(stringResource(R.string.contacts_edit_number)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.weight(1f).testTag("contact-number-$index"),
                            )
                            IconButton(onClick = { draft = draft.removingPhone(index) }, enabled = draft.phones.size > 1 || phone.number.isNotEmpty()) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.contacts_edit_removeNumber))
                            }
                        }
                    }

                    TextButton(onClick = { draft = draft.addingPhone() }, modifier = Modifier.testTag("contact-add-number")) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text(stringResource(R.string.contacts_edit_addNumber), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }

            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.email,
                    onValueChange = { draft = draft.copy(email = it) },
                    label = { Text(stringResource(R.string.contacts_edit_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth().testTag("contact-email"),
                )
                OutlinedTextField(
                    value = draft.notes,
                    onValueChange = { draft = draft.copy(notes = it) },
                    label = { Text(stringResource(R.string.contacts_edit_notes)) },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().testTag("contact-notes"),
                )
            }

            if (lists.isNotEmpty()) {
                section(stringResource(R.string.contacts_edit_lists), null) {
                    for (list in lists) {
                        CheckRow(list.name, list.id in draft.listIds) {
                            draft = draft.copy(listIds = if (list.id in draft.listIds) draft.listIds - list.id else draft.listIds + list.id)
                        }
                    }
                }
            }
        }
    }

    if (staleNotice) {
        AlertDialog(
            onDismissRequest = { staleNotice = false },
            title = { Text(stringResource(R.string.contacts_edit_stale_title)) },
            text = { Text(stringResource(R.string.contacts_edit_stale_message)) },
            confirmButton = { TextButton(onClick = { staleNotice = false }) { Text(stringResource(R.string.contacts_edit_stale_ok)) } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.contacts_edit_discard_title)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onDismiss() }) { Text(stringResource(R.string.contacts_edit_discard_confirm), color = Brand.hangUp) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.contacts_edit_discard_keep)) } },
        )
    }
}

@Composable
private fun Field(value: String, label: String, tag: String, capitalization: KeyboardCapitalization, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = capitalization),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun LabelPicker(label: ContactPhoneLabel, onPick: (ContactPhoneLabel) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Box {
        FilterChip(selected = false, onClick = { open = true }, label = { Text(contactLabel(label)) }, modifier = Modifier.testTag("contact-label"))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (choice in ContactPhoneLabel.entries) {
                DropdownMenuItem(text = { Text(contactLabel(choice)) }, onClick = { open = false; onPick(choice) })
            }
        }
    }
}

/** The country code for a number typed in national form; a number with its own code (`+32...`) shows that country. */
@Composable
private fun CountryPicker(country: PhoneCountry, onPick: (PhoneCountry) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.contacts_edit_country)
    val options = remember { PhoneCountry.all { java.util.Locale("", it.region).displayCountry } }

    Box {
        FilterChip(
            selected = false,
            onClick = { open = true },
            label = { Text("${country.region} ${country.dialText}") },
            modifier = Modifier.testTag("contact-country").semantics { contentDescription = "$description: ${java.util.Locale("", country.region).displayCountry} ${country.dialText}" },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (choice in options) {
                DropdownMenuItem(text = { Text("${java.util.Locale("", choice.region).displayCountry} (${choice.dialText})") }, onClick = { open = false; onPick(choice) })
            }
        }
    }
}
