// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.sounds

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.SoundSelection
import nl.fullstackstudio.fsvoip.core.SoundsModel
import nl.fullstackstudio.fsvoip.ui.SecondaryButton
import nl.fullstackstudio.fsvoip.ui.SubpageScaffold
import nl.fullstackstudio.fsvoip.ui.section
import nl.fullstackstudio.fsvoip.ui.settings.ChoiceRow
import nl.fullstackstudio.fsvoip.ui.settings.NoticeCard
import nl.fullstackstudio.fsvoip.ui.settings.settingsHubs

/** A sound the chain already knows by name (used when the account may not manage sounds: no listening, no adding). */
data class AudioOption(val id: String, val name: String)

/**
 * "Audiobestanden": choose the sound for a message, listen to each one, add a new one with the microphone or from the files. "Toepassen"
 * sets the choice ([onApply]); "Annuleren" leaves it as it was ([back]). A sound that was just added is selected. For an account that
 * may not manage sounds this is a plain choice from [fallback].
 */
@Composable
fun AudioPickerScreen(
    model: AppModel,
    accountId: String,
    title: String,
    soundId: String?,
    fallback: List<AudioOption>,
    allowsNone: Boolean,
    onApply: (String?) -> Unit,
    back: () -> Unit,
) {
    val context = LocalContext.current
    val sounds = remember(accountId) { model.settingsHubs(context).sounds(accountId) }

    if (sounds == null) {
        PlainSoundChoice(title, soundId, fallback, allowsNone, onApply, back)
    } else {
        AudioPickerContent(title, soundId, fallback, allowsNone, sounds, onApply, back)
    }
}

@Composable
private fun AudioPickerContent(title: String, soundId: String?, fallback: List<AudioOption>, allowsNone: Boolean, model: SoundsModel, onApply: (String?) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by model.sounds.collectAsStateWithLifecycle()
    val hasLoaded by model.hasLoaded.collectAsStateWithLifecycle()
    val isLoading by model.isLoading.collectAsStateWithLifecycle()
    val failure by model.failure.collectAsStateWithLifecycle()
    val banner by model.banner.collectAsStateWithLifecycle()
    val upload by model.upload.collectAsStateWithLifecycle()
    val lastAdded by model.lastAddedId.collectAsStateWithLifecycle()
    val durations by model.durations.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf(soundId) }
    var openId by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val file = pickedFile(context, uri)
            scope.launch { model.addFile(file.fileName, file.size, read = file.read)?.let { selection = it } }
        }
    }

    LaunchedEffect(model) { model.load() }
    LaunchedEffect(lastAdded) { lastAdded?.let { selection = it } }
    DisposableEffect(model) { onDispose { model.stopPlaying() } }

    val rows = if (hasLoaded) list.map { PickerRow(it.id, it.name, durations[it.id] ?: it.durationSeconds, it.playable) } else fallback.map { PickerRow(it.id, it.name, null, false) }
    val canApply = SoundSelection.canApply(selection, soundId, allowsNone, upload != null)

    SubpageScaffold(
        stringResource(R.string.snd_sounds_picker_title),
        back,
        footer = {
            Surface(tonalElevation = 3.dp) {
                Column {
                    HorizontalDivider()
                    Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SaveFooterButtons(canApply, onApply = { model.stopPlaying(); onApply(selection) }, onCancel = back)
                        SecondaryButton(
                            stringResource(R.string.snd_sounds_newRecording),
                            onClick = { model.stopPlaying(); recording = true },
                            enabled = upload == null,
                            icon = Icons.Filled.Mic,
                            modifier = Modifier.testTag("sound-new-recording"),
                        )
                    }
                }
            }
        },
    ) {
        banner?.let { SoundNotice(it) }
        upload?.let { SoundUploadBar(it) { model.cancelUpload() } }

        when {
            isLoading && !hasLoaded && rows.isEmpty() -> Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            failure != null && !hasLoaded -> {
                NoticeCard(soundFailureText(failure!!))
                if (failure!!.isRetryable) SecondaryButton(stringResource(R.string.action_retry), onClick = { scope.launch { model.load() } }, modifier = Modifier.padding(horizontal = 16.dp))
            }
            rows.isEmpty() && !allowsNone -> Column(Modifier.fillMaxWidth().heightIn(min = 200.dp).padding(32.dp).testTag("chain-sound-empty"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                Text(stringResource(R.string.snd_numbers_sound_empty_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                Text(stringResource(R.string.snd_sounds_picker_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
            else -> section(title, stringResource(R.string.snd_sounds_picker_footer)) {
                if (allowsNone) {
                    ChoiceRow(stringResource(R.string.snd_numbers_sound_none), null, selected = selection == null, modifier = Modifier.testTag("sound-none")) {
                        selection = null
                        model.stopPlaying()
                    }
                }

                for (row in rows) {
                    PickerRowView(
                        model, row, selected = selection == row.id, open = openId == row.id,
                        onSelect = { selection = row.id },
                        onToggleOpen = {
                            if (openId == row.id) {
                                openId = null
                                model.stopPlaying()
                            } else {
                                openId = row.id
                                model.sound(row.id)?.let { model.play(it) }
                            }
                        },
                    )
                }

                TextButton(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = upload == null, modifier = Modifier.padding(horizontal = 8.dp).testTag("sound-add-file")) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.snd_sounds_addFile), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    if (recording) SoundRecorderDialog(model, onAdded = { selection = it }, onClose = { recording = false })
}

private data class PickerRow(val id: String, val name: String, val duration: Double?, val playable: Boolean)

@Composable
private fun PickerRowView(model: SoundsModel, row: PickerRow, selected: Boolean, open: Boolean, onSelect: () -> Unit, onToggleOpen: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clickable(role = Role.RadioButton, onClick = onSelect).semantics(mergeDescendants = true) { this.selected = selected }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("sound-choice"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                SoundDurationChip(row.duration)
            }

            if (row.playable) {
                val label = stringResource(R.string.snd_sounds_listen_named, row.name)
                val stateText = stringResource(if (open) R.string.snd_sounds_listen_open else R.string.snd_sounds_listen_closed)

                TextButton(onClick = onToggleOpen, modifier = Modifier.semantics { contentDescription = label; stateDescription = stateText }.testTag("sound-listen")) {
                    Text(stringResource(R.string.snd_sounds_listen), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f))
                }
            }
        }

        if (open && row.playable) SoundInlinePlayer(model, row.id) { model.sound(row.id)?.let { model.togglePlay(it) } }
    }
}

@Composable
private fun SaveFooterButtons(canApply: Boolean, onApply: () -> Unit, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel, modifier = Modifier.weight(1f).testTag("sound-cancel")) { Text(stringResource(R.string.action_cancel)) }
        androidx.compose.material3.Button(
            onClick = onApply,
            enabled = canApply,
            modifier = Modifier.weight(1f).testTag("sound-apply"),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = nl.fullstackstudio.fsvoip.ui.Brand.lime, contentColor = nl.fullstackstudio.fsvoip.ui.Brand.ink, disabledContainerColor = nl.fullstackstudio.fsvoip.ui.Brand.lime.copy(alpha = 0.4f), disabledContentColor = nl.fullstackstudio.fsvoip.ui.Brand.ink.copy(alpha = 0.6f)),
        ) { Text(stringResource(R.string.snd_sounds_apply), fontWeight = FontWeight.SemiBold) }
    }
}

/** The choice without sounds management: the names the chain knows, no listening and no adding. */
@Composable
private fun PlainSoundChoice(title: String, soundId: String?, options: List<AudioOption>, allowsNone: Boolean, onApply: (String?) -> Unit, back: () -> Unit) {
    SubpageScaffold(title, back) {
        if (options.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp).testTag("chain-sound-empty"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                Text(stringResource(R.string.snd_numbers_sound_empty_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.snd_numbers_sound_empty_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        } else {
            section(null, stringResource(R.string.snd_numbers_sound_footer)) {
                if (allowsNone) ChoiceRow(stringResource(R.string.snd_numbers_sound_none), null, selected = soundId == null) { onApply(null) }

                for (option in options) ChoiceRow(option.name, null, selected = soundId == option.id) { onApply(option.id) }
            }
        }
    }
}

