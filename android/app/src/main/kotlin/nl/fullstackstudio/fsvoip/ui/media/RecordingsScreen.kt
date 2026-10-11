// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.media

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.CallItem
import nl.fullstackstudio.fsvoip.ui.Brand

/** "Opnames" (admins): the calls of one month that were recorded; tap one to listen. Behind the local check. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(model: AppModel, accountId: String, back: () -> Unit) {
    val context = LocalContext.current
    val hub = remember { model.mediaHub(context) }

    BackHandler { back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calls_media_recordings_title)) },
                navigationIcon = { IconButton(onClick = back, modifier = Modifier.testTag("recordings-back")) { BackIcon(stringResource(R.string.calls_action_back)) } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            MediaGate(
                hub = hub,
                reason = GateReason.RECORDINGS,
                message = stringResource(R.string.calls_media_lock_message_recordings),
                isAllowed = { hub.hasRecordings(accountId) },
                onLost = back,
            ) {
                RecordingsContent(hub, accountId)
            }
        }
    }
}

/**
 * The door of a protected screen: the local check first, valid for five minutes together with the other protected parts. When the
 * right goes away while the screen is open, it closes ([onLost]).
 */
@Composable
fun MediaGate(hub: MediaHub, reason: GateReason, message: String, isAllowed: () -> Boolean, onLost: () -> Unit, content: @Composable () -> Unit) {
    val access by hub.access.collectAsStateWithLifecycle()
    var lock by remember { mutableStateOf(GateLock.CHECKING) }
    val scope = rememberCoroutineScope()

    suspend fun unlock() {
        if (!isAllowed()) return

        lock = when (hub.gate.ensureUnlocked(reason)) {
            GateResult.UNLOCKED -> GateLock.OPEN
            GateResult.CANCELLED, GateResult.FAILED -> GateLock.LOCKED
            GateResult.UNAVAILABLE -> GateLock.NO_SCREEN_LOCK
        }
    }

    LaunchedEffect(Unit) { unlock() }

    // Coming back after five idle minutes: ask again before showing anything, and stop what was playing.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (lock == GateLock.OPEN && !hub.gate.isUnlocked) {
            hub.player.stop()
            lock = GateLock.CHECKING
            scope.launch { unlock() }
        }
    }

    LaunchedEffect(access) { if (!isAllowed()) onLost() }

    when (lock) {
        GateLock.OPEN -> content()
        GateLock.CHECKING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        GateLock.LOCKED -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
            MediaLockCard(stringResource(R.string.calls_media_lock_title), message, stringResource(R.string.calls_pbx_lock_unlock)) { scope.launch { unlock() } }
        }
        GateLock.NO_SCREEN_LOCK -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
            MediaLockCard(stringResource(R.string.calls_pbx_lock_noPasscode_title), stringResource(R.string.calls_media_lock_noPasscode), null)
        }
    }
}

private enum class GateLock { CHECKING, LOCKED, OPEN, NO_SCREEN_LOCK }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingsContent(hub: MediaHub, accountId: String) {
    val model = remember(accountId) { RecordingsModel(accountId, hub) }
    val ui by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val words = RecordingWords(
        anonymous = stringResource(R.string.calls_media_voicemail_anonymous),
        inbound = stringResource(R.string.calls_media_direction_inbound),
        outbound = stringResource(R.string.calls_media_direction_outbound),
        internal = stringResource(R.string.calls_media_direction_internal),
    )
    var refreshing by remember { mutableStateOf(false) }
    var monthMenu by remember { mutableStateOf(false) }
    val rows = remember(ui) { model.rows }
    val months = remember(ui) { model.months }

    LaunchedEffect(model) {
        model.load()

        // Demo mode: start the first recording.
        if (MediaDemoFlags.take("recordingplay")) model.rows.firstOrNull { it.hasRecording }?.let { model.play(it, words) }
    }

    DisposableEffect(model) {
        onDispose {
            model.close()
            hub.player.stop()
        }
    }

    Column(Modifier.fillMaxSize().testTag("recordings-list")) {
        ui.banner?.let { MediaBannerView(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    model.load()
                    refreshing = false
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (ui.page != null) {
                    SectionLabel(stringResource(R.string.calls_media_recordings_month))

                    val monthLabel = stringResource(R.string.calls_media_recordings_month)

                    Box {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { monthMenu = true }.padding(horizontal = 16.dp)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = monthLabel
                                    stateDescription = model.monthTitle
                                }
                                .testTag("recordings-month"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                            Text(model.monthTitle.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        DropdownMenu(expanded = monthMenu, onDismissRequest = { monthMenu = false }) {
                            for (month in months) {
                                DropdownMenuItem(
                                    text = { Text(MediaFormat.month(month).replaceFirstChar { it.uppercase() }, fontWeight = if (month == ui.page?.month) FontWeight.SemiBold else FontWeight.Normal) },
                                    onClick = {
                                        monthMenu = false
                                        scope.launch { model.select(month) }
                                    },
                                )
                            }
                        }
                    }

                    HorizontalDivider()
                }

                when {
                    ui.page == null -> if (ui.isLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        val failure = ui.failure

                        if (failure != null) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                                MediaEmpty(
                                    Icons.Filled.Waves,
                                    stringResource(R.string.calls_media_recordings_title),
                                    mediaFailureText(failure, MediaKind.RECORDING),
                                    actionTitle = if (failure.isRetryable) stringResource(R.string.action_retry) else null,
                                    onAction = { scope.launch { model.load() } },
                                )
                            }
                        }
                    }
                    ui.isLoading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    rows.isEmpty() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                        MediaEmpty(Icons.Filled.Waves, stringResource(R.string.calls_media_recordings_empty_title), stringResource(R.string.calls_media_recordings_empty_message))
                    }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(rows, key = { it.id }) { call ->
                            RecordingRow(
                                title = model.title(call, words),
                                subtitle = model.subtitle(call, words),
                                isGone = model.isGone(call) || call.recordingExpired,
                                isPlaying = ui.nowPlaying?.id == call.id,
                            ) { model.play(call, words) }
                            HorizontalDivider(Modifier.padding(start = 56.dp))
                        }

                        item {
                            val footer = stringResource(R.string.calls_media_recordings_footer) + if (model.isTruncated) "\n" + stringResource(R.string.calls_media_recordings_truncated) else ""

                            Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }

        ui.nowPlaying?.let { playing ->
            AudioPlayerBar(hub.player, playing, MediaKind.RECORDING, onClose = model::closePlayer, onRetry = { model.retryPlaying(words) })
        }
    }
}

@Composable
private fun RecordingRow(title: String, subtitle: String, isGone: Boolean, isPlaying: Boolean, onClick: () -> Unit) {
    val goneWord = stringResource(R.string.calls_media_unavailable_row)
    val hint = stringResource(R.string.calls_media_row_hint)
    val description = listOfNotNull(title, subtitle, goneWord.takeIf { isGone }).joinToString(", ")

    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(enabled = !isGone, onClickLabel = if (isGone) null else hint, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (isPlaying) stateDescription = "▶"
            }
            .testTag("media-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            if (isPlaying) Icons.Filled.GraphicEq else Icons.Filled.PlayCircle,
            contentDescription = null,
            tint = if (isPlaying) MaterialTheme.colorScheme.primary else if (isGone) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(28.dp),
        )

        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (isGone) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.ui.graphics.Color.Unspecified, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)

            if (isGone) Text(goneWord, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
