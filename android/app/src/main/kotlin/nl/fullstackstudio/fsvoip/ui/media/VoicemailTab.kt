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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.StoredAccount
import nl.fullstackstudio.fsvoip.core.VoicemailMessage
import nl.fullstackstudio.fsvoip.ui.Brand
import nl.fullstackstudio.fsvoip.ui.InitialsAvatar
import nl.fullstackstudio.fsvoip.ui.ShellActions

private enum class VoicemailFilter { ALL, NEW }

/**
 * The "Voicemail" tab: the messages of the own box, Alles | Nieuw; an admin can switch to other boxes (after the local check).
 * Tapping a message opens its sheet: listen, call back, delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicemailTab(model: AppModel) {
    val context = LocalContext.current
    val hub = remember { model.mediaHub(context) }
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val access by hub.access.collectAsStateWithLifecycle()
    model.settingsRevision.collectAsStateWithLifecycle().value
    var chosenAccountId by rememberSaveable { mutableStateOf<String?>(null) }

    // The accounts with a voicemail box.
    val withVoicemail = accounts.filter { access[it.id]?.hasVoicemail == true }
    val account = chosenAccountId?.let { chosen -> withVoicemail.firstOrNull { it.id == chosen } }
        ?: model.defaultOutgoingAccountId?.let { preferred -> withVoicemail.firstOrNull { it.id == preferred } }
        ?: withVoicemail.firstOrNull()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.calls_media_voicemail_title)) }, actions = { ShellActions(model) }) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (account != null) {
                // Another account is another box: start over with its own model.
                androidx.compose.runtime.key(account.id) {
                    VoicemailContent(model, hub, account, withVoicemail) { chosenAccountId = it }
                }
            } else {
                MediaEmpty(Icons.Filled.Voicemail, stringResource(R.string.calls_voicemail_empty_title), stringResource(R.string.calls_voicemail_empty_message), Modifier.align(Alignment.Center))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicemailContent(app: AppModel, hub: MediaHub, account: StoredAccount, accounts: List<StoredAccount>, selectAccount: (String) -> Unit) {
    val model = remember(account.id) { VoicemailModel(account.id, account.extensionNumber, hub) }
    val ui by model.state.collectAsStateWithLifecycle()
    val access by hub.access.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val anonymous = stringResource(R.string.calls_media_voicemail_anonymous)
    var filter by rememberSaveable { mutableStateOf(VoicemailFilter.ALL) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showsBoxes by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    // Derived values: read again after every change of the state or of the rights.
    val messages = remember(ui, access) { model.messages }
    val rows = if (filter == VoicemailFilter.NEW) messages.filter { it.isNew } else messages
    val selected = selectedId?.let { id -> ui.page?.messages?.firstOrNull { it.id == id } }

    LaunchedEffect(model) {
        model.load()

        // Demo mode: open the first message and start it.
        if (MediaDemoFlags.take("voicemailplay")) {
            model.messages.firstOrNull()?.let {
                selectedId = it.id
                model.play(it, anonymous)
            }
        }

        // The five minutes of the local check run out while the screen stays open.
        while (true) {
            delay(10_000)
            model.refreshLock()
        }
    }

    DisposableEffect(model) {
        onDispose {
            model.close()
            hub.player.stop()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refreshLock() }

    Column(Modifier.fillMaxSize().testTag("voicemail-list")) {
        VoicemailControls(model, ui, access, filter, { filter = it }, accounts.size > 1) { showsBoxes = true }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    model.reload()
                    refreshing = false
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when {
                model.needsUnlock -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    MediaLockCard(
                        title = if (ui.noPasscode) stringResource(R.string.calls_pbx_lock_noPasscode_title) else stringResource(R.string.calls_media_lock_title),
                        message = stringResource(if (ui.noPasscode) R.string.calls_media_lock_noPasscode else R.string.calls_media_lock_message_voicemail),
                        buttonTitle = if (ui.noPasscode) null else stringResource(R.string.calls_pbx_lock_unlock),
                    ) { scope.launch { model.unlock() } }
                }
                ui.page == null -> if (ui.isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
                } else {
                    val failure = ui.failure

                    if (failure != null) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                            MediaEmpty(
                                Icons.Filled.ErrorOutline,
                                stringResource(R.string.calls_voicemail_failure_title),
                                mediaFailureText(failure, MediaKind.VOICEMAIL),
                                actionTitle = if (failure.isRetryable) stringResource(R.string.action_retry) else null,
                                onAction = { scope.launch { model.load() } },
                            )
                        }
                    }
                }
                model.isUnavailable -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                    MediaEmpty(Icons.Filled.CloudOff, stringResource(R.string.calls_media_voicemail_unavailable_title), stringResource(R.string.calls_media_voicemail_unavailable_message))
                }
                rows.isEmpty() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                    val onlyNewIsEmpty = filter == VoicemailFilter.NEW && messages.isNotEmpty()

                    MediaEmpty(
                        Icons.Filled.Voicemail,
                        stringResource(if (onlyNewIsEmpty) R.string.calls_voicemail_empty_new_title else R.string.calls_media_voicemail_empty_title),
                        stringResource(if (onlyNewIsEmpty) R.string.calls_voicemail_empty_new_message else if (model.scope == VoicemailModel.Scope.Own) R.string.calls_media_voicemail_empty_own else R.string.calls_media_voicemail_empty_other),
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }) { message ->
                        VoicemailRow(
                            title = model.title(message, anonymous),
                            subtitle = model.subtitle(message),
                            transcription = message.transcription,
                            isNew = message.isNew,
                            isGone = model.isGone(message),
                        ) { selectedId = message.id }
                        HorizontalDivider(Modifier.padding(start = 16.dp))
                    }

                    item {
                        Text(
                            stringResource(R.string.calls_media_voicemail_footerShort),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    selected?.let { message ->
        VoicemailDetailSheet(app, hub, model, ui, message, anonymous) {
            hub.player.stop()
            selectedId = null
        }
    }

    if (showsBoxes) {
        VoicemailBoxSheet(model, ui, accounts, account.id, selectAccount) { showsBoxes = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicemailControls(
    model: VoicemailModel,
    ui: VoicemailUi,
    access: Map<String, MediaHub.Access>,
    filter: VoicemailFilter,
    onFilter: (VoicemailFilter) -> Unit,
    multipleAccounts: Boolean,
    onOpenBoxes: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val options = listOf(VoicemailFilter.ALL to R.string.calls_voicemail_filter_all, VoicemailFilter.NEW to R.string.calls_voicemail_filter_new)

            SingleChoiceSegmentedButtonRow(Modifier.weight(1f).testTag("voicemail-segments")) {
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

            if (model.canChooseBox || multipleAccounts) {
                val title = if (model.canChooseBox) {
                    when (val scope = ui.scope) {
                        VoicemailModel.Scope.Own -> stringResource(R.string.calls_media_voicemail_scope_own)
                        VoicemailModel.Scope.All -> stringResource(R.string.calls_media_voicemail_scope_all)
                        is VoicemailModel.Scope.Box -> model.boxes.firstOrNull { it.id == scope.id }?.let(model::boxTitle) ?: stringResource(R.string.calls_media_voicemail_scope_all)
                    }
                } else {
                    stringResource(R.string.calls_voicemail_account)
                }

                FilterButton(title, model.canChooseBox && ui.scope != VoicemailModel.Scope.Own, Modifier.widthIn(max = 180.dp).testTag("voicemail-scope"), onOpenBoxes)
            }
        }

        if (model.isReadOnly) {
            MediaNotice(Icons.Filled.Lock, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.calls_media_readOnly_notice))
        }

        ui.banner?.let { MediaBannerView(it) }
    }

    // `access` is read so the controls follow the rights.
    access.size
}

// MARK: Row

@Composable
private fun VoicemailRow(title: String, subtitle: String, transcription: String?, isNew: Boolean, isGone: Boolean, onClick: () -> Unit) {
    val newWord = stringResource(R.string.calls_voicemail_new)
    val goneWord = stringResource(R.string.calls_media_unavailable_row)
    val hint = stringResource(R.string.calls_voicemail_row_hint)
    val description = (if (isNew) "$newWord, " else "") + listOfNotNull(title, subtitle, transcription?.takeIf { it.isNotEmpty() }, goneWord.takeIf { isGone }).joinToString(", ")

    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(onClickLabel = hint, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("media-row"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // New messages: a lime dot in front, the title heavier.
        Box(Modifier.padding(top = 18.dp).size(8.dp).background(if (isNew) Brand.lime else Color.Transparent, CircleShape))

        InitialsAvatar(title.takeIf { it.firstOrNull()?.isDigit() != true }, 40, modifier = Modifier.padding(top = 4.dp))

        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isNew) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isGone) MaterialTheme.colorScheme.outline else Color.Unspecified,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)

            if (!transcription.isNullOrEmpty()) {
                Text(transcription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
            }

            if (isGone) {
                Text(goneWord, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

// MARK: Detail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicemailDetailSheet(app: AppModel, hub: MediaHub, model: VoicemailModel, ui: VoicemailUi, message: VoicemailMessage, anonymous: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var confirmsDelete by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val title = model.title(message, anonymous)
    val playing = ui.nowPlaying?.id == message.id
    val gone = model.isGone(message)
    val readOnly = model.isReadOnly
    val kept = daysLeftText(message.daysLeft)
    val keptUntil = message.daysLeft?.takeIf { it >= 0 }?.let { days ->
        val until = java.time.LocalDate.now().plusDays(days.toLong())
        val format = java.time.format.DateTimeFormatter.ofPattern("d MMMM", java.util.Locale.getDefault())
        stringResource(R.string.calls_voicemail_detail_keptUntil, until.format(format), days)
    } ?: stringResource(R.string.calls_voicemail_detail_retention)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InitialsAvatar(title.takeIf { it.firstOrNull()?.isDigit() != true }, 64)
                    Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)

                    message.caller?.takeIf { it.isNotEmpty() && it != title }?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                ui.banner?.let { MediaBannerView(it, Modifier.padding(horizontal = 16.dp)) }

                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val caller = message.caller?.takeIf { it.isNotEmpty() }

                    if (caller != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)) {
                            RoundAction(Icons.Filled.Phone, stringResource(R.string.calls_voicemail_action_callBack), primary = true, modifier = Modifier.testTag("voicemail-callback")) {
                                onDismiss()
                                app.call(caller, model.accountId)
                            }
                            RoundAction(
                                if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                stringResource(if (copied) R.string.calls_history_action_copied else R.string.calls_history_action_copy),
                                primary = false,
                                modifier = Modifier.testTag("voicemail-copy"),
                            ) {
                                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("number", caller))) }
                                copied = true
                            }
                        }
                    }

                    Button(
                        onClick = { model.play(message, anonymous) },
                        enabled = !gone,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("voicemail-play"),
                    ) {
                        Icon(if (playing) Icons.Filled.GraphicEq else Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(stringResource(if (playing) R.string.calls_voicemail_action_playing else R.string.calls_voicemail_action_play), Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = { confirmsDelete = true },
                        enabled = !readOnly && !ui.isDeleting,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("voicemail-delete"),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = if (readOnly) MaterialTheme.colorScheme.outline else Brand.hangUp, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.calls_media_delete_action), Modifier.padding(start = 8.dp), color = if (readOnly) MaterialTheme.colorScheme.outline else Brand.hangUp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Column {
                    DetailRow(stringResource(R.string.calls_voicemail_detail_received), message.receivedLabel)

                    if (message.durationLabel.isNotEmpty()) DetailRow(stringResource(R.string.calls_history_detail_duration), message.durationLabel)

                    if (model.canChooseBox && message.boxName.isNotEmpty()) DetailRow(stringResource(R.string.calls_voicemail_detail_box), message.boxName)

                    DetailRow(stringResource(R.string.calls_voicemail_detail_kept), kept ?: stringResource(R.string.calls_voicemail_detail_keptUnknown))

                    Text(keptUntil, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }

                message.transcription?.takeIf { it.isNotEmpty() }?.let { text ->
                    Column {
                        SectionLabel(stringResource(R.string.calls_voicemail_detail_transcription))
                        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }

            ui.nowPlaying?.let { current ->
                AudioPlayerBar(hub.player, current, MediaKind.VOICEMAIL, onClose = model::closePlayer, onRetry = { model.retryPlaying(anonymous) })
            }
        }
    }

    if (confirmsDelete) {
        AlertDialog(
            onDismissRequest = { confirmsDelete = false },
            title = { Text(stringResource(R.string.calls_media_delete_title)) },
            text = { Text(stringResource(R.string.calls_media_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmsDelete = false
                        scope.launch {
                            model.delete(message)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.testTag("voicemail-delete-confirm"),
                ) { Text(stringResource(R.string.calls_media_delete_confirm), color = Brand.hangUp) }
            },
            dismissButton = { TextButton(onClick = { confirmsDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

// MARK: Box / account chooser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicemailBoxSheet(model: VoicemailModel, ui: VoicemailUi, accounts: List<StoredAccount>, current: String, selectAccount: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.calls_media_voicemail_scope_header), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            if (accounts.size > 1) {
                SectionLabel(stringResource(R.string.calls_history_filter_account))

                for (account in accounts) {
                    ChoiceRow(account.displayLabel, account.id == current) {
                        selectAccount(account.id)
                        onDismiss()
                    }
                }
            }

            if (model.canChooseBox) {
                SectionLabel(stringResource(R.string.calls_media_voicemail_scope_header))
                ChoiceRow(stringResource(R.string.calls_media_voicemail_scope_own), ui.scope == VoicemailModel.Scope.Own, enabled = model.ownBoxId != null, modifier = Modifier.testTag("voicemail-scope-own")) {
                    scope.launch {
                        model.select(VoicemailModel.Scope.Own)
                        onDismiss()
                    }
                }
                ChoiceRow(stringResource(R.string.calls_media_voicemail_scope_all), ui.scope == VoicemailModel.Scope.All, modifier = Modifier.testTag("voicemail-scope-all")) {
                    scope.launch {
                        model.select(VoicemailModel.Scope.All)
                        onDismiss()
                    }
                }

                val shared = stringResource(R.string.calls_media_voicemail_shared)

                for (box in model.boxes) {
                    ChoiceRow(model.boxTitle(box) + if (box.shared) " ($shared)" else "", ui.scope == VoicemailModel.Scope.Box(box.id)) {
                        scope.launch {
                            model.select(VoicemailModel.Scope.Box(box.id))
                            onDismiss()
                        }
                    }
                }

                if (ui.scope != VoicemailModel.Scope.Own) {
                    Text(stringResource(R.string.calls_media_voicemail_scope_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
    }
}
