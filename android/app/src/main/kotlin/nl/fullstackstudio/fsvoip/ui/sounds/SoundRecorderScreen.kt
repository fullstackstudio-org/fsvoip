// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.sounds

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.AudioRecorderSession
import nl.fullstackstudio.fsvoip.core.RecorderFailure
import nl.fullstackstudio.fsvoip.core.SoundsModel
import nl.fullstackstudio.fsvoip.ui.Brand
import nl.fullstackstudio.fsvoip.ui.PrimaryButton
import nl.fullstackstudio.fsvoip.ui.SecondaryButton
import nl.fullstackstudio.fsvoip.ui.SubpageScaffold
import nl.fullstackstudio.fsvoip.ui.section

/** "Nieuwe opname" as a full-screen dialog (it works from "Geluiden" and from the audio picker). Leaving it throws away what was not added. */
@Composable
fun SoundRecorderDialog(model: SoundsModel, onAdded: (String) -> Unit, onClose: () -> Unit) {
    val upload by model.upload.collectAsStateWithLifecycle()

    fun close() {
        if (upload != null) return

        model.stopPlaying()
        model.recorder.cancel()
        onClose()
    }

    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            SoundRecorderScreen(model, onAdded = { onAdded(it); onClose() }, close = ::close)
        }
    }
}

@Composable
fun SoundRecorderScreen(model: SoundsModel, onAdded: (String) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = model.recorder
    val state by recorder.state.collectAsStateWithLifecycle()
    val elapsed by recorder.elapsed.collectAsStateWithLifecycle()
    val banner by model.banner.collectAsStateWithLifecycle()
    val upload by model.upload.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    val defaultLabel = stringResource(R.string.snd_sounds_record_defaultName)
    val formatter = remember { DateTimeFormatter.ofPattern("d MMM HH:mm", java.util.Locale.getDefault()) }

    LaunchedEffect(state) {
        if (state is AudioRecorderSession.State.Recorded && name.isEmpty()) name = model.defaultRecordingName(defaultLabel) { formatter.format(it) }
    }

    SubpageScaffold(stringResource(R.string.snd_sounds_record_title), close) {
        banner?.let { SoundNotice(it) }

        Column(Modifier.fillMaxWidth().padding(16.dp).testTag("sound-recorder"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (val current = state) {
                AudioRecorderSession.State.Idle, AudioRecorderSession.State.RequestingPermission -> {
                    Column(Modifier.padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.snd_sounds_record_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        RecordButton(isRecording = false, enabled = current == AudioRecorderSession.State.Idle) { scope.launch { recorder.start() } }
                        Text(stringResource(R.string.snd_sounds_record_max, clock(AudioRecorderSession.MAX_DURATION)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }

                AudioRecorderSession.State.Recording -> {
                    Column(Modifier.padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(Brand.hangUp, CircleShape))
                            Text(stringResource(R.string.snd_sounds_record_recording), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }

                        val spoken = stringResource(R.string.snd_sounds_record_elapsed, clock(elapsed))

                        Text(
                            clock(elapsed),
                            fontSize = 54.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.semantics { contentDescription = spoken }.testTag("sound-record-time"),
                        )
                        LinearProgressIndicator(progress = { (elapsed / AudioRecorderSession.MAX_DURATION).toFloat().coerceIn(0f, 1f) }, color = Brand.hangUp, modifier = Modifier.fillMaxWidth())
                        RecordButton(isRecording = true, enabled = true) { recorder.stop() }
                    }
                }

                is AudioRecorderSession.State.Recorded -> {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        section(stringResource(R.string.snd_sounds_record_listen), null) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.snd_sounds_record_preview), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                SoundDurationChip(elapsed)
                            }
                            recorder.file?.let { file -> SoundInlinePlayer(model, SoundsModel.LOCAL_PREVIEW_ID) { model.playLocal(file) } }
                        }

                        section(stringResource(R.string.snd_sounds_record_name), null) {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                placeholder = { Text(stringResource(R.string.snd_sounds_record_name_placeholder)) },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("sound-record-name"),
                            )
                        }

                        upload?.let { SoundUploadBar(it) { model.cancelUpload() } }

                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton(
                                stringResource(R.string.snd_sounds_record_save),
                                onClick = {
                                    scope.launch {
                                        val id = model.addRecording(name)
                                        if (id != null) onAdded(id)
                                    }
                                },
                                enabled = upload == null && SoundsModel.cleanName(name).isNotEmpty(),
                                modifier = Modifier.testTag("sound-record-save"),
                            )
                            SecondaryButton(
                                stringResource(R.string.snd_sounds_record_again),
                                onClick = { model.stopPlaying(); recorder.discard(); name = "" },
                                enabled = upload == null,
                                modifier = Modifier.testTag("sound-record-again"),
                            )
                        }
                    }
                }

                is AudioRecorderSession.State.Failed -> {
                    Column(Modifier.padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.MicOff, contentDescription = null, tint = Brand.hangUp, modifier = Modifier.size(36.dp))
                        Text(recorderFailureText(current.failure), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.testTag("sound-record-failure"))

                        if (current.failure == RecorderFailure.PERMISSION_DENIED) {
                            SecondaryButton(
                                stringResource(R.string.snd_sounds_record_openSettings),
                                onClick = {
                                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                },
                                modifier = Modifier.testTag("sound-record-settings"),
                            )
                        }

                        if (current.failure != RecorderFailure.CALL_IN_PROGRESS && current.failure != RecorderFailure.PERMISSION_DENIED) {
                            PrimaryButton(stringResource(R.string.snd_sounds_record_tryAgain), onClick = { scope.launch { recorder.start() } })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordButton(isRecording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (isRecording) R.string.snd_sounds_record_stop else R.string.snd_sounds_record_start)

    Box(
        Modifier
            .size(96.dp)
            .semantics { contentDescription = label; role = Role.Button }
            .border(BorderStroke(3.dp, MaterialTheme.colorScheme.outline), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(if (isRecording) "sound-record-stop" else "sound-record-start"),
        contentAlignment = Alignment.Center,
    ) {
        if (isRecording) {
            Box(Modifier.size(32.dp).background(Brand.hangUp, RoundedCornerShape(6.dp)))
        } else {
            Box(Modifier.size(66.dp).background(if (enabled) Brand.hangUp else Brand.hangUp.copy(alpha = 0.4f), CircleShape))
        }
    }
}
