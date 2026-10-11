// SPDX-License-Identifier: AGPL-3.0-or-later
//
// "In de wacht": the calls parked on the PBX, for the whole team. Tap a call to pick it up (it is dialled as a normal call), the
// three dots hang it up. The list refreshes when the tab opens, on pull to refresh, when the app comes to the front and every few
// seconds while the tab is visible.

package nl.fullstackstudio.fsvoip.ui.park

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PhoneCallback
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.ParkedCall
import nl.fullstackstudio.fsvoip.core.StoredAccount
import nl.fullstackstudio.fsvoip.ui.Brand
import nl.fullstackstudio.fsvoip.ui.InitialsAvatar
import nl.fullstackstudio.fsvoip.ui.ShellActions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnHoldTab(model: AppModel) {
    // Recompose when the accounts, the capabilities or the chosen account change.
    model.accounts.collectAsStateWithLifecycle().value
    model.meByAccount.collectAsStateWithLifecycle().value
    model.chosenParkAccountId.collectAsStateWithLifecycle().value
    val account = model.parkAccount()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_onHold)) }, actions = { ShellActions(model) }) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (account == null) {
                ParkEmptyState(Icons.Filled.PauseCircle, stringResource(R.string.onHold_unavailable_title), stringResource(R.string.onHold_unavailable_message), "onhold-unavailable")
            } else {
                // Another account is another list: start over.
                androidx.compose.runtime.key(account.id) { OnHoldContent(model, account) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnHoldContent(model: AppModel, account: StoredAccount) {
    val park = model.park
    val calls by park.calls.collectAsStateWithLifecycle()
    val available by park.available.collectAsStateWithLifecycle()
    val failure by park.failure.collectAsStateWithLifecycle()
    val isLoading by park.isLoading.collectAsStateWithLifecycle()
    val busy by park.busyIds.collectAsStateWithLifecycle()
    model.accounts.collectAsStateWithLifecycle().value
    var scope by remember { mutableStateOf(ParkModel.Scope.ALL) }
    var pendingHangUp by remember { mutableStateOf<ParkedCall?>(null) }
    val coroutines = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mineCount = calls.count { it.mine }
    val rows = if (scope == ParkModel.Scope.MINE) calls.filter { it.mine } else calls

    // Poll while this tab is on screen and the app is in the foreground; leaving either stops it.
    LaunchedEffect(account.id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { park.poll(account) }
    }

    // Once a second: the "since" and the "rings back in" run.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(1_000)
        }
    }

    Column(Modifier.fillMaxSize().testTag("onhold-list")) {
        Controls(model, account, scope, mineCount) { scope = it }

        when {
            available == false -> ParkEmptyState(Icons.Filled.PauseCircle, stringResource(R.string.onHold_unavailable_title), stringResource(R.string.onHold_unavailable_message), "onhold-unavailable")
            rows.isEmpty() && available == null && failure == null -> {
                val loading = stringResource(R.string.onHold_loading)
                Box(Modifier.fillMaxSize().semantics { contentDescription = loading }, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            rows.isEmpty() && failure != null && available == null -> ParkEmptyState(
                Icons.Filled.ErrorOutline,
                stringResource(R.string.onHold_error_title),
                model.message(failure!!),
                "onhold-error",
                actionTitle = stringResource(R.string.action_retry),
            ) { coroutines.launch { park.refresh(account) } }
            rows.isEmpty() -> ParkEmptyState(Icons.Filled.LocalCafe, stringResource(R.string.onHold_empty_title), stringResource(R.string.onHold_empty_message), "onhold-empty")
            else -> PullToRefreshBox(isRefreshing = isLoading && calls.isNotEmpty(), onRefresh = { coroutines.launch { park.refresh(account) } }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }) { call ->
                        ParkedRow(
                            call = call,
                            now = now,
                            isBusy = call.id in busy,
                            canHangUp = park.canHangUp(call, account.id),
                            pickUp = { coroutines.launch { pickUp(model, call, account) } },
                            hangUp = { pendingHangUp = call },
                        )
                        HorizontalDivider(Modifier.padding(start = 72.dp))
                    }
                }
            }
        }
    }

    pendingHangUp?.let { call ->
        AlertDialog(
            onDismissRequest = { pendingHangUp = null },
            title = { Text(stringResource(R.string.onHold_hangUp_title)) },
            text = { Text(stringResource(R.string.onHold_hangUp_message, titleOf(call, stringResource(R.string.call_anonymous)))) },
            confirmButton = {
                TextButton(onClick = {
                    pendingHangUp = null
                    coroutines.launch { hangUp(model, call, account) }
                }) { Text(stringResource(R.string.onHold_hangUp), color = Brand.hangUp) }
            },
            dismissButton = { TextButton(onClick = { pendingHangUp = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Controls(model: AppModel, account: StoredAccount, scope: ParkModel.Scope, mineCount: Int, select: (ParkModel.Scope) -> Unit) {
    val parkAccounts = model.parkAccounts()
    var menu by remember { mutableStateOf(false) }
    val accountLabel = stringResource(R.string.onHold_account)

    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f).testTag("onhold-segments")) {
            SegmentedButton(
                selected = scope == ParkModel.Scope.ALL,
                onClick = { select(ParkModel.Scope.ALL) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                label = { Text(stringResource(R.string.onHold_scope_all), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.testTag("onhold-scope-all"),
            )
            SegmentedButton(
                selected = scope == ParkModel.Scope.MINE,
                onClick = { select(ParkModel.Scope.MINE) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                label = { Text(stringResource(R.string.onHold_scope_mine, mineCount), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.testTag("onhold-scope-mine"),
            )
        }

        if (parkAccounts.size > 1) {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).semantics { contentDescription = accountLabel; stateDescription = account.displayLabel }) {
                    Icon(Icons.Filled.AccountCircle, contentDescription = null)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (candidate in parkAccounts) {
                        DropdownMenuItem(
                            text = { Text(candidate.displayLabel + if (candidate.id == account.id) " ✓" else "") },
                            onClick = {
                                menu = false
                                model.chooseParkAccount(candidate.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ParkedRow(call: ParkedCall, now: Instant, isBusy: Boolean, canHangUp: Boolean, pickUp: () -> Unit, hangUp: () -> Unit) {
    val title = titleOf(call, stringResource(R.string.call_anonymous))
    val since = ParkFormat.elapsed(call.parkedAt, now)
    val remaining = if (call.mine) ParkFormat.remaining(call.expiresAt, now) else null
    val parker = call.parkedBy?.name?.takeIf { it.isNotEmpty() }
    val subtitle = buildList {
        add(stringResource(R.string.onHold_slot, ParkFormat.slot(call.slot)))
        if (since != null) add(stringResource(R.string.onHold_since, since))
    }.joinToString(" · ")
    val ringsText = remaining?.let { stringResource(R.string.onHold_rings, it) }
    val parkedBy = parker?.let { stringResource(R.string.onHold_parkedBy, it) }
    val hint = stringResource(R.string.onHold_row_hint)
    val hangUpLabel = stringResource(R.string.onHold_hangUp)
    val spoken = listOfNotNull(title, subtitle, ringsText, parkedBy, hint).joinToString(", ")
    var menu by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .clickable(enabled = !isBusy, onClick = pickUp)
                .padding(vertical = 10.dp)
                .semantics(mergeDescendants = false) {
                    contentDescription = spoken
                    onClick(label = hint) { if (!isBusy) pickUp(); true }
                    if (canHangUp) customActions = listOf(CustomAccessibilityAction(hangUpLabel) { hangUp(); true })
                }
                .testTag("onhold-row-${call.slot}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.clearAndSetSemantics {}) { InitialsAvatar(parker, 40) }

            Column(Modifier.weight(1f).clearAndSetSemantics {}) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)

                if (ringsText != null) {
                    Text(ringsText, style = MaterialTheme.typography.labelMedium, color = Brand.amber)
                }
            }

            Box(Modifier.size(24.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                if (isBusy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.PhoneCallback, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Box {
            val more = stringResource(R.string.onHold_more, title)
            IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).testTag("onhold-more")) {
                Icon(Icons.Filled.MoreVert, contentDescription = more, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.onHold_pickUp)) }, enabled = !isBusy, onClick = {
                    menu = false
                    pickUp()
                })

                if (canHangUp) {
                    DropdownMenuItem(text = { Text(hangUpLabel, color = Brand.hangUp) }, onClick = {
                        menu = false
                        hangUp()
                    }, modifier = Modifier.testTag("onhold-hangup"))
                }
            }
        }
    }
}

@Composable
private fun ParkEmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    tag: String,
    actionTitle: String? = null,
    action: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp).testTag(tag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(34.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))

        if (actionTitle != null) {
            TextButton(onClick = action, modifier = Modifier.padding(top = 8.dp)) { Text(actionTitle) }
        }
    }
}

private fun titleOf(call: ParkedCall, anonymous: String): String =
    call.callerName?.takeIf { it.isNotEmpty() } ?: call.callerNumber?.takeIf { it.isNotEmpty() } ?: anonymous

private suspend fun pickUp(model: AppModel, call: ParkedCall, account: StoredAccount) {
    when (val result = model.park.retrieve(call, account)) {
        ParkModel.RetrieveResult.Dialing -> Unit
        ParkModel.RetrieveResult.Gone -> model.showGoneNotice()
        // `AppModel.call` has already shown its own, specific notice.
        ParkModel.RetrieveResult.DialFailed -> Unit
        is ParkModel.RetrieveResult.Failed -> model.showNotice(model.message(result.failure), isError = true)
    }
}

private suspend fun hangUp(model: AppModel, call: ParkedCall, account: StoredAccount) {
    when (val result = model.park.hangUp(call, account)) {
        ParkModel.HangupResult.Done -> Unit
        ParkModel.HangupResult.Gone -> model.showGoneNotice()
        is ParkModel.HangupResult.Failed -> model.showNotice(model.message(result.failure), isError = true)
    }
}
