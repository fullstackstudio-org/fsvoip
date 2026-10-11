// SPDX-License-Identifier: AGPL-3.0-or-later
//
// "Nummers": every number of the PBX as a chain - Naam, Openingstijden, Welkomstbericht, Doorschakeling and Gespreksopname, each a
// page of its own (administrator pairings). A number that does more than the chain can show ("uitgebreid") says what it does in
// words; only name and recording can change then. Android port of the iOS `NumbersView` / `NumberView` / `Number*View`.

package nl.fullstackstudio.fsvoip.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.ChainForwardingDraft
import nl.fullstackstudio.fsvoip.core.ChainHoursDraft
import nl.fullstackstudio.fsvoip.core.ChainKey
import nl.fullstackstudio.fsvoip.core.ChainMember
import nl.fullstackstudio.fsvoip.core.ChainOptions
import nl.fullstackstudio.fsvoip.core.ChainRecordingDraft
import nl.fullstackstudio.fsvoip.core.ChainSaveOutcome
import nl.fullstackstudio.fsvoip.core.ChainStep
import nl.fullstackstudio.fsvoip.core.ChainWelcomeDraft
import nl.fullstackstudio.fsvoip.core.Fallback
import nl.fullstackstudio.fsvoip.core.ForwardingKind
import nl.fullstackstudio.fsvoip.core.NumberChain
import nl.fullstackstudio.fsvoip.core.PbxFailure
import nl.fullstackstudio.fsvoip.core.PbxNumberEntry
import nl.fullstackstudio.fsvoip.core.PbxTarget
import nl.fullstackstudio.fsvoip.core.PbxTargetOption
import nl.fullstackstudio.fsvoip.core.PbxVocabulary
import nl.fullstackstudio.fsvoip.core.RecordingPrice
import nl.fullstackstudio.fsvoip.core.RingStrategy
import nl.fullstackstudio.fsvoip.core.StoredAccount
import nl.fullstackstudio.fsvoip.core.TargetType
import nl.fullstackstudio.fsvoip.core.ChainHolidayDate
import nl.fullstackstudio.fsvoip.core.WeekDraft

enum class NumberStepKind { NAME, HOURS, WELCOME, FORWARDING, RECORDING }

// MARK: The list

@Composable
fun NumbersScreen(model: AppModel, accountId: String, open: (String) -> Unit, back: () -> Unit) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val all by model.pbx.numbers.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    var failure by remember { mutableStateOf<PbxFailure?>(null) }
    var loading by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(accountId, attempt) {
        account ?: return@LaunchedEffect
        loading = true
        failure = model.pbx.loadNumbers(account)
        loading = false
    }

    AdminPage(model, stringResource(R.string.numbers_title), back) {
        ReadOnlyNote(model, accountId)
        failure?.let { NoteCard(pbxFailureText(it), isError = true) }

        val page = all[accountId]

        if (page == null) {
            if (loading) Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
            else if (failure != null) TextButton(onClick = { attempt++ }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.action_retry)) }
            return@AdminPage
        }

        if (page.numbers.isEmpty()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.numbers_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.numbers_empty_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }

        section(null, stringResource(R.string.numbers_list_footer)) {
            for (entry in page.numbers) {
                NumberRow(entry) { open(entry.id) }
            }
        }
    }
}

@Composable
private fun NumberRow(entry: PbxNumberEntry, onClick: () -> Unit) {
    val hours = entry.summary.hours.ifEmpty { stringResource(R.string.numbers_hours_alwaysOpen) }
    val forwarding = when (entry.summary.forwarding) {
        ForwardingKind.STANDARD -> stringResource(R.string.numbers_forwarding_standard)
        ForwardingKind.MENU -> stringResource(R.string.numbers_forwarding_menu)
        else -> stringResource(R.string.numbers_advanced_short)
    }
    val line = listOfNotNull(
        hours,
        stringResource(R.string.numbers_welcome_short).takeIf { entry.summary.welcome },
        forwarding,
        stringResource(R.string.numbers_recording_short).takeIf { entry.summary.recording },
        syncNote(entry.sync),
    ).joinToString(" · ")

    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp).testTag("pbx-number-row"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(PbxVocabulary.formatNumber(entry.number), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            entry.name?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

// MARK: One number

@Composable
fun NumberScreen(model: AppModel, accountId: String, numberId: String, openStep: (NumberStepKind) -> Unit, back: () -> Unit) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val chains by model.pbx.chains.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    val chain = chains["$accountId/$numberId"]
    var failure by remember { mutableStateOf<PbxFailure?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    LaunchedEffect(accountId, numberId, attempt) {
        account ?: return@LaunchedEffect
        failure = model.pbx.loadChain(account, numberId)
    }

    AdminPage(model, chain?.let { it.name?.takeIf { name -> name.isNotBlank() } ?: PbxVocabulary.formatNumber(it.number) } ?: stringResource(R.string.numbers_title), back) {
        ReadOnlyNote(model, accountId)

        if (chain == null) {
            failure?.let {
                NoteCard(pbxFailureText(it), isError = true)
                TextButton(onClick = { attempt++ }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.action_retry)) }
            } ?: Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
            return@AdminPage
        }

        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(PbxVocabulary.formatNumber(chain.number), style = MaterialTheme.typography.headlineMedium)
            syncNote(chain.sync)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        section(null, null) {
            LinkRow(stringResource(R.string.numbers_name_title), chain.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.numbers_name_none)) { openStep(NumberStepKind.NAME) }

            if (chain.isEditable) {
                val hours = chain.hours?.let { WeekDraft.summary(it.week, stringResource(R.string.pbx_hours_never)) } ?: stringResource(R.string.numbers_off)
                val welcome = chain.welcome?.let { soundName(it.soundId, chain.options) ?: stringResource(R.string.numbers_on) } ?: stringResource(R.string.numbers_off)

                LinkRow(stringResource(R.string.numbers_hours_title), listOfNotNull(hours, alsoFor(chain.hours?.sharedWith)).joinToString("\n")) { openStep(NumberStepKind.HOURS) }
                LinkRow(stringResource(R.string.numbers_welcome_title), listOfNotNull(welcome, alsoFor(chain.welcome?.sharedWith)).joinToString("\n")) { openStep(NumberStepKind.WELCOME) }
                LinkRow(stringResource(R.string.numbers_forwarding_title), forwardingLine(chain)) { openStep(NumberStepKind.FORWARDING) }
            }

            val recording = when {
                chain.recording.enabled -> stringResource(R.string.numbers_on)
                chain.recording.available -> stringResource(R.string.numbers_off)
                else -> stringResource(R.string.numbers_recording_notAvailable)
            }
            LinkRow(stringResource(R.string.numbers_recording_title), recording) { openStep(NumberStepKind.RECORDING) }
        }

        if (!chain.isEditable) {
            section(stringResource(R.string.numbers_advanced_title), stringResource(R.string.numbers_advanced_message)) {
                val lines = chain.advanced?.summary.orEmpty()
                if (lines.isEmpty()) InfoRow(stringResource(R.string.numbers_advanced_noSummary))
                for (line in lines) {
                    Text("• $line", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp))
                }
                ActionRow(stringResource(R.string.numbers_advanced_portal), null) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://fullstackstudio.nl/portal")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                InfoRow(stringResource(R.string.numbers_advanced_portal_hint))
            }
        }
    }
}

@Composable
private fun alsoFor(names: List<String>?): String? = names?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.numbers_alsoFor, it.joinToString(", ")) }

@Composable
private fun forwardingLine(chain: NumberChain): String = when (val forwarding = chain.forwarding) {
    is nl.fullstackstudio.fsvoip.core.ChainForwarding.Standard -> listOfNotNull(
        stringResource(R.string.numbers_forwarding_standard),
        stringResource(R.string.numbers_forwarding_phones, forwarding.members.size),
        alsoFor(forwarding.sharedWith),
    ).joinToString(" · ")
    is nl.fullstackstudio.fsvoip.core.ChainForwarding.Menu -> listOfNotNull(stringResource(R.string.numbers_forwarding_menu), stringResource(R.string.numbers_menu_keyCount, forwarding.keys.size), alsoFor(forwarding.sharedWith)).joinToString(" · ")
    else -> stringResource(R.string.numbers_portalOnly)
}

// MARK: The step pages

@Composable
fun NumberStepScreen(model: AppModel, accountId: String, numberId: String, kind: NumberStepKind, back: () -> Unit) {
    when (kind) {
        NumberStepKind.NAME -> NameStep(model, accountId, numberId, back)
        NumberStepKind.HOURS -> HoursStep(model, accountId, numberId, back)
        NumberStepKind.WELCOME -> WelcomeStep(model, accountId, numberId, back)
        NumberStepKind.FORWARDING -> ForwardingStep(model, accountId, numberId, back)
        NumberStepKind.RECORDING -> RecordingStep(model, accountId, numberId, back)
    }
}

/**
 * The frame of a step page: loads the chain, keeps a draft, rebases it when the chain changed on the server (`409 stale`: the
 * fresh state is shown and what the user typed stays), and saves. [save] returns `null` when nothing differs.
 */
@Composable
private fun <D> ChainStepPage(
    model: AppModel,
    accountId: String,
    numberId: String,
    title: String,
    back: () -> Unit,
    makeDraft: (NumberChain) -> D,
    rebase: (mine: D, fresh: D, baseline: D) -> D,
    isValid: (D) -> Boolean,
    save: suspend (account: StoredAccount, draft: D, baseline: D, basedOn: NumberChain) -> ChainSaveOutcome?,
    needsEditableChain: Boolean = true,
    content: @Composable ColumnScope.(draft: D, change: (D) -> Unit, chain: NumberChain) -> Unit,
) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val chains by model.pbx.chains.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    val chain = chains["$accountId/$numberId"]
    val scope = rememberCoroutineScope()
    val pending = stringResource(R.string.pbx_pending_message)

    var baseline by remember(numberId) { mutableStateOf<D?>(null) }
    var basedOn by remember(numberId) { mutableStateOf<NumberChain?>(null) }
    var draft by remember(numberId) { mutableStateOf<D?>(null) }
    var failure by remember(numberId) { mutableStateOf<PbxFailure?>(null) }
    var stale by remember(numberId) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(accountId, numberId) { if (account != null && chain == null) model.pbx.loadChain(account, numberId) }

    LaunchedEffect(chain) {
        val fresh = chain ?: return@LaunchedEffect
        val freshDraft = makeDraft(fresh)
        val start = baseline
        val mine = draft

        draft = if (start == null || mine == null) freshDraft else rebase(mine, freshDraft, start)
        baseline = freshDraft
        basedOn = fresh
    }

    val readOnly = model.isPbxReadOnly(accountId)

    AdminPage(model, title, back) {
        val current = draft
        val start = baseline
        val based = basedOn

        if (chain == null || current == null || start == null || based == null) {
            Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
            return@AdminPage
        }

        if (needsEditableChain && !chain.isEditable) {
            NoteCard(stringResource(R.string.pbx_error_advanced), isError = true)
            return@AdminPage
        }

        ReadOnlyNote(model, accountId)
        if (stale) NoteCard(stringResource(R.string.numbers_stale_message), title = stringResource(R.string.numbers_stale_title))
        failure?.let { NoteCard(pbxFailureText(it), isError = true) }

        ReadOnlyLock(readOnly) { Column { content(current, { draft = it }, chain) } }

        if (!readOnly) {
            Column(Modifier.padding(16.dp)) {
                PrimaryButton(
                    stringResource(R.string.action_save),
                    enabled = !saving && isValid(current),
                    modifier = Modifier.testTag("number-step-save"),
                    onClick = {
                        val target = account ?: return@PrimaryButton
                        saving = true
                        stale = false

                        scope.launch {
                            when (val outcome = save(target, current, start, based)) {
                                null -> back()
                                is ChainSaveOutcome.Saved -> {
                                    failure = null
                                    model.showNotice(pending, isError = false)
                                    back()
                                }
                                is ChainSaveOutcome.Stale -> {
                                    failure = null
                                    stale = true
                                }
                                is ChainSaveOutcome.Failed -> failure = outcome.failure
                            }

                            saving = false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun NameStep(model: AppModel, accountId: String, numberId: String, back: () -> Unit) {
    ChainStepPage<String>(
        model, accountId, numberId, stringResource(R.string.numbers_name_title), back,
        makeDraft = { it.name.orEmpty() },
        rebase = { mine, fresh, baseline -> if (mine == baseline) fresh else mine },
        isValid = { it.trim().length <= 60 },
        save = { account, draft, baseline, basedOn ->
            if (draft.trim() == baseline.trim()) null else model.pbx.saveStep(account, numberId, ChainStep.Name(draft.trim().ifEmpty { null }), basedOn)
        },
        needsEditableChain = false,
    ) { draft, change, _ ->
        section(null, stringResource(R.string.numbers_name_footer)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { change(it.take(60)) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.numbers_name_none)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("number-name-field"),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HoursStep(model: AppModel, accountId: String, numberId: String, back: () -> Unit) {
    var adding by remember { mutableStateOf(false) }

    ChainStepPage<ChainHoursDraft>(
        model, accountId, numberId, stringResource(R.string.numbers_hours_title), back,
        makeDraft = { ChainHoursDraft.of(it) },
        rebase = { mine, fresh, baseline -> mine.rebase(fresh, baseline) },
        isValid = { it.isValid },
        save = { account, draft, baseline, basedOn -> draft.step(baseline)?.let { model.pbx.saveStep(account, numberId, it, basedOn) } },
    ) { draft, change, chain ->
        section(null, stringResource(R.string.numbers_hours_explanation)) {
            SwitchRow(stringResource(R.string.numbers_hours_toggle), if (draft.enabled) null else stringResource(R.string.numbers_hours_off_message), draft.enabled, Modifier.testTag("number-hours-toggle")) { change(draft.copy(enabled = it)) }
        }

        if (draft.enabled) {
            section(stringResource(R.string.numbers_hours_tab_week), stringResource(R.string.numbers_hours_day_footer)) {
                WeekEditor(draft.days) { change(draft.copy(days = it)) }
            }

            section(stringResource(R.string.numbers_hours_closedSettings), null) {
                FallbackRow(stringResource(R.string.numbers_hours_closed), draft.closed, null, chain.options, "number-closed") { change(draft.copy(closed = it ?: draft.closed)) }
            }

            section(stringResource(R.string.numbers_hours_tab_exceptions), null) {
                SwitchRow(stringResource(R.string.numbers_hours_national), stringResource(R.string.numbers_hours_national_explanation), draft.national) { change(draft.copy(national = it)) }

                for (date in draft.dates) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(date.date, style = MaterialTheme.typography.bodyLarge)
                            Text(date.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { change(draft.copy(dates = draft.dates - date)) }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.pbx_closed_reopen)) }
                    }
                }

                ActionRow(stringResource(R.string.numbers_hours_addClosed), Icons.Filled.Add, Modifier.testTag("number-hours-addclosed")) { adding = true }

                FallbackRow(stringResource(R.string.numbers_hours_onHoliday), draft.holiday, stringResource(R.string.numbers_hours_onHoliday_sameAsClosed), chain.options, "number-holiday") { change(draft.copy(holiday = it)) }
            }
        }

        if (adding) {
            ClosedDayDialog(onDismiss = { adding = false }) { date ->
                adding = false
                if (draft.dates.none { it.date == date.date } && draft.dates.size < 20) change(draft.copy(dates = (draft.dates + date).sortedBy { it.date }))
            }
        }
    }
}

/** One own day (a name and a date) on which the number is closed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClosedDayDialog(onDismiss: () -> Unit, onAdd: (ChainHolidayDate) -> Unit) {
    val state = rememberDatePickerState()
    var name by remember { mutableStateOf("") }
    val defaultName = stringResource(R.string.pbx_closed_defaultName)

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    val millis = state.selectedDateMillis ?: return@TextButton
                    onAdd(ChainHolidayDate(name.trim().ifEmpty { defaultName }, Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()))
                },
            ) { Text(stringResource(R.string.numbers_hours_addClosed)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        Column {
            DatePicker(state = state, modifier = Modifier.weight(1f, fill = false))
            OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text(stringResource(R.string.pbx_closed_reason)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun WelcomeStep(model: AppModel, accountId: String, numberId: String, back: () -> Unit) {
    ChainStepPage<ChainWelcomeDraft>(
        model, accountId, numberId, stringResource(R.string.numbers_welcome_title), back,
        makeDraft = { ChainWelcomeDraft.of(it) },
        rebase = { mine, fresh, baseline -> mine.rebase(fresh, baseline) },
        isValid = { it.isValid },
        save = { account, draft, baseline, basedOn -> draft.step(baseline)?.let { model.pbx.saveStep(account, numberId, it, basedOn) } },
    ) { draft, change, chain ->
        section(null, stringResource(R.string.numbers_welcome_explanation)) {
            SwitchRow(stringResource(R.string.numbers_welcome_toggle), null, draft.enabled, Modifier.testTag("number-welcome-toggle")) { change(draft.copy(enabled = it)) }
        }

        if (draft.enabled) {
            SoundChoiceSection(stringResource(R.string.numbers_welcome_sound), draft.soundId, chain.options, allowNone = false, footer = if (draft.soundId == null) stringResource(R.string.numbers_welcome_soundNeeded) else stringResource(R.string.numbers_sound_footer)) {
                change(draft.copy(soundId = it))
            }
        }
    }
}

@Composable
private fun SoundChoiceSection(title: String, selected: String?, options: ChainOptions, allowNone: Boolean, footer: String?, onChange: (String?) -> Unit) {
    section(title, footer) {
        if (allowNone) CheckRow(stringResource(R.string.numbers_sound_none), selected == null) { onChange(null) }

        if (options.sounds.isEmpty()) {
            InfoRow(stringResource(R.string.numbers_sound_empty_title) + ". " + stringResource(R.string.numbers_sound_empty_message))
        }

        for (sound in options.sounds) {
            CheckRow(sound.name, selected == sound.id) { onChange(sound.id) }
        }
    }
}

@Composable
private fun ForwardingStep(model: AppModel, accountId: String, numberId: String, back: () -> Unit) {
    ChainStepPage<ChainForwardingDraft>(
        model, accountId, numberId, stringResource(R.string.numbers_forwarding_title), back,
        makeDraft = { ChainForwardingDraft.of(it) },
        rebase = { mine, fresh, baseline -> mine.rebase(fresh, baseline) },
        isValid = { it.isValid },
        save = { account, draft, baseline, basedOn -> draft.step(baseline)?.let { model.pbx.saveStep(account, numberId, it, basedOn) } },
    ) { draft, change, chain ->
        val original = ChainForwardingDraft.of(chain)

        section(null, stringResource(R.string.numbers_forwarding_intro)) {
            for (kind in listOf(ForwardingKind.STANDARD, ForwardingKind.MENU)) {
                Row(Modifier.fillMaxWidth().clickable { change(draft.copy(kind = kind)) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = draft.kind == kind, onClick = { change(draft.copy(kind = kind)) })
                    Text(stringResource(if (kind == ForwardingKind.STANDARD) R.string.numbers_forwarding_standard else R.string.numbers_forwarding_menu), style = MaterialTheme.typography.bodyLarge)
                }
            }

            if (original.kind != draft.kind) {
                InfoRow(stringResource(if (draft.kind == ForwardingKind.MENU) R.string.numbers_forwarding_switch_toMenu else R.string.numbers_forwarding_switch_toStandard))
            }
        }

        if (draft.kind == ForwardingKind.STANDARD) StandardForwarding(draft, change, chain.options) else MenuForwarding(draft, change, chain.options)
    }
}

@Composable
private fun StandardForwarding(draft: ChainForwardingDraft, change: (ChainForwardingDraft) -> Unit, options: ChainOptions) {
    section(stringResource(R.string.numbers_forwarding_members), if (draft.members.isEmpty()) stringResource(R.string.numbers_forwarding_members_none) else null) {
        for (device in options.devices) {
            val member = draft.members.firstOrNull { it.deviceId == device.id }

            Row(
                Modifier.fillMaxWidth().clickable {
                    change(draft.copy(members = if (member != null) draft.members - member else draft.members + ChainMember(device.id, if (draft.strategy == RingStrategy.SEQUENCE) draft.members.sumOf { it.timeoutSeconds } else 0, 25)))
                }.padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = member != null, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                Text(listOfNotNull(device.name, device.extensionNumber).joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
            }

            if (member != null) {
                if (draft.strategy == RingStrategy.SEQUENCE) {
                    NumberStepperRow(stringResource(R.string.pbx_ringGroup_delay, member.delaySeconds), member.delaySeconds, 0..120, 5, "number-member-delay") { value -> change(draft.copy(members = draft.members.map { if (it.deviceId == device.id) it.copy(delaySeconds = value) else it })) }
                }
                NumberStepperRow(stringResource(R.string.pbx_ringGroup_rings, member.timeoutSeconds), member.timeoutSeconds, 5..120, 5, "number-member-timeout") { value -> change(draft.copy(members = draft.members.map { if (it.deviceId == device.id) it.copy(timeoutSeconds = value) else it })) }
            }
        }
    }

    section(stringResource(R.string.numbers_forwarding_strategy), null) {
        for (strategy in RingStrategy.entries) {
            Row(Modifier.fillMaxWidth().clickable { change(draft.copy(strategy = strategy)) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = draft.strategy == strategy, onClick = { change(draft.copy(strategy = strategy)) })
                Text(
                    stringResource(
                        when (strategy) {
                            RingStrategy.ALL -> R.string.pbx_strategy_all
                            RingStrategy.SEQUENCE -> R.string.pbx_strategy_sequence
                            RingStrategy.ROUND -> R.string.pbx_strategy_round
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }

    section(stringResource(R.string.numbers_forwarding_unanswered), null) {
        FallbackRow(stringResource(R.string.numbers_forwarding_unanswered), draft.unanswered, null, options, "number-unanswered") { change(draft.copy(unanswered = it ?: draft.unanswered)) }
    }
}

@Composable
private fun MenuForwarding(draft: ChainForwardingDraft, change: (ChainForwardingDraft) -> Unit, options: ChainOptions) {
    SoundChoiceSection(stringResource(R.string.numbers_menu_instruction), draft.greetingSoundId, options, allowNone = true, footer = stringResource(R.string.numbers_menu_instruction_footer)) { change(draft.copy(greetingSoundId = it)) }

    section(stringResource(R.string.numbers_menu_repeats), stringResource(R.string.numbers_menu_repeats_footer)) {
        NumberStepperRow(stringResource(R.string.numbers_menu_repeats_value, draft.repeats), draft.repeats, ChainForwardingDraft.MENU_REPEATS, 1, "number-menu-repeats") { change(draft.copy(repeats = it)) }
        NumberStepperRow(stringResource(R.string.numbers_forwarding_seconds, draft.timeoutSeconds), draft.timeoutSeconds, ChainForwardingDraft.MENU_TIMEOUT, 1, "number-menu-timeout") { change(draft.copy(timeoutSeconds = it)) }
    }

    section(stringResource(R.string.numbers_menu_noChoiceGroup), stringResource(R.string.numbers_menu_defaultKey_footer)) {
        CheckRow(stringResource(R.string.numbers_menu_defaultKey_none), draft.defaultKey == null) { change(draft.copy(defaultKey = null)) }
        for (key in draft.keys) {
            CheckRow(stringResource(R.string.numbers_menu_key_label, key.digit), draft.defaultKey == key.digit) { change(draft.copy(defaultKey = key.digit)) }
        }
        FallbackRow(stringResource(R.string.numbers_menu_noChoice), draft.noChoice, null, options, "number-nochoice") { change(draft.copy(noChoice = it ?: draft.noChoice)) }
    }

    val choices = chainTargetOptions(options)

    section(stringResource(R.string.numbers_menu_keys), if (draft.keys.isEmpty()) stringResource(R.string.numbers_menu_keys_none) else null) {
        draft.keys.forEachIndexed { index, key ->
            if (!key.editable) {
                LinkRow(stringResource(R.string.numbers_menu_key_label, key.digit), stringResource(R.string.numbers_menu_key_portal)) {}
            } else {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.numbers_menu_key_label, key.digit), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { change(draft.copy(keys = draft.keys.filterIndexed { i, _ -> i != index }, defaultKey = draft.defaultKey.takeIf { it != key.digit })) }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.numbers_menu_key_off)) }
                }
                TargetChoiceRow(stringResource(R.string.numbers_menu_key_label, key.digit), key.target.takeIf { it.type != TargetType.UNKNOWN }, choices, stringResource(R.string.pbx_target_unset), "number-key-$index") { picked ->
                    change(draft.copy(keys = draft.keys.mapIndexed { i, k -> if (i == index) k.copy(target = picked ?: PbxTarget()) else k }))
                }
            }
        }

        draft.nextFreeDigit()?.let { digit ->
            ActionRow(stringResource(R.string.pbx_followMe_add), Icons.Filled.Add, Modifier.testTag("number-key-add")) { change(draft.copy(keys = draft.keys + ChainKey(digit, PbxTarget()))) }
        }
    }
}

/** The places a key may send callers to: a colleague, a ring group, or a number outside. */
private fun chainTargetOptions(options: ChainOptions): List<PbxTargetOption> =
    options.devices.map { PbxTargetOption("extension:${it.id}", TargetType.DEVICE, it.id, it.name, it.extensionNumber) } +
        options.groups.map { PbxTargetOption("ring_group:${it.id}", TargetType.RING_GROUP, it.id, it.name) } +
        PbxTargetOption("external", TargetType.EXTERNAL, null, "")

@Composable
private fun RecordingStep(model: AppModel, accountId: String, numberId: String, back: () -> Unit) {
    ChainStepPage<ChainRecordingDraft>(
        model, accountId, numberId, stringResource(R.string.numbers_recording_title), back,
        makeDraft = { ChainRecordingDraft.of(it) },
        rebase = { mine, fresh, baseline -> mine.rebase(fresh, baseline) },
        isValid = { true },
        save = { account, draft, baseline, basedOn ->
            val chainNow = model.pbx.chain(account.id, numberId) ?: basedOn
            val patch = draft.patch(baseline, basedOn)

            when {
                patch == null -> null
                !draft.canSave(chainNow) -> ChainSaveOutcome.Failed(PbxFailure.CostNotAccepted(chainNow.recording.cost))
                else -> model.pbx.saveRecording(account, numberId, patch)
            }
        },
        needsEditableChain = false,
    ) { draft, change, chain ->
        if (!chain.recording.available && !chain.recording.enabled) {
            NoteCard(stringResource(R.string.numbers_recording_unavailable_message), title = stringResource(R.string.numbers_recording_unavailable_title))
            return@ChainStepPage
        }

        section(null, stringResource(R.string.numbers_recording_explanation) + "\n" + stringResource(R.string.numbers_recording_legal)) {
            SwitchRow(stringResource(R.string.numbers_recording_toggle), null, draft.enabled, Modifier.testTag("number-recording-toggle")) { change(draft.copy(enabled = it)) }
        }

        val cost = chain.recording.cost

        if (draft.enabled && cost != null && !chain.recording.billingActive) {
            val price = RecordingPrice.format(cost)

            section(stringResource(R.string.numbers_recording_cost), stringResource(R.string.numbers_recording_consent_explanation)) {
                Text(stringResource(if (cost.vatIncluded) R.string.numbers_recording_price_incl else R.string.numbers_recording_price_excl, price), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                SwitchRow(stringResource(R.string.numbers_recording_consent), null, draft.costAccepted, Modifier.testTag("number-recording-consent")) { change(draft.copy(costAccepted = it)) }
            }
        }

        if (draft.enabled) {
            SoundChoiceSection(stringResource(R.string.numbers_recording_announce), draft.announcementSoundId, chain.options, allowNone = true, footer = stringResource(R.string.numbers_recording_announce_explanation)) { change(draft.copy(announcementSoundId = it)) }
        }
    }
}

// MARK: Fallback

/** A row "Gesloten · Voicemail >" that opens the choice of what happens. Something the portal set up ([Fallback.Other]) is read-only. */
@Composable
fun FallbackRow(title: String, fallback: Fallback?, noneTitle: String?, options: ChainOptions, tag: String, onChange: (Fallback?) -> Unit) {
    var open by remember { mutableStateOf(false) }

    if (fallback is Fallback.Other) {
        LinkRow(title, stringResource(R.string.numbers_portalOnly) + ". " + stringResource(R.string.numbers_portalOnly_hint)) {}
        return
    }

    LinkRow(title, fallbackText(fallback, options, noneTitle ?: stringResource(R.string.pbx_target_none)), onClick = { open = true })

    if (open) {
        var number by remember { mutableStateOf((fallback as? Fallback.Forward)?.number.orEmpty()) }
        val cleaned = number.filter { it.isDigit() || it == '+' }

        fun pick(value: Fallback?) {
            open = false
            onChange(value)
        }

        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag(tag)) {
                    if (noneTitle != null) ChoiceLine(noneTitle, fallback == null) { pick(null) }

                    DialogHeading(stringResource(R.string.numbers_fallback_group_voicemail))
                    ChoiceLine(stringResource(R.string.numbers_fallback_voicemail_shared), fallback is Fallback.Voicemail && !fallback.ofDevice) {
                        // The shared box the chain already points at stays the same box; a fresh choice lets the server pick it.
                        pick(if (fallback is Fallback.Voicemail && !fallback.ofDevice) fallback else Fallback.Voicemail())
                    }
                    for (device in options.devices) {
                        ChoiceLine(stringResource(R.string.numbers_fallback_voicemailOf, device.name), fallback is Fallback.Voicemail && fallback.boxId == device.id) { pick(Fallback.Voicemail(device.id, true)) }
                    }

                    DialogHeading(stringResource(R.string.numbers_fallback_group_message))
                    if (options.sounds.isEmpty()) Text(stringResource(R.string.numbers_sound_empty_title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (sound in options.sounds) {
                        ChoiceLine(sound.name, fallback is Fallback.Message && fallback.soundId == sound.id) { pick(Fallback.Message(sound.id)) }
                    }

                    DialogHeading(stringResource(R.string.numbers_fallback_group_device))
                    for (device in options.devices) {
                        ChoiceLine(listOfNotNull(device.name, device.extensionNumber).joinToString(" · "), fallback is Fallback.Device && fallback.deviceId == device.id) { pick(Fallback.Device(device.id)) }
                    }

                    DialogHeading(stringResource(R.string.numbers_fallback_group_forward))
                    OutlinedTextField(
                        value = number,
                        onValueChange = { number = it },
                        label = { Text(stringResource(R.string.numbers_fallback_forward_placeholder)) },
                        supportingText = { Text(stringResource(R.string.numbers_fallback_forward_footer)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { pick(Fallback.Forward(cleaned)) }, enabled = cleaned.length >= 3) { Text(stringResource(R.string.numbers_fallback_forward_use)) }

                    ChoiceLine(stringResource(R.string.numbers_fallback_hangup), fallback == Fallback.Hangup) { pick(Fallback.Hangup) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun DialogHeading(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}
