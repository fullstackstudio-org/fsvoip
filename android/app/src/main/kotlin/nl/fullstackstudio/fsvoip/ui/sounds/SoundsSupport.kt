// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.sounds

import android.content.Context
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.ByteArrayOutputStream
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.ApiErrorPlace
import nl.fullstackstudio.fsvoip.core.RecorderFailure
import nl.fullstackstudio.fsvoip.core.SoundBanner
import nl.fullstackstudio.fsvoip.core.SoundFailure
import nl.fullstackstudio.fsvoip.core.SoundPlayer
import nl.fullstackstudio.fsvoip.core.SoundUpload
import nl.fullstackstudio.fsvoip.core.SoundUploadState
import nl.fullstackstudio.fsvoip.core.SoundUse
import nl.fullstackstudio.fsvoip.core.SoundsModel
import nl.fullstackstudio.fsvoip.ui.Brand
import nl.fullstackstudio.fsvoip.ui.settings.NoticeCard

/** `m:ss` (or `h:mm:ss`), never negative. */
fun clock(seconds: Double): String {
    val total = maxOf(0, seconds.toInt())
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val rest = total % 60

    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

/** The sentence for the user. No server text, no telecom words. */
@Composable
fun soundFailureText(failure: SoundFailure): String = when (failure) {
    SoundFailure.InvalidAudio -> stringResource(R.string.snd_sounds_error_invalidAudio)
    SoundFailure.TooLarge -> stringResource(R.string.snd_sounds_error_tooLarge)
    SoundFailure.TooMany -> stringResource(R.string.snd_sounds_error_tooMany)
    is SoundFailure.InUse -> inUseMessage(failure.places)
    is SoundFailure.RateLimited -> {
        val seconds = failure.retryAfterSeconds

        if (seconds != null && seconds > 0) stringResource(R.string.snd_sounds_error_rateLimited_seconds, seconds) else stringResource(R.string.snd_sounds_error_rateLimited)
    }
    SoundFailure.AccessDenied -> stringResource(R.string.snd_media_error_accessDenied)
    SoundFailure.Revoked -> stringResource(R.string.snd_media_error_revoked)
    SoundFailure.ReadOnly -> stringResource(R.string.snd_media_error_readOnly)
    SoundFailure.NotFound -> stringResource(R.string.snd_sounds_error_notFound)
    SoundFailure.Offline -> stringResource(R.string.snd_media_error_offline)
    SoundFailure.Unavailable -> stringResource(R.string.snd_media_error_unavailable)
    SoundFailure.CallInProgress -> stringResource(R.string.snd_media_error_callInProgress)
    SoundFailure.Other -> stringResource(R.string.error_generic)
}

@Composable
fun recorderFailureText(failure: RecorderFailure): String = stringResource(
    when (failure) {
        RecorderFailure.PERMISSION_DENIED -> R.string.snd_sounds_record_error_permission
        RecorderFailure.CALL_IN_PROGRESS -> R.string.snd_sounds_record_error_call
        RecorderFailure.TOO_SHORT -> R.string.snd_sounds_record_error_tooShort
        RecorderFailure.COULD_NOT_START -> R.string.snd_sounds_record_error_start
    },
)

/** "Wachtmuziek (Voorbeeld BV)": what the place is, then its name. */
@Composable
fun placeLabel(kind: String, name: String): String {
    val res = when (kind) {
        "voicemail_greeting" -> R.string.snd_sounds_place_voicemail_greeting
        "extension_voicemail_greeting" -> R.string.snd_sounds_place_extension_voicemail_greeting
        "queue_music" -> R.string.snd_sounds_place_queue_music
        "queue_welcome" -> R.string.snd_sounds_place_queue_welcome
        "menu_greeting" -> R.string.snd_sounds_place_menu_greeting
        "closed_message" -> R.string.snd_sounds_place_closed_message
        else -> null
    } ?: return name

    val label = stringResource(res)

    return if (name.isEmpty()) label else "$label ($name)"
}

@Composable
fun placeLabel(place: ApiErrorPlace): String = placeLabel(place.kind, place.name)

@Composable
fun placeLabel(use: SoundUse): String = placeLabel(use.kind, use.name)

@Composable
fun inUseMessage(places: List<ApiErrorPlace>): String =
    if (places.isEmpty()) stringResource(R.string.snd_sounds_error_inUse_unknown) else stringResource(R.string.snd_sounds_error_inUse, places.map { placeLabel(it) }.joinToString(", "))

/** The size and name of a document the user picked, and a bounded way to read it. */
class PickedFile(val fileName: String, val size: Long?, val read: suspend () -> ByteArray)

fun pickedFile(context: Context, uri: android.net.Uri): PickedFile {
    var name = uri.lastPathSegment ?: "geluid"
    var size: Long? = null

    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { index -> cursor.getString(index)?.let { name = it } }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
            }
        }
    }

    return PickedFile(name, size) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("no stream")
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)

            input.use {
                while (true) {
                    val read = it.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    // The size said at most 20 MB; never read more than that plus one byte.
                    if (out.size() > SoundUpload.MAX_BYTES) throw java.io.IOException("too large")
                }
            }

            out.toByteArray()
        }
    }
}

// MARK: - Pieces

@Composable
fun SoundDurationChip(seconds: Double?) {
    if (seconds != null) {
        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(clock(seconds), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

/** Play/pause and a position bar for the sound that is open: under the row of the picker and of "Geluiden". */
@Composable
fun SoundInlinePlayer(model: SoundsModel, id: String, onToggle: () -> Unit) {
    val state by model.player.state.collectAsStateWithLifecycle()
    val isCurrent = state.currentId == id
    val isPlaying = isCurrent && state.status == SoundPlayer.Status.PLAYING
    val isLoading = isCurrent && state.status == SoundPlayer.Status.LOADING
    val duration = state.durationMs
    var drag by remember { mutableStateOf<Float?>(null) }
    val playLabel = stringResource(if (isPlaying) R.string.snd_media_player_pause else R.string.snd_media_player_play)

    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconButton(
            onClick = onToggle,
            modifier = Modifier.size(48.dp).background(Brand.lime, CircleShape).semantics { contentDescription = playLabel }.testTag("sound-play"),
        ) {
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Brand.ink)
            } else {
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = Brand.ink)
            }
        }

        if (isCurrent && duration != null && duration > 0) {
            val position = (drag ?: state.positionMs.toFloat()).coerceIn(0f, duration.toFloat())
            val value = stringResource(R.string.snd_media_player_position_value, clock(position / 1000.0), clock(duration / 1000.0))
            val positionLabel = stringResource(R.string.snd_media_player_position)

            Slider(
                value = position,
                onValueChange = { drag = it },
                onValueChangeFinished = {
                    drag?.let { model.player.seekTo(it.toInt()) }
                    drag = null
                },
                valueRange = 0f..duration.toFloat(),
                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.onSurface, activeTrackColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = positionLabel
                    stateDescription = value
                },
            )
            Text("-" + clock(maxOf(0.0, (duration - position) / 1000.0)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (isCurrent && state.status == SoundPlayer.Status.FAILED) {
            Text(
                state.failure?.let { soundFailureText(it) } ?: stringResource(R.string.snd_media_error_unplayable),
                style = MaterialTheme.typography.bodySmall,
                color = Brand.hangUp,
                modifier = Modifier.weight(1f),
            )
        } else {
            Column(Modifier.weight(1f)) {}
        }
    }
}

/** An inline notice (an error or a hint) at the top of a list. */
@Composable
fun SoundNotice(banner: SoundBanner, onDismiss: (() -> Unit)? = null) {
    NoticeCard(soundFailureText(banner.failure), modifier = Modifier.testTag("sound-banner"))
    @Suppress("UNUSED_EXPRESSION") onDismiss
}

/** The progress of an upload with a way out. */
@Composable
fun SoundUploadBar(upload: SoundUploadState, onCancel: () -> Unit) {
    val label = stringResource(R.string.snd_sounds_upload_label)
    val percent = (upload.fraction * 100).toInt()

    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("sound-upload"), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.snd_sounds_upload_progress, upload.name), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
                TextButton(onClick = onCancel, modifier = Modifier.testTag("sound-upload-cancel")) { Text(stringResource(R.string.action_cancel)) }
            }

            LinearProgressIndicator(
                progress = { upload.fraction.toFloat().coerceIn(0f, 1f) },
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = label
                    stateDescription = "$percent%"
                },
            )
        }
    }
}

