// SPDX-License-Identifier: AGPL-3.0-or-later
//
// "Centrale": the call flow per number as a tree, the counters and the way into every part of the PBX (administrator pairings). Android
// port of the iOS `PbxOverviewView`. The destination of a number can be changed here ("Bestemming wijzigen").

package nl.fullstackstudio.fsvoip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.Change
import nl.fullstackstudio.fsvoip.core.FlowNode
import nl.fullstackstudio.fsvoip.core.PbxFailure
import nl.fullstackstudio.fsvoip.core.PbxOverviewNumber
import nl.fullstackstudio.fsvoip.core.PbxRoutingPatch
import nl.fullstackstudio.fsvoip.core.PbxVocabulary

@Composable
fun PbxOverviewScreen(model: AppModel, accountId: String, open: (SettingsPage) -> Unit, back: () -> Unit) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val overviews by model.pbx.overview.collectAsStateWithLifecycle()
    val devices by model.pbx.devices.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<PbxFailure?>(null) }
    var loading by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val pending = stringResource(R.string.pbx_pending_message)

    LaunchedEffect(accountId, attempt) {
        account ?: return@LaunchedEffect
        loading = true
        failure = model.pbx.loadOverview(account)
        // The destinations a number can be sent to come with the extensions.
        if (devices[accountId] == null) model.pbx.loadDevices(account)
        loading = false
    }

    AdminPage(model, stringResource(R.string.pbx_title), back) {
        ReadOnlyNote(model, accountId)
        failure?.let { NoteCard(pbxFailureText(it), isError = true) }

        val overview = overviews[accountId]

        if (overview == null) {
            if (loading) Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
            else if (failure != null) TextButton(onClick = { attempt++ }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.action_retry)) }
            return@AdminPage
        }

        if (overview.outboundBlocked) {
            NoteCard(stringResource(R.string.pbx_overview_outboundBlocked), isError = true)
        }

        section(null, stringResource(R.string.pbx_section_footer)) {
            LinkRow(
                stringResource(R.string.pbx_devices_title),
                overview.connectedCount?.let { stringResource(R.string.pbx_row_devices_connected, overview.deviceCount, it) } ?: overview.deviceCount.toString(),
            ) { open(SettingsPage.Devices(accountId)) }
            LinkRow(stringResource(R.string.pbx_ringGroups_title), overview.ringGroupCount.toString()) { open(SettingsPage.RingGroups(accountId)) }
            LinkRow(stringResource(R.string.pbx_hours_title), null) { open(SettingsPage.Hours(accountId)) }
            LinkRow(stringResource(R.string.settings_admin_numbers), null) { open(SettingsPage.Numbers(accountId)) }
        }

        overview.entryFlow?.takeIf { it.kind != nl.fullstackstudio.fsvoip.core.FlowKind.NONE }?.let { entry ->
            section(stringResource(R.string.pbx_overview_entry), null) { FlowTree(entry) }
        }

        for (number in overview.numbers) {
            NumberFlowSection(
                number = number,
                targets = devices[accountId]?.targets.orEmpty(),
                readOnly = model.isPbxReadOnly(accountId),
                onRoute = { target ->
                    val patch = PbxRoutingPatch(Change.of(target), number.version)
                    scope.launch {
                        failure = account?.let { model.pbx.changeRouting(it, number.id, patch) }
                        if (failure == null) model.showNotice(pending, isError = false)
                    }
                },
            )
        }
    }
}

@Composable
private fun NumberFlowSection(number: PbxOverviewNumber, targets: List<nl.fullstackstudio.fsvoip.core.PbxTargetOption>, readOnly: Boolean, onRoute: (nl.fullstackstudio.fsvoip.core.PbxTarget?) -> Unit) {
    section(PbxVocabulary.formatNumber(number.number), syncNote(number.sync)) {
        FlowTree(number.flow)

        if (!readOnly && targets.isNotEmpty()) {
            TargetChoiceRow(
                title = stringResource(R.string.pbx_routing_callers),
                target = number.routing,
                options = targetChoices(targets),
                noneLabel = stringResource(R.string.pbx_routing_standard),
                tag = "pbx-routing-${number.number}",
                onChange = onRoute,
            )
        }
    }
}

/** A call flow as an indented list: why a step follows ("Buiten openingstijden") above what happens. */
@Composable
fun FlowTree(node: FlowNode, depth: Int = 0, why: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = (16 + 18 * depth.coerceAtMost(6)).dp, end = 16.dp, top = 6.dp, bottom = 6.dp).testTag("flow-node"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(flowIcon(node.kind), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Column {
            if (why != null) Text(why, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(flowTitle(node), style = MaterialTheme.typography.bodyLarge, fontWeight = if (depth == 0) FontWeight.Medium else FontWeight.Normal)
            flowDetail(node)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (node.dnd) Text(stringResource(R.string.pbx_flow_dnd), style = MaterialTheme.typography.bodySmall, color = Brand.hangUp)
        }
    }

    for (branch in node.branches) {
        FlowTree(branch.node, depth + 1, flowWhen(branch.condition))
    }
}
