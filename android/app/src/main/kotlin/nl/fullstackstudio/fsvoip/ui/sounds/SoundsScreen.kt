// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.sounds

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.Sound
import nl.fullstackstudio.fsvoip.core.SoundsModel
import nl.fullstackstudio.fsvoip.ui.Brand
import nl.fullstackstudio.fsvoip.ui.PrimaryButton
import nl.fullstackstudio.fsvoip.ui.SecondaryButton
import nl.fullstackstudio.fsvoip.ui.SubpageScaffold
import nl.fullstackstudio.fsvoip.ui.section
import nl.fullstackstudio.fsvoip.ui.settings.settingsHubs

/**
 * "Geluiden" (Beheer, admins): every sound of the centrale with listening, renaming, deleting, a new recording and adding a file.
 * Open it through the settings (behind the local access gate); [back] leaves the page.
 */
@Composable
fun SoundsScreen(model: AppModel, accountId: String, back: () -> Unit) {
    val context = LocalContext.current
    val hubs = remember { model.settingsHubs(context) }
    val sounds = hubs.sounds(accountId)

    if (sounds == null) {
        LaunchedEffect(Unit) { back() }
        return
    }

    SubpageScaffold(stringResource(R.string.snd_settings_admin_sounds), back) {
        SoundsContent(sounds)
    }
}

@Composable
private fun SoundsContent(model: SoundsModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by model.sounds.collectAsStateWithLifecycle()
    val hasLoaded by model.hasLoaded.collectAsStateWithLifecycle()
    val isLoading by model.isLoading.collectAsStateWithLifecycle()
    val failure by model.failure.collectAsStateWithLifecycle()
    val banner by model.banner.collectAsStateWithLifecycle()
    val upload by model.upload.collectAsStateWithLifecycle()
    val deleteBlock by model.deleteBlock.collectAsStateWithLifecycle()
    val durations by model.durations.collectAsStateWithLifecycle()
    var openId by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Sound?>(null) }
    var newName by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Sound?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val file = pickedFile(context, uri)
            scope.launch { model.addFile(file.fileName, file.size, read = file.read) }
        }
    }

    LaunchedEffect(model) { model.load() }
    DisposableEffect(model) { onDispose { model.stopPlaying() } }

    banner?.let { SoundNotice(it) }
    upload?.let { SoundUploadBar(it) { model.cancelUpload() } }

    if (!hasLoaded) {
        if (isLoading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (failure != null) {
            val shown = failure!!
            nl.fullstackstudio.fsvoip.ui.settings.NoticeCard(soundFailureText(shown))
            if (shown.isRetryable) SecondaryButton(stringResource(R.string.action_retry), onClick = { scope.launch { model.load() } }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    } else if (list.isEmpty()) {
        Column(Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(32.dp).testTag("sounds-empty"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
            Text(stringResource(R.string.snd_numbers_sound_empty_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
            Text(stringResource(R.string.snd_sounds_empty_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    } else {
        section(null, stringResource(R.string.snd_sounds_footer)) {
            for (sound in list) {
                SoundRow(
                    model = model,
                    sound = sound,
                    duration = durations[sound.id] ?: sound.durationSeconds,
                    open = openId == sound.id,
                    onToggleOpen = {
                        if (openId == sound.id) {
                            openId = null
                            model.stopPlaying()
                        } else {
                            openId = sound.id
                            model.play(sound)
                        }
                    },
                    onRename = { newName = sound.name; renaming = sound },
                    onDelete = { deleting = sound },
                )
            }
        }
    }

    Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(
            stringResource(R.string.snd_sounds_newRecording),
            onClick = { model.stopPlaying(); recording = true },
            enabled = upload == null,
            icon = Icons.Filled.Mic,
            modifier = Modifier.testTag("sounds-new-recording"),
        )
        SecondaryButton(
            stringResource(R.string.snd_sounds_addFile),
            onClick = { picker.launch(arrayOf("audio/*")) },
            enabled = upload == null,
            icon = Icons.Filled.AttachFile,
            modifier = Modifier.testTag("sounds-add-file"),
        )
    }

    if (recording) {
        SoundRecorderDialog(model, onAdded = {}, onClose = { recording = false })
    }

    renaming?.let { sound ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.snd_sounds_rename_title)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.snd_sounds_record_name_placeholder)) },
                    modifier = Modifier.fillMaxWidth().testTag("sound-rename-field"),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renaming = null
                    scope.launch { model.rename(sound, newName) }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    deleting?.let { sound ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.snd_sounds_delete_title, sound.name)) },
            text = { Text(stringResource(R.string.snd_sounds_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { model.delete(sound) }
                }, modifier = Modifier.testTag("sound-delete-confirm")) { Text(stringResource(R.string.snd_sounds_delete_confirm), color = Brand.hangUp) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    deleteBlock?.let { block ->
        AlertDialog(
            onDismissRequest = { model.dismissDeleteBlock() },
            title = { Text(stringResource(R.string.snd_sounds_inUse_title, block.soundName)) },
            text = { Text(inUseMessage(block.places), modifier = Modifier.testTag("sound-inuse-message")) },
            confirmButton = { TextButton(onClick = { model.dismissDeleteBlock() }) { Text(stringResource(R.string.snd_action_done)) } },
        )
    }
}

@Composable
private fun SoundRow(model: SoundsModel, sound: Sound, duration: Double?, open: Boolean, onToggleOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val usage = when {
        !sound.playable -> stringResource(R.string.snd_sounds_notPlayable)
        sound.uses.isEmpty() -> stringResource(R.string.snd_sounds_unused)
        else -> stringResource(R.string.snd_sounds_usedIn, sound.uses.map { placeLabel(it) }.joinToString(", "))
    }

    Column(Modifier.testTag("sound-row")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))

            Column(Modifier.weight(1f).padding(vertical = 8.dp).semantics(mergeDescendants = true) {}) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(sound.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                    SoundDurationChip(duration)
                }
                Text(usage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (sound.playable) {
                val listenLabel = stringResource(R.string.snd_sounds_listen_named, sound.name)
                val stateText = stringResource(if (open) R.string.snd_sounds_listen_open else R.string.snd_sounds_listen_closed)

                TextButton(
                    onClick = onToggleOpen,
                    modifier = Modifier.semantics {
                        contentDescription = listenLabel
                        stateDescription = stateText
                    }.testTag("sound-listen"),
                ) {
                    Text(stringResource(R.string.snd_sounds_listen), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f))
                }
            }

            Box {
                IconButton(
                    onClick = { menu = true },
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "" }.testTag("sound-menu"),
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.snd_sounds_actions_named, sound.name))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.snd_sounds_rename)) }, onClick = { menu = false; onRename() }, modifier = Modifier.testTag("sound-rename"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.snd_sounds_delete), color = Brand.hangUp) }, onClick = { menu = false; onDelete() }, modifier = Modifier.testTag("sound-delete"))
                }
            }
        }

        if (open && sound.playable) SoundInlinePlayer(model, sound.id) { model.togglePlay(sound) }
    }
}
