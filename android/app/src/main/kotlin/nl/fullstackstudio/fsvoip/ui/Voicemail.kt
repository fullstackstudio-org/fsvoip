// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The "Voicemail" tab: the messages of the own box, newest first, with a player at the bottom (ExoPlayer, the device token in the
// request headers). Android port of the iOS `VoicemailTabView` / `AudioPlayerBar`.

package nl.fullstackstudio.fsvoip.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.MediaFailure
import nl.fullstackstudio.fsvoip.core.MediaFormat
import nl.fullstackstudio.fsvoip.core.PlayerSnapshot
import nl.fullstackstudio.fsvoip.core.VoicemailMessage
import nl.fullstackstudio.fsvoip.core.VoicemailModel
import nl.fullstackstudio.fsvoip.core.VoicemailPage
import nl.fullstackstudio.fsvoip.core.VoicemailScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicemailScreen(model: AppModel) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val nowPlaying by model.nowPlaying.collectAsStateWithLifecycle()
    model.settingsRevision.collectAsStateWithLifecycle().value
    val scope = rememberCoroutineScope()

    var chosenAccountId by remember { mutableStateOf<String?>(null) }
    var onlyNew by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<VoicemailMessage?>(null) }

    val accountId = chosenAccountId?.takeIf { id -> accounts.any { it.id == id } } ?: model.defaultOutgoingAccountId
    val account = accounts.firstOrNull { it.id == accountId }

    if (account == null) {
        VoicemailEmpty(stringResource(R.string.voicemail_empty_title), stringResource(R.string.voicemail_empty_message))
        return
    }

    val voicemail = remember(account.id) { model.voicemailModel(account) }
    val page by voicemail.page.collectAsStateWithLifecycle()
    val loading by voicemail.isLoading.collectAsStateWithLifecycle()
    val failure by voicemail.failure.collectAsStateWithLifecycle()
    val banner by voicemail.banner.collectAsStateWithLifecycle()
    val gone by voicemail.goneIds.collectAsStateWithLifecycle()
    val voicemailScope by voicemail.scope.collectAsStateWithLifecycle()
    val othersUnlocked by voicemail.othersUnlocked.collectAsStateWithLifecycle()
    val noScreenLock by voicemail.noScreenLock.collectAsStateWithLifecycle()
    val snapshot by model.player.snapshot.collectAsStateWithLifecycle()

    LaunchedEffect(account.id) { voicemail.load() }

    // A message the PBX says is past its retention (410 while playing) is marked, not retried.
    LaunchedEffect(snapshot.status, snapshot.failure) {
        if (snapshot.status == PlayerSnapshot.Status.FAILED && snapshot.failure == MediaFailure.Gone) {
            page?.messages?.firstOrNull { "${account.id}/${it.key}" == snapshot.id }?.let(voicemail::markGone)
        }
    }

    // The five minutes of the local check run out while the screen stays open (and when the app comes back).
    fun refreshLock() {
        if (voicemail.refreshLock()) {
            val playingId = nowPlaying?.id
            val playing = page?.messages?.firstOrNull { "${account.id}/${it.key}" == playingId }

            if (playing != null && page?.let { !voicemail.isOwn(it, playing) } == true) model.stopPlayer()
        }
    }

    LaunchedEffect(voicemail) {
        while (true) {
            kotlinx.coroutines.delay(10_000)
            refreshLock()
        }
    }

    androidx.lifecycle.compose.LifecycleResumeEffect(voicemail) {
        refreshLock()

        onPauseOrDispose { }
    }

    val current = page
    val locked = voicemail.canChooseBox && voicemailScope != VoicemailScope.Own && !othersUnlocked
    val messages = current?.let { voicemail.messages(it) }.orEmpty().let { list -> if (onlyNew) list.filter { it.isNew } else list }
    var choosingBox by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !onlyNew, onClick = { onlyNew = false }, label = { Text(stringResource(R.string.voicemail_filter_all)) })
            FilterChip(selected = onlyNew, onClick = { onlyNew = true }, label = { Text(stringResource(R.string.voicemail_filter_new)) }, modifier = Modifier.testTag("voicemail-filter-new"))

            if (voicemail.canChooseBox && current != null) {
                Box {
                    FilterChip(
                        selected = voicemailScope != VoicemailScope.Own,
                        onClick = { choosingBox = true },
                        label = { Text(scopeTitle(voicemail, current, voicemailScope), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.testTag("voicemail-scope"),
                    )
                    VoicemailScopeMenu(voicemail, current, voicemailScope, choosingBox) { choosingBox = false }
                }
            }
        }

        banner?.let { problem ->
            Text(mediaFailureText(problem), style = MaterialTheme.typography.bodySmall, color = Brand.hangUp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("voicemail-banner"))
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    voicemail.load()
                    refreshing = false
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when {
                locked -> VoicemailLocked(noScreenLock) { scope.launch { voicemail.unlock() } }
                loading && current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                failure != null && current == null -> VoicemailEmpty(stringResource(R.string.voicemail_failure_title), mediaFailureText(failure!!))
                current != null && !current.available -> VoicemailEmpty(stringResource(R.string.media_voicemail_unavailable_title), stringResource(R.string.media_voicemail_unavailable_message))
                !model.canSeeVoicemail(account.id) -> VoicemailEmpty(stringResource(R.string.voicemail_empty_title), stringResource(R.string.voicemail_empty_message))
                messages.isEmpty() && onlyNew -> VoicemailEmpty(stringResource(R.string.voicemail_empty_new_title), stringResource(R.string.voicemail_empty_new_message))
                messages.isEmpty() -> VoicemailEmpty(stringResource(R.string.media_voicemail_empty_title), stringResource(if (voicemailScope == VoicemailScope.Own) R.string.media_voicemail_empty_own else R.string.media_voicemail_empty_other))
                else -> LazyColumn(Modifier.fillMaxSize().testTag("voicemail-list")) {
                    items(messages, key = { it.key }) { message ->
                        val id = "${account.id}/${message.key}"
                        val title = messageTitle(model, message)
                        val boxName = if (current != null && voicemail.showsBoxName(current, message)) message.boxName else ""
                        val subtitle = listOf(message.receivedLabel, message.durationLabel, boxName).filter { it.isNotEmpty() }.joinToString(" · ")

                        VoicemailRow(
                            message = message,
                            title = title,
                            subtitle = subtitle,
                            isGone = voicemail.isGone(message) || message.key in gone,
                            playing = snapshot.id == id && snapshot.status == PlayerSnapshot.Status.PLAYING,
                            selected = snapshot.id == id && snapshot.isActive,
                            onPlay = {
                                if (snapshot.id == id && snapshot.isActive) model.player.toggle() else model.playVoicemail(account, message, title, subtitle)
                            },
                            onCallBack = message.caller?.takeIf { it.isNotEmpty() }?.let { number -> { model.call(number, account.id) } },
                            onDelete = { pendingDelete = message },
                        )
                        HorizontalDivider(Modifier.padding(start = 16.dp))
                    }

                    item {
                        Text(stringResource(R.string.media_voicemail_footerShort), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
                    }
                }
            }
        }

        nowPlaying?.let { playing ->
            if (playing.id.startsWith("${account.id}/")) {
                AudioPlayerBar(model, playing.title, playing.subtitle)
            }
        }
    }

    pendingDelete?.let { message ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.media_delete_title)) },
            text = { Text(stringResource(R.string.media_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null

                    if (nowPlaying?.id == "${account.id}/${message.key}") model.stopPlayer()

                    scope.launch { voicemail.delete(message) }
                }) { Text(stringResource(R.string.media_delete_confirm), color = Brand.hangUp) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun scopeTitle(voicemail: VoicemailModel, page: VoicemailPage, scope: VoicemailScope): String = when (scope) {
    VoicemailScope.Own -> stringResource(R.string.media_voicemail_scope_own)
    VoicemailScope.All -> stringResource(R.string.media_voicemail_scope_all)
    is VoicemailScope.Box -> page.boxes.firstOrNull { it.id == scope.id }?.let(voicemail::boxTitle) ?: stringResource(R.string.media_voicemail_scope_all)
}

/** "Voicemail van": my own, all of them, or one box. Anything but my own asks for the local check first. */
@Composable
private fun VoicemailScopeMenu(voicemail: VoicemailModel, page: VoicemailPage, current: VoicemailScope, expanded: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val shared = stringResource(R.string.media_voicemail_shared)

    fun choose(next: VoicemailScope) {
        onDismiss()
        scope.launch { voicemail.select(next) }
    }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.media_voicemail_scope_own), fontWeight = if (current == VoicemailScope.Own) FontWeight.SemiBold else FontWeight.Normal) },
            enabled = voicemail.ownBoxId(page) != null,
            onClick = { choose(VoicemailScope.Own) },
            modifier = Modifier.testTag("voicemail-scope-own"),
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.media_voicemail_scope_all), fontWeight = if (current == VoicemailScope.All) FontWeight.SemiBold else FontWeight.Normal) },
            onClick = { choose(VoicemailScope.All) },
            modifier = Modifier.testTag("voicemail-scope-all"),
        )

        for (box in page.boxes) {
            DropdownMenuItem(
                text = { Text(voicemail.boxTitle(box) + if (box.shared) " ($shared)" else "", fontWeight = if (current == VoicemailScope.Box(box.id)) FontWeight.SemiBold else FontWeight.Normal) },
                onClick = { choose(VoicemailScope.Box(box.id)) },
            )
        }
    }
}

@Composable
private fun VoicemailLocked(noScreenLock: Boolean, onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp).testTag("voicemail-locked"), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(34.dp))
        Text(
            stringResource(if (noScreenLock) R.string.pbx_lock_noScreenLock_title else R.string.media_lock_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            stringResource(if (noScreenLock) R.string.media_lock_noScreenLock_voicemail else R.string.media_lock_message_voicemail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )

        if (!noScreenLock) {
            TextButton(onClick = onUnlock, modifier = Modifier.padding(top = 8.dp).testTag("voicemail-unlock")) { Text(stringResource(R.string.pbx_lock_unlock)) }
        }
    }
}

@Composable
private fun messageTitle(model: AppModel, message: VoicemailMessage): String {
    message.callerName?.takeIf { it.isNotEmpty() }?.let { return it }
    message.caller?.takeIf { it.isNotEmpty() }?.let { return model.name(it) ?: it }

    return stringResource(R.string.media_voicemail_anonymous)
}

@Composable
private fun VoicemailEmpty(title: String, message: String?) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Voicemail, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(34.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))

        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun VoicemailRow(
    message: VoicemailMessage,
    title: String,
    subtitle: String,
    isGone: Boolean,
    playing: Boolean,
    selected: Boolean,
    onPlay: () -> Unit,
    onCallBack: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val daysLeft = message.daysLeft
    val kept = when {
        daysLeft == null -> null
        daysLeft <= 0 -> stringResource(R.string.media_daysLeft_today)
        daysLeft == 1 -> stringResource(R.string.media_daysLeft_one)
        else -> stringResource(R.string.media_daysLeft_many, daysLeft)
    }
    val newLabel = stringResource(R.string.voicemail_new)
    val hint = stringResource(R.string.media_row_hint)

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(enabled = !isGone, onClick = onPlay)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
            .testTag("voicemail-row")
            .semantics(mergeDescendants = true) { contentDescription = "$title, $subtitle. $hint" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).background(if (message.isNew) Brand.lime else androidx.compose.ui.graphics.Color.Transparent, CircleShape).semantics { if (message.isNew) contentDescription = newLabel })

        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)

        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (message.isNew) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)

            if (isGone) {
                Text(stringResource(R.string.media_unavailable_row), style = MaterialTheme.typography.bodySmall, color = Brand.hangUp)
            } else {
                message.transcription?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                kept?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        if (onCallBack != null) {
            IconButton(onClick = onCallBack) { Icon(Icons.Filled.Phone, contentDescription = stringResource(R.string.voicemail_action_callBack)) }
        }

        IconButton(onClick = onDelete, modifier = Modifier.testTag("voicemail-delete")) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.media_delete_action)) }
    }
}

/** The bar at the bottom while a message plays: play/pause, 10 seconds back and forward, the position, the speed. */
@Composable
fun AudioPlayerBar(model: AppModel, title: String, subtitle: String) {
    val snapshot by model.player.snapshot.collectAsStateWithLifecycle()
    val playing = snapshot.status == PlayerSnapshot.Status.PLAYING
    val seekable = snapshot.durationMs > 0 && snapshot.status != PlayerSnapshot.Status.FAILED
    val speeds = listOf(1f, 1.5f, 2f)

    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth().testTag("audio-player-bar")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                IconButton(onClick = { model.stopPlayer() }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close)) }
            }

            if (snapshot.status == PlayerSnapshot.Status.FAILED) {
                Text(mediaFailureText(snapshot.failure ?: MediaFailure.Other), style = MaterialTheme.typography.bodySmall, color = Brand.hangUp)
            } else {
                Slider(
                    value = if (snapshot.durationMs > 0) snapshot.positionMs.toFloat() / snapshot.durationMs else 0f,
                    onValueChange = { model.player.seekTo((it * snapshot.durationMs).toLong()) },
                    enabled = seekable,
                    colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.onSurface, activeTrackColor = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "" },
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.media_player_position_value, MediaFormat.clock(snapshot.positionMs), MediaFormat.clock(snapshot.durationMs)), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))

                    IconButton(onClick = { model.player.skip(-10_000) }, enabled = seekable) { Icon(Icons.Filled.Replay10, contentDescription = stringResource(R.string.media_player_back)) }

                    if (snapshot.status == PlayerSnapshot.Status.LOADING) {
                        CircularProgressIndicator(Modifier.size(32.dp).padding(4.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { model.player.toggle() }, modifier = Modifier.testTag("player-toggle")) {
                            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = stringResource(if (playing) R.string.media_player_pause else R.string.media_player_play))
                        }
                    }

                    IconButton(onClick = { model.player.skip(10_000) }, enabled = seekable) { Icon(Icons.Filled.Forward10, contentDescription = stringResource(R.string.media_player_forward)) }

                    TextButton(onClick = { model.player.setSpeed(speeds[(speeds.indexOf(snapshot.speed).coerceAtLeast(0) + 1) % speeds.size]) }) {
                        Text(if (snapshot.speed == 1f) "1×" else "${snapshot.speed}×")
                    }
                }
            }
        }
    }
}
