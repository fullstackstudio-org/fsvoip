// SPDX-License-Identifier: AGPL-3.0-or-later
//
// "Oproepvoorkeuren": what happens to a call for the own extension (always forward, when nobody picks up, voicemail). The number a
// call goes out with is chosen per call on the dial screen, not here. Android port of the iOS `CallPreferencesPage`.

package nl.fullstackstudio.fsvoip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.PbxTarget
import nl.fullstackstudio.fsvoip.core.PbxTargetOption
import nl.fullstackstudio.fsvoip.core.PbxVocabulary
import nl.fullstackstudio.fsvoip.core.SelfExtensionDraft
import nl.fullstackstudio.fsvoip.core.SelfExtensionFailure
import nl.fullstackstudio.fsvoip.core.SelfExtensionSaveOutcome
import nl.fullstackstudio.fsvoip.core.TargetType

@Composable
fun CallPreferencesScreen(model: AppModel, accountId: String, back: () -> Unit) {
    val states by model.selfExtension.states.collectAsStateWithLifecycle()
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    val state = states[accountId]
    val scope = rememberCoroutineScope()

    var baseline by remember(accountId) { mutableStateOf<SelfExtensionDraft?>(null) }
    var draft by remember(accountId) { mutableStateOf(SelfExtensionDraft()) }
    var message by remember(accountId) { mutableStateOf<Message?>(null) }
    var saving by remember { mutableStateOf(false) }

    // Load once; a later refresh only moves the fields the user did not touch.
    LaunchedEffect(accountId) {
        account?.let { model.selfExtension.load(it) }
    }

    LaunchedEffect(state) {
        val fresh = state ?: return@LaunchedEffect
        val existing = baseline

        if (existing == null) {
            draft = SelfExtensionDraft.of(fresh)
            baseline = draft
        } else {
            val (rebased, newBaseline) = draft.rebase(fresh, existing)
            draft = rebased
            baseline = newBaseline
        }
    }

    val dirty = baseline?.let { draft.patch(it, version = 0) != null } == true

    SubpageScaffold(stringResource(R.string.settings_callPreferences), back) {
        val loaded = state != null && baseline != null

        when (val current = message) {
            Message.Stale -> InfoRow(stringResource(R.string.self_stale_title) + ". " + stringResource(R.string.self_stale_message))
            is Message.Failure -> InfoRow(failureText(current.failure))
            null -> Unit
        }

        if (!loaded) {
            InfoRow(stringResource(R.string.prefs_unavailable))
            return@SubpageScaffold
        }

        section(stringResource(R.string.prefs_forward), stringResource(R.string.prefs_forward_footer)) {
            TargetChoiceRow(
                title = stringResource(R.string.pbx_device_forward_title),
                target = draft.forwardAlways,
                options = state!!.targets,
                noneLabel = stringResource(R.string.pbx_device_forward_off),
                tag = "prefs-forward",
            ) { draft = draft.copy(forwardAlways = it) }
        }

        section(stringResource(R.string.pbx_device_noAnswer_title), stringResource(R.string.pbx_device_noAnswer_footer)) {
            StepperRow(
                text = stringResource(R.string.pbx_device_noAnswer_after, draft.noAnswerSeconds),
                onMinus = { draft = draft.copy(noAnswerSeconds = (draft.noAnswerSeconds - 5).coerceAtLeast(SelfExtensionDraft.NO_ANSWER_RANGE.first)) },
                onPlus = { draft = draft.copy(noAnswerSeconds = (draft.noAnswerSeconds + 5).coerceAtMost(SelfExtensionDraft.NO_ANSWER_RANGE.last)) },
            )
            TargetChoiceRow(
                title = stringResource(R.string.pbx_device_noAnswer_then),
                target = draft.noAnswerTarget,
                options = state!!.targets,
                noneLabel = stringResource(if (draft.voicemailEnabled) R.string.pbx_device_noAnswer_default_voicemail else R.string.pbx_device_noAnswer_default_ring),
                tag = "prefs-noanswer-target",
            ) { draft = draft.copy(noAnswerTarget = it) }
        }

        val hasEmail = draft.cleanEmail.isNotEmpty()

        section(
            stringResource(R.string.prefs_voicemail),
            if (hasEmail) stringResource(R.string.prefs_voicemail_email_to, draft.cleanEmail) else stringResource(R.string.prefs_voicemail_email_needsAddress),
        ) {
            SwitchRow(
                stringResource(R.string.pbx_device_voicemail),
                stringResource(R.string.pbx_device_voicemail_footer),
                draft.voicemailEnabled,
                Modifier.testTag("prefs-voicemail-toggle"),
            ) { draft = draft.copy(voicemailEnabled = it) }

            if (draft.voicemailEnabled && hasEmail) {
                SwitchRow(stringResource(R.string.prefs_voicemail_email), null, draft.voicemailToEmail, Modifier.testTag("prefs-voicemail-email-toggle")) {
                    draft = draft.copy(voicemailToEmail = it)
                }
            }

            OutlinedTextField(
                value = draft.email,
                onValueChange = { draft = draft.copy(email = it) },
                label = { Text(stringResource(R.string.profile_email)) },
                placeholder = { Text(stringResource(R.string.profile_email_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("prefs-email-field"),
            )
        }

        Column(Modifier.padding(16.dp)) {
            PrimaryButton(
                stringResource(R.string.action_save),
                enabled = dirty && !saving,
                modifier = Modifier.testTag("prefs-save"),
                onClick = {
                    val start = baseline ?: return@PrimaryButton
                    val target = account ?: return@PrimaryButton
                    saving = true

                    scope.launch {
                        when (val outcome = model.selfExtension.save(draft, start, target)) {
                            is SelfExtensionSaveOutcome.Saved -> {
                                message = null
                                outcome.state?.let { fresh ->
                                    draft = SelfExtensionDraft.of(fresh)
                                    baseline = draft
                                }
                                back()
                            }
                            SelfExtensionSaveOutcome.Unchanged -> back()
                            is SelfExtensionSaveOutcome.Stale -> {
                                val (rebased, newBaseline) = draft.rebase(outcome.fresh, start)
                                draft = rebased
                                baseline = newBaseline
                                message = Message.Stale
                            }
                            is SelfExtensionSaveOutcome.Failed -> message = Message.Failure(outcome.failure)
                        }

                        saving = false
                    }
                },
            )
        }
    }
}

private sealed interface Message {
    data object Stale : Message

    data class Failure(val failure: SelfExtensionFailure) : Message
}

@Composable
private fun failureText(failure: SelfExtensionFailure): String = when (failure) {
    SelfExtensionFailure.AccessDenied -> stringResource(R.string.self_error_denied)
    SelfExtensionFailure.Revoked -> stringResource(R.string.self_error_revoked)
    SelfExtensionFailure.ReadOnly -> stringResource(R.string.self_error_denied)
    SelfExtensionFailure.Conflict -> stringResource(R.string.self_error_conflict)
    SelfExtensionFailure.Invalid -> stringResource(R.string.self_error_invalid)
    is SelfExtensionFailure.RateLimited -> {
        val seconds = failure.retryAfterSeconds

        if (seconds != null && seconds > 60) stringResource(R.string.self_error_rateLimited_minutes, (seconds + 59) / 60) else stringResource(R.string.self_error_rateLimited)
    }
    SelfExtensionFailure.Offline -> stringResource(R.string.self_error_offline)
    SelfExtensionFailure.Unavailable -> stringResource(R.string.self_error_unavailable)
    SelfExtensionFailure.Other -> stringResource(R.string.self_error_other)
}

@Composable
private fun StepperRow(text: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).testTag("prefs-noanswer-seconds"))
        IconButton(onClick = onMinus) { Icon(Icons.Filled.Remove, contentDescription = null) }
        IconButton(onClick = onPlus) { Icon(Icons.Filled.Add, contentDescription = null) }
    }
}

/** A row that shows a destination by name and opens a list to pick another one (a colleague, a group, a voicemail box, an external number). */
@Composable
private fun TargetChoiceRow(title: String, target: PbxTarget?, options: List<PbxTargetOption>, noneLabel: String, tag: String, onChange: (PbxTarget?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val gone = stringResource(R.string.pbx_target_gone)

    LinkRow(title, PbxVocabulary.targetLabel(target, options, noneLabel, gone)) { open = true }

    if (open) {
        // The destinations that make sense here: never "hang up" or an object type this app version does not know.
        val choices = options.filter { it.type != TargetType.HANGUP && it.type != TargetType.UNKNOWN }
        var picked by remember { mutableStateOf(PbxVocabulary.optionFor(target, choices)?.value ?: if (target?.type == TargetType.EXTERNAL) "external" else "") }
        var number by remember { mutableStateOf(target?.takeIf { it.type == TargetType.EXTERNAL }?.number.orEmpty()) }
        var invalid by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.testTag(tag)) {
                    ChoiceLine(noneLabel, picked.isEmpty()) { picked = "" }

                    for (option in choices) {
                        val label = if (option.type == TargetType.EXTERNAL) stringResource(R.string.self_pick_external) else PbxVocabulary.targetLabel(PbxTarget.obj(option.type, option.id.orEmpty()), choices, noneLabel, gone)
                        ChoiceLine(label, picked == option.value) { picked = option.value }
                    }

                    if (picked == "external") {
                        OutlinedTextField(
                            value = number,
                            onValueChange = {
                                number = it
                                invalid = false
                            },
                            label = { Text(stringResource(R.string.self_pick_external_hint)) },
                            isError = invalid,
                            supportingText = if (invalid) ({ Text(stringResource(R.string.self_pick_invalid)) }) else null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val option = choices.firstOrNull { it.value == picked }
                    val result = if (option == null) null else PbxVocabulary.targetFor(option, number)

                    if (option != null && result == null) {
                        invalid = true
                    } else {
                        open = false
                        onChange(result)
                    }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ChoiceLine(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
