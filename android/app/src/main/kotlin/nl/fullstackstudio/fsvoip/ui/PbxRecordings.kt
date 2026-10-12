// SPDX-License-Identifier: AGPL-3.0-or-later
//
// "Opnames" (administrator pairings): the calls of one month that were recorded; tap one to listen. The whole screen sits behind the
// local check (fingerprint, face or screen lock). Android port of the iOS `RecordingsView`.

package nl.fullstackstudio.fsvoip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.MediaFailure
import nl.fullstackstudio.fsvoip.core.PbxCall
import nl.fullstackstudio.fsvoip.core.PbxCallDirection
import nl.fullstackstudio.fsvoip.core.PbxCallsPage

/** `2026-10` -> `oktober 2026`. */
fun monthTitle(key: String, locale: Locale = Locale.getDefault()): String = runCatching {
    LocalDate.parse("$key-01").format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
}.getOrDefault(key)

@Composable
fun RecordingsScreen(model: AppModel, accountId: String, back: () -> Unit) {
    SecureScreen()
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    val nowPlaying by model.nowPlaying.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var page by remember(accountId) { mutableStateOf<PbxCallsPage?>(null) }
    var loading by remember(accountId) { mutableStateOf(false) }
    var failure by remember(accountId) { mutableStateOf<MediaFailure?>(null) }
    var gone by remember(accountId) { mutableStateOf(setOf<String>()) }
    var monthMenu by remember { mutableStateOf(false) }

    suspend fun load(month: String?) {
        account ?: return
        loading = true
        try {
            page = model.mediaService.calls(account, month)
            failure = null
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            val result = MediaFailure.classify(error)
            if (result == MediaFailure.Revoked || result == MediaFailure.AccessDenied) model.pbx.onDenied()
            failure = result
        } finally {
            loading = false
        }
    }

    LaunchedEffect(accountId) { load(null) }

    // Nothing plays on after the screen is left.
    DisposableEffect(Unit) { onDispose { model.stopPlayer() } }

    AdminPage(
        model,
        stringResource(R.string.media_recordings_title),
        back,
        reasonRes = R.string.media_lock_reason_recordings,
        messageRes = R.string.media_lock_message_recordings,
        bottomBar = { nowPlaying?.let { playing -> AudioPlayerBar(model, playing.title, playing.subtitle) } },
    ) {
        val current = page

        if (current == null) {
            if (loading) Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
            failure?.let { NoteCard(mediaFailureText(it), isError = true) }
            if (failure != null) TextButton(onClick = { scope.launch { load(null) } }) { Text(stringResource(R.string.action_retry)) }
            return@AdminPage
        }

        val months = (current.months + current.month).distinct().sortedDescending()

        section(stringResource(R.string.media_recordings_month), null) {
            Row(Modifier.fillMaxWidth().clickable { monthMenu = true }.padding(horizontal = 16.dp, vertical = 14.dp).testTag("recordings-month"), verticalAlignment = Alignment.CenterVertically) {
                Text(monthTitle(current.month).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)

                DropdownMenu(expanded = monthMenu, onDismissRequest = { monthMenu = false }) {
                    for (month in months) {
                        DropdownMenuItem(text = { Text(monthTitle(month).replaceFirstChar { it.uppercase() }) }, onClick = {
                            monthMenu = false
                            model.stopPlayer()
                            scope.launch { load(month) }
                        })
                    }
                }
            }
        }

        failure?.let { NoteCard(mediaFailureText(it), isError = true) }

        val rows = current.calls.filter { it.hasRecording || it.recordingExpired }.sortedByDescending { it.startedSort.orEmpty() }

        if (loading) {
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
        } else if (rows.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(34.dp))
                Text(stringResource(R.string.media_recordings_empty_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
                Text(stringResource(R.string.media_recordings_empty_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        } else {
            section(null, stringResource(R.string.media_recordings_footer) + if (current.truncated) "\n" + stringResource(R.string.media_recordings_truncated) else "") {
                for (call in rows) {
                    val title = recordingTitle(model, call)
                    val subtitle = recordingSubtitle(call)
                    val isGone = !call.hasRecording || call.id in gone
                    val playingThis = nowPlaying?.id == "${accountId}/recording/${call.id}"

                    RecordingRow(title, subtitle, call.direction, isGone, playingThis) {
                        account?.let { model.playRecording(it, call.id, title, subtitle) }
                    }
                }
            }
        }
    }

    // A recording that is gone (past its time) or refused while playing is marked as such.
    val snapshot by model.player.snapshot.collectAsStateWithLifecycle()
    LaunchedEffect(snapshot.failure, snapshot.id) {
        val id = snapshot.id?.removePrefix("$accountId/recording/") ?: return@LaunchedEffect
        when (snapshot.failure) {
            MediaFailure.Gone, MediaFailure.NotFound -> gone = gone + id
            MediaFailure.AccessDenied -> model.pbx.onDenied()
            MediaFailure.Revoked -> model.pbx.onRevoked()
            else -> Unit
        }
    }
}

@Composable
private fun recordingTitle(model: AppModel, call: PbxCall): String {
    call.number?.takeIf { it.isNotEmpty() }?.let { return model.name(it) ?: it }
    call.extensionName?.takeIf { it.isNotEmpty() }?.let { return it }

    return stringResource(R.string.media_voicemail_anonymous)
}

@Composable
private fun recordingSubtitle(call: PbxCall): String {
    val direction = when (call.direction) {
        PbxCallDirection.INBOUND -> stringResource(R.string.media_direction_inbound)
        PbxCallDirection.OUTBOUND -> stringResource(R.string.media_direction_outbound)
        PbxCallDirection.INTERNAL -> stringResource(R.string.media_direction_internal)
        PbxCallDirection.UNKNOWN -> ""
    }
    val who = call.extensionName?.takeIf { it.isNotEmpty() && call.number != null }

    return listOfNotNull(call.startedLabel, call.durationLabel.takeIf { it.isNotEmpty() }, direction.takeIf { it.isNotEmpty() }, who).filter { it.isNotEmpty() }.joinToString(" · ")
}

@Composable
private fun RecordingRow(title: String, subtitle: String, direction: PbxCallDirection, isGone: Boolean, playing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !isGone, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp).testTag("recording-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (playing) Icons.Filled.GraphicEq else Icons.Filled.PlayCircle,
            contentDescription = null,
            tint = if (isGone) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (isGone) Text(stringResource(R.string.media_unavailable_row), style = MaterialTheme.typography.labelSmall, color = Brand.hangUp)
        }
        Icon(
            when (direction) {
                PbxCallDirection.INBOUND -> Icons.AutoMirrored.Filled.CallReceived
                PbxCallDirection.OUTBOUND -> Icons.AutoMirrored.Filled.CallMade
                else -> Icons.Filled.SwapHoriz
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}
