// SPDX-License-Identifier: AGPL-3.0-or-later
//
// "Toestellen" and "Belgroepen" of the PBX (administrator pairings). Android port of the iOS `DevicesView` / `RingGroupsView`. A
// list opens an editor; the editor keeps a draft, sends only what changed and handles `409 stale` by loading the new state and
// keeping what the user typed.

package nl.fullstackstudio.fsvoip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.FollowMeStep
import nl.fullstackstudio.fsvoip.core.PbxDevice
import nl.fullstackstudio.fsvoip.core.PbxDeviceDraft
import nl.fullstackstudio.fsvoip.core.PbxFailure
import nl.fullstackstudio.fsvoip.core.PbxRingGroup
import nl.fullstackstudio.fsvoip.core.PbxRingGroupDraft
import nl.fullstackstudio.fsvoip.core.PbxTarget
import nl.fullstackstudio.fsvoip.core.RingStrategy

// MARK: Devices

@Composable
fun DevicesScreen(model: AppModel, accountId: String, open: (String) -> Unit, back: () -> Unit) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val all by model.pbx.devices.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    var failure by remember { mutableStateOf<PbxFailure?>(null) }
    var loading by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(accountId, attempt) {
        account ?: return@LaunchedEffect
        loading = true
        failure = model.pbx.loadDevices(account)
        loading = false
    }

    AdminPage(model, stringResource(R.string.pbx_devices_title), back) {
        ReadOnlyNote(model, accountId)

        val page = all[accountId]

        failure?.let { NoteCard(pbxFailureText(it), isError = true) }

        if (page == null) {
            if (loading) Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
            else if (failure != null) TextButton(onClick = { attempt++ }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.action_retry)) }
            return@AdminPage
        }

        if (page.devices.isEmpty()) {
            InfoRow(stringResource(R.string.pbx_devices_empty))
        }

        section(null, stringResource(R.string.pbx_devices_footer)) {
            for (device in page.devices) {
                DeviceRow(device, page.targets) { open(device.id) }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: PbxDevice, targets: List<nl.fullstackstudio.fsvoip.core.PbxTargetOption>, onClick: () -> Unit) {
    val none = stringResource(R.string.pbx_target_none)
    val status = when (device.registration?.connected) {
        true -> stringResource(R.string.pbx_device_connected)
        false -> stringResource(R.string.pbx_device_disconnected)
        null -> null
    }
    val forward = device.forwardAlways?.let { stringResource(R.string.pbx_device_forwarding, targetText(it, targets, none)) }
    val parts = listOfNotNull(device.extensionNumber, status, if (device.dnd) stringResource(R.string.pbx_device_dnd) else null, forward, syncNote(device.sync))

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp).testTag("pbx-device-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun DeviceEditScreen(model: AppModel, accountId: String, deviceId: String, back: () -> Unit) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val all by model.pbx.devices.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    val page = all[accountId]
    val device = page?.devices?.firstOrNull { it.id == deviceId }
    val scope = rememberCoroutineScope()
    val pending = stringResource(R.string.pbx_pending_message)

    var baseline by remember(deviceId) { mutableStateOf<PbxDeviceDraft?>(null) }
    var draft by remember(deviceId) { mutableStateOf(PbxDeviceDraft()) }
    var failure by remember(deviceId) { mutableStateOf<PbxFailure?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(accountId) {
        if (account != null && page == null) model.pbx.loadDevices(account)
    }

    // The first state becomes the draft; a later state (after a `stale`) only moves the fields the user did not touch.
    LaunchedEffect(device) {
        val fresh = device ?: return@LaunchedEffect
        val freshDraft = PbxDeviceDraft.of(fresh)
        val start = baseline

        if (start == null) {
            draft = freshDraft
        } else {
            draft = draft.rebase(freshDraft, start)
        }

        baseline = freshDraft
    }

    val readOnly = model.isPbxReadOnly(accountId)
    val dirty = baseline?.let { device != null && draft.patch(it, device.version) != null } == true

    AdminPage(model, device?.name ?: stringResource(R.string.pbx_devices_title), back) {
        if (device == null || page == null) {
            InfoRow(stringResource(R.string.pbx_devices_loadFailed))
            return@AdminPage
        }

        ReadOnlyNote(model, accountId)
        failure?.let { NoteCard(pbxFailureText(it), isError = true) }

        val targets = page.targets
        val none = stringResource(R.string.pbx_target_none)

        ReadOnlyLock(readOnly) {
            Column {
                section(null, null) {
                    ValueRow(stringResource(R.string.profile_device_number), device.extensionNumber.orEmpty())
                    ValueRow(
                        stringResource(R.string.account_connection),
                        when (device.registration?.connected) {
                            true -> stringResource(R.string.pbx_device_connected)
                            false -> stringResource(R.string.pbx_device_disconnected)
                            null -> "—"
                        },
                    )
                    device.email?.let { ValueRow(stringResource(R.string.pbx_device_email), it) }
                    syncNote(device.sync)?.let { InfoRow(it) }
                }

                section(null, stringResource(R.string.pbx_device_dnd_footer)) {
                    SwitchRow(stringResource(R.string.pbx_device_dnd), null, draft.dnd, Modifier.testTag("pbx-device-dnd")) { draft = draft.copy(dnd = it) }
                }

                section(stringResource(R.string.pbx_device_forward_title), stringResource(R.string.pbx_device_forward_footer)) {
                    TargetChoiceRow(stringResource(R.string.pbx_device_forward_title), draft.forwardAlways, targetChoices(targets, deviceId), stringResource(R.string.pbx_device_forward_off), "pbx-device-forward") { draft = draft.copy(forwardAlways = it) }
                }

                section(stringResource(R.string.pbx_device_noAnswer_title), stringResource(R.string.pbx_device_noAnswer_footer)) {
                    NumberStepperRow(stringResource(R.string.pbx_device_noAnswer_after, draft.noAnswerSeconds), draft.noAnswerSeconds, PbxDeviceDraft.NO_ANSWER_RANGE, 5, "pbx-device-noanswer") { draft = draft.copy(noAnswerSeconds = it) }
                    TargetChoiceRow(
                        stringResource(R.string.pbx_device_noAnswer_then),
                        draft.noAnswerTarget,
                        targetChoices(targets, deviceId),
                        stringResource(if (draft.voicemailEnabled) R.string.pbx_device_noAnswer_default_voicemail else R.string.pbx_device_noAnswer_default_ring),
                        "pbx-device-noanswer-target",
                    ) { draft = draft.copy(noAnswerTarget = it) }
                }

                section(stringResource(R.string.pbx_device_busy_title), null) {
                    TargetChoiceRow(stringResource(R.string.pbx_device_busy_then), draft.busyTarget, targetChoices(targets, deviceId), stringResource(R.string.pbx_device_busy_default), "pbx-device-busy") { draft = draft.copy(busyTarget = it) }
                }

                section(stringResource(R.string.pbx_device_offline_title), null) {
                    TargetChoiceRow(stringResource(R.string.pbx_device_offline_then), draft.notRegisteredTarget, targetChoices(targets, deviceId), stringResource(R.string.pbx_device_offline_default), "pbx-device-offline") { draft = draft.copy(notRegisteredTarget = it) }
                }

                FollowMeSection(draft.followMe, targets, deviceId, none) { draft = draft.copy(followMe = it) }

                section(stringResource(R.string.prefs_voicemail), stringResource(R.string.pbx_device_voicemail_footer)) {
                    SwitchRow(stringResource(R.string.pbx_device_voicemail_email), null, draft.voicemailEnabled, Modifier.testTag("pbx-device-voicemail")) { draft = draft.copy(voicemailEnabled = it) }

                    if (draft.voicemailEnabled && !device.email.isNullOrBlank()) {
                        SwitchRow(stringResource(R.string.prefs_voicemail_email), null, draft.voicemailToEmail) { draft = draft.copy(voicemailToEmail = it) }
                        if (draft.voicemailToEmail) {
                            SwitchRow(stringResource(R.string.pbx_device_attachFile), null, draft.voicemailAttachFile) { draft = draft.copy(voicemailAttachFile = it) }
                        }
                    }
                }

                if (page.numbers.size > 1) {
                    section(stringResource(R.string.pbx_device_outbound), stringResource(R.string.pbx_device_outbound_footer)) {
                        CheckRow(stringResource(R.string.pbx_device_outbound_default), draft.outboundNumberId == null) { draft = draft.copy(outboundNumberId = null) }
                        for (number in page.numbers) {
                            CheckRow(nl.fullstackstudio.fsvoip.core.PbxVocabulary.formatNumber(number.number), draft.outboundNumberId == number.id) { draft = draft.copy(outboundNumberId = number.id) }
                        }
                    }
                }
            }
        }

        section(null, stringResource(R.string.pbx_devices_footer)) {}

        if (!readOnly) {
            Column(Modifier.padding(16.dp)) {
                PrimaryButton(
                    stringResource(R.string.action_save),
                    enabled = dirty && !saving,
                    modifier = Modifier.testTag("pbx-device-save"),
                    onClick = {
                        val start = baseline ?: return@PrimaryButton
                        val patch = draft.patch(start, device.version) ?: return@PrimaryButton
                        val target = account ?: return@PrimaryButton
                        saving = true

                        scope.launch {
                            failure = model.pbx.saveDevice(target, deviceId, patch)

                            if (failure == null) {
                                model.showNotice(pending, isError = false)
                                back()
                            }

                            saving = false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun FollowMeSection(steps: List<FollowMeStep>, targets: List<nl.fullstackstudio.fsvoip.core.PbxTargetOption>, deviceId: String, none: String, change: (List<FollowMeStep>) -> Unit) {
    section(stringResource(R.string.pbx_followMe_title), stringResource(R.string.pbx_followMe_footer)) {
        steps.forEachIndexed { index, step ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${stringResource(R.string.pbx_followMe_step)} ${index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { change(steps.toMutableList().also { java.util.Collections.swap(it, index, index - 1) }) }, enabled = index > 0) { Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.pbx_followMe_moveUp)) }
                    IconButton(onClick = { change(steps.toMutableList().also { java.util.Collections.swap(it, index, index + 1) }) }, enabled = index < steps.lastIndex) { Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.pbx_followMe_moveDown)) }
                    IconButton(onClick = { change(steps.filterIndexed { i, _ -> i != index }) }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.pbx_followMe_remove)) }
                }
                TargetChoiceRow(stringResource(R.string.pbx_followMe_who), step.target.takeIf { it.type != nl.fullstackstudio.fsvoip.core.TargetType.UNKNOWN }, targetChoices(targets, deviceId), stringResource(R.string.pbx_target_unset), "pbx-followme-target-$index") { picked ->
                    change(steps.mapIndexed { i, s -> if (i == index) s.copy(target = picked ?: PbxTarget()) else s })
                }
                NumberStepperRow(stringResource(R.string.pbx_followMe_delay, step.delaySeconds), step.delaySeconds, 0..120, 5, "pbx-followme-delay-$index") { value -> change(steps.mapIndexed { i, s -> if (i == index) s.copy(delaySeconds = value) else s }) }
                NumberStepperRow(stringResource(R.string.pbx_followMe_timeout, step.timeoutSeconds), step.timeoutSeconds, 5..300, 5, "pbx-followme-timeout-$index") { value -> change(steps.mapIndexed { i, s -> if (i == index) s.copy(timeoutSeconds = value) else s }) }
            }
        }

        if (steps.size < PbxDeviceDraft.FOLLOW_ME_MAX) {
            ActionRow(stringResource(R.string.pbx_followMe_add), Icons.Filled.Add, Modifier.testTag("pbx-followme-add")) {
                change(steps + FollowMeStep(PbxTarget(), delaySeconds = steps.sumOf { it.timeoutSeconds }.coerceAtMost(120), timeoutSeconds = 25))
            }
        }
    }
}

// MARK: Ring groups

@Composable
fun RingGroupsScreen(model: AppModel, accountId: String, open: (String?) -> Unit, back: () -> Unit) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val all by model.pbx.ringGroups.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    var failure by remember { mutableStateOf<PbxFailure?>(null) }
    var loading by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(accountId, attempt) {
        account ?: return@LaunchedEffect
        loading = true
        failure = model.pbx.loadRingGroups(account)
        loading = false
    }

    AdminPage(model, stringResource(R.string.pbx_ringGroups_title), back) {
        ReadOnlyNote(model, accountId)
        failure?.let { NoteCard(pbxFailureText(it), isError = true) }

        val page = all[accountId]

        if (page == null) {
            if (loading) Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
            else if (failure != null) TextButton(onClick = { attempt++ }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.action_retry)) }
            return@AdminPage
        }

        if (page.ringGroups.isEmpty()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.pbx_ringGroups_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.pbx_ringGroups_empty_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }

        section(null, null) {
            for (group in page.ringGroups) {
                RingGroupRow(group) { open(group.id) }
            }
        }

        if (!model.isPbxReadOnly(accountId)) {
            ActionRow(stringResource(R.string.pbx_ringGroups_add), Icons.Filled.Add, Modifier.testTag("pbx-ringgroup-add")) { open(null) }
        }
    }
}

@Composable
private fun strategyName(strategy: RingStrategy): String = stringResource(
    when (strategy) {
        RingStrategy.ALL -> R.string.pbx_strategy_all
        RingStrategy.SEQUENCE -> R.string.pbx_strategy_sequence
        RingStrategy.ROUND -> R.string.pbx_strategy_round
    },
)

@Composable
private fun RingGroupRow(group: PbxRingGroup, onClick: () -> Unit) {
    val parts = listOfNotNull(group.extensionNumber, strategyName(group.strategy), syncNote(group.sync))

    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp).testTag("pbx-ringgroup-row"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(group.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun RingGroupEditScreen(model: AppModel, accountId: String, groupId: String?, back: () -> Unit) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val all by model.pbx.ringGroups.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    val page = all[accountId]
    val group = groupId?.let { id -> page?.ringGroups?.firstOrNull { it.id == id } }
    val scope = rememberCoroutineScope()
    val pending = stringResource(R.string.pbx_pending_message)

    var baseline by remember(groupId) { mutableStateOf<PbxRingGroupDraft?>(if (groupId == null) PbxRingGroupDraft() else null) }
    var draft by remember(groupId) { mutableStateOf(PbxRingGroupDraft()) }
    var failure by remember(groupId) { mutableStateOf<PbxFailure?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(accountId) {
        if (account != null && page == null) model.pbx.loadRingGroups(account)
    }

    LaunchedEffect(group) {
        val fresh = group ?: return@LaunchedEffect
        val freshDraft = PbxRingGroupDraft.of(fresh)
        val start = baseline

        draft = if (start == null) freshDraft else draft.rebase(freshDraft, start)
        baseline = freshDraft
    }

    val readOnly = model.isPbxReadOnly(accountId)
    val title = group?.name ?: stringResource(R.string.pbx_ringGroup_new)
    val dirty = if (groupId == null) draft.isValid else baseline?.let { group != null && draft.isValid && draft.patch(it, group.version) != null } == true

    AdminPage(model, title, back) {
        if (page == null || (groupId != null && group == null)) {
            InfoRow(stringResource(R.string.pbx_ringGroups_loadFailed))
            return@AdminPage
        }

        ReadOnlyNote(model, accountId)
        failure?.let { NoteCard(pbxFailureText(it), isError = true) }

        ReadOnlyLock(readOnly) {
            Column {
                section(stringResource(R.string.pbx_ringGroup_name), null) {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it.take(60)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("pbx-ringgroup-name"),
                    )
                }

                section(stringResource(R.string.pbx_ringGroup_strategy), strategyHint(draft.strategy)) {
                    for (strategy in RingStrategy.entries) {
                        Row(Modifier.fillMaxWidth().clickable { draft = draft.copy(strategy = strategy) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = draft.strategy == strategy, onClick = { draft = draft.copy(strategy = strategy) })
                            Text(strategyName(strategy), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }

                section(stringResource(R.string.pbx_ringGroup_members), if (draft.members.isEmpty()) stringResource(R.string.pbx_ringGroup_members_empty) else null) {
                    for (device in page.devices) {
                        val member = draft.members.firstOrNull { it.extensionId == device.id }

                        Row(Modifier.fillMaxWidth().clickable { draft = draft.toggle(device.id) }.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = member != null, onCheckedChange = { draft = draft.toggle(device.id) })
                            Text(listOfNotNull(device.name, device.extensionNumber).joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
                        }

                        if (member != null) {
                            if (draft.strategy == RingStrategy.SEQUENCE) {
                                NumberStepperRow(stringResource(R.string.pbx_ringGroup_delay, member.delaySeconds), member.delaySeconds, PbxRingGroupDraft.DELAY_RANGE, 5, "pbx-member-delay") { value -> draft = draft.update(device.id) { it.copy(delaySeconds = value) } }
                            }
                            NumberStepperRow(stringResource(R.string.pbx_ringGroup_rings, member.timeoutSeconds), member.timeoutSeconds, PbxRingGroupDraft.RING_RANGE, 5, "pbx-member-timeout") { value -> draft = draft.update(device.id) { it.copy(timeoutSeconds = value) } }
                        }
                    }
                }

                section(stringResource(R.string.pbx_ringGroup_nobody), stringResource(R.string.pbx_ringGroup_nobody_footer)) {
                    TargetChoiceRow(stringResource(R.string.pbx_ringGroup_nobody_then), draft.timeoutTarget, targetChoices(page.targets), stringResource(R.string.pbx_target_none), "pbx-ringgroup-timeout") { draft = draft.copy(timeoutTarget = it) }
                }
            }
        }

        if (!readOnly) {
            Column(Modifier.padding(16.dp)) {
                PrimaryButton(
                    stringResource(R.string.action_save),
                    enabled = dirty && !saving,
                    modifier = Modifier.testTag("pbx-ringgroup-save"),
                    onClick = {
                        val target = account ?: return@PrimaryButton
                        saving = true

                        scope.launch {
                            failure = if (groupId == null) {
                                model.pbx.createRingGroup(target, draft.create())
                            } else {
                                val patch = baseline?.let { draft.patch(it, group!!.version) }
                                if (patch == null) null else model.pbx.saveRingGroup(target, groupId, patch)
                            }

                            if (failure == null) {
                                model.showNotice(pending, isError = false)
                                back()
                            }

                            saving = false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun strategyHint(strategy: RingStrategy): String = stringResource(
    when (strategy) {
        RingStrategy.ALL -> R.string.pbx_strategy_all_hint
        RingStrategy.SEQUENCE -> R.string.pbx_strategy_sequence_hint
        RingStrategy.ROUND -> R.string.pbx_strategy_round_hint
    },
)

