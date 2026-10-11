// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.pbx

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneForwarded
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.FollowMeStep
import nl.fullstackstudio.fsvoip.core.PbxDevice
import nl.fullstackstudio.fsvoip.core.PbxDeviceRef
import nl.fullstackstudio.fsvoip.core.PbxHoliday
import nl.fullstackstudio.fsvoip.core.PbxHours
import nl.fullstackstudio.fsvoip.core.PbxNumber
import nl.fullstackstudio.fsvoip.core.PbxOverview
import nl.fullstackstudio.fsvoip.core.PbxRingGroup
import nl.fullstackstudio.fsvoip.core.PbxTarget
import nl.fullstackstudio.fsvoip.core.RingGroupMember
import nl.fullstackstudio.fsvoip.core.RingStrategy
import nl.fullstackstudio.fsvoip.core.TargetType

// MARK: - Overview

/** The first screen of the section: every number of the centrale with where its callers go, and the way to the rest. */
@Composable
fun PbxOverviewScreen(section: PbxSectionModel, nav: PbxNav) {
    val state by section.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val numberWords = rememberNumberWords()
    val overview = state.overview

    LaunchedEffect(Unit) {
        section.loadIfNeeded(PbxPart.OVERVIEW)
        section.loadIfNeeded(PbxPart.NUMBERS)
    }

    PbxPage(
        title = stringResource(R.string.adm_pbx_title),
        onBack = { nav.pop() },
        onRefresh = {
            section.refresh(PbxPart.OVERVIEW)
            section.load(PbxPart.NUMBERS)
        },
        testTag = "pbx-overview",
    ) {
        SectionNoticeCards(state)

        if (overview != null) {
            OverviewSummary(overview)

            if (overview.numbers.isEmpty()) {
                SettingsGroup {
                    Text(stringResource(R.string.adm_numbers_empty_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
            } else {
                SettingsGroup(title = stringResource(R.string.adm_numbers_title)) {
                    GroupItems(overview.numbers) { _, number ->
                        OverviewNumberRow(number, state, numberWords, nav)
                    }
                }
            }

            SettingsGroup(title = stringResource(R.string.adm_pbx_overview_settings)) {
                SettingsRow(
                    stringResource(R.string.adm_pbx_devices_title),
                    icon = Icons.Filled.Phone,
                    value = devicesRowDetail(overview),
                    onClick = { nav.push(PbxRoute.Part(PbxPart.DEVICES)) },
                    testTag = "pbx-devices-link",
                )
                GroupDivider()
                SettingsRow(
                    stringResource(R.string.adm_pbx_ringGroups_title),
                    icon = Icons.Filled.Groups,
                    value = overview.ringGroupCount.toString(),
                    onClick = { nav.push(PbxRoute.Part(PbxPart.RING_GROUPS)) },
                    testTag = "pbx-ringgroups-link",
                )
                GroupDivider()
                SettingsRow(
                    stringResource(R.string.adm_pbx_hours_title),
                    icon = Icons.Filled.Schedule,
                    onClick = { nav.push(PbxRoute.Part(PbxPart.HOURS)) },
                    testTag = "pbx-hours-link",
                )
            }
        } else if (state.isLoading) {
            LoadingBlock()
        } else if (state.loadFailure != null || state.isOutdated) {
            EmptyState(Icons.Filled.WifiOff, stringResource(R.string.adm_pbx_overview_loadFailed), actionTitle = stringResource(R.string.action_retry), onAction = { scope.launch { section.refresh(PbxPart.OVERVIEW) } })
        }
    }
}

@Composable
private fun OverviewSummary(overview: PbxOverview) {
    val connected = overview.connectedCount
    val devices = if (connected != null) {
        stringResource(R.string.adm_pbx_count_devices_connected, overview.deviceCount, connected)
    } else {
        stringResource(R.string.adm_pbx_count_devices, overview.deviceCount)
    }
    val line = "$devices · ${stringResource(R.string.adm_pbx_count_ringGroups, overview.ringGroupCount)}"

    SettingsGroup {
        Column(Modifier.padding(16.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(overview.pbx.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (overview.outboundBlocked) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.PhoneDisabled, contentDescription = null, tint = PbxWarm, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.adm_pbx_overview_outboundBlocked), style = MaterialTheme.typography.bodySmall, color = PbxWarm)
                }
            }
        }
    }
}

@Composable
private fun devicesRowDetail(overview: PbxOverview): String {
    val connected = overview.connectedCount ?: return overview.deviceCount.toString()

    return stringResource(R.string.adm_pbx_row_devices_connected, overview.deviceCount, connected)
}

/** A number in short: its name or number, how it is set up, and whether it is still being applied. The chain opens on a tap. */
@Composable
private fun OverviewNumberRow(number: PbxNumber, state: PbxSectionState, words: NumberWords, nav: PbxNav) {
    val entry = state.numbers?.numbers?.firstOrNull { it.id == number.id }
    val name = entry?.name?.trim() ?: ""
    val title = name.ifEmpty { PbxVocabulary.formatNumber(number.number) }

    Row(
        Modifier.fillMaxWidth().clickable { nav.push(PbxRoute.Number(number.id, title)) }.padding(horizontal = 16.dp, vertical = 12.dp).testTag("pbx-number-link").semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)

            if (name.isNotEmpty()) {
                Text(PbxVocabulary.formatNumber(number.number), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (entry != null) {
                Text(words.listLine(entry), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                PbxSyncBadge(number.sync)
            }
        }

        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

// MARK: - Devices

/** "Toestellen": every toestel of the centrale with whether it is connected and what it does with a call. */
@Composable
fun DevicesScreen(section: PbxSectionModel, nav: PbxNav) {
    val state by section.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val response = state.devices
    val vocabulary = rememberPbxVocabulary()

    LaunchedEffect(Unit) { section.loadIfNeeded(PbxPart.DEVICES) }

    PbxPage(title = stringResource(R.string.adm_pbx_devices_title), onBack = { nav.pop() }, onRefresh = { section.refresh(PbxPart.DEVICES) }, testTag = "pbx-devices") {
        SectionNoticeCards(state)

        if (response != null) {
            if (response.devices.isEmpty()) {
                EmptyState(Icons.Filled.Phone, stringResource(R.string.adm_pbx_devices_empty))
            } else {
                SettingsGroup(footer = stringResource(R.string.adm_pbx_devices_footer)) {
                    GroupItems(response.devices) { _, device ->
                        DeviceRow(device, response.targets, vocabulary) { nav.push(PbxRoute.Device(device.id)) }
                    }
                }
            }
        } else if (state.isLoading) {
            LoadingBlock()
        } else if (state.loadFailure != null || state.isOutdated) {
            EmptyState(Icons.Filled.WifiOff, stringResource(R.string.adm_pbx_devices_loadFailed), actionTitle = stringResource(R.string.action_retry), onAction = { scope.launch { section.refresh(PbxPart.DEVICES) } })
        }
    }
}

@Composable
private fun DeviceRow(device: PbxDevice, options: List<nl.fullstackstudio.fsvoip.core.PbxTargetOption>, vocabulary: PbxVocabulary, onClick: () -> Unit) {
    val registration = device.registration
    val connection = registration?.let { if (it.connected) stringResource(R.string.adm_pbx_device_connected) else stringResource(R.string.adm_pbx_device_disconnected) }

    Row(
        Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp).testTag("pbx-device-row").semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DeviceAvatar(device.name, 40.dp, connected = registration?.connected == true)

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                device.extensionNumber?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            if (connection != null) {
                Text(connection, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            device.forwardAlways?.let {
                Text(stringResource(R.string.adm_pbx_device_forwarding, vocabulary.describe(it, options)), style = MaterialTheme.typography.bodySmall, color = PbxWarm)
            }

            if (device.dnd) {
                Text(stringResource(R.string.adm_pbx_flow_dnd), style = MaterialTheme.typography.bodySmall, color = PbxWarm)
            }

            PbxSyncBadge(device.sync)
        }

        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** The settings of one toestel an admin may change from the app. The form keeps the version it was opened with. */
@Composable
fun DeviceEditScreen(section: PbxSectionModel, deviceId: String, nav: PbxNav) {
    val state by section.state.collectAsStateWithLifecycle()
    val original = remember(deviceId) { section.state.value.devices?.devices?.firstOrNull { it.id == deviceId } }

    if (original == null) {
        LaunchedEffect(deviceId) { nav.pop() }
        return
    }

    DeviceEditor(section, state, original, nav)
}

@Composable
private fun DeviceEditor(section: PbxSectionModel, state: PbxSectionState, original: PbxDevice, nav: PbxNav) {
    var draft by remember { mutableStateOf(DeviceDraft(original)) }
    var failure by remember { mutableStateOf<PbxFailure?>(null) }
    var editingStep by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val options = TargetChoice.excluding(original.id, state.targetOptions)
    val vocabulary = rememberPbxVocabulary()
    val text = rememberPbxText()
    val isDirty = draft != DeviceDraft(original)

    fun save() {
        // A step without a destination is a half-filled form, not something the server should judge.
        if (draft.followMe.any { it.target.type == TargetType.EXTERNAL && (it.target.number ?: "").isBlank() }) {
            failure = PbxFailure.Invalid
            return
        }

        scope.launch {
            val outcome = section.saveDevice(draft, original)

            if (outcome.closesForm) nav.pop() else if (outcome is PbxSaveOutcome.Failed) failure = outcome.failure
        }
    }

    EditorPage(state, original.name, isDirty, onBack = { nav.pop() }, onSave = { save() }, message = failure?.let { ChainEditorMessage.Failure(it) }, testTag = "pbx-device-edit") {
        SettingsGroup(title = original.name) {
            ToggleRow(stringResource(R.string.adm_pbx_device_voicemail), draft.voicemailEnabled, { draft = draft.copy(voicemailEnabled = it) }, explanation = stringResource(R.string.adm_pbx_device_voicemail_footer), testTag = "pbx-voicemail-toggle")
            GroupDivider()
            ToggleRow(stringResource(R.string.adm_pbx_device_dnd), draft.dnd, { draft = draft.copy(dnd = it) }, explanation = stringResource(R.string.adm_pbx_device_dnd_footer), testTag = "pbx-dnd-toggle")
        }

        SettingsGroup(footer = stringResource(R.string.adm_pbx_device_forward_footer)) {
            TargetChoiceRow(
                stringResource(R.string.adm_pbx_device_forward_title),
                draft.forwardAlways,
                options,
                { draft = draft.copy(forwardAlways = it) },
                noneLabel = stringResource(R.string.adm_pbx_device_forward_off),
                icon = Icons.Filled.PhoneForwarded,
            )
        }

        SettingsGroup(title = stringResource(R.string.adm_pbx_device_noAnswer_title), footer = stringResource(R.string.adm_pbx_device_noAnswer_footer)) {
            StepperRow(stringResource(R.string.adm_pbx_device_noAnswer_after, draft.noAnswerSeconds), draft.noAnswerSeconds, DeviceDraft.noAnswerRange, 5, { draft = draft.copy(noAnswerSeconds = it) }, testTag = "pbx-noanswer-seconds")
            GroupDivider()
            TargetChoiceRow(
                stringResource(R.string.adm_pbx_device_noAnswer_then),
                draft.noAnswerTarget,
                options,
                { draft = draft.copy(noAnswerTarget = it) },
                noneLabel = stringResource(if (draft.voicemailEnabled) R.string.adm_pbx_device_noAnswer_default_voicemail else R.string.adm_pbx_device_noAnswer_default_ring),
            )
        }

        SettingsGroup(title = stringResource(R.string.adm_pbx_device_busy_title)) {
            TargetChoiceRow(stringResource(R.string.adm_pbx_device_busy_then), draft.busyTarget, options, { draft = draft.copy(busyTarget = it) }, noneLabel = stringResource(R.string.adm_pbx_device_busy_default))
        }

        SettingsGroup(title = stringResource(R.string.adm_pbx_device_offline_title)) {
            TargetChoiceRow(stringResource(R.string.adm_pbx_device_offline_then), draft.notRegisteredTarget, options, { draft = draft.copy(notRegisteredTarget = it) }, noneLabel = stringResource(R.string.adm_pbx_device_offline_default))
        }

        SettingsGroup(title = stringResource(R.string.adm_pbx_followMe_title), footer = stringResource(R.string.adm_pbx_followMe_footer)) {
            GroupItems(draft.followMe) { index, step ->
                SettingsRow(
                    vocabulary.describe(step.target, options),
                    subtitle = text.get(R.string.adm_pbx_followMe_timing, step.delaySeconds, step.timeoutSeconds),
                    onClick = { editingStep = index },
                    testTag = "pbx-followme-row",
                )
            }

            if (draft.followMe.size < DeviceDraft.FOLLOW_ME_LIMIT) {
                if (draft.followMe.isNotEmpty()) GroupDivider()
                SettingsRow(
                    stringResource(R.string.adm_pbx_followMe_add),
                    icon = Icons.Filled.Add,
                    chevron = false,
                    onClick = {
                        draft = draft.copy(followMe = draft.followMe + FollowMeStep(PbxTarget.external(""), delaySeconds = if (draft.followMe.isEmpty()) 0 else 10))
                    },
                    testTag = "pbx-followme-add",
                )
            }
        }
    }

    editingStep?.let { index ->
        val step = draft.followMe.getOrNull(index)

        if (step == null) {
            editingStep = null
        } else {
            FollowMeStepPage(
                step = step,
                options = options,
                canMoveUp = index > 0,
                canMoveDown = index < draft.followMe.size - 1,
                onChange = { new -> draft = draft.copy(followMe = draft.followMe.toMutableList().also { it[index] = new }) },
                onMove = { offset ->
                    val target = index + offset

                    if (target in draft.followMe.indices) {
                        val list = draft.followMe.toMutableList()
                        list[index] = list[target].also { list[target] = list[index] }
                        draft = draft.copy(followMe = list)
                        editingStep = target
                    }
                },
                onDelete = {
                    editingStep = null
                    draft = draft.copy(followMe = draft.followMe.filterIndexed { i, _ -> i != index })
                },
                onBack = { editingStep = null },
            )
        }
    }
}

/** One step of "volg mij": who is called next, after how long, and for how long. */
@Composable
private fun FollowMeStepPage(
    step: FollowMeStep,
    options: List<nl.fullstackstudio.fsvoip.core.PbxTargetOption>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (FollowMeStep) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    PbxSubPage(title = stringResource(R.string.adm_pbx_followMe_step), onBack = onBack, testTag = "pbx-followme-page") {
        SettingsGroup {
            TargetChoiceRow(stringResource(R.string.adm_pbx_followMe_who), step.target, options, { it?.let { target -> onChange(step.copy(target = target)) } }, allowsNone = false)
        }

        SettingsGroup(footer = stringResource(R.string.adm_pbx_followMe_step_footer)) {
            StepperRow(stringResource(R.string.adm_pbx_followMe_delay, step.delaySeconds), step.delaySeconds, 0..120, 5, { onChange(step.copy(delaySeconds = it)) }, testTag = "pbx-followme-delay")
            GroupDivider()
            StepperRow(stringResource(R.string.adm_pbx_followMe_timeout, step.timeoutSeconds), step.timeoutSeconds, 5..120, 5, { onChange(step.copy(timeoutSeconds = it)) }, testTag = "pbx-followme-timeout")
        }

        SettingsGroup {
            if (canMoveUp) {
                SettingsRow(stringResource(R.string.adm_pbx_followMe_moveUp), icon = Icons.Filled.ArrowUpward, chevron = false, onClick = { onMove(-1) })
                GroupDivider()
            }

            if (canMoveDown) {
                SettingsRow(stringResource(R.string.adm_pbx_followMe_moveDown), icon = Icons.Filled.ArrowDownward, chevron = false, onClick = { onMove(1) })
                GroupDivider()
            }

            SettingsRow(stringResource(R.string.adm_pbx_followMe_remove), icon = Icons.Filled.Delete, chevron = false, destructive = true, onClick = onDelete, testTag = "pbx-followme-remove")
        }
    }
}

// MARK: - Ring groups

/** "Belgroepen": the groups of toestellen that ring together or one after the other. */
@Composable
fun RingGroupsScreen(section: PbxSectionModel, nav: PbxNav) {
    val state by section.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val response = state.ringGroups
    val vocabulary = rememberPbxVocabulary()

    LaunchedEffect(Unit) { section.loadIfNeeded(PbxPart.RING_GROUPS) }

    PbxPage(title = stringResource(R.string.adm_pbx_ringGroups_title), onBack = { nav.pop() }, onRefresh = { section.refresh(PbxPart.RING_GROUPS) }, testTag = "pbx-ringgroups") {
        SectionNoticeCards(state)

        if (response != null) {
            if (response.ringGroups.isEmpty()) {
                EmptyState(Icons.Filled.Groups, stringResource(R.string.adm_pbx_ringGroups_empty_title), message = stringResource(R.string.adm_pbx_ringGroups_empty_message))
            } else {
                SettingsGroup {
                    GroupItems(response.ringGroups) { _, group ->
                        SettingsRow(
                            listOfNotNull(group.name, group.extensionNumber).joinToString(" · "),
                            icon = Icons.Filled.Groups,
                            subtitle = "${vocabulary.strategy(group.strategy)} · ${stringResource(R.string.adm_pbx_count_members, group.members.size)}",
                            onClick = { nav.push(PbxRoute.RingGroup(group.id)) },
                            testTag = "pbx-ringgroup-row",
                        )
                    }
                }
            }

            SettingsGroup {
                SettingsRow(
                    stringResource(R.string.adm_pbx_ringGroups_add),
                    icon = Icons.Filled.Add,
                    chevron = false,
                    enabled = !state.isReadOnly,
                    onClick = { nav.push(PbxRoute.RingGroup(null)) },
                    testTag = "pbx-ringgroup-add",
                )
            }
        } else if (state.isLoading) {
            LoadingBlock()
        } else if (state.loadFailure != null || state.isOutdated) {
            EmptyState(Icons.Filled.WifiOff, stringResource(R.string.adm_pbx_ringGroups_loadFailed), actionTitle = stringResource(R.string.action_retry), onAction = { scope.launch { section.refresh(PbxPart.RING_GROUPS) } })
        }
    }
}

@Composable
fun RingGroupEditScreen(section: PbxSectionModel, groupId: String?, nav: PbxNav) {
    val state by section.state.collectAsStateWithLifecycle()
    val original = remember(groupId) { groupId?.let { id -> section.state.value.ringGroups?.ringGroups?.firstOrNull { it.id == id } } }

    LaunchedEffect(Unit) { section.loadIfNeeded(PbxPart.RING_GROUPS) }

    if (groupId != null && original == null) {
        LaunchedEffect(groupId) { nav.pop() }
        return
    }

    RingGroupEditor(section, state, original, nav)
}

/** Make a new belgroep or change one: name, how the phones ring, who is in it, and what happens when nobody picks up. */
@Composable
private fun RingGroupEditor(section: PbxSectionModel, state: PbxSectionState, original: PbxRingGroup?, nav: PbxNav) {
    var draft by remember { mutableStateOf(original?.let(::RingGroupDraft) ?: RingGroupDraft()) }
    var failure by remember { mutableStateOf<PbxFailure?>(null) }
    val scope = rememberCoroutineScope()
    val devices = state.ringGroups?.devices ?: emptyList()
    val options = TargetChoice.excluding(original?.id, state.ringGroups?.targets ?: emptyList())
    val vocabulary = rememberPbxVocabulary()
    val isDirty = if (original != null) draft.patch(original) != null else draft.isFilledIn
    val goneName = stringResource(R.string.adm_pbx_target_gone)

    fun isMember(id: String) = draft.members.any { it.extensionId == id }

    fun toggle(device: PbxDeviceRef) {
        val index = draft.members.indexOfFirst { it.extensionId == device.id }

        draft = when {
            index >= 0 -> draft.copy(members = draft.members.filterIndexed { i, _ -> i != index })
            draft.members.size < RingGroupDraft.MEMBER_LIMIT -> draft.copy(members = draft.members + RingGroupMember(device.id))
            else -> draft
        }
    }

    fun move(index: Int, offset: Int) {
        val target = index + offset

        if (index !in draft.members.indices || target !in draft.members.indices) return

        val list = draft.members.toMutableList()
        list[index] = list[target].also { list[target] = list[index] }
        draft = draft.copy(members = list)
    }

    fun save() {
        scope.launch {
            val outcome = if (original != null) section.saveRingGroup(draft, original) else section.createRingGroup(draft)

            if (outcome.closesForm) nav.pop() else if (outcome is PbxSaveOutcome.Failed) failure = outcome.failure
        }
    }

    EditorPage(
        state,
        original?.name ?: stringResource(R.string.adm_pbx_ringGroup_new),
        isDirty,
        onBack = { nav.pop() },
        onSave = { save() },
        canSave = original != null || draft.isFilledIn,
        message = failure?.let { ChainEditorMessage.Failure(it) },
        testTag = "pbx-ringgroup-edit",
    ) {
        SettingsGroup(title = stringResource(R.string.adm_pbx_ringGroup_name)) {
            FieldRow(draft.name, { draft = draft.copy(name = it) }, stringResource(R.string.adm_pbx_ringGroup_name), testTag = "pbx-ringgroup-name")
        }

        SettingsGroup(title = stringResource(R.string.adm_pbx_ringGroup_strategy), footer = vocabulary.strategyHint(draft.strategy)) {
            GroupItems(listOf(RingStrategy.ALL, RingStrategy.SEQUENCE, RingStrategy.ROUND)) { _, strategy ->
                ChoiceRow(vocabulary.strategy(strategy), selected = draft.strategy == strategy, onClick = { draft = draft.copy(strategy = strategy) }, testTag = "pbx-strategy-${strategy.wire}")
            }
        }

        SettingsGroup(title = stringResource(R.string.adm_pbx_ringGroup_members), footer = if (draft.members.isEmpty()) stringResource(R.string.adm_pbx_ringGroup_members_empty) else null) {
            GroupItems(devices) { _, device ->
                Row(
                    Modifier.fillMaxWidth().clickable { toggle(device) }.padding(horizontal = 16.dp, vertical = 8.dp).testTag("pbx-ringgroup-member-row").semantics(mergeDescendants = true) { this.selected = isMember(device.id) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DeviceAvatar(device.name, 36.dp)
                    ChoiceRowLabel(device.name, isMember(device.id), Modifier.weight(1f), subtitle = device.extensionNumber?.let { stringResource(R.string.adm_pbx_account_extension, it) })
                }
            }
        }

        if (draft.members.isNotEmpty()) {
            val allAtOnce = draft.strategy == RingStrategy.ALL

            SettingsGroup(
                title = stringResource(if (allAtOnce) R.string.adm_pbx_ringGroup_times else R.string.adm_pbx_ringGroup_order),
                footer = if (allAtOnce) null else stringResource(R.string.adm_pbx_ringGroup_order_footer),
            ) {
                GroupItems(draft.members.indices.toList()) { _, index ->
                    MemberTimingRow(
                        name = devices.firstOrNull { it.id == draft.members[index].extensionId }?.name ?: goneName,
                        member = draft.members[index],
                        showsDelay = !allAtOnce,
                        showsOrder = !allAtOnce && draft.members.size > 1,
                        canMoveUp = index > 0,
                        canMoveDown = index < draft.members.size - 1,
                        onMove = { move(index, it) },
                        onChange = { new -> draft = draft.copy(members = draft.members.toMutableList().also { it[index] = new }) },
                    )
                }
            }
        }

        SettingsGroup(title = stringResource(R.string.adm_pbx_ringGroup_nobody), footer = stringResource(R.string.adm_pbx_ringGroup_nobody_footer)) {
            TargetChoiceRow(stringResource(R.string.adm_pbx_ringGroup_nobody_then), draft.timeoutTarget, options, { draft = draft.copy(timeoutTarget = it) })
        }
    }
}

@Composable
private fun MemberTimingRow(
    name: String,
    member: RingGroupMember,
    showsDelay: Boolean,
    showsOrder: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onChange: (RingGroupMember) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))

            if (showsOrder) {
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(Icons.Filled.ExpandLess, contentDescription = stringResource(R.string.adm_pbx_followMe_moveUp))
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(Icons.Filled.ExpandMore, contentDescription = stringResource(R.string.adm_pbx_followMe_moveDown))
                }
            }
        }

        if (showsDelay) {
            StepperRow(stringResource(R.string.adm_pbx_ringGroup_delay, member.delaySeconds), member.delaySeconds, 0..120, 5, { onChange(member.copy(delaySeconds = it)) })
        }

        StepperRow(stringResource(R.string.adm_pbx_ringGroup_rings, member.timeoutSeconds), member.timeoutSeconds, 5..120, 5, { onChange(member.copy(timeoutSeconds = it)) })
    }
}

// MARK: - Opening hours

/** "Openingstijden": the opening hours of the centrale with the way to change them and to close for a few days. */
@Composable
fun HoursScreen(section: PbxSectionModel, nav: PbxNav) {
    val state by section.state.collectAsStateWithLifecycle()
    val response = state.hours
    val vocabulary = rememberPbxVocabulary()

    LaunchedEffect(Unit) { section.loadIfNeeded(PbxPart.HOURS) }

    PbxPage(title = stringResource(R.string.adm_pbx_hours_title), onBack = { nav.pop() }, onRefresh = { section.refresh(PbxPart.HOURS) }, testTag = "pbx-hours") {
        SectionNoticeCards(state)

        if (response != null) {
            if (response.hours.isEmpty()) {
                EmptyState(Icons.Filled.Schedule, stringResource(R.string.adm_pbx_hours_empty_title), message = stringResource(R.string.adm_pbx_hours_empty_message))
            }

            for (hours in response.hours) {
                SettingsGroup(title = hours.name) {
                    SettingsRow(
                        vocabulary.hoursSummary(hours.week),
                        subtitle = stringResource(R.string.adm_pbx_hours_holidays, hours.holidays.size),
                        onClick = { nav.push(PbxRoute.HoursEdit(hours.id)) },
                        testTag = "pbx-hours-row",
                    )
                    if (hours.sync != nl.fullstackstudio.fsvoip.core.SyncState.OK && hours.sync != nl.fullstackstudio.fsvoip.core.SyncState.UNKNOWN) {
                        PbxSyncBadge(hours.sync, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    GroupDivider()
                    SettingsRow(stringResource(R.string.adm_pbx_closed_title), icon = Icons.Filled.CalendarMonth, onClick = { nav.push(PbxRoute.Closed(hours.id)) }, testTag = "pbx-closed-link")
                }
            }
        } else if (state.isLoading) {
            LoadingBlock()
        }
    }
}

/** The week of the opening hours and where callers go inside and outside them. */
@Composable
fun HoursEditScreen(section: PbxSectionModel, hoursId: String, nav: PbxNav) {
    val state by section.state.collectAsStateWithLifecycle()
    val original = remember(hoursId) { section.state.value.hours?.hours?.firstOrNull { it.id == hoursId } }

    if (original == null) {
        LaunchedEffect(hoursId) { nav.pop() }
        return
    }

    HoursEditor(section, state, original, nav)
}

@Composable
private fun HoursEditor(section: PbxSectionModel, state: PbxSectionState, original: PbxHours, nav: PbxNav) {
    var draft by remember { mutableStateOf(HoursDraft(original)) }
    var failure by remember { mutableStateOf<PbxFailure?>(null) }
    var editingDay by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val options = TargetChoice.excluding(original.id, state.hours?.targets ?: emptyList())
    val isDirty = !draft.sameAs(HoursDraft(original))

    fun save() {
        scope.launch {
            val outcome = section.saveHours(draft, original)

            if (outcome.closesForm) nav.pop() else if (outcome is PbxSaveOutcome.Failed) failure = outcome.failure
        }
    }

    EditorPage(state, original.name, isDirty, onBack = { nav.pop() }, onSave = { save() }, message = failure?.let { ChainEditorMessage.Failure(it) }, testTag = "pbx-hours-edit") {
        SettingsGroup(title = stringResource(R.string.adm_numbers_hours_tab_week), footer = stringResource(R.string.adm_numbers_hours_bars_footer)) {
            DayBars(WeekPlan(draft.days), onSelect = { editingDay = it }, enabled = !state.isReadOnly)
        }

        SettingsGroup(title = stringResource(R.string.adm_pbx_hours_targets), footer = stringResource(R.string.adm_pbx_hours_targets_footer)) {
            TargetChoiceRow(stringResource(R.string.adm_pbx_hours_open_then), draft.openTarget, options, { draft = draft.copy(openTarget = it) })
            GroupDivider()
            TargetChoiceRow(stringResource(R.string.adm_pbx_hours_closed_then), draft.closedTarget, options, { draft = draft.copy(closedTarget = it) })
        }
    }

    editingDay?.let { day ->
        DaySlotsPage(
            day = day,
            slots = draft.days[day] ?: emptyList(),
            enabled = !state.isReadOnly,
            onChange = { slots -> draft = draft.copy(days = draft.days + (day to slots)) },
            onBack = { editingDay = null },
        )
    }
}

/** The slots of one day: up to four from-to pairs. */
@Composable
fun DaySlotsPage(day: Int, slots: List<HoursInterval>, enabled: Boolean, onChange: (List<HoursInterval>) -> Unit, onBack: () -> Unit) {
    val vocabulary = rememberPbxVocabulary()

    PbxSubPage(title = stringResource(R.string.adm_numbers_hours_title), onBack = onBack, testTag = "dayslots-page") {
        SettingsGroup(title = vocabulary.weekday(day), footer = stringResource(R.string.adm_numbers_hours_day_footer)) {
            if (slots.isEmpty()) {
                Text(stringResource(R.string.adm_numbers_hours_day_closed), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }

            GroupItems(slots) { index, slot ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("dayslot-$index"), verticalAlignment = Alignment.CenterVertically) {
                    TimeField(stringResource(R.string.adm_pbx_hours_from), slot.from, isEnd = false, onChange = { v -> onChange(slots.toMutableList().also { it[index] = slot.copy(from = v) }) })
                    Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TimeField(stringResource(R.string.adm_pbx_hours_to), slot.to, isEnd = true, onChange = { v -> onChange(slots.toMutableList().also { it[index] = slot.copy(to = v) }) })

                    if (!DayBarsLogic.isValid(slot)) {
                        Icon(Icons.Filled.ErrorOutline, contentDescription = stringResource(R.string.adm_numbers_hours_slot_invalid), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    }

                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { onChange(slots.filterIndexed { i, _ -> i != index }) }, enabled = enabled) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.adm_pbx_hours_removeSlot), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (slots.size < WeekPlan.SLOTS_PER_DAY) {
                if (slots.isNotEmpty()) GroupDivider()
                SettingsRow(
                    stringResource(R.string.adm_pbx_hours_addSlot),
                    icon = Icons.Filled.Add,
                    chevron = false,
                    enabled = enabled,
                    onClick = { onChange(slots + DayBarsLogic.nextSlot(slots)) },
                    testTag = "dayslots-add",
                )
            }
        }
    }
}

// MARK: - Temporarily closed

/**
 * "Tijdelijk dicht": a few days (holiday, building holiday, a day off) on which callers get what they get outside the opening
 * hours. They are own dates in the holiday list; the dates already there stay, and each one can be removed again.
 */
@Composable
fun TemporaryClosedScreen(section: PbxSectionModel, hoursId: String, nav: PbxNav) {
    val state by section.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val hours = state.hours?.hours?.firstOrNull { it.id == hoursId }
    val today = remember { LocalDate.now() }
    var from by remember { mutableStateOf(today) }
    var until by remember { mutableStateOf(today) }
    var reason by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<PbxFailure?>(null) }
    var planError by remember { mutableStateOf<TemporaryClosure.PlanError?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var justClosed by remember { mutableStateOf(false) }
    val text = rememberPbxText()
    val defaultName = stringResource(R.string.adm_pbx_closed_defaultName)
    val ownDays = (hours?.holidays ?: emptyList()).filter { it.date != null }.sortedBy { it.date }

    LaunchedEffect(Unit) { section.loadIfNeeded(PbxPart.HOURS) }

    fun handle(outcome: PbxSaveOutcome) {
        failure = (outcome as? PbxSaveOutcome.Failed)?.failure
    }

    fun close() {
        val current = hours ?: return
        val dates = TemporaryClosure.dates(from, until)

        when (val plan = TemporaryClosure.closing(current, reason, dates, defaultName)) {
            is TemporaryClosure.Plan.Failure -> {
                planError = plan.error
                failure = null
            }
            is TemporaryClosure.Plan.Success -> {
                planError = null
                failure = null

                scope.launch {
                    // The newest version of the hours, not the one the screen was opened with.
                    val latest = section.state.value.hours?.hours?.firstOrNull { it.id == hoursId } ?: return@launch
                    val outcome = section.saveHolidays(plan.holidays, latest)
                    handle(outcome)
                    justClosed = outcome == PbxSaveOutcome.Saved
                }
            }
        }
    }

    fun reopen(holiday: PbxHoliday) {
        val current = hours ?: return
        val date = holiday.date ?: return

        removing = date
        justClosed = false

        scope.launch {
            val outcome = section.saveHolidays(TemporaryClosure.reopening(current, date), current)
            removing = null
            handle(outcome)
        }
    }

    PbxPage(title = stringResource(R.string.adm_pbx_closed_title), onBack = { nav.pop() }, testTag = "pbx-closed") {
        SectionNoticeCards(state)
        FormFailure(failure)

        planError?.let { error ->
            val message = when (error) {
                TemporaryClosure.PlanError.EmptyRange -> stringResource(R.string.adm_pbx_closed_error_range)
                is TemporaryClosure.PlanError.TooMany -> text.get(R.string.adm_pbx_closed_error_tooMany, TemporaryClosure.CUSTOM_DATE_LIMIT, error.room)
            }

            NoticeCard(Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.error, message, testTag = "pbx-closed-plan-error")
        }

        SettingsGroup(title = stringResource(R.string.adm_pbx_closed_header), footer = stringResource(R.string.adm_pbx_closed_footer)) {
            DateRow(stringResource(R.string.adm_pbx_closed_from), from, { from = it; if (until.isBefore(it)) until = it }, testTag = "pbx-closed-from")
            GroupDivider()
            DateRow(stringResource(R.string.adm_pbx_closed_until), until, { until = it }, min = from, testTag = "pbx-closed-until")
            GroupDivider()
            FieldRow(reason, { reason = it }, stringResource(R.string.adm_pbx_closed_reason), testTag = "pbx-closed-reason")
            GroupDivider()
            SettingsRow(
                stringResource(R.string.adm_pbx_closed_action),
                chevron = false,
                enabled = !state.isReadOnly && !state.isSaving && hours != null,
                onClick = { close() },
                testTag = "pbx-close-action",
            )
        }

        if (justClosed) {
            NoticeCard(Icons.Filled.Check, MaterialTheme.colorScheme.onSurface, stringResource(R.string.adm_pbx_closed_done), testTag = "pbx-closed-done")
        }

        if (ownDays.isNotEmpty()) {
            val vocabularyLocale = remember { java.util.Locale.getDefault() }
            val formatter = remember { java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(vocabularyLocale) }

            fun display(date: String?): String = date?.let { runCatching { LocalDate.parse(it).format(formatter) }.getOrDefault(it) } ?: ""

            SettingsGroup(title = stringResource(R.string.adm_pbx_closed_current), footer = text.get(R.string.adm_pbx_closed_limit, TemporaryClosure.CUSTOM_DATE_LIMIT)) {
                GroupItems(ownDays) { _, holiday ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                            Text(display(holiday.date), style = MaterialTheme.typography.bodyLarge)
                            Text(holiday.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        TextButton(
                            onClick = { reopen(holiday) },
                            enabled = !state.isReadOnly && !state.isSaving,
                            modifier = Modifier.semantics { contentDescription = text.get(R.string.adm_pbx_closed_reopen_label, display(holiday.date)) },
                        ) {
                            Text(stringResource(R.string.adm_pbx_closed_reopen))
                        }
                    }
                }
            }
        }
    }
}
