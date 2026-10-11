// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Small screen pieces shared by Recent, Voicemail and Opnames: sentences for failures, empty states, notices, the lock card, the
// round action buttons and the player bar.

package nl.fullstackstudio.fsvoip.ui.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.AudioStreamer
import nl.fullstackstudio.fsvoip.core.MediaFailure
import nl.fullstackstudio.fsvoip.core.PlayerState
import nl.fullstackstudio.fsvoip.ui.Brand

/** The sentence for the user. No server text, no telecom words. */
@Composable
fun mediaFailureText(failure: MediaFailure, kind: MediaKind): String = when (failure) {
    MediaFailure.AccessDenied -> stringResource(R.string.calls_media_error_accessDenied)
    MediaFailure.Revoked -> stringResource(R.string.calls_media_error_revoked)
    MediaFailure.Gone -> stringResource(if (kind == MediaKind.RECORDING) R.string.calls_media_error_gone_recording else R.string.calls_media_error_gone_voicemail)
    MediaFailure.NotFound -> stringResource(R.string.calls_media_error_notFound)
    is MediaFailure.RateLimited ->
        if (failure.retryAfterSeconds != null && failure.retryAfterSeconds!! > 0) {
            stringResource(R.string.calls_media_error_rateLimited_seconds, failure.retryAfterSeconds!!)
        } else {
            stringResource(R.string.calls_media_error_rateLimited)
        }
    MediaFailure.Offline -> stringResource(R.string.calls_media_error_offline)
    MediaFailure.Unavailable -> stringResource(R.string.calls_media_error_unavailable)
    MediaFailure.ReadOnly -> stringResource(R.string.calls_media_error_readOnly)
    MediaFailure.CallInProgress -> stringResource(R.string.calls_media_error_callInProgress)
    MediaFailure.Unplayable -> stringResource(R.string.calls_media_error_unplayable)
    MediaFailure.Other -> stringResource(R.string.error_generic)
}

@Composable
fun mediaMessageText(message: MediaMessage): String = when (message) {
    is MediaMessage.Failure -> mediaFailureText(message.failure, message.kind)
    MediaMessage.DeleteReadOnly -> stringResource(R.string.calls_media_delete_readOnly)
    MediaMessage.NoScreenLock -> stringResource(R.string.calls_media_lock_noPasscode)
}

@Composable
fun daysLeftText(days: Int?): String? = when {
    days == null -> null
    days < 1 -> stringResource(R.string.calls_media_daysLeft_today)
    days == 1 -> stringResource(R.string.calls_media_daysLeft_one)
    else -> stringResource(R.string.calls_media_daysLeft_many, days)
}

/** A line with a small icon: a banner or a notice above a list. */
@Composable
fun MediaNotice(icon: ImageVector, tint: Color, text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun MediaBannerView(banner: MediaBanner, modifier: Modifier = Modifier) {
    MediaNotice(
        icon = if (banner.isError) Icons.Filled.Error else Icons.Filled.Info,
        tint = if (banner.isError) Brand.hangUp else MaterialTheme.colorScheme.onSurfaceVariant,
        text = mediaMessageText(banner.message),
        modifier = modifier.testTag("media-banner"),
    )
}

/** An empty list or a failure: an icon, a title, a sentence and optionally one button. */
@Composable
fun MediaEmpty(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, actionTitle: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(34.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)

        if (actionTitle != null && onAction != null) {
            OutlinedButton(onClick = onAction, modifier = Modifier.padding(top = 6.dp).heightIn(min = 48.dp)) { Text(actionTitle) }
        }
    }
}

/** The lock picture of the protected parts (a colleague's voicemail, the recordings). */
@Composable
fun MediaLockCard(title: String, message: String, buttonTitle: String?, modifier: Modifier = Modifier, onAction: () -> Unit = {}) {
    Column(modifier.fillMaxWidth().widthIn(max = 420.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)

        if (buttonTitle != null) {
            OutlinedButton(onClick = onAction, modifier = Modifier.padding(top = 6.dp).heightIn(min = 48.dp).testTag("media-unlock")) { Text(buttonTitle) }
        }
    }
}

/** The round buttons under a number: call (lime) and copy (raised). */
@Composable
fun RoundAction(icon: ImageVector, label: String, primary: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (primary) Brand.lime else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (primary) Brand.ink else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.size(56.dp).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
}

/** A title on the left and a value on the right, as a settings row. */
@Composable
fun DetailRow(title: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(1.4f))
    }
}

/** A choice with a check mark at the end (the filter sheets). */
@Composable
fun ChoiceRow(title: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { stateDescription = if (selected) "✓" else "" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.outline)

        if (selected) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
    )
}

@Composable
fun BackIcon(contentDescription: String) = Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = contentDescription)

// MARK: The player bar

/**
 * The player at the bottom of Voicemail, Opnames and the call detail: what plays, a scrubber, play/pause, ten seconds back and
 * forward and the speed (1×, 1,5×, 2×). One component for all screens.
 */
@Composable
fun AudioPlayerBar(player: AudioStreamer, nowPlaying: NowPlaying, kind: MediaKind, onClose: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val state by player.state.collectAsStateWithLifecycle()
    val position by player.position.collectAsStateWithLifecycle()
    val duration by player.duration.collectAsStateWithLifecycle()
    val speed by player.speed.collectAsStateWithLifecycle()

    Surface(
        modifier = modifier.fillMaxWidth().testTag("audio-player"),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text(nowPlaying.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(nowPlaying.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                IconButton(onClick = onClose, modifier = Modifier.size(48.dp).testTag("player-close")) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close), modifier = Modifier.size(20.dp))
                }
            }

            val current = state

            if (current is PlayerState.Failed) {
                PlayerFailure(current.failure, kind, onRetry)
            } else {
                Scrubber(position = position, duration = duration, isLoading = current == PlayerState.Loading) { player.seek(it) }

                Row(Modifier.fillMaxWidth()) {
                    Text(MediaFormat.clock(position), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    duration?.let { Text("-" + MediaFormat.clock(maxOf(0.0, it - position)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }

                PlayerControls(player, state = current, speed = speed, hasDuration = duration != null)
            }
        }
    }
}

@Composable
private fun PlayerControls(player: AudioStreamer, state: PlayerState, speed: nl.fullstackstudio.fsvoip.core.PlaybackSpeed, hasDuration: Boolean) {
    val speedLabel = stringResource(R.string.calls_media_player_speed)
    val speedText = MediaFormat.speed(speed)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        OutlinedButton(
            onClick = { player.cycleSpeed() },
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.widthIn(min = 64.dp).heightIn(min = 48.dp).testTag("player-speed").semantics { contentDescription = "$speedLabel $speedText" },
        ) { Text(speedText, fontWeight = FontWeight.SemiBold) }

        IconButton(onClick = { player.skip(-10.0) }, enabled = hasDuration, modifier = Modifier.size(52.dp).testTag("player-back")) {
            Icon(Icons.Filled.Replay10, contentDescription = stringResource(R.string.calls_media_player_back), modifier = Modifier.size(30.dp))
        }

        val playing = state == PlayerState.Playing

        Button(
            onClick = { player.togglePlayPause() },
            enabled = state != PlayerState.Loading,
            shape = CircleShape,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            modifier = Modifier.size(58.dp).testTag("player-play"),
        ) {
            if (state == PlayerState.Loading) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.calls_media_player_pause else R.string.calls_media_player_play),
                    modifier = Modifier.size(30.dp),
                )
            }
        }

        IconButton(onClick = { player.skip(10.0) }, enabled = hasDuration, modifier = Modifier.size(52.dp).testTag("player-forward")) {
            Icon(Icons.Filled.Forward10, contentDescription = stringResource(R.string.calls_media_player_forward), modifier = Modifier.size(30.dp))
        }

        // Balances the speed button so the play button stays in the middle.
        Spacer(Modifier.widthIn(min = 64.dp))
    }
}

@Composable
private fun Scrubber(position: Double, duration: Double?, isLoading: Boolean, onSeek: (Double) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val fraction = drag ?: if (duration != null && duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
    val label = stringResource(R.string.calls_media_player_position)
    val value = if (duration != null) stringResource(R.string.calls_media_player_position_value, MediaFormat.clock(position), MediaFormat.clock(duration)) else MediaFormat.clock(position)

    Slider(
        value = fraction,
        onValueChange = { drag = it },
        onValueChangeFinished = {
            val target = drag

            if (target != null && duration != null) onSeek(target * duration)

            drag = null
        },
        enabled = duration != null && !isLoading,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
            disabledThumbColor = MaterialTheme.colorScheme.outline,
            disabledActiveTrackColor = MaterialTheme.colorScheme.outline,
            disabledInactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("player-scrubber").semantics { contentDescription = label; stateDescription = value },
    )
}

@Composable
private fun PlayerFailure(failure: MediaFailure, kind: MediaKind, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("player-failure"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (failure == MediaFailure.Gone) Icons.Filled.Schedule else Icons.Filled.Error,
            contentDescription = null,
            tint = if (failure == MediaFailure.Gone) MaterialTheme.colorScheme.onSurfaceVariant else Brand.hangUp,
            modifier = Modifier.size(22.dp),
        )
        Text(mediaFailureText(failure, kind), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))

        if (failure.isRetryable) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("player-retry")) { Text(stringResource(R.string.action_retry)) }
        }
    }
}
