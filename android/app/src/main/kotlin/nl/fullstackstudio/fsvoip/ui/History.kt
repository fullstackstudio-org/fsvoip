// SPDX-License-Identifier: AGPL-3.0-or-later
//
// "Geschiedenis": the calls of the whole PBX (the team history), newest first, a month at a time. Without an answer from the PBX the
// list is the calls made on this phone. Android port of the iOS `HistoryView`.

package nl.fullstackstudio.fsvoip.ui

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.HistoryEntry
import nl.fullstackstudio.fsvoip.core.HistoryModel
import nl.fullstackstudio.fsvoip.core.MediaFailure
import nl.fullstackstudio.fsvoip.core.PbxVocabulary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(model: AppModel) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val recents by model.recents.collectAsStateWithLifecycle()
    val pages by model.history.pages.collectAsStateWithLifecycle()
    val states by model.history.states.collectAsStateWithLifecycle()
    val loadingMore by model.history.isLoadingMore.collectAsStateWithLifecycle()
    val failure by model.history.failure.collectAsStateWithLifecycle()
    model.settingsRevision.collectAsStateWithLifecycle().value
    val scope = rememberCoroutineScope()

    var chosenAccountId by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(HistoryEntry.Filter.ALL) }
    var selected by remember { mutableStateOf<HistoryEntry?>(null) }
    var confirmsClear by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    val accountId = chosenAccountId?.takeIf { id -> accounts.any { it.id == id } } ?: model.defaultOutgoingAccountId
    val account = accounts.firstOrNull { it.id == accountId }

    // Load when the tab opens and when another line is chosen.
    LaunchedEffect(accountId, accounts.size) {
        account?.let { model.history.load(it) }
    }

    if (account == null) {
        HistoryEmpty(stringResource(R.string.recents_empty_title), stringResource(R.string.history_empty_message))
        return
    }

    val state = states[account.id] ?: HistoryModel.LoadState.IDLE
    val all = remember(pages, recents, account.id) { model.history.entries(account.id, model.localHistory(recents)) }
    val shown = HistoryModel.filtered(all, filter)
    val anyServer = pages[account.id].orEmpty().isNotEmpty()
    val nextMonth = model.history.nextMonth(account.id)

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                model.history.load(account)
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = filter == HistoryEntry.Filter.ALL, onClick = { filter = HistoryEntry.Filter.ALL }, label = { Text(stringResource(R.string.history_filter_all)) }, modifier = Modifier.testTag("history-filter-all"))
                FilterChip(selected = filter == HistoryEntry.Filter.MISSED, onClick = { filter = HistoryEntry.Filter.MISSED }, label = { Text(stringResource(R.string.history_filter_missed)) }, modifier = Modifier.testTag("history-filter-missed"))
                Box(Modifier.weight(1f))

                if (accounts.size > 1) {
                    AccountMenu(accounts.map { it.id to it.displayLabel }, account.id) { chosenAccountId = it }
                }

                if (recents.isNotEmpty() && !anyServer) {
                    TextButton(onClick = { confirmsClear = true }) { Text(stringResource(R.string.recents_clear)) }
                }
            }

            if (state == HistoryModel.LoadState.UNAVAILABLE && recents.isNotEmpty()) {
                Text(stringResource(R.string.history_unavailable_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            when {
                shown.isEmpty() && state == HistoryModel.LoadState.LOADING -> HistoryEmpty(stringResource(R.string.history_loading), null)
                shown.isEmpty() && filter == HistoryEntry.Filter.MISSED -> HistoryEmpty(stringResource(R.string.history_empty_missed_title), null)
                shown.isEmpty() && state == HistoryModel.LoadState.UNAVAILABLE -> HistoryEmpty(stringResource(R.string.recents_empty_title), stringResource(R.string.history_empty_unavailable))
                shown.isEmpty() -> HistoryEmpty(stringResource(R.string.recents_empty_title), stringResource(R.string.history_empty_message))
                else -> LazyColumn(Modifier.fillMaxSize().testTag("history-list")) {
                    items(shown, key = { it.id }) { entry ->
                        HistoryRow(model, entry, showsLine = accounts.size > 1) { selected = entry }
                        HorizontalDivider(Modifier.padding(start = 58.dp))
                    }

                    item {
                        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (nextMonth != null) {
                                OutlinedButton(onClick = { scope.launch { model.history.loadMore(account) } }, enabled = !loadingMore, modifier = Modifier.testTag("history-more")) {
                                    Text(stringResource(R.string.history_more))
                                }
                            } else if (model.history.isTruncated(account.id)) {
                                Text(stringResource(R.string.history_truncated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }

    failure?.let { current ->
        AlertDialog(
            onDismissRequest = { model.history.clearFailure() },
            text = { Text(mediaFailureText(current)) },
            confirmButton = { TextButton(onClick = { model.history.clearFailure() }) { Text(stringResource(R.string.action_close)) } },
        )
    }

    selected?.let { entry ->
        HistoryDetail(model, entry, onDismiss = { selected = null })
    }

    if (confirmsClear) {
        AlertDialog(
            onDismissRequest = { confirmsClear = false },
            title = { Text(stringResource(R.string.recents_clear_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmsClear = false
                    model.clearRecents()
                }) { Text(stringResource(R.string.recents_clear_confirm), color = Brand.hangUp) }
            },
            dismissButton = { TextButton(onClick = { confirmsClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun AccountMenu(options: List<Pair<String, String>>, selectedId: String, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selectedId }?.second.orEmpty()

    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
        }

        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((id, name) in options) {
                androidx.compose.material3.DropdownMenuItem(text = { Text(name) }, onClick = {
                    open = false
                    select(id)
                })
            }
        }
    }
}

@Composable
private fun HistoryEmpty(title: String, message: String?) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(34.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp), textAlign = TextAlign.Center)

        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** The title of a line: a name the phone knows, the name of the call itself, the number, or "unknown number". */
@Composable
private fun entryTitle(model: AppModel, entry: HistoryEntry): String {
    val number = entry.number

    return when {
        number != null -> model.name(number) ?: entry.knownName ?: number
        else -> entry.knownName ?: stringResource(R.string.call_anonymous)
    }
}

@Composable
private fun HistoryRow(model: AppModel, entry: HistoryEntry, showsLine: Boolean, onClick: () -> Unit) {
    val title = entryTitle(model, entry)
    val handled = entry.extensionName?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.history_handledBy, it) }
    val subtitle = buildList {
        if (entry.number != null && title != entry.number) add(entry.number)
        handled?.let(::add)
        if (entry.durationLabel.isNotEmpty()) add(entry.durationLabel)
    }.joinToString(" · ")

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp).testTag("history-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            InitialsAvatar(entry.extensionName, size = 36.dp)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    if (entry.kind == HistoryEntry.Kind.OUTGOING) Icons.AutoMirrored.Filled.CallMade else Icons.AutoMirrored.Filled.CallReceived,
                    contentDescription = null,
                    tint = if (entry.isMissed) Brand.hangUp else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (entry.isMissed) Brand.hangUp else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Text(entry.whenLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryDetail(model: AppModel, entry: HistoryEntry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val title = entryTitle(model, entry)
    val kind = stringResource(
        when (entry.kind) {
            HistoryEntry.Kind.INCOMING -> R.string.history_kind_incoming
            HistoryEntry.Kind.OUTGOING -> R.string.history_kind_outgoing
            HistoryEntry.Kind.INTERNAL -> R.string.history_kind_internal
        },
    )
    val copied = stringResource(R.string.history_action_copied)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState()).testTag("history-detail")) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

            DetailLine(stringResource(R.string.history_detail_when), entry.whenLabel)
            DetailLine(stringResource(R.string.history_detail_duration), entry.durationLabel)
            DetailLine(stringResource(R.string.history_detail_kind), kind)
            DetailLine(stringResource(R.string.history_detail_ourNumber), entry.ourNumber?.let(PbxVocabulary::formatNumber).orEmpty())
            DetailLine(
                stringResource(if (entry.kind == HistoryEntry.Kind.OUTGOING) R.string.history_detail_madeBy else R.string.history_detail_handledBy),
                entry.extensionName.orEmpty(),
            )
            DetailLine(stringResource(R.string.history_detail_country), entry.countryName.orEmpty())

            val number = entry.number

            if (number != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        stringResource(R.string.history_action_call),
                        modifier = Modifier.weight(1f).testTag("history-call"),
                        icon = Icons.Filled.Phone,
                        onClick = {
                            onDismiss()
                            model.call(number, entry.accountId.takeIf { id -> model.account(id) != null })
                        },
                    )
                    SecondaryButton(
                        stringResource(R.string.history_action_copy),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("number", number))
                            model.showNotice(copied, isError = false)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    if (value.isEmpty()) return

    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1.4f))
    }
}

/** A failure of media or history in the words of the iOS app. */
@Composable
fun mediaFailureText(failure: MediaFailure): String = when (failure) {
    MediaFailure.Gone -> stringResource(R.string.media_error_gone_voicemail)
    MediaFailure.NotFound -> stringResource(R.string.media_error_notFound)
    MediaFailure.AccessDenied -> stringResource(R.string.media_error_accessDenied)
    MediaFailure.Revoked -> stringResource(R.string.media_error_revoked)
    is MediaFailure.RateLimited -> failure.retryAfterSeconds?.takeIf { it > 0 }?.let { stringResource(R.string.media_error_rateLimited_seconds, it) } ?: stringResource(R.string.media_error_rateLimited)
    MediaFailure.Offline -> stringResource(R.string.media_error_offline)
    MediaFailure.Unavailable -> stringResource(R.string.media_error_unavailable)
    MediaFailure.ReadOnly -> stringResource(R.string.media_error_readOnly)
    MediaFailure.Other -> stringResource(R.string.error_generic)
}
