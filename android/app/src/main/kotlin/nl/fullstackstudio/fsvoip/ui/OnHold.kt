// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The "In de wacht" tab: the calls parked on the PBX. Picking one up is dialling its retrieve number; hanging up ends it. Android
// port of the iOS `OnHoldView`.

package nl.fullstackstudio.fsvoip.ui

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
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.ParkedCall

@Composable
fun OnHoldScreen(model: AppModel) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val calls by model.park.calls.collectAsStateWithLifecycle()
    val availability by model.park.available.collectAsStateWithLifecycle()
    model.me.collectAsStateWithLifecycle().value
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    val parkable = accounts.filter { model.canPark(it.id) }

    // Refreshed when the tab opens and while it stays open (a parked call rings back or is picked up elsewhere).
    LaunchedEffect(parkable.map { it.id }) {
        while (true) {
            for (account in parkable) model.park.refresh(account)
            kotlinx.coroutines.delay(10_000)
        }
    }

    val rows = parkable.flatMap { account -> calls[account.id].orEmpty().map { account.id to it } }.sortedBy { it.second.slot }
    val unavailable = parkable.isNotEmpty() && parkable.all { availability[it.id] == false } && rows.isEmpty()

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                for (account in parkable) model.park.refresh(account)
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            parkable.isEmpty() || unavailable -> Empty(stringResource(R.string.park_unavailable), null)
            rows.isEmpty() -> Empty(stringResource(R.string.park_empty_title), stringResource(R.string.park_empty_body))
            else -> LazyColumn(Modifier.fillMaxSize().testTag("parked-list")) {
                items(rows, key = { "${it.first}/${it.second.id}" }) { (accountId, call) ->
                    ParkedRow(call, onPickUp = { model.retrieveParked(accountId, call) }, onHangUp = { model.hangupParked(accountId, call) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Empty(title: String, message: String?) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.PauseCircle, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(34.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))

        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun ParkedRow(call: ParkedCall, onPickUp: () -> Unit, onHangUp: () -> Unit) {
    val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/Amsterdam"))
    val who = call.callerName?.takeIf { it.isNotBlank() } ?: call.callerNumber ?: stringResource(R.string.call_anonymous)
    val by = if (call.mine) stringResource(R.string.park_mine) else call.parkedBy?.name?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.park_by, it) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("parked-row"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(who, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.park_slot, call.slot), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        by?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        call.expiresAt?.let { Text(stringResource(R.string.park_expires, time.format(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onPickUp, enabled = call.retrieveNumber != null, modifier = Modifier.testTag("park-pickup")) { Text(stringResource(R.string.park_pickup)) }
            TextButton(onClick = onHangUp, modifier = Modifier.testTag("park-hangup")) { Text(stringResource(R.string.park_hangup), color = Brand.hangUp) }
        }
    }
}
