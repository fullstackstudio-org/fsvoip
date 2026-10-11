// SPDX-License-Identifier: AGPL-3.0-or-later
//
// "Geluiden": the sounds of the PBX (welcome messages, music, greetings) - listen, record with the microphone or add a file, rename and
// delete (administrator pairings). A sound that is still used cannot be deleted: the places are named. Android port of the iOS
// `SoundsView` / `SoundRecorderSheet`.

package nl.fullstackstudio.fsvoip.ui

import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.call.SoundRecorder
import nl.fullstackstudio.fsvoip.core.PbxFailure
import nl.fullstackstudio.fsvoip.core.PbxSound
import nl.fullstackstudio.fsvoip.core.SoundPlaces
import nl.fullstackstudio.fsvoip.core.SoundRecordingRules
import nl.fullstackstudio.fsvoip.core.SoundUse
import nl.fullstackstudio.fsvoip.core.UsePlace

@Composable
fun SoundsScreen(model: AppModel, accountId: String, back: () -> Unit) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val all by model.pbx.sounds.collectAsStateWithLifecycle()
    val nowPlaying by model.nowPlaying.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var failure by remember { mutableStateOf<PbxFailure?>(null) }
    var loading by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var uploading by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<PendingSound?>(null) }
    var renaming by remember { mutableStateOf<PbxSound?>(null) }
    var deleting by remember { mutableStateOf<PbxSound?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val tooLarge = stringResource(R.string.pbx_error_tooLarge)
    val unreadable = stringResource(R.string.pbx_error_invalidAudio)

    LaunchedEffect(accountId, attempt) {
        account ?: return@LaunchedEffect
        loading = true
        failure = model.pbx.loadSounds(account)
        loading = false
    }

    DisposableEffect(Unit) { onDispose { model.stopPlayer() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        val info = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) to cursor.getLong(1) else null
        }
        val fileName = info?.first ?: "geluid"

        if ((info?.second ?: 0L) > SoundRecordingRules.MAX_UPLOAD_BYTES) {
            error = tooLarge
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }

            if (bytes == null || bytes.isEmpty()) {
                error = unreadable
            } else if (bytes.size > SoundRecordingRules.MAX_UPLOAD_BYTES) {
                error = tooLarge
            } else {
                naming = PendingSound(fileName.substringBeforeLast('.'), fileName, SoundRecordingRules.contentTypeFor(fileName, context.contentResolver.getType(uri)), bytes)
            }
        }
    }

    AdminPage(
        model,
        stringResource(R.string.settings_admin_sounds),
        back,
        reasonRes = R.string.sounds_lock_reason,
        messageRes = R.string.sounds_lock_message,
        bottomBar = { nowPlaying?.takeIf { it.id.contains("/sound/") }?.let { playing -> AudioPlayerBar(model, playing.title, playing.subtitle) } },
    ) {
        ReadOnlyNote(model, accountId)
        failure?.let { NoteCard(pbxFailureText(it), isError = true) }
        error?.let { NoteCard(it, isError = true) }
        uploading?.let { name ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.sounds_upload_progress, name), style = MaterialTheme.typography.bodyMedium)
            }
        }

        val sounds = all[accountId]

        if (!model.isPbxReadOnly(accountId)) {
            section(null, null) {
                ActionRow(stringResource(R.string.sounds_newRecording), Icons.Filled.FiberManualRecord, Modifier.testTag("sounds-record"), color = Brand.hangUp) { error = null; recording = true }
                ActionRow(stringResource(R.string.sounds_addFile), Icons.Filled.FolderOpen, Modifier.testTag("sounds-file")) { error = null; picker.launch(arrayOf("audio/*")) }
            }
        }

        if (sounds == null) {
            if (loading) Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
            else if (failure != null) TextButton(onClick = { attempt++ }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.action_retry)) }
            return@AdminPage
        }

        if (sounds.isEmpty()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.numbers_sound_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.sounds_empty_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }

        section(null, stringResource(R.string.sounds_footer)) {
            for (sound in sounds) {
                val playingThis = nowPlaying?.id == "$accountId/sound/${sound.id}"

                SoundRow(
                    sound = sound,
                    playing = playingThis,
                    readOnly = model.isPbxReadOnly(accountId),
                    onPlay = { account?.let { model.playSound(it, sound) } },
                    onRename = { renaming = sound },
                    onDelete = { error = null; deleting = sound },
                )
            }
        }
    }

    val defaultRecordingName = stringResource(R.string.sounds_record_defaultName)

    if (recording) {
        SoundRecorderDialog(
            model,
            onDismiss = { recording = false },
            onRecorded = { file, seconds ->
                recording = false
                naming = PendingSound(defaultRecordingName, "opname.m4a", "audio/mp4", file.readBytes(), recordedFile = file, seconds = seconds)
            },
        )
    }

    naming?.let { pending ->
        var name by remember(pending) { mutableStateOf(pending.name) }

        AlertDialog(
            onDismissRequest = { naming = null; pending.recordedFile?.delete() },
            title = { Text(stringResource(R.string.sounds_record_name)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    placeholder = { Text(stringResource(R.string.sounds_record_name_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().testTag("sound-name-field"),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val target = account ?: return@TextButton
                        val clean = name.trim()
                        naming = null
                        uploading = clean
                        error = null

                        scope.launch {
                            val result = model.pbx.uploadSound(target, clean, pending.fileName, pending.contentType, pending.bytes)
                            pending.recordedFile?.delete()
                            uploading = null
                            failure = result
                        }
                    },
                ) { Text(stringResource(R.string.sounds_record_save)) }
            },
            dismissButton = { TextButton(onClick = { naming = null; pending.recordedFile?.delete() }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    renaming?.let { sound ->
        var name by remember(sound.id) { mutableStateOf(sound.name) }

        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.sounds_rename_title)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val target = account ?: return@TextButton
                        renaming = null
                        scope.launch { failure = model.pbx.renameSound(target, sound.id, name.trim()) }
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    deleting?.let { sound ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.sounds_delete_title, sound.name)) },
            text = { Text(stringResource(R.string.sounds_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    val target = account ?: return@TextButton
                    deleting = null
                    if (nowPlaying?.id == "$accountId/sound/${sound.id}") model.stopPlayer()

                    scope.launch {
                        val result = model.pbx.deleteSound(target, sound.id)
                        failure = if (result is PbxFailure.InUse) null else result
                        if (result is PbxFailure.InUse) inUsePlaces = result.places to sound.name
                    }
                }) { Text(stringResource(R.string.sounds_delete_confirm), color = Brand.hangUp) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    inUsePlaces?.let { (places, name) ->
        AlertDialog(
            onDismissRequest = { inUsePlaces = null },
            title = { Text(stringResource(R.string.sounds_inUse_title, name)) },
            text = { Text(inUseText(places)) },
            confirmButton = { TextButton(onClick = { inUsePlaces = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

private var inUsePlaces by mutableStateOf<Pair<List<UsePlace>, String>?>(null)

private class PendingSound(
    val name: String,
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray,
    val recordedFile: File? = null,
    val seconds: Double = 0.0,
)

/** "Dit geluid wordt nog gebruikt bij: Keuzemenu (Support). Kies daar eerst een ander geluid." */
@Composable
private fun inUseText(places: List<UsePlace>): String {
    if (places.isEmpty()) return stringResource(R.string.sounds_error_inUse_unknown)

    val kindLabel = placeKindLabeler()

    return stringResource(R.string.sounds_error_inUse, places.joinToString(", ") { SoundPlaces.label(it, kindLabel(it.kind)) })
}

/** Names for the kinds of place a sound is used ("Keuzemenu", "Wachtrij" ...); `null` for a kind the app does not know. */
@Composable
private fun placeKindLabeler(): (String) -> String? {
    val context = LocalContext.current

    return { kind ->
        val id = context.resources.getIdentifier("sounds_place_$kind", "string", context.packageName)
        if (id != 0) context.getString(id) else null
    }
}

@Composable
private fun SoundRow(sound: PbxSound, playing: Boolean, readOnly: Boolean, onPlay: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val kindLabel = placeKindLabeler()
    val uses = if (sound.uses.isEmpty()) stringResource(R.string.sounds_unused) else stringResource(R.string.sounds_usedIn, sound.uses.joinToString(", ") { SoundPlaces.label(UsePlace(it.kind, it.name), kindLabel(it.kind)) })
    val detail = listOfNotNull(if (sound.playable) null else stringResource(R.string.sounds_notPlayable), uses, syncNote(sound.sync)).joinToString(" · ")

    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp).testTag("sound-row"), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPlay, enabled = sound.playable) {
            Icon(if (playing) Icons.Filled.GraphicEq else Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.sounds_listen_named, sound.name))
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(sound.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }

        if (!readOnly) {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.sounds_actions_named, sound.name))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.sounds_rename)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.sounds_delete), color = Brand.hangUp) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

// MARK: Recording

/** A recording of up to five minutes: start, stop, listen back, then give it a name. Never during a call. */
@Composable
private fun SoundRecorderDialog(model: AppModel, onDismiss: () -> Unit, onRecorded: (File, Double) -> Unit) {
    val context = LocalContext.current
    val recorder = remember { SoundRecorder(context) }
    val scope = rememberCoroutineScope()
    val inCall by model.phone.sessions.collectAsStateWithLifecycle()

    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0.0) }
    var recordedSeconds by remember { mutableStateOf<Double?>(null) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var listening by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            recorder.cancel()
            player?.release()
        }
    }

    fun finish() {
        val seconds = recorder.stop()
        recording = false

        if (SoundRecordingRules.isLongEnough(seconds) && recorder.file != null) {
            recordedSeconds = seconds
        } else {
            recorder.cancel()
            errorRes = R.string.sounds_record_error_tooShort
        }
    }

    LaunchedEffect(recording) {
        while (recording) {
            elapsed = recorder.elapsedSeconds
            if (elapsed >= SoundRecordingRules.MAX_SECONDS) {
                finish()
                break
            }
            delay(200)
        }
    }

    fun start() {
        errorRes = null
        player?.release()
        player = null
        recordedSeconds = null

        if (inCall.isNotEmpty()) {
            errorRes = R.string.sounds_record_error_call
            return
        }

        scope.launch {
            if (model.permissions?.requestMicrophone() != true) {
                errorRes = R.string.sounds_record_error_permission
                return@launch
            }

            if (recorder.start()) {
                elapsed = 0.0
                recording = true
            } else {
                errorRes = R.string.sounds_record_error_start
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!recording) onDismiss() },
        title = { Text(stringResource(R.string.sounds_record_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.sounds_record_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.sounds_record_max, SoundRecordingRules.clock(SoundRecordingRules.MAX_SECONDS.toDouble())), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                errorRes?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = Brand.hangUp) }

                when {
                    recording -> {
                        Text(stringResource(R.string.sounds_record_elapsed, SoundRecordingRules.clock(elapsed)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("sound-elapsed"))
                        PrimaryButton(stringResource(R.string.sounds_record_stop), onClick = { finish() }, icon = Icons.Filled.Stop, modifier = Modifier.testTag("sound-stop"))
                    }
                    recordedSeconds != null -> {
                        Text(stringResource(R.string.sounds_record_preview) + " · " + SoundRecordingRules.clock(recordedSeconds ?: 0.0), style = MaterialTheme.typography.titleMedium)
                        SecondaryButton(
                            stringResource(R.string.sounds_record_listen),
                            icon = if (listening) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            onClick = {
                                val active = player

                                if (active != null && listening) {
                                    active.pause()
                                    listening = false
                                } else if (active != null) {
                                    active.start()
                                    listening = true
                                } else {
                                    recorder.file?.let { file ->
                                        player = MediaPlayer().apply {
                                            setDataSource(file.absolutePath)
                                            setOnCompletionListener { listening = false }
                                            prepare()
                                            start()
                                        }
                                        listening = true
                                    }
                                }
                            },
                        )
                        TextButton(onClick = { start() }) { Text(stringResource(R.string.sounds_record_again)) }
                    }
                    else -> PrimaryButton(stringResource(R.string.sounds_record_start), onClick = { start() }, icon = Icons.Filled.FiberManualRecord, modifier = Modifier.testTag("sound-start"))
                }
            }
        },
        confirmButton = {
            if (recordedSeconds != null && !recording) {
                TextButton(onClick = {
                    val file = recorder.file ?: return@TextButton
                    player?.release()
                    player = null
                    // The file now belongs to the next step (naming and uploading); the recorder no longer deletes it.
                    onRecorded(file, recordedSeconds ?: 0.0)
                    recorder.detach()
                }) { Text(stringResource(R.string.sounds_record_save)) }
            }
        },
        dismissButton = { TextButton(onClick = { if (recording) recorder.cancel().also { recording = false } ; onDismiss() }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
