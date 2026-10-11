// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Building blocks of the "Beheer" screens (administrator pairings): the local check in front of them, the words for what can go
// wrong, the "wordt bijgewerkt" notes and the steppers. The screens themselves are in `PbxDevices.kt`, `PbxHours.kt` and friends.

package nl.fullstackstudio.fsvoip.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.LocalAccessOutcome
import nl.fullstackstudio.fsvoip.core.LocalAuthAvailability
import nl.fullstackstudio.fsvoip.core.PbxFailure
import nl.fullstackstudio.fsvoip.core.PbxTarget
import nl.fullstackstudio.fsvoip.core.PbxTargetOption
import nl.fullstackstudio.fsvoip.core.SyncState
import nl.fullstackstudio.fsvoip.core.TargetType

private sealed interface GateState {
    data object Checking : GateState

    data object Open : GateState

    data object Locked : GateState

    data object NoScreenLock : GateState
}

/**
 * A screen of the "Beheer" section: asks for the local check (fingerprint, face or screen lock) before it shows anything, keeps it open
 * while the user works, and shows the lock when it cannot be opened. The server enforces the role regardless; this is a comfort for a
 * phone that is picked up while unlocked.
 */
@Composable
fun AdminPage(
    model: AppModel,
    title: String,
    back: () -> Unit,
    reasonRes: Int = R.string.pbx_lock_reason,
    messageRes: Int = R.string.pbx_lock_message,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    var gate by remember { mutableStateOf<GateState>(GateState.Checking) }
    val scope = rememberCoroutineScope()
    val reason = stringResource(reasonRes)

    fun attempt() {
        scope.launch {
            gate = GateState.Checking
            gate = when (val outcome = model.localAccess.ensureUnlocked(reason)) {
                LocalAccessOutcome.Unlocked -> GateState.Open
                is LocalAccessOutcome.Unavailable -> if (outcome.availability == LocalAuthAvailability.NO_SCREEN_LOCK) GateState.NoScreenLock else GateState.Locked
                else -> GateState.Locked
            }
        }
    }

    LaunchedEffect(Unit) { attempt() }

    SubpageScaffold(title, back, bottomBar = bottomBar) {
        when (gate) {
            GateState.Open -> {
                SideEffect { model.localAccess.touch() }
                content()
            }
            GateState.Checking -> Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.pbx_lock_checking), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GateState.Locked -> LockedNote(R.string.pbx_lock_title, messageRes, onUnlock = { attempt() })
            GateState.NoScreenLock -> LockedNote(R.string.pbx_lock_noScreenLock_title, R.string.pbx_lock_noScreenLock, onUnlock = null)
        }
    }
}

@Composable
private fun LockedNote(titleRes: Int, messageRes: Int, onUnlock: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(34.dp))
        Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(messageRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)

        if (onUnlock != null) {
            PrimaryButton(stringResource(R.string.pbx_lock_unlock), onUnlock, Modifier.padding(top = 8.dp).testTag("pbx-unlock"))
        }
    }
}

/** A tinted card with one sentence (an error, a note). */
@Composable
fun NoteCard(text: String, isError: Boolean = false, title: String? = null, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(if (isError) Icons.Filled.Error else Icons.Filled.Info, contentDescription = null, tint = if (isError) Brand.hangUp else MaterialTheme.colorScheme.onSurfaceVariant)
            Column {
                if (title != null) Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** "Alleen lezen" when the PBX is frozen or being set up. */
@Composable
fun ReadOnlyNote(model: AppModel, accountId: String) {
    if (model.isPbxReadOnly(accountId)) {
        NoteCard(stringResource(R.string.pbx_readOnly_message), title = stringResource(R.string.pbx_readOnly_title))
    }
}

/** Greys out and blocks the taps on [content] while [locked] (a frozen PBX): everything can be read, nothing can be changed. */
@Composable
fun ReadOnlyLock(locked: Boolean, content: @Composable () -> Unit) {
    Box {
        Box(Modifier.alpha(if (locked) 0.6f else 1f)) { content() }

        if (locked) {
            Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })
        }
    }
}

/** "Wordt bijgewerkt" / "Bijwerken niet gelukt" next to an object the PBX has not caught up with. */
@Composable
fun syncNote(sync: SyncState): String? = when (sync) {
    SyncState.PENDING -> stringResource(R.string.pbx_sync_pending)
    SyncState.ERROR -> stringResource(R.string.pbx_sync_error)
    else -> null
}

/** The sentence for a failed `/pbx` request. Never server text. */
@Composable
fun pbxFailureText(failure: PbxFailure): String = when (failure) {
    PbxFailure.AccessDenied -> stringResource(R.string.pbx_error_accessLost)
    PbxFailure.Revoked -> stringResource(R.string.pbx_error_revoked)
    PbxFailure.ReadOnly -> stringResource(R.string.pbx_error_readOnly)
    is PbxFailure.Stale -> stringResource(R.string.pbx_error_stale)
    PbxFailure.Advanced -> stringResource(R.string.pbx_error_advanced)
    is PbxFailure.InUse -> stringResource(R.string.pbx_error_inUse, failure.places.map { it.name }.filter { it.isNotBlank() }.distinct().joinToString(", ").ifEmpty { stringResource(R.string.pbx_error_inUse_somewhere) })
    PbxFailure.TooMany -> stringResource(R.string.pbx_error_tooMany)
    is PbxFailure.Conflict -> stringResource(R.string.pbx_error_conflict)
    is PbxFailure.Blocked -> stringResource(R.string.pbx_error_blocked, failure.numbers.joinToString(", "))
    is PbxFailure.CostNotAccepted -> stringResource(R.string.pbx_error_costRequired)
    is PbxFailure.Invalid -> stringResource(if (failure.code == "greeting_required") R.string.pbx_error_greetingRequired else R.string.pbx_error_invalid)
    PbxFailure.InvalidAudio -> stringResource(R.string.pbx_error_invalidAudio)
    PbxFailure.TooLarge -> stringResource(R.string.pbx_error_tooLarge)
    PbxFailure.NotFound -> stringResource(R.string.pbx_error_stale)
    is PbxFailure.RateLimited -> failure.retryAfterSeconds?.takeIf { it > 0 }?.let { stringResource(R.string.pbx_error_rateLimited_seconds, it) } ?: stringResource(R.string.pbx_error_rateLimited)
    PbxFailure.Offline -> stringResource(R.string.pbx_error_offline)
    PbxFailure.Unavailable -> stringResource(R.string.pbx_error_unavailable)
    PbxFailure.Other -> stringResource(R.string.pbx_error_other)
}

/** A "- 25 +" row. [step] is how much one tap changes the value; the value stays inside [range]. */
@Composable
fun NumberStepperRow(text: String, value: Int, range: IntRange, step: Int, tag: String, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).testTag(tag))
        IconButton(onClick = { onChange((value - step).coerceAtLeast(range.first)) }, enabled = value > range.first) { Icon(Icons.Filled.Remove, contentDescription = null) }
        IconButton(onClick = { onChange((value + step).coerceAtMost(range.last)) }, enabled = value < range.last) { Icon(Icons.Filled.Add, contentDescription = null) }
    }
}

/** The words for a destination: the name of the object, the external number, or "Niet ingesteld". */
@Composable
fun targetText(target: PbxTarget?, options: List<PbxTargetOption>, none: String): String =
    nl.fullstackstudio.fsvoip.core.PbxVocabulary.targetLabel(target, options, none, stringResource(R.string.pbx_target_gone))

/** The choices for a destination without the extension itself and without "hang up" (never a good answer to "where do calls go"). */
fun targetChoices(options: List<PbxTargetOption>, exceptDeviceId: String? = null): List<PbxTargetOption> =
    options.filterNot { exceptDeviceId != null && it.type == TargetType.DEVICE && it.id == exceptDeviceId }
