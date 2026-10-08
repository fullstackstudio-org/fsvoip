// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.RecentCall

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentsScreen(model: AppModel) {
    val recents by model.recents.collectAsStateWithLifecycle()
    val accounts by model.accounts.collectAsStateWithLifecycle()
    var confirmsClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_recents)) },
                actions = {
                    if (recents.isNotEmpty()) {
                        TextButton(onClick = { confirmsClear = true }) { Text(stringResource(R.string.recents_clear)) }
                    }
                },
            )
        },
    ) { padding ->
        if (recents.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(34.dp))
                Text(stringResource(R.string.recents_empty_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
                Text(stringResource(R.string.recents_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(recents, key = { it.id }) { call ->
                    RecentRow(call, showsLine = accounts.size > 1) {
                        if (call.number.isNotEmpty()) {
                            model.call(call.number, call.accountId.takeIf { id -> accounts.any { it.id == id } })
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 48.dp))
                }
            }
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

@Composable
private fun RecentRow(call: RecentCall, showsLine: Boolean, onClick: () -> Unit) {
    val missed = call.direction == RecentCall.Direction.INCOMING && call.outcome == RecentCall.Outcome.MISSED
    val anonymous = stringResource(R.string.call_anonymous)
    val parts = buildList {
        if (call.name != null && call.number.isNotEmpty()) add(call.number)
        add(
            when (call.outcome) {
                RecentCall.Outcome.ANSWERED -> RecentFormat.duration(call.durationSeconds)
                RecentCall.Outcome.MISSED -> stringResource(R.string.recents_outcome_missed)
                RecentCall.Outcome.DECLINED -> stringResource(R.string.recents_outcome_declined)
                RecentCall.Outcome.NOT_ANSWERED -> stringResource(R.string.recents_outcome_notAnswered)
                RecentCall.Outcome.FAILED -> stringResource(R.string.recents_outcome_failed)
            },
        )
        if (showsLine) add(call.accountLabel)
    }
    val yesterday = stringResource(R.string.recents_yesterday)

    Row(
        Modifier.fillMaxWidth().clickable(enabled = call.number.isNotEmpty(), onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
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
            Text(
                call.name ?: call.number.ifEmpty { anonymous },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (missed) Brand.hangUp else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(RecentFormat.`when`(Instant.ofEpochMilli(call.startedAtMillis), yesterday), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

object RecentFormat {
    /** `0:42`, `5:12`, `1:02:03`. */
    fun duration(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val rest = total % 60

        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
    }

    fun `when`(instant: Instant, yesterday: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        val date = instant.atZone(zone)

        return when (date.toLocalDate()) {
            today -> date.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
            today.minusDays(1) -> yesterday
            else -> date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        }
    }
}
