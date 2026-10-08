// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The call screen inside the app (also on the lock screen through the full-screen notification). On Android the
// incoming call shows the caller AND the dialled account apart (plan D10). Every button goes through Telecom
// (PhoneController → CallSystem), so headsets, cars and this screen always agree.

package nl.fullstackstudio.fsvoip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.callcontroller.CallSession
import nl.fullstackstudio.fsvoip.sipengine.CallDirection
import nl.fullstackstudio.fsvoip.sipengine.CallEndReason

@Composable
fun InCallScreen(model: AppModel, session: CallSession) {
    val phone = model.phone
    val speaker by phone.isSpeakerOn.collectAsStateWithLifecycle()
    var showsKeypad by remember(session.id) { mutableStateOf(false) }
    var sentDigits by remember(session.id) { mutableStateOf("") }
    val ended = session.phase.isEnded

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Brand.inkRaised, Brand.ink)))
            .clickable(enabled = false) {}
            .testTag("in-call-screen"),
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Header(session, sentDigits.takeIf { showsKeypad && it.isNotEmpty() })
            Spacer(Modifier.weight(1f))

            when {
                session.phase == CallSession.Phase.Incoming -> IncomingActions(
                    decline = { phone.hangUp(session.id) },
                    answer = {
                        phone.answer(session.id)
                    },
                )
                showsKeypad -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Keypad(keySize = 70.dp, dark = true, onKey = { key ->
                        sentDigits += key
                        phone.sendDtmf(session.id, key.toString())
                    })
                    TextButton(onClick = { showsKeypad = false }) { Text(stringResource(R.string.call_keypad_hide), color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.SemiBold) }
                }
                else -> Controls(session, speaker, ended, onKeypad = { showsKeypad = true }, model = model)
            }

            Spacer(Modifier.weight(1f))

            if (session.phase != CallSession.Phase.Incoming) {
                RoundAction(Icons.Filled.CallEnd, stringResource(R.string.call_end), Brand.hangUp, Color.White, enabled = !ended, modifier = Modifier.padding(bottom = 24.dp).testTag("end-button")) {
                    phone.hangUp(session.id)
                }
            }
        }
    }
}

@Composable
private fun Header(session: CallSession, digits: String?) {
    val ended = session.phase.isEnded

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 28.dp)) {
        // Which line this call is on: the signature of the app, also in a call.
        Row(
            Modifier.background(Color.White.copy(alpha = 0.08f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(Modifier.size(7.dp).background(if (ended) Color.White.copy(alpha = 0.3f) else Brand.lime, CircleShape))
            Text(
                stringResource(if (session.direction == CallDirection.INCOMING) R.string.call_line_incoming else R.string.call_line_outgoing, session.accountLabel),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            session.remoteTitle ?: stringResource(R.string.call_anonymous),
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 24.dp).testTag("call-title"),
        )

        if (session.remoteName != null && session.remoteNumber != null) {
            Text(session.remoteNumber!!, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyLarge)
        }

        Text(statusText(session), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))

        if (digits != null) {
            Text(digits.takeLast(18), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Light, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun statusText(session: CallSession): String = when (val phase = session.phase) {
    CallSession.Phase.Starting -> stringResource(R.string.call_status_starting)
    CallSession.Phase.Ringing -> stringResource(R.string.call_status_ringing)
    CallSession.Phase.Incoming -> stringResource(R.string.call_status_incoming)
    CallSession.Phase.Connecting -> stringResource(if (session.answerPendingForUi()) R.string.call_answering else R.string.call_status_connecting)
    CallSession.Phase.Active -> session.connectedAt?.let { timer(it) } ?: stringResource(R.string.call_status_connecting)
    CallSession.Phase.Held -> stringResource(R.string.call_status_held)
    CallSession.Phase.HeldByRemote -> stringResource(R.string.call_status_heldByRemote)
    is CallSession.Phase.Ended -> stringResource(
        when (phase.reason) {
            CallEndReason.Busy -> R.string.call_ended_busy
            CallEndReason.Unanswered -> R.string.call_ended_unanswered
            is CallEndReason.Failed -> R.string.call_ended_failed
            else -> R.string.call_ended
        },
    )
}

private fun CallSession.answerPendingForUi(): Boolean = answerPending && awaitingInvite

@Composable
private fun timer(start: Instant): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(start) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    return RecentFormat.duration(Duration.between(start, Instant.ofEpochMilli(now)).seconds)
}

@Composable
private fun Controls(session: CallSession, speaker: Boolean, ended: Boolean, onKeypad: () -> Unit, model: AppModel) {
    val phone = model.phone
    val connected = session.phase.isConnected || session.phase == CallSession.Phase.Connecting

    Column(Modifier.alpha(if (ended) 0.4f else 1f), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            ControlButton(if (session.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic, stringResource(R.string.call_mute), session.isMuted, enabled = !ended, tag = "mute-button") {
                phone.setMuted(session.id, !session.isMuted)
            }
            ControlButton(Icons.Filled.Dialpad, stringResource(R.string.call_keypad), false, enabled = !ended && connected, tag = "keypad-button", onClick = onKeypad)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            ControlButton(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.call_speaker), speaker, enabled = !ended, tag = "speaker-button") {
                phone.setSpeaker(!speaker)
            }
            ControlButton(Icons.Filled.Pause, stringResource(R.string.call_hold), session.isOnHold, enabled = !ended && session.phase.isConnected, tag = "hold-button") {
                phone.setHeld(session.id, !session.isOnHold)
            }
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, title: String, on: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(if (enabled) 1f else 0.35f).testTag(tag)) {
        Box(
            Modifier.size(72.dp).background(if (on) Color.White else Color.White.copy(alpha = 0.12f), CircleShape).clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = title, tint = if (on) Brand.ink else Color.White, modifier = Modifier.size(28.dp))
        }
        Text(title, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun IncomingActions(decline: () -> Unit, answer: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        RoundAction(Icons.Filled.CallEnd, stringResource(R.string.call_decline), Brand.hangUp, Color.White, modifier = Modifier.testTag("decline-button"), onClick = decline)
        RoundAction(Icons.Filled.Phone, stringResource(R.string.call_answer), Brand.lime, Brand.ink, modifier = Modifier.testTag("answer-button"), onClick = answer)
    }
}

@Composable
private fun RoundAction(icon: ImageVector, title: String, fill: Color, glyph: Color, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.alpha(if (enabled) 1f else 0.4f)) {
        Box(Modifier.size(76.dp).background(fill, CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = title, tint = glyph, modifier = Modifier.size(32.dp))
        }
        Text(title, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}
