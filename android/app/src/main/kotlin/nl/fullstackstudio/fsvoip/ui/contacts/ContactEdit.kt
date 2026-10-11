// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.contacts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.contacts.ContactDraft
import nl.fullstackstudio.fsvoip.contacts.ContactEditRules
import nl.fullstackstudio.fsvoip.contacts.ContactFailure
import nl.fullstackstudio.fsvoip.contacts.PhoneCountry
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.ContactPhoneLabel
import nl.fullstackstudio.fsvoip.ui.section
import nl.fullstackstudio.fsvoip.ui.SwitchRow

/** New contact / edit a contact of the customer's address book. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactEditScreen(model: AppModel, kit: ContactsKit, entryId: String?) {
    val hub = kit.hub
    val state = kit.edit
    val isNew = entryId == null
    val formKey = entryId ?: "new"
    val entries by hub.entries.collectAsStateWithLifecycle()
    val accountStates by hub.accountStates.collectAsStateWithLifecycle()
    val accounts by model.accounts.collectAsStateWithLifecycle()
    var confirmsDiscard by remember { mutableStateOf(false) }

    // Fill the form once per contact; a rotation finds it filled already and leaves what was typed alone.
    LaunchedEffect(formKey) {
        if (state.key == formKey) return@LaunchedEffect

        state.reset()
        state.key = formKey

        if (entryId == null) {
            state.accountId = hub.writableAccountIds.firstOrNull()
            state.original = state.draft

            return@LaunchedEffect
        }

        val entry = entries.firstOrNull { it.id == entryId }
        val id = entry?.contactId
        val account = entry?.let { hub.writeAccountId(it) }

        if (entry == null || id == null || account == null) {
            state.reset()
            kit.nav.home()

            return@LaunchedEffect
        }

        state.contactId = id
        state.accountId = account
        // Show what we have at once; the notes and lists come from the server.
        state.draft = ContactDraft.from(entry)
        state.original = state.draft
        state.expectedUpdatedAt = entry.updatedAt
        state.isLoading = true
        reload(kit, account, id, silently = true)
        state.isLoading = false
    }

    fun close() {
        state.reset()
        kit.nav.back()
    }

    fun requestClose() {
        if (state.isDirty && !state.isSaving) confirmsDiscard = true else close()
    }

    fun save() {
        val accountId = state.accountId ?: return

        if (state.isSaving || !ContactEditRules.canSave(state.draft)) return

        state.isSaving = true
        state.failure = null

        val prepared = ContactEditRules.prepared(state.draft, state.countries)
        val contactId = state.contactId
        val expected = state.expectedUpdatedAt

        // The kit's scope, not the screen's: a save that is under way survives a rotation.
        kit.scope.launch {
            try {
                if (isNew) {
                    hub.create(prepared, accountId)
                } else {
                    if (contactId == null || expected == null) throw ApiException.NotFound()

                    hub.update(prepared, contactId, expected, accountId)
                }

                state.reset()
                kit.nav.back()
            } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                throw error
            } catch (error: ApiException.Stale) {
                // Someone else changed it first: take the server's version and tell the user.
                if (contactId != null) reload(kit, accountId, contactId, silently = false)

                state.showsStale = true
            } catch (error: Exception) {
                state.failure = ContactFailure.of(error)

                if (error is ApiException.Unauthorized) model.refreshAccounts()
            } finally {
                state.isSaving = false
            }
        }
    }

    BackHandler { requestClose() }

    val canSave = ContactEditRules.canSave(state.draft) && !state.isLoading && !state.isSaving

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (isNew) R.string.contacts_edit_titleNew else R.string.contacts_edit_titleEdit)) },
                navigationIcon = {
                    IconButton(onClick = ::requestClose, modifier = Modifier.testTag("contact-edit-close")) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
                actions = {
                    if (state.isSaving) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(24.dp), strokeWidth = 2.dp)

                    TextButton(onClick = ::save, enabled = canSave, modifier = Modifier.testTag("contact-save")) { Text(stringResource(R.string.action_save)) }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val writable = hub.writableAccountIds
            val lists = state.accountId?.let { accountStates[it]?.lists?.filter { list -> list.isEnabled } } ?: emptyList()

            Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                if (isNew && writable.size > 1) {
                    section(stringResource(R.string.contacts_edit_account), stringResource(R.string.contacts_edit_account_footer)) {
                        writable.forEach { id ->
                            val label = accounts.firstOrNull { it.id == id }?.displayLabel ?: id

                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                                    state.accountId = id
                                    state.draft = state.draft.copy(listIds = emptySet())
                                }.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = state.accountId == id, onClick = null)
                                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                section(stringResource(R.string.contacts_edit_name), null) {
                    Fields {
                        EditField(stringResource(R.string.contacts_edit_firstName), state.draft.firstName, "contact-first-name", capitalization = KeyboardCapitalization.Words) { state.draft = state.draft.copy(firstName = it) }
                        EditField(stringResource(R.string.contacts_edit_lastName), state.draft.lastName, "contact-last-name", capitalization = KeyboardCapitalization.Words) { state.draft = state.draft.copy(lastName = it) }
                        EditField(stringResource(R.string.contacts_edit_company), state.draft.company, "contact-company", capitalization = KeyboardCapitalization.Words) { state.draft = state.draft.copy(company = it) }
                    }
                }

                section(stringResource(R.string.contacts_edit_numbers), null) {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.draft.phones.forEach { phone ->
                            PhoneRow(
                                phone = phone,
                                country = PhoneCountry.detect(phone.number) ?: state.countries[phone.id] ?: PhoneCountry.NETHERLANDS,
                                canRemove = state.draft.phones.size > 1,
                                onNumber = { number -> state.draft = state.draft.copy(phones = state.draft.phones.map { if (it.id == phone.id) it.copy(number = number) else it }) },
                                onLabel = { label -> state.draft = state.draft.copy(phones = state.draft.phones.map { if (it.id == phone.id) it.copy(label = label) else it }) },
                                onCountry = { country ->
                                    state.countries = state.countries + (phone.id to country)
                                    state.countryTouched = true
                                },
                                onRemove = { state.draft = state.draft.copy(phones = state.draft.phones.filter { it.id != phone.id }) },
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                            state.draft = state.draft.copy(phones = state.draft.phones + ContactDraft.Phone(label = if (state.draft.phones.isEmpty()) ContactPhoneLabel.MOBILE else ContactPhoneLabel.WORK))
                        }.padding(horizontal = 16.dp).testTag("contact-add-number"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.Filled.AddCircle, contentDescription = null)
                        Text(stringResource(R.string.contacts_edit_addNumber), style = MaterialTheme.typography.bodyLarge)
                    }
                }

                section(stringResource(R.string.contacts_edit_email), null) {
                    Fields {
                        EditField(stringResource(R.string.contacts_edit_email), state.draft.email, "contact-email", keyboardType = KeyboardType.Email) { state.draft = state.draft.copy(email = it) }
                    }
                }

                section(stringResource(R.string.contacts_edit_notes), null) {
                    Fields {
                        EditField(stringResource(R.string.contacts_edit_notes), state.draft.notes, "contact-notes-field", singleLine = false, capitalization = KeyboardCapitalization.Sentences) { state.draft = state.draft.copy(notes = it) }
                    }
                }

                if (lists.isNotEmpty()) {
                    section(stringResource(R.string.contacts_edit_lists), null) {
                        lists.forEach { list ->
                            SwitchRow(list.name, null, checked = list.id in state.draft.listIds, modifier = Modifier.testTag("contact-list-switch")) { on ->
                                state.draft = state.draft.copy(listIds = if (on) state.draft.listIds + list.id else state.draft.listIds - list.id)
                            }
                        }
                    }
                }

                if (!ContactEditRules.canSave(state.draft)) {
                    Text(stringResource(R.string.contacts_edit_needs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }

                state.failure?.let {
                    Text(it.message(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("contact-error"))
                }
            }
        }
    }

    if (state.showsStale) {
        AlertDialog(
            onDismissRequest = { state.showsStale = false },
            title = { Text(stringResource(R.string.contacts_edit_stale_title)) },
            text = { Text(stringResource(R.string.contacts_edit_stale_message)) },
            confirmButton = { TextButton(onClick = { state.showsStale = false }) { Text(stringResource(R.string.contacts_edit_stale_ok)) } },
        )
    }

    if (confirmsDiscard) {
        AlertDialog(
            onDismissRequest = { confirmsDiscard = false },
            title = { Text(stringResource(R.string.contacts_edit_discard_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmsDiscard = false
                    close()
                }) { Text(stringResource(R.string.contacts_edit_discard_confirm), color = nl.fullstackstudio.fsvoip.ui.Brand.hangUp) }
            },
            dismissButton = { TextButton(onClick = { confirmsDiscard = false }) { Text(stringResource(R.string.contacts_edit_discard_keep)) } },
        )
    }
}

/** Fill the form from the server's current version of the contact. */
private suspend fun reload(kit: ContactsKit, accountId: String, contactId: String, silently: Boolean) {
    val state = kit.edit

    try {
        val detail = kit.hub.detail(accountId, contactId)
        state.draft = ContactDraft.from(detail)
        state.original = state.draft
        state.countries = emptyMap()
        state.countryTouched = false
        state.expectedUpdatedAt = detail.contact.updatedAt
    } catch (error: kotlin.coroutines.cancellation.CancellationException) {
        throw error
    } catch (error: Exception) {
        if (!silently) state.failure = ContactFailure.of(error)
    }
}

@Composable
private fun Fields(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun EditField(
    label: String,
    value: String,
    tag: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = capitalization),
        modifier = modifier.fillMaxWidth().testTag(tag),
    )
}

/** Country code, label and number of one phone number. The number is on its own line so it never gets squeezed. */
@Composable
private fun PhoneRow(
    phone: ContactDraft.Phone,
    country: PhoneCountry,
    canRemove: Boolean,
    onNumber: (String) -> Unit,
    onLabel: (ContactPhoneLabel) -> Unit,
    onCountry: (PhoneCountry) -> Unit,
    onRemove: () -> Unit,
) {
    var countryMenu by remember { mutableStateOf(false) }
    var labelMenu by remember { mutableStateOf(false) }
    val countryDescription = stringResource(R.string.contacts_edit_country)
    val labelDescription = stringResource(R.string.contacts_edit_labelPicker)
    val labelTitle = phone.label.title()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                Pill("${country.region} ${country.dialText}", Modifier.semantics { contentDescription = "$countryDescription: ${country.name} ${country.dialText}" }.testTag("contact-country")) { countryMenu = true }

                DropdownMenu(expanded = countryMenu, onDismissRequest = { countryMenu = false }) {
                    PhoneCountry.all.forEach { option ->
                        DropdownMenuItem(text = { Text("${option.name} (${option.dialText})") }, onClick = {
                            countryMenu = false
                            onCountry(option)
                        })
                    }
                }
            }

            Box {
                Pill(labelTitle, Modifier.semantics { contentDescription = "$labelDescription: $labelTitle" }.testTag("contact-label")) { labelMenu = true }

                DropdownMenu(expanded = labelMenu, onDismissRequest = { labelMenu = false }) {
                    contactLabelChoices.forEach { choice ->
                        DropdownMenuItem(text = { Text(choice.title()) }, onClick = {
                            labelMenu = false
                            onLabel(choice)
                        })
                    }
                }
            }

            Box(Modifier.weight(1f))

            if (canRemove) {
                IconButton(onClick = onRemove, modifier = Modifier.testTag("contact-remove-number")) {
                    Icon(Icons.Filled.RemoveCircle, contentDescription = stringResource(R.string.contacts_edit_removeNumber), tint = MaterialTheme.colorScheme.outline)
                }
            }
        }

        EditField(stringResource(R.string.contacts_edit_number), phone.number, "contact-number-field", keyboardType = KeyboardType.Phone, onChange = onNumber)
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.heightIn(min = 48.dp)) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}
