// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.pbx

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.FlowKind
import nl.fullstackstudio.fsvoip.core.FlowNode
import nl.fullstackstudio.fsvoip.core.PbxTarget
import nl.fullstackstudio.fsvoip.core.PbxTargetOption
import nl.fullstackstudio.fsvoip.core.TargetType

// MARK: - Notices at the top of a screen

/** What the user needs to know before touching anything: frozen, not current, still being applied, or a message. */
@Composable
fun SectionNoticeCards(state: PbxSectionState) {
    val text = rememberPbxText()

    if (state.isReadOnly) {
        NoticeCard(Icons.Filled.Lock, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.adm_pbx_readOnly_title), message = stringResource(R.string.adm_pbx_readOnly_message), testTag = "pbx-readonly-banner")
    }

    if (state.isOutdated) {
        NoticeCard(Icons.Filled.WifiOff, PbxWarm, stringResource(R.string.adm_pbx_outdated_title), message = stringResource(R.string.adm_pbx_outdated_message), testTag = "pbx-outdated-banner")
    }

    if (state.hasPendingSync) {
        PendingCard()
    } else if (state.syncTimedOut) {
        NoticeCard(Icons.Filled.HourglassTop, PbxWarm, stringResource(R.string.adm_pbx_pending_timeout_title), message = stringResource(R.string.adm_pbx_pending_timeout_message), testTag = "pbx-timeout-banner")
    }

    state.banner?.let { banner ->
        NoticeCard(
            if (banner.isError) Icons.Filled.ErrorOutline else Icons.Filled.Info,
            if (banner.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            banner.failure.message(text),
            testTag = "pbx-banner",
        )
    }

    state.loadFailure?.let { failure ->
        NoticeCard(Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.error, failure.message(text), testTag = "pbx-load-failure")
    }
}

@Composable
private fun PendingCard() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("pbx-pending-banner"),
    ) {
        Row(Modifier.padding(16.dp).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.adm_pbx_pending_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.adm_pbx_pending_message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** An error under a form ("these numbers are blocked"). */
@Composable
fun FormFailure(failure: PbxFailure?) {
    if (failure != null) {
        NoticeCard(Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.error, failure.message(rememberPbxText()), testTag = "pbx-form-error")
    }
}

/** What a step form says above its fields after a save that did not go through. */
sealed class ChainEditorMessage {
    /** Someone changed the number in the meantime: the newest state is shown, the user's changes are still there. */
    data object Stale : ChainEditorMessage()

    data class Failure(val failure: PbxFailure) : ChainEditorMessage()
}

/**
 * A form page: title and back (asks before throwing away changes), the notices, the fields (locked when the centrale is frozen) and
 * Annuleren | Opslaan at the bottom.
 */
@Composable
fun EditorPage(
    state: PbxSectionState,
    title: String,
    isDirty: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    canSave: Boolean = true,
    message: ChainEditorMessage? = null,
    footerOn: Boolean = true,
    testTag: String = "pbx-editor",
    content: @Composable ColumnScope.() -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    val leave = { if (isDirty && !state.isSaving) asking = true else onBack() }

    BackHandler(enabled = true) { leave() }

    PbxPage(
        title = title,
        onBack = leave,
        testTag = testTag,
        footer = if (footerOn) {
            { PbxFormFooter(canSave = isDirty && canSave && !state.isReadOnly, isSaving = state.isSaving, onSave = onSave, onCancel = leave) }
        } else {
            null
        },
    ) {
        if (state.isReadOnly) {
            NoticeCard(Icons.Filled.Lock, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.adm_pbx_readOnly_title), message = stringResource(R.string.adm_pbx_readOnly_message), testTag = "pbx-readonly-banner")
        }

        when (message) {
            ChainEditorMessage.Stale -> NoticeCard(Icons.Filled.Refresh, PbxWarm, stringResource(R.string.adm_numbers_stale_title), message = stringResource(R.string.adm_numbers_stale_message), testTag = "chain-stale")
            is ChainEditorMessage.Failure -> {
                NoticeCard(Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.error, message.failure.message(rememberPbxText()), testTag = "chain-error")
            }
            null -> Unit
        }

        // The fields are greyed out and locked while the centrale is frozen or a save is running.
        Box(Modifier.alpha(if (state.isReadOnly) 0.5f else 1f)) {
            Column(content = content)
        }
    }

    if (asking) {
        DiscardChangesDialog(onDiscard = { asking = false; onBack() }, onKeep = { asking = false })
    }
}

// MARK: - Choosing where a call goes

/** A row "label ... current choice >" that opens the picker. */
@Composable
fun TargetChoiceRow(
    title: String,
    target: PbxTarget?,
    options: List<PbxTargetOption>,
    onChange: (PbxTarget?) -> Unit,
    modifier: Modifier = Modifier,
    noneLabel: String = stringResource(R.string.adm_pbx_target_none),
    allowsNone: Boolean = true,
    allowsHangup: Boolean = true,
    footer: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val vocabulary = rememberPbxVocabulary()
    var open by remember { mutableStateOf(false) }

    SettingsRow(
        title = title,
        modifier = modifier,
        icon = icon,
        value = target?.let { vocabulary.describe(it, options) } ?: noneLabel,
        enabled = enabled,
        onClick = { open = true },
        testTag = "target-row",
    )

    if (open) {
        TargetChoicePage(
            title = title,
            selection = target,
            options = options,
            noneLabel = noneLabel,
            allowsNone = allowsNone,
            allowsHangup = allowsHangup,
            footer = footer,
            onPick = {
                open = false
                onChange(it)
            },
            onBack = { open = false },
        )
    }
}

/** One list for every kind of destination: a check on the chosen one, an external number typed in a field. */
@Composable
fun TargetChoicePage(
    title: String,
    selection: PbxTarget?,
    options: List<PbxTargetOption>,
    noneLabel: String,
    allowsNone: Boolean,
    allowsHangup: Boolean,
    footer: String?,
    onPick: (PbxTarget?) -> Unit,
    onBack: () -> Unit,
) {
    val vocabulary = rememberPbxVocabulary()
    val hasExternal = options.any { it.type == TargetType.EXTERNAL }
    var number by remember { mutableStateOf(if (selection?.type == TargetType.EXTERNAL) selection.number ?: "" else "") }
    val clean = number.trim()

    PbxSubPage(title = title, onBack = onBack, testTag = "target-choice-page") {
        if (allowsNone) {
            SettingsGroup(footer = footer) {
                ChoiceRow(noneLabel, selected = selection == null, onClick = { onPick(null) }, testTag = "target-none")
            }
        }

        for (type in PbxVocabulary.pickerOrder) {
            val group = options.filter { it.type == type }

            if (group.isNotEmpty()) {
                SettingsGroup(title = vocabulary.groupName(type)) {
                    GroupItems(group) { _, option ->
                        ChoiceRow(
                            vocabulary.name(option),
                            selected = TargetChoice.option(selection, options)?.value == option.value,
                            onClick = { onPick(TargetChoice.target(option)) },
                            testTag = "target-option",
                        )
                    }
                }
            }
        }

        if (allowsHangup) {
            SettingsGroup {
                ChoiceRow(stringResource(R.string.adm_pbx_type_hangup), selected = selection?.type == TargetType.HANGUP, onClick = { onPick(PbxTarget.HANGUP) }, testTag = "target-hangup")
            }
        }

        if (hasExternal) {
            SettingsGroup(title = stringResource(R.string.adm_pbx_external_title), footer = stringResource(R.string.adm_pbx_external_footer)) {
                FieldRow(number, { number = it }, stringResource(R.string.adm_pbx_external_placeholder), keyboard = androidx.compose.ui.text.input.KeyboardType.Phone, testTag = "pbx-external-number")
                GroupDivider()
                SettingsRow(
                    stringResource(R.string.adm_pbx_external_use),
                    icon = if (selection?.type == TargetType.EXTERNAL) Icons.Filled.Check else null,
                    chevron = false,
                    enabled = clean.isNotEmpty(),
                    onClick = { if (clean.isNotEmpty()) onPick(PbxTarget.external(clean)) },
                    testTag = "pbx-external-use",
                )
            }
        }
    }
}

// MARK: - The call flow of a number

private fun flowIcon(kind: FlowKind): ImageVector = when (kind) {
    FlowKind.DEVICE -> Icons.Filled.Phone
    FlowKind.RING_GROUP -> Icons.Filled.Groups
    FlowKind.QUEUE -> Icons.Filled.SupportAgent
    FlowKind.IVR -> Icons.Filled.Dialpad
    FlowKind.BUSINESS_HOURS -> Icons.Filled.Schedule
    FlowKind.VOICEMAIL -> Icons.Filled.Voicemail
    FlowKind.RECORDING -> Icons.AutoMirrored.Filled.VolumeUp
    FlowKind.HANGUP -> Icons.Filled.CallEnd
    FlowKind.EXTERNAL -> Icons.AutoMirrored.Filled.CallMade
    FlowKind.MISSING -> Icons.Filled.Warning
    FlowKind.LOOP -> Icons.Filled.Refresh
    FlowKind.NO_DESTINATION -> Icons.AutoMirrored.Filled.HelpOutline
    FlowKind.MORE -> Icons.Filled.MoreHoriz
    FlowKind.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline
}

/**
 * The call flow of a number as a line: where a caller starts and what happens next, step by step ("openingstijden, binnen
 * kantooruren belgroep Iedereen, geen antwoord na 25 sec voicemail"). The line runs down the left; every branch says why it is taken.
 */
@Composable
fun FlowView(node: FlowNode, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("pbx-flow")) { FlowStep(node, 0) }
}

private const val MAX_FLOW_DEPTH = 8

@Composable
private fun FlowStep(node: FlowNode, depth: Int) {
    val vocabulary = rememberPbxVocabulary()

    Column(Modifier.fillMaxWidth()) {
        FlowStepRow(node, vocabulary)

        if (depth < MAX_FLOW_DEPTH) {
            for (branch in node.branches) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    // The line of the flow.
                    Box(
                        Modifier.padding(start = 17.dp, top = 2.dp, bottom = 2.dp).width(2.dp).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(1.dp)),
                    )

                    Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(vocabulary.whenText(branch.`when`), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                        FlowStep(branch.node, depth + 1)
                    }
                }
            }
        } else if (node.branches.isNotEmpty()) {
            Text(stringResource(R.string.adm_pbx_flow_more), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 46.dp))
        }
    }
}

@Composable
private fun FlowStepRow(node: FlowNode, vocabulary: PbxVocabulary) {
    val problem = PbxVocabulary.isProblem(node.kind)
    val tint = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(flowIcon(node.kind), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(vocabulary.title(node), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = tint)

            vocabulary.detail(node)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (node.dnd) {
                Text(stringResource(R.string.adm_pbx_flow_dnd), style = MaterialTheme.typography.bodySmall, color = PbxWarm)
            }
        }
    }
}
