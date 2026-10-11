// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.contacts

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.contacts.ContactEntry
import nl.fullstackstudio.fsvoip.contacts.ContactFailure
import nl.fullstackstudio.fsvoip.contacts.ContactPhoneEntry
import nl.fullstackstudio.fsvoip.contacts.ContactRecentCalls
import nl.fullstackstudio.fsvoip.contacts.ContactSource
import nl.fullstackstudio.fsvoip.contacts.ContactTimelineModel
import nl.fullstackstudio.fsvoip.contacts.TimelineSymbol
import nl.fullstackstudio.fsvoip.core.ContactDetail
import nl.fullstackstudio.fsvoip.core.RecentCall
import nl.fullstackstudio.fsvoip.core.TimelineItem
import nl.fullstackstudio.fsvoip.ui.Brand
import nl.fullstackstudio.fsvoip.ui.InitialsAvatar
import nl.fullstackstudio.fsvoip.ui.RecentFormat
import nl.fullstackstudio.fsvoip.ui.section

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(model: AppModel, kit: ContactsKit, entryId: String) {
    val hub = kit.hub
    val entries by hub.entries.collectAsStateWithLifecycle()
    val entry = entries.firstOrNull { it.id == entryId }

    // Deleted (here or on another device): back to the list.
    if (entry == null) {
        LaunchedEffect(Unit) { kit.nav.home() }
        return
    }

    var detail by remember(entryId) { mutableStateOf<ContactDetail?>(null) }
    var confirmsDelete by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<ContactFailure?>(null) }
    val scope = rememberCoroutineScope()
    val accountsFlow by model.accounts.collectAsStateWithLifecycle()
    val recents by model.recents.collectAsStateWithLifecycle()
    val favorites by kit.favorites.ids.collectAsStateWithLifecycle()
    val states by hub.accountStates.collectAsStateWithLifecycle()
    val canWrite = hub.canWrite(entry)
    val canDelete = hub.canDelete(entry)
    val isFavorite = entry.id in favorites
    val first = entry.phones.firstOrNull()?.number
    val context = LocalContext.current

    // Notes are not part of the sync; they come with the single contact. Offline: the screen simply has no notes.
    LaunchedEffect(entry.updatedAt) {
        val contactId = entry.contactId
        val accountId = hub.writeAccountId(entry) ?: entry.accountIds.firstOrNull()

        if (entry.source == ContactSource.Customer && contactId != null && accountId != null) {
            detail = runCatching { hub.detail(accountId, contactId) }.getOrNull() ?: detail
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { kit.nav.back() }, modifier = Modifier.testTag("contact-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.contacts_back))
                    }
                },
                actions = {
                    if (canWrite) {
                        TextButton(onClick = { kit.nav.open(ContactsRoute.Edit(entry.id)) }, modifier = Modifier.testTag("contact-edit")) {
                            Text(stringResource(R.string.contacts_detail_edit))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Header(entry)

            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(Icons.Filled.Phone, stringResource(R.string.contacts_call), Modifier.weight(1f), primary = true, enabled = first != null, tag = "contact-call") { first?.let { model.call(it, null) } }
                ActionTile(Icons.Filled.ContentCopy, stringResource(R.string.contacts_copy), Modifier.weight(1f), enabled = first != null, tag = "contact-copy") { first?.let { copyToClipboard(context, it) } }
                ActionTile(
                    if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    stringResource(if (isFavorite) R.string.contacts_favorite_remove else R.string.contacts_favorite_add),
                    Modifier.weight(1f),
                    tag = "contact-favorite",
                ) { kit.favorites.toggle(entry.id) }
            }

            section(stringResource(R.string.contacts_detail_numbers), null) {
                if (entry.phones.isEmpty()) {
                    Text(stringResource(R.string.contacts_detail_noNumbers), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }

                entry.phones.forEach { NumberRow(model, it, accountsFlow.map { account -> account.id to account.displayLabel }) }
            }

            entry.email?.takeIf { it.isNotEmpty() }?.let { email ->
                section(stringResource(R.string.contacts_detail_email), null) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                            runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }.padding(horizontal = 16.dp).testTag("contact-email"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.Filled.Email, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(email, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            if (entry.phones.isNotEmpty()) {
                val calls = ContactRecentCalls.matching(recents, entry.numbers)

                section(stringResource(R.string.contacts_detail_recent), null) {
                    if (calls.isEmpty()) {
                        Text(stringResource(R.string.contacts_detail_recent_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }

                    calls.forEach { RecentCallRow(it) }
                }
            }

            val timelineAccount = hub.writeAccountId(entry) ?: entry.accountIds.firstOrNull()
            val timelineContact = entry.contactId

            if (entry.source == ContactSource.Customer && timelineContact != null && timelineAccount != null) {
                ContactTimelineSection(kit, timelineAccount, timelineContact)
            }

            detail?.notes?.takeIf { it.isNotEmpty() }?.let { notes ->
                section(stringResource(R.string.contacts_detail_notes), null) {
                    Text(notes, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("contact-notes"))
                }
            }

            val listNames = listNames(entry, states)

            if (listNames.isNotEmpty()) {
                section(stringResource(R.string.contacts_detail_lists), null) {
                    Text(listNames.joinToString(", "), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }

            when (entry.source) {
                ContactSource.Device -> SourceNote(R.string.contacts_detail_deviceNote)
                ContactSource.InternalExtensions -> SourceNote(R.string.contacts_detail_colleagueNote)
                else -> Unit
            }

            failure?.let {
                Text(it.message(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("contact-error"))
            }

            if (canDelete) {
                section(null, null) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = !isDeleting) { confirmsDelete = true }.padding(horizontal = 16.dp).testTag("contact-delete"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = Brand.hangUp)
                        Text(stringResource(R.string.contacts_detail_delete), style = MaterialTheme.typography.bodyLarge, color = Brand.hangUp)
                    }
                }
            }
        }
    }

    if (confirmsDelete) {
        AlertDialog(
            onDismissRequest = { confirmsDelete = false },
            title = { Text(stringResource(R.string.contacts_detail_delete_title, entry.displayName)) },
            text = { Text(stringResource(R.string.contacts_detail_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmsDelete = false
                        val contactId = entry.contactId
                        val accountId = hub.deleteAccountId(entry)

                        if (contactId != null && accountId != null) {
                            isDeleting = true
                            failure = null

                            scope.launch {
                                try {
                                    hub.delete(contactId, accountId)
                                    kit.nav.home()
                                } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    failure = ContactFailure.of(error)
                                }

                                isDeleting = false
                            }
                        }
                    },
                    modifier = Modifier.testTag("contact-delete-confirm"),
                ) { Text(stringResource(R.string.contacts_detail_delete_confirm), color = Brand.hangUp) }
            },
            dismissButton = { TextButton(onClick = { confirmsDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun listNames(entry: ContactEntry, states: Map<String, nl.fullstackstudio.fsvoip.contacts.ContactsAccountState>): List<String> {
    val contactId = entry.contactId ?: return emptyList()
    val names = mutableListOf<String>()

    for (accountId in entry.accountIds) {
        for (list in states[accountId]?.lists.orEmpty()) {
            if (list.isEnabled && contactId in list.memberIds && list.name !in names) names += list.name
        }
    }

    return names
}

@Composable
private fun Header(entry: ContactEntry) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InitialsAvatar(entry.displayName, 84)
        Text(entry.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() }.testTag("contact-name"))

        entry.company?.takeIf { it.isNotEmpty() && it != entry.displayName }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun ActionTile(icon: ImageVector, title: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, tag: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = if (primary) Brand.lime else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (primary) Brand.ink else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.heightIn(min = 64.dp).testTag(tag),
    ) {
        Column(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
            Icon(icon, contentDescription = null)
            Text(title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NumberRow(model: AppModel, phone: ContactPhoneEntry, accounts: List<Pair<String, String>>) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val title = phone.label.title()
    val description = stringResource(R.string.contacts_call_number, title, phone.number)

    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp)
                .combinedClickable(onClick = { model.call(phone.number, null) }, onLongClickLabel = stringResource(R.string.contacts_call_options), onLongClick = { menuOpen = true })
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) { contentDescription = description }
                .testTag("contact-number"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(phone.number, style = MaterialTheme.typography.bodyLarge)
            }

            Box(Modifier.size(36.dp).background(Brand.lime, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Phone, contentDescription = null, tint = Brand.ink, modifier = Modifier.size(16.dp))
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (accounts.size > 1) {
                accounts.forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.contacts_call_with, label)) }, onClick = {
                        menuOpen = false
                        model.call(phone.number, id)
                    })
                }
            }

            DropdownMenuItem(text = { Text(stringResource(R.string.contacts_copy)) }, onClick = {
                menuOpen = false
                copyToClipboard(context, phone.number)
            })
        }
    }
}

@Composable
private fun RecentCallRow(call: RecentCall) {
    val missed = call.direction == RecentCall.Direction.INCOMING && call.outcome == RecentCall.Outcome.MISSED
    val outcome = when (call.outcome) {
        RecentCall.Outcome.ANSWERED -> RecentFormat.duration(call.durationSeconds)
        RecentCall.Outcome.MISSED -> stringResource(R.string.recents_outcome_missed)
        RecentCall.Outcome.DECLINED -> stringResource(R.string.recents_outcome_declined)
        RecentCall.Outcome.NOT_ANSWERED -> stringResource(R.string.recents_outcome_notAnswered)
        RecentCall.Outcome.FAILED -> stringResource(R.string.recents_outcome_failed)
    }

    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 6.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            if (call.direction == RecentCall.Direction.OUTGOING) Icons.AutoMirrored.Filled.CallMade else Icons.AutoMirrored.Filled.CallReceived,
            contentDescription = null,
            tint = if (missed) Brand.hangUp else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(outcome, style = MaterialTheme.typography.bodyLarge, color = if (missed) Brand.hangUp else MaterialTheme.colorScheme.onSurface)
            Text(formatDateTime(Instant.ofEpochMilli(call.startedAtMillis)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SourceNote(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

// MARK: Timeline

/** "Activiteit" on the contact screen: what the customer's website knows about this contact. */
@Composable
private fun ContactTimelineSection(kit: ContactsKit, accountId: String, contactId: String) {
    val timeline = remember(accountId, contactId) { ContactTimelineModel({ cursor, limit -> kit.hub.timeline(accountId, contactId, cursor, limit) }) }
    val items by timeline.items.collectAsStateWithLifecycle()
    val phase by timeline.phase.collectAsStateWithLifecycle()
    val hasLoaded by timeline.hasLoaded.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(timeline) { timeline.loadFirstPage() }

    section(stringResource(R.string.timeline_title), null) {
        Column(Modifier.testTag("contact-timeline")) {
            if (items.isEmpty()) {
                when {
                    phase == ContactTimelineModel.Phase.LOADING || (!hasLoaded && phase != ContactTimelineModel.Phase.FAILED) ->
                        TimelineMessage(stringResource(R.string.timeline_loading))
                    phase == ContactTimelineModel.Phase.FAILED -> TimelineRetry { scope.launch { if (hasLoaded) timeline.loadMore() else timeline.loadFirstPage() } }
                    else -> TimelineMessage(stringResource(R.string.timeline_empty))
                }
            }

            items.forEach { TimelineRow(it) }

            if (items.isNotEmpty() && phase == ContactTimelineModel.Phase.FAILED) {
                TimelineRetry { scope.launch { timeline.loadMore() } }
            }

            if (timeline.canLoadMore && phase != ContactTimelineModel.Phase.FAILED) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { scope.launch { timeline.loadMore() } }.padding(horizontal = 16.dp).testTag("timeline-more"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.timeline_more), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun TimelineMessage(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
private fun TimelineRetry(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 16.dp).testTag("timeline-retry"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.timeline_error), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TimelineRow(item: TimelineItem) {
    val subtitle = listOfNotNull(item.ref?.takeIf { it.isNotEmpty() }, item.occurredAtInstant?.let { formatDateTime(it) }).joinToString(" · ")

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {}.testTag("timeline-item"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(TimelineSymbol.of(item.kind).icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp).size(18.dp))

        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)

            item.summary?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }

    HorizontalDivider(Modifier.padding(start = 48.dp), color = MaterialTheme.colorScheme.outlineVariant)
}
