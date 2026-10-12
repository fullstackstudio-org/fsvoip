// SPDX-License-Identifier: AGPL-3.0-or-later
//
// "Live" in the "On hold" tab: the calls that are running on the PBX right now, for the whole team. A ringing call can be taken over to the
// own phone, a call the own extension answered can be transferred (an administrator transfers every answered call). The server says which
// button a row gets (`canPickup`, `canTransfer`). The list is polled every few seconds while this section is visible; there is no push for this.
// Android port of the iOS `LiveCallsView`.

package nl.fullstackstudio.fsvoip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.RingVolume
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.LiveCall
import nl.fullstackstudio.fsvoip.core.LiveFailure
import nl.fullstackstudio.fsvoip.core.LiveRules
import nl.fullstackstudio.fsvoip.core.LiveState
import nl.fullstackstudio.fsvoip.core.LiveTarget
import nl.fullstackstudio.fsvoip.core.LiveTargetType
import nl.fullstackstudio.fsvoip.core.StoredAccount

@Composable
internal fun LiveSection(model: AppModel, liveAccounts: List<StoredAccount>) {
    var chosenId by remember { mutableStateOf<String?>(null) }
    val account = liveAccounts.firstOrNull { it.id == chosenId } ?: liveAccounts.firstOrNull() ?: return

    Column(Modifier.fillMaxSize()) {
        if (liveAccounts.size > 1) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (candidate in liveAccounts) {
                    FilterChip(
                        selected = candidate.id == account.id,
                        onClick = { chosenId = candidate.id },
                        label = { Text(candidate.displayLabel) },
                        modifier = Modifier.testTag("live-account"),
                    )
                }
            }
        }

        LiveCallsContent(model, account)
    }
}

@Composable
private fun LiveCallsContent(model: AppModel, account: StoredAccount) {
    val live = model.live
    val calls by live.calls.collectAsStateWithLifecycle()
    val enabled by live.enabled.collectAsStateWithLifecycle()
    val fresh by live.fresh.collectAsStateWithLifecycle()
    val failure by live.failure.collectAsStateWithLifecycle()
    val readOnly by live.readOnly.collectAsStateWithLifecycle()
    val targets by live.targets.collectAsStateWithLifecycle()
    val busy by live.busyIds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var transferringId by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Polls only while this section is on screen and the app is in the foreground: resumed + in composition (the tab view and
    // the section switch both remove it from the composition when it is left).
    LifecycleResumeEffect(account.id) {
        live.startPolling(account)
        onPauseOrDispose { live.stopPolling() }
    }

    // Once a second: the running time.
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                live.refresh(account)
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            enabled == false -> Empty(stringResource(R.string.live_disabled_title), stringResource(R.string.live_disabled_message))
            enabled == null && failure != null -> Column(Modifier.fillMaxSize()) {
                Empty(stringResource(R.string.live_error_title), stringResource(liveMessage(failure!!)))
                TextButton(onClick = { scope.launch { live.refresh(account) } }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.action_retry)) }
            }
            enabled == null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = "" }.size(32.dp))
                Text(stringResource(R.string.live_loading), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            }
            else -> Column(Modifier.fillMaxSize()) {
                Banners(failure != null, !fresh, readOnly)

                if (calls.isEmpty()) {
                    Empty(stringResource(R.string.live_empty_title), stringResource(R.string.live_empty_message))
                } else {
                    val ringing = calls.filter { it.isRinging }
                    val running = calls.filter { it.isRunning }
                    val parked = calls.filter { it.isParked }

                    LazyColumn(Modifier.fillMaxSize().testTag("live-list")) {
                        section(R.string.live_section_ringing, ringing, account, model, targets, busy, now) { transferringId = it.id }
                        section(R.string.live_section_running, running, account, model, targets, busy, now) { transferringId = it.id }
                        section(R.string.live_section_parked, parked, account, model, targets, busy, now) { transferringId = it.id }
                    }
                }
            }
        }
    }

    val callGone = stringResource(R.string.live_error_callNotFound)
    val transferring = transferringId?.let { id -> calls.firstOrNull { it.id == id } }

    if (transferring != null) {
        TransferSheet(
            call = transferring,
            targets = targets,
            close = { transferringId = null },
            pick = { target ->
                transferringId = null
                // The ref of the row changes with every poll: the latest list has the one the server knows.
                val latest = live.calls.value.firstOrNull { it.id == transferring.id }

                if (latest == null) model.showNotice(callGone, isError = true) else model.transferLive(account.id, latest, target)
            },
        )
    } else if (transferringId != null) {
        // The call ended while the sheet was open.
        LaunchedEffect(transferringId) { transferringId = null }
    }
}

private fun liveMessage(failure: LiveFailure): Int = when (failure) {
    LiveFailure.CallNotFound -> R.string.live_error_callNotFound
    LiveFailure.NotRinging -> R.string.live_error_notRinging
    LiveFailure.NotTransferable -> R.string.live_error_notTransferable
    LiveFailure.Unavailable -> R.string.live_error_unavailable
    LiveFailure.Forbidden -> R.string.live_error_forbidden
    LiveFailure.Revoked -> R.string.media_error_revoked
    LiveFailure.ReadOnly -> R.string.media_error_readOnly
    LiveFailure.InvalidTarget -> R.string.live_error_invalidTarget
    is LiveFailure.RateLimited -> R.string.media_error_rateLimited
    LiveFailure.Offline -> R.string.media_error_offline
    LiveFailure.Other -> R.string.error_generic
}

@Composable
private fun Banners(stale: Boolean, notFresh: Boolean, readOnly: Boolean) {
    Column {
        if (stale) Banner(stringResource(R.string.live_stale), "live-stale") else if (notFresh) Banner(stringResource(R.string.live_notFresh), "live-notfresh")
        if (readOnly) Banner(stringResource(R.string.live_readOnly), "live-readonly")
    }
}

@Composable
private fun Banner(text: String, tag: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag(tag),
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    titleRes: Int,
    calls: List<LiveCall>,
    account: StoredAccount,
    model: AppModel,
    targets: List<LiveTarget>,
    busy: Set<String>,
    now: Long,
    transfer: (LiveCall) -> Unit,
) {
    if (calls.isEmpty()) return

    item(key = "header-$titleRes") {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    items(calls, key = { it.id }) { call ->
        LiveRow(
            call = call,
            now = now,
            isBusy = call.id in busy,
            showPickup = LiveRules.showPickup(call),
            showTransfer = LiveRules.showTransfer(call, targets),
            pickUp = { model.pickUpLive(account.id, call) },
            transfer = { transfer(call) },
        )
        HorizontalDivider()
    }
}

@Composable
private fun liveTitle(call: LiveCall): String =
    call.callerName?.takeIf { it.isNotBlank() } ?: call.callerNumber?.takeIf { it.isNotBlank() } ?: stringResource(R.string.call_anonymous)

@Composable
private fun liveDetail(call: LiveCall): String {
    if (call.isParked) return stringResource(R.string.live_parkedSlot, "%02d".format(call.parked?.slot ?: 0))

    if (call.isRinging) {
        val names = call.ringing.joinToString(", ") { it.label }
        return if (names.isEmpty()) stringResource(R.string.live_ringing) else stringResource(R.string.live_ringingAt, names)
    }

    call.answeredBy?.let { by -> return stringResource(if (call.state == LiveState.HELD) R.string.live_heldAt else R.string.live_onTheLine, by.label) }

    return stringResource(
        when (call.direction) {
            nl.fullstackstudio.fsvoip.core.LiveDirection.INBOUND -> R.string.live_direction_inbound
            nl.fullstackstudio.fsvoip.core.LiveDirection.OUTBOUND -> R.string.live_direction_outbound
            nl.fullstackstudio.fsvoip.core.LiveDirection.INTERNAL -> R.string.live_direction_internal
            nl.fullstackstudio.fsvoip.core.LiveDirection.UNKNOWN -> R.string.live_direction_unknown
        },
    )
}

@Composable
private fun LiveRow(call: LiveCall, now: Long, isBusy: Boolean, showPickup: Boolean, showTransfer: Boolean, pickUp: () -> Unit, transfer: () -> Unit) {
    val title = liveTitle(call)
    val detail = liveDetail(call)
    val elapsed = LiveRules.clock((now - call.clockStart.toEpochMilli()) / 1000)
    val elapsedDescription = stringResource(R.string.live_elapsed, elapsed)

    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp).testTag("live-row"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                when {
                    call.isParked -> Icons.Filled.PauseCircle
                    call.direction == nl.fullstackstudio.fsvoip.core.LiveDirection.OUTBOUND -> Icons.AutoMirrored.Filled.CallMade
                    call.isRinging -> Icons.Filled.RingVolume
                    else -> Icons.AutoMirrored.Filled.CallReceived
                },
                contentDescription = null,
                tint = if (call.isRinging) Brand.amber else if (call.isParked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(28.dp),
            )

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.weight(1f, fill = false))

                    if (call.mine) {
                        Text(
                            stringResource(R.string.live_mine),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                if (!call.callerName.isNullOrBlank() && call.callerNumber != null) {
                    Text(call.callerNumber!!, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text(
                elapsed,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = elapsedDescription },
            )
        }

        if (showPickup || showTransfer) {
            Row(Modifier.padding(start = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showPickup) {
                    val label = stringResource(R.string.live_pickUp_for, title)
                    Button(onClick = pickUp, enabled = !isBusy, modifier = Modifier.testTag("live-pickup").semantics { contentDescription = label }) {
                        Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.live_pickUp), modifier = Modifier.padding(start = 6.dp))
                    }
                }

                if (showTransfer) {
                    val label = stringResource(R.string.live_transfer_for, title)
                    OutlinedButton(onClick = transfer, enabled = !isBusy, modifier = Modifier.testTag("live-transfer").semantics { contentDescription = label }) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.live_transfer), modifier = Modifier.padding(start = 6.dp))
                    }
                }

                if (isBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}

// MARK: - Transfer

/** Where to transfer a call: only inside the own PBX (extensions, ring groups, queues, menus, opening hours, voicemail). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferSheet(call: LiveCall, targets: List<LiveTarget>, close: () -> Unit, pick: (LiveTarget) -> Unit) {
    var search by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf<LiveTarget?>(null) }
    val groups = LiveRules.grouped(targets, search)

    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("live-transfer-sheet")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.live_transfer_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                label = { Text(stringResource(R.string.live_transfer_search)) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("live-transfer-search"),
            )

            if (groups.isEmpty()) {
                Text(
                    stringResource(if (targets.isEmpty()) R.string.live_transfer_none else R.string.live_transfer_nothing),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }

            LazyColumn(Modifier.weight(1f, fill = false)) {
                for ((type, items) in groups) {
                    item(key = "h-${type.wire}") {
                        Text(stringResource(targetTitle(type)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    }

                    items(items, key = { it.id }) { target ->
                        val label = stringResource(R.string.live_transfer_to, target.name, target.extension)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { confirming = target }.semantics { contentDescription = label }.testTag("live-target"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(target.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(target.extension, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
            }

            Text(
                stringResource(R.string.live_transfer_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }

    confirming?.let { target ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.live_transfer_confirm_title, target.name)) },
            text = { Text(stringResource(R.string.live_transfer_confirm_message, liveTitle(call))) },
            confirmButton = {
                TextButton(onClick = { confirming = null; pick(target) }, modifier = Modifier.testTag("live-transfer-confirm")) { Text(stringResource(R.string.live_transfer)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun targetTitle(type: LiveTargetType): Int = when (type) {
    LiveTargetType.EXTENSION -> R.string.live_target_extension
    LiveTargetType.RING_GROUP -> R.string.live_target_ring_group
    LiveTargetType.QUEUE -> R.string.live_target_queue
    LiveTargetType.IVR -> R.string.live_target_ivr
    LiveTargetType.BUSINESS_HOURS -> R.string.live_target_business_hours
    LiveTargetType.VOICEMAIL -> R.string.live_target_voicemail
    LiveTargetType.UNKNOWN -> R.string.live_direction_unknown
}
