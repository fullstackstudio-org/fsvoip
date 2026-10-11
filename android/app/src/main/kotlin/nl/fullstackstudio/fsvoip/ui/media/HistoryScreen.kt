// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.media

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.StoredAccount
import nl.fullstackstudio.fsvoip.ui.Brand
import nl.fullstackstudio.fsvoip.ui.InitialsAvatar
import nl.fullstackstudio.fsvoip.ui.ShellActions

/**
 * "Recent": the calls of the PBX, newest first, for everyone on the team (the phone's own calls while the server cannot say).
 * Missed calls are red. An admin can open the recording of a call; a `user` never sees one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(model: AppModel) {
    val context = LocalContext.current
    val hub = remember { model.mediaHub(context) }
    val history = remember { model.historyModel(context) }
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val recents by model.recents.collectAsStateWithLifecycle()
    val pages by history.pages.collectAsStateWithLifecycle()
    val states by history.states.collectAsStateWithLifecycle()
    val loadingMore by history.isLoadingMore.collectAsStateWithLifecycle()
    model.settingsRevision.collectAsStateWithLifecycle().value

    var filter by rememberSaveable { mutableStateOf(HistoryEntry.Filter.ALL) }
    var kind by rememberSaveable { mutableStateOf<HistoryEntry.Kind?>(null) }
    var chosenAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var showsFilter by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmsClear by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val loadingDescription = stringResource(R.string.calls_history_loading)

    val account: StoredAccount? = chosenAccountId?.let { model.account(it) }
        ?: model.defaultOutgoingAccountId?.let { model.account(it) }
        ?: accounts.firstOrNull()
    val all: List<HistoryEntry> = remember(pages, recents, account?.id, account?.extensionName) {
        account?.let { history.entries(it.id, recents, it.extensionName) } ?: emptyList()
    }
    val rows = remember(all, filter, kind) { HistoryFilter.apply(all, filter, kind) }
    val state = account?.let { states[it.id] } ?: HistoryModel.LoadState.IDLE
    val selected = selectedId?.let { id -> all.firstOrNull { it.id == id } }

    LaunchedEffect(account?.id) { account?.let { history.load(it.id) } }

    // Leaving the tab stops a recording that plays.
    DisposableEffect(Unit) { onDispose { history.stopPlayer() } }

    // Automatic actions for the demo screens.
    LaunchedEffect(all.size, account?.id) {
        val auto = MediaDemoFlags.auto

        if (auto == "callrecording" || auto == "callrecording-gone") {
            val wantsGone = auto == "callrecording-gone"
            val entry = all.firstOrNull { !it.isLocal && it.hasRecording && (it.id.endsWith("08") == wantsGone) }

            if (entry != null && MediaDemoFlags.take(auto)) {
                selectedId = entry.id
                history.play(entry)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_recents)) },
                actions = {
                    if (recents.isNotEmpty()) {
                        TextButton(onClick = { confirmsClear = true }, modifier = Modifier.testTag("history-clear")) { Text(stringResource(R.string.recents_clear)) }
                    }
                    ShellActions(model)
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            HistoryControls(
                filter = filter,
                onFilter = { filter = it },
                filterActive = kind != null || (accounts.size > 1 && chosenAccountId != null),
                onOpenFilter = { showsFilter = true },
            )

            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        account?.let { history.load(it.id) }
                        refreshing = false
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                if (account == null) {
                    EmptyHistory(stringResource(R.string.recents_empty_title), stringResource(R.string.calls_history_empty_message))
                } else if (rows.isEmpty()) {
                    when {
                        state == HistoryModel.LoadState.LOADING && all.isEmpty() ->
                            Box(Modifier.fillMaxSize().semantics { contentDescription = loadingDescription }, contentAlignment = Alignment.Center) {
                                androidx.compose.material3.CircularProgressIndicator()
                            }
                        all.isNotEmpty() ->
                            EmptyHistory(
                                stringResource(if (filter == HistoryEntry.Filter.MISSED) R.string.calls_history_empty_missed_title else R.string.calls_history_empty_filtered_title),
                                stringResource(R.string.calls_history_empty_filtered_message),
                            )
                        else ->
                            EmptyHistory(
                                stringResource(R.string.recents_empty_title),
                                stringResource(if (state == HistoryModel.LoadState.UNAVAILABLE) R.string.calls_history_empty_unavailable else R.string.calls_history_empty_message),
                            )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().testTag("history-list")) {
                        if (state == HistoryModel.LoadState.UNAVAILABLE) {
                            item {
                                Text(
                                    stringResource(R.string.calls_history_unavailable_notice),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("history-unavailable"),
                                )
                            }
                        }

                        items(rows, key = { it.id }) { entry ->
                            HistoryRow(entry, history.title(entry)) { selectedId = entry.id }
                            HorizontalDivider(Modifier.padding(start = 16.dp))
                        }

                        item {
                            if (history.nextMonth(account.id) != null) {
                                TextButton(
                                    onClick = { scope.launch { history.loadMore(account.id) } },
                                    enabled = !loadingMore,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("history-more"),
                                ) { Text(stringResource(R.string.calls_history_more), fontWeight = FontWeight.SemiBold) }
                            } else if (history.isTruncated(account.id)) {
                                Text(
                                    stringResource(R.string.calls_history_truncated),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showsFilter) {
        HistoryFilterSheet(
            accounts = accounts,
            kind = kind,
            onKind = { kind = it },
            accountId = account?.id,
            onAccount = { chosenAccountId = it },
            onDismiss = { showsFilter = false },
        )
    }

    selected?.let { entry ->
        HistoryDetailSheet(model, hub, history, entry, all) {
            history.stopPlayer()
            selectedId = null
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryControls(filter: HistoryEntry.Filter, onFilter: (HistoryEntry.Filter) -> Unit, filterActive: Boolean, onOpenFilter: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val options = listOf(HistoryEntry.Filter.ALL to R.string.calls_history_filter_all, HistoryEntry.Filter.MISSED to R.string.calls_history_filter_missed)

        SingleChoiceSegmentedButtonRow(Modifier.weight(1f).testTag("history-segments")) {
            options.forEachIndexed { index, (value, title) ->
                SegmentedButton(
                    selected = filter == value,
                    onClick = { onFilter(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text(stringResource(title)) }
            }
        }

        FilterButton(stringResource(R.string.calls_history_filter_button), filterActive, Modifier.testTag("history-filter"), onOpenFilter)
    }
}

/** The "Filter" pill: filled when a filter is on. */
@Composable
fun FilterButton(title: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (active) {
        FilledTonalButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(title, Modifier.padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(title, Modifier.padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyHistory(title: String, message: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        MediaEmpty(Icons.Filled.History, title, message)
    }
}

// MARK: Row

@Composable
private fun kindWord(entry: HistoryEntry): String = when {
    entry.isMissed -> stringResource(R.string.calls_history_kind_missed)
    entry.kind == HistoryEntry.Kind.INCOMING -> stringResource(R.string.calls_history_kind_incoming)
    entry.kind == HistoryEntry.Kind.OUTGOING -> stringResource(R.string.calls_history_kind_outgoing)
    else -> stringResource(R.string.calls_history_kind_internal)
}

@Composable
private fun HistoryRow(entry: HistoryEntry, title: String, onClick: () -> Unit) {
    val word = kindWord(entry)
    val subtitle = buildList {
        add(word)
        entry.number?.takeIf { it != title }?.let { add(it) }
        entry.ourNumber?.takeIf { it.isNotEmpty() }?.let { add(it) }
        if (entry.durationLabel.isNotEmpty()) add(entry.durationLabel)
    }.joinToString(" · ")
    val tint = if (entry.isMissed) Brand.hangUp else MaterialTheme.colorScheme.onSurfaceVariant
    val handledBy = entry.extensionName?.let { stringResource(R.string.calls_history_handledBy, it) }
    val hint = stringResource(R.string.calls_history_row_hint)

    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(onClickLabel = hint, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(title, subtitle, entry.whenLabel, handledBy).joinToString(", ") }
            .testTag("history-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (entry.isMissed) Brand.hangUp else androidx.compose.ui.graphics.Color.Unspecified,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    when (entry.kind) {
                        HistoryEntry.Kind.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
                        HistoryEntry.Kind.OUTGOING -> Icons.AutoMirrored.Filled.CallMade
                        HistoryEntry.Kind.INTERNAL -> Icons.Filled.SwapHoriz
                    },
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.padding(top = 2.dp).size(15.dp),
                )
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = tint, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        Text(entry.whenLabel, style = MaterialTheme.typography.bodySmall, color = tint, textAlign = androidx.compose.ui.text.style.TextAlign.End)

        // Who took (or made) the call, like the avatar at the right of a row in a colleague list.
        InitialsAvatar(entry.extensionName, 38)
    }
}

// MARK: Filter sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryFilterSheet(
    accounts: List<StoredAccount>,
    kind: HistoryEntry.Kind?,
    onKind: (HistoryEntry.Kind?) -> Unit,
    accountId: String?,
    onAccount: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.calls_history_filter_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            SectionLabel(stringResource(R.string.calls_history_filter_kind))
            ChoiceRow(stringResource(R.string.calls_history_kind_all), kind == null, Modifier.testTag("history-kind-all")) { onKind(null) }
            ChoiceRow(stringResource(R.string.calls_history_kind_incoming), kind == HistoryEntry.Kind.INCOMING, Modifier.testTag("history-kind-incoming")) { onKind(HistoryEntry.Kind.INCOMING) }
            ChoiceRow(stringResource(R.string.calls_history_kind_outgoing), kind == HistoryEntry.Kind.OUTGOING, Modifier.testTag("history-kind-outgoing")) { onKind(HistoryEntry.Kind.OUTGOING) }
            ChoiceRow(stringResource(R.string.calls_history_kind_internal), kind == HistoryEntry.Kind.INTERNAL, Modifier.testTag("history-kind-internal")) { onKind(HistoryEntry.Kind.INTERNAL) }

            if (accounts.size > 1) {
                SectionLabel(stringResource(R.string.calls_history_filter_account))

                for (account in accounts) {
                    ChoiceRow(account.displayLabel, account.id == accountId) { onAccount(account.id) }
                }
            }

            TextButton(onClick = onDismiss, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) { Text(stringResource(R.string.action_close)) }
        }
    }
}

// MARK: Detail sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryDetailSheet(model: AppModel, hub: MediaHub, history: HistoryModel, entry: HistoryEntry, entries: List<HistoryEntry>, onDismiss: () -> Unit) {
    val nowPlaying by history.nowPlaying.collectAsStateWithLifecycle()
    val banner by history.banner.collectAsStateWithLifecycle()
    val goneIds by history.goneIds.collectAsStateWithLifecycle()
    val access by hub.access.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var copied by remember { mutableStateOf(false) }
    val title = history.title(entry)
    val word = kindWord(entry)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Header: the name (or the number, large) and whether it was missed.
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val unnamed = entry.number != null && title == entry.number

                    if (unnamed) {
                        Text(entry.number ?: "", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    } else {
                        InitialsAvatar(title, 64)
                        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        entry.number?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }

                    if (entry.isMissed) {
                        Text(stringResource(R.string.calls_history_kind_missed), style = MaterialTheme.typography.titleSmall, color = Brand.hangUp)
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)) {
                    RoundAction(Icons.Filled.Phone, stringResource(R.string.calls_history_action_call), primary = true, enabled = entry.number != null, modifier = Modifier.testTag("history-call")) {
                        entry.number?.let { number ->
                            onDismiss()
                            model.call(number, entry.accountId.takeIf { model.account(it) != null })
                        }
                    }

                    entry.number?.let { number ->
                        RoundAction(
                            if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                            stringResource(if (copied) R.string.calls_history_action_copied else R.string.calls_history_action_copy),
                            primary = false,
                            modifier = Modifier.testTag("history-copy"),
                        ) {
                            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("number", number))) }
                            copied = true
                        }
                    }
                }

                Column {
                    DetailRow(stringResource(R.string.calls_history_detail_when), entry.whenLabel)

                    if (entry.durationLabel.isNotEmpty()) DetailRow(stringResource(R.string.calls_history_detail_duration), entry.durationLabel)

                    DetailRow(stringResource(R.string.calls_history_detail_kind), word)

                    entry.ourNumber?.takeIf { it.isNotEmpty() }?.let { DetailRow(stringResource(R.string.calls_history_detail_ourNumber), it) }

                    entry.extensionName?.takeIf { it.isNotEmpty() }?.let {
                        DetailRow(stringResource(if (entry.kind == HistoryEntry.Kind.OUTGOING) R.string.calls_history_detail_madeBy else R.string.calls_history_detail_handledBy), it)
                    }

                    entry.countryName?.takeIf { it.isNotEmpty() }?.let { DetailRow(stringResource(R.string.calls_history_detail_country), it) }
                }

                // Only an admin pairing is offered a recording; for a `user` nothing here exists, not even a disabled button.
                if (history.canPlayRecording(entry) || history.hasExpiredRecording(entry)) {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        banner?.let { MediaBannerView(it) }

                        if (history.isRecordingGone(entry) || entry.id in goneIds) {
                            Text(stringResource(R.string.calls_history_recording_gone), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("history-recording-gone"))
                        } else {
                            FilledTonalButton(
                                onClick = { scope.launch { history.play(entry) } },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("history-play-recording"),
                            ) {
                                Icon(Icons.Filled.PlayCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                                Text(stringResource(R.string.calls_history_recording_play), Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            nowPlaying?.let { playing ->
                AudioPlayerBar(hub.player, playing, MediaKind.RECORDING, onClose = history::stopPlayer, onRetry = { scope.launch { history.retryPlaying(entries) } })
            }
        }
    }

    // `access` is read so the recording part disappears when a 403 takes the right away.
    access.size
}
