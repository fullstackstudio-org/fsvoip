// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.PbxTarget
import nl.fullstackstudio.fsvoip.core.PbxTargetOption
import nl.fullstackstudio.fsvoip.core.SelfExtension
import nl.fullstackstudio.fsvoip.core.SelfExtensionDraft
import nl.fullstackstudio.fsvoip.core.SelfExtensionFailure
import nl.fullstackstudio.fsvoip.core.SelfExtensionHub
import nl.fullstackstudio.fsvoip.core.TargetType
import nl.fullstackstudio.fsvoip.ui.section

/** What a page of the own extension says above its fields after a save that did not go through. */
sealed interface SelfExtensionMessage {
    /** Changed somewhere else in the meantime: the newest state is in, what the user typed is still there. */
    data object Stale : SelfExtensionMessage

    data class Failure(val failure: SelfExtensionFailure) : SelfExtensionMessage
}

@Composable
fun selfFailureText(failure: SelfExtensionFailure): String = when (failure) {
    SelfExtensionFailure.AccessDenied -> stringResource(R.string.snd_self_error_denied)
    SelfExtensionFailure.Revoked -> stringResource(R.string.snd_self_error_revoked)
    SelfExtensionFailure.ReadOnly -> stringResource(R.string.snd_pbx_readOnly_message)
    SelfExtensionFailure.OwnDevice -> stringResource(R.string.snd_invite_error_ownDevice)
    SelfExtensionFailure.Conflict -> stringResource(R.string.snd_self_error_conflict)
    SelfExtensionFailure.Invalid -> stringResource(R.string.snd_self_error_invalid)
    is SelfExtensionFailure.RateLimited -> {
        val seconds = failure.retryAfterSeconds

        if (seconds != null && seconds > 60) stringResource(R.string.snd_self_error_rateLimited_minutes, (seconds + 59) / 60) else stringResource(R.string.snd_self_error_rateLimited)
    }
    SelfExtensionFailure.Offline -> stringResource(R.string.snd_self_error_offline)
    SelfExtensionFailure.Unavailable -> stringResource(R.string.snd_self_error_unavailable)
    SelfExtensionFailure.Other -> stringResource(R.string.snd_self_error_other)
}

@Composable
fun SelfExtensionNotice(message: SelfExtensionMessage?) {
    when (message) {
        SelfExtensionMessage.Stale -> NoticeCard(
            stringResource(R.string.snd_self_stale_message),
            isError = false,
            title = stringResource(R.string.snd_self_stale_title),
            modifier = Modifier.testTag("self-stale"),
        )
        is SelfExtensionMessage.Failure -> NoticeCard(selfFailureText(message.failure), modifier = Modifier.testTag("self-error"))
        null -> Unit
    }
}

/** The shared bookkeeping of a page that edits the own extension: the draft, the state it started from, and the save. */
class SelfExtensionForm {
    var draft by mutableStateOf(SelfExtensionDraft())
    var baseline by mutableStateOf<SelfExtensionDraft?>(null)
    var message by mutableStateOf<SelfExtensionMessage?>(null)

    val isLoaded: Boolean get() = baseline != null

    val isDirty: Boolean get() = baseline?.let { draft.patch(it, 0) != null } ?: false

    /** Takes the state in: the first time as the start, later (a refresh) only the fields the user did not touch. */
    fun adopt(state: SelfExtension?) {
        if (state == null) return

        val existing = baseline

        if (existing != null) {
            val result = draft.rebased(state, existing)
            draft = result.draft
            baseline = result.baseline
        } else {
            draft = SelfExtensionDraft(state)
            baseline = draft
        }
    }

    /** Takes the result of a save in. True = nothing left to do (saved or nothing to save). */
    fun finish(outcome: SelfExtensionHub.SaveOutcome): Boolean = when (outcome) {
        is SelfExtensionHub.SaveOutcome.Saved -> {
            message = null

            // What was saved is now the start; a field the server answers differently follows the server.
            outcome.state?.let { draft = SelfExtensionDraft(it); baseline = draft }

            true
        }
        SelfExtensionHub.SaveOutcome.Unchanged -> {
            message = null
            true
        }
        is SelfExtensionHub.SaveOutcome.Stale -> {
            baseline?.let {
                val result = draft.rebased(outcome.state, it)
                draft = result.draft
                baseline = result.baseline
            }
            message = SelfExtensionMessage.Stale
            false
        }
        is SelfExtensionHub.SaveOutcome.Failed -> {
            message = SelfExtensionMessage.Failure(outcome.failure)
            false
        }
    }
}

/** Finding the option a target stands for, and the target an option stands for (the iOS `TargetChoice`). */
object SelfTargetChoice {
    /** The option the target belongs to (`external` has no id; `hangup` is not an option of the server). */
    fun option(target: PbxTarget?, options: List<PbxTargetOption>): PbxTargetOption? =
        target?.let { wanted -> options.firstOrNull { it.type == wanted.type && it.id == wanted.id } }

    fun target(option: PbxTargetOption, externalNumber: String = ""): PbxTarget? = when (option.type) {
        TargetType.EXTERNAL -> externalNumber.trim().takeIf { it.isNotEmpty() }?.let { PbxTarget.external(it) }
        TargetType.HANGUP -> PbxTarget.HANGUP
        TargetType.UNKNOWN -> null
        else -> option.id?.let { PbxTarget.obj(option.type, it) }
    }

    /** The order of the groups in the picker. */
    val pickerOrder = listOf(TargetType.DEVICE, TargetType.RING_GROUP, TargetType.QUEUE, TargetType.IVR, TargetType.BUSINESS_HOURS, TargetType.VOICEMAIL, TargetType.RECORDING)
}

@Composable
fun targetGroupName(type: TargetType): String = stringResource(
    when (type) {
        TargetType.DEVICE -> R.string.snd_pbx_group_device
        TargetType.RING_GROUP -> R.string.snd_pbx_group_ringGroup
        TargetType.QUEUE -> R.string.snd_pbx_group_queue
        TargetType.IVR -> R.string.snd_pbx_group_ivr
        TargetType.BUSINESS_HOURS -> R.string.snd_pbx_group_hours
        TargetType.VOICEMAIL -> R.string.snd_pbx_group_voicemail
        TargetType.RECORDING -> R.string.snd_pbx_group_recording
        TargetType.HANGUP -> R.string.snd_pbx_type_hangup
        TargetType.EXTERNAL -> R.string.snd_pbx_type_external
        TargetType.UNKNOWN -> R.string.snd_pbx_type_unknown
    },
)

/** "Receptie · 100", "Voicemail van Jan de Vries", "+31 6 12345678", "Ophangen". */
@Composable
fun targetOptionName(option: PbxTargetOption): String = when (option.type) {
    TargetType.VOICEMAIL -> stringResource(if (option.ofDevice) R.string.snd_pbx_voicemail_of else R.string.snd_pbx_voicemail_shared, option.name)
    TargetType.DEVICE, TargetType.RING_GROUP, TargetType.QUEUE, TargetType.IVR, TargetType.BUSINESS_HOURS -> listOfNotNull(option.name, option.extensionNumber).joinToString(" · ")
    else -> option.name
}

@Composable
fun describeTarget(target: PbxTarget, options: List<PbxTargetOption>): String = when (target.type) {
    TargetType.HANGUP -> stringResource(R.string.snd_pbx_type_hangup)
    TargetType.EXTERNAL -> (target.number ?: "").trim().ifEmpty { stringResource(R.string.snd_pbx_target_unset) }
    else -> SelfTargetChoice.option(target, options)?.let { targetOptionName(it) } ?: stringResource(R.string.snd_pbx_target_gone)
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
    onChoose: (PbxTarget?) -> Unit,
    back: () -> Unit,
) {
    var externalNumber by remember { mutableStateOf(if (selection?.type == TargetType.EXTERNAL) selection.number ?: "" else "") }
    val hasExternal = options.any { it.type == TargetType.EXTERNAL }
    val clean = externalNumber.trim()

    nl.fullstackstudio.fsvoip.ui.SubpageScaffold(title, back) {
        if (allowsNone) {
            section(null, footer) {
                ChoiceRow(noneLabel, null, selected = selection == null, modifier = Modifier.testTag("target-none")) { onChoose(null) }
            }
        }

        for (type in SelfTargetChoice.pickerOrder) {
            val group = options.filter { it.type == type }

            if (group.isNotEmpty()) {
                section(targetGroupName(type), null) {
                    for (option in group) {
                        ChoiceRow(targetOptionName(option), null, selected = SelfTargetChoice.option(selection, options)?.value == option.value) { onChoose(SelfTargetChoice.target(option)) }
                    }
                }
            }
        }

        if (allowsHangup) {
            section(null, null) {
                ChoiceRow(stringResource(R.string.snd_pbx_type_hangup), null, selected = selection?.type == TargetType.HANGUP) { onChoose(PbxTarget.HANGUP) }
            }
        }

        if (hasExternal) {
            section(stringResource(R.string.snd_pbx_external_title), stringResource(R.string.snd_pbx_external_footer)) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        value = externalNumber,
                        onValueChange = { externalNumber = it },
                        placeholder = { Text(stringResource(R.string.snd_pbx_external_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth().testTag("pbx-external-number"),
                    )
                    androidx.compose.material3.TextButton(
                        onClick = { if (clean.isNotEmpty()) onChoose(PbxTarget.external(clean)) },
                        enabled = clean.isNotEmpty(),
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text(stringResource(R.string.snd_pbx_external_use), color = if (clean.isEmpty()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface) }
                }
            }
        }
    }

}
