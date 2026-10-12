// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The words of the "Centrale" section. They are the portal's: toestel, belgroep, wachtrij, keuzemenu, openingstijden, voicemail.
// Never extension, ring group, IVR, trunk or gateway. (The iOS `PbxVocabulary`.)

package nl.fullstackstudio.fsvoip.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.Fallback
import nl.fullstackstudio.fsvoip.core.FlowKind
import nl.fullstackstudio.fsvoip.core.FlowNode
import nl.fullstackstudio.fsvoip.core.FlowWhen
import nl.fullstackstudio.fsvoip.core.FlowWhenKind
import nl.fullstackstudio.fsvoip.core.ChainOptions

fun flowIcon(kind: FlowKind): ImageVector = when (kind) {
    FlowKind.DEVICE -> Icons.Filled.Phone
    FlowKind.RING_GROUP -> Icons.Filled.Groups
    FlowKind.QUEUE -> Icons.Filled.SupportAgent
    FlowKind.IVR -> Icons.Filled.Dialpad
    FlowKind.BUSINESS_HOURS -> Icons.Filled.Schedule
    FlowKind.VOICEMAIL -> Icons.Filled.Voicemail
    FlowKind.RECORDING -> Icons.Filled.Campaign
    FlowKind.HANGUP -> Icons.Filled.CallEnd
    FlowKind.EXTERNAL -> Icons.Filled.NorthEast
    FlowKind.MISSING -> Icons.Filled.Warning
    FlowKind.LOOP -> Icons.Filled.Sync
    FlowKind.MORE -> Icons.Filled.MoreHoriz
    FlowKind.NONE, FlowKind.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline
}

/** The step in words: "Belgroep Iedereen". */
@Composable
fun flowTitle(node: FlowNode): String {
    val name = node.name.orEmpty()

    return when (node.kind) {
        FlowKind.DEVICE -> stringResource(R.string.pbx_flow_device, name)
        FlowKind.RING_GROUP -> stringResource(R.string.pbx_flow_ringGroup, name)
        FlowKind.QUEUE -> stringResource(R.string.pbx_flow_queue, name)
        FlowKind.IVR -> stringResource(R.string.pbx_flow_ivr, name)
        FlowKind.BUSINESS_HOURS -> stringResource(R.string.pbx_flow_hours, name)
        FlowKind.VOICEMAIL -> stringResource(if (node.ofDevice) R.string.pbx_voicemail_of else R.string.pbx_voicemail_shared, name)
        FlowKind.RECORDING -> stringResource(R.string.pbx_flow_recording, name)
        FlowKind.HANGUP -> stringResource(R.string.pbx_flow_hangup)
        FlowKind.EXTERNAL -> stringResource(R.string.pbx_flow_external, node.number.orEmpty())
        FlowKind.MISSING -> stringResource(R.string.pbx_flow_missing)
        FlowKind.LOOP -> stringResource(R.string.pbx_flow_loop)
        FlowKind.NONE -> stringResource(R.string.pbx_flow_none)
        FlowKind.MORE -> stringResource(R.string.pbx_flow_more)
        FlowKind.UNKNOWN -> stringResource(R.string.pbx_flow_unknown)
    }
}

@Composable
fun flowDetail(node: FlowNode): String? = when (node.kind) {
    FlowKind.DEVICE, FlowKind.RING_GROUP, FlowKind.QUEUE, FlowKind.IVR, FlowKind.BUSINESS_HOURS -> node.extensionNumber?.let { stringResource(R.string.pbx_flow_internalNumber, it) }
    else -> null
}

/** Why a step follows another: "Buiten openingstijden". */
@Composable
fun flowWhen(condition: FlowWhen): String = when (condition.kind) {
    FlowWhenKind.OPEN -> stringResource(R.string.pbx_when_open)
    FlowWhenKind.CLOSED -> stringResource(R.string.pbx_when_closed)
    FlowWhenKind.NO_ANSWER -> condition.seconds?.let { stringResource(R.string.pbx_when_noAnswer_seconds, it) } ?: stringResource(R.string.pbx_when_noAnswer)
    FlowWhenKind.BUSY -> stringResource(R.string.pbx_when_busy)
    FlowWhenKind.TIMEOUT -> stringResource(R.string.pbx_when_timeout)
    FlowWhenKind.KEY -> stringResource(R.string.pbx_when_key, condition.digit ?: "?")
    FlowWhenKind.NO_CHOICE -> stringResource(R.string.pbx_when_noChoice)
    FlowWhenKind.FORWARD_ALWAYS -> stringResource(R.string.pbx_when_forwardAlways)
    FlowWhenKind.UNKNOWN -> stringResource(R.string.pbx_when_unknown)
}

/** What happens when it is closed or nobody answers, in words. */
@Composable
fun fallbackText(fallback: Fallback?, options: ChainOptions, none: String): String = when (fallback) {
    null -> none
    is Fallback.Voicemail -> {
        val device = fallback.boxId?.let { id -> options.devices.firstOrNull { it.id == id } }

        if (fallback.ofDevice && device != null) stringResource(R.string.numbers_fallback_voicemailOf, device.name) else stringResource(R.string.numbers_fallback_voicemail_shared)
    }
    is Fallback.Message -> stringResource(R.string.numbers_fallback_messageNamed, soundName(fallback.soundId, options) ?: stringResource(R.string.numbers_sound_unknown))
    is Fallback.Forward -> stringResource(R.string.numbers_fallback_forwardTo, fallback.number)
    Fallback.Hangup -> stringResource(R.string.numbers_fallback_hangup)
    is Fallback.Device -> options.devices.firstOrNull { it.id == fallback.deviceId }?.name ?: stringResource(R.string.numbers_device_unknown)
    is Fallback.Other -> stringResource(R.string.numbers_portalOnly)
}

fun soundName(id: String?, options: ChainOptions): String? = id?.let { sound -> options.sounds.firstOrNull { it.id == sound }?.name }
