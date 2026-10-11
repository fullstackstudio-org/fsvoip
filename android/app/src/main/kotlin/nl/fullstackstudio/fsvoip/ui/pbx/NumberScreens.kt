// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.pbx

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.ChainForwarding
import nl.fullstackstudio.fsvoip.core.ChainKey
import nl.fullstackstudio.fsvoip.core.ChainMode
import nl.fullstackstudio.fsvoip.core.ChainOptions
import nl.fullstackstudio.fsvoip.core.Fallback
import nl.fullstackstudio.fsvoip.core.NumberChain
import nl.fullstackstudio.fsvoip.core.PbxNumberEntry
import nl.fullstackstudio.fsvoip.core.PbxTarget
import nl.fullstackstudio.fsvoip.core.RecordingCost
import nl.fullstackstudio.fsvoip.core.RingStrategy
import nl.fullstackstudio.fsvoip.core.TargetType

// MARK: - The list of numbers

/** "Nummers": every number of the centrale with a one-line summary of its chain. */
@Composable
fun NumbersListScreen(section: PbxSectionModel, nav: PbxNav) {
    val state by section.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val words = rememberNumberWords()
    val page = state.numbers

    LaunchedEffect(Unit) { section.loadIfNeeded(PbxPart.NUMBERS) }

    PbxPage(title = stringResource(R.string.adm_numbers_title), onBack = { nav.pop() }, onRefresh = { section.refresh(PbxPart.NUMBERS) }, testTag = "numbers-list") {
        SectionNoticeCards(state)

        if (page != null) {
            if (page.numbers.isEmpty()) {
                EmptyState(Icons.Filled.Dialpad, stringResource(R.string.adm_numbers_empty_title), message = stringResource(R.string.adm_numbers_empty_message))
            } else {
                SettingsGroup(footer = stringResource(R.string.adm_numbers_list_footer)) {
                    GroupItems(page.numbers) { _, entry ->
                        SettingsRow(
                            entry.displayName,
                            icon = if (entry.mode == ChainMode.ADVANCED) Icons.Filled.Tune else Icons.Filled.Dialpad,
                            subtitle = words.listLine(entry),
                            value = if (entry.name?.isNotBlank() == true) PbxVocabulary.formatNumber(entry.number) else null,
                            onClick = { nav.push(PbxRoute.Number(entry.id, entry.displayName)) },
                            testTag = "number-entry",
                        )
                    }
                }
            }
        } else if (state.isLoading) {
            LoadingBlock()
        } else if (state.loadFailure != null || state.isOutdated) {
            EmptyState(Icons.Filled.WifiOff, stringResource(R.string.adm_numbers_loadFailed), actionTitle = stringResource(R.string.action_retry), onAction = { scope.launch { section.refresh(PbxPart.NUMBERS) } })
        }
    }
}

// MARK: - One number

/**
 * One number as a chain: Naam, Openingstijden, Welkomstbericht, Doorschakeling and Gespreksopname, each opening its own page. An
 * `advanced` number shows what it does in words; only name and recording can change here.
 */
@Composable
fun NumberScreen(section: PbxSectionModel, numberId: String, placeholderTitle: String?, nav: PbxNav) {
    val state by section.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val words = rememberNumberWords()
    val chain = state.chains[numberId]

    LaunchedEffect(numberId) {
        section.loadChainIfNeeded(numberId)
        section.loadSounds()
    }

    PbxPage(
        title = chain?.displayName ?: placeholderTitle ?: "",
        onBack = { nav.pop() },
        onRefresh = { section.loadChain(numberId) },
        testTag = "number-view",
    ) {
        SectionNoticeCards(state)

        if (chain != null) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val spoken = chain.number.map { it.toString() }.joinToString(" ")
                Text(
                    PbxVocabulary.formatNumber(chain.number),
                    fontSize = 28.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.semantics { contentDescription = spoken },
                )
                PbxSyncBadge(chain.sync)
            }

            fun open(step: NumberStepKind) = nav.push(PbxRoute.NumberStep(numberId, step))

            if (chain.isEditable) {
                SettingsGroup {
                    NameRow(chain, ::open)
                    GroupDivider()
                    SettingsRow(stringResource(R.string.adm_numbers_hours_title), icon = Icons.Filled.CalendarMonth, subtitle = words.shared(chain.hours?.sharedWith), value = words.hours(chain), onClick = { open(NumberStepKind.HOURS) }, testTag = "number-row-hours")
                    GroupDivider()
                    SettingsRow(stringResource(R.string.adm_numbers_welcome_title), icon = Icons.Filled.VolumeUp, subtitle = words.shared(chain.welcome?.sharedWith), value = words.welcome(chain), onClick = { open(NumberStepKind.WELCOME) }, testTag = "number-row-welcome")
                    GroupDivider()
                    SettingsRow(stringResource(R.string.adm_numbers_forwarding_title), icon = Icons.Filled.Call, subtitle = words.forwardingDetail(chain), value = words.forwarding(chain), onClick = { open(NumberStepKind.FORWARDING) }, testTag = "number-row-forwarding")
                    GroupDivider()
                    RecordingRow(chain, words, ::open)
                }
            } else {
                SettingsGroup {
                    NameRow(chain, ::open)
                    GroupDivider()
                    RecordingRow(chain, words, ::open)
                }

                NumberAdvancedBlock(section, state, chain)
            }
        } else {
            val failure = state.chainFailures[numberId]

            if (failure != null) {
                EmptyState(Icons.Filled.ErrorOutline, failure.message(rememberPbxText()), actionTitle = stringResource(R.string.action_retry), onAction = { scope.launch { section.loadChain(numberId) } })
            } else {
                LoadingBlock()
            }
        }
    }
}

@Composable
private fun NameRow(chain: NumberChain, open: (NumberStepKind) -> Unit) {
    SettingsRow(
        stringResource(R.string.adm_numbers_name_title),
        icon = Icons.Filled.Label,
        value = chain.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.adm_numbers_name_none),
        onClick = { open(NumberStepKind.NAME) },
        testTag = "number-row-name",
    )
}

@Composable
private fun RecordingRow(chain: NumberChain, words: NumberWords, open: (NumberStepKind) -> Unit) {
    SettingsRow(stringResource(R.string.adm_numbers_recording_title), icon = Icons.Filled.Mic, value = words.recording(chain), onClick = { open(NumberStepKind.RECORDING) }, testTag = "number-row-recording")
}

/**
 * A number that is set up in more detail than the chain can show (a queue, a sub menu, ...): what it does in words, the flow when the
 * overview has it, and the way to the portal. Name and recording stay editable in the app.
 */
@Composable
private fun NumberAdvancedBlock(section: PbxSectionModel, state: PbxSectionState, chain: NumberChain) {
    val context = LocalContext.current
    val flow = state.overview?.numbers?.firstOrNull { it.id == chain.id }?.flow
    val summary = chain.advancedSummary ?: emptyList()

    LaunchedEffect(Unit) { section.loadIfNeeded(PbxPart.OVERVIEW) }

    NoticeCard(Icons.Filled.Tune, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.adm_numbers_advanced_title), message = stringResource(R.string.adm_numbers_advanced_message), testTag = "number-advanced")

    SettingsGroup(title = stringResource(R.string.adm_numbers_advanced_summary)) {
        if (summary.isEmpty()) {
            Text(stringResource(R.string.adm_numbers_advanced_noSummary), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }

        GroupItems(summary) { _, line ->
            Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }

    if (flow != null) {
        SettingsGroup(title = stringResource(R.string.adm_numbers_advanced_flow)) {
            Column(Modifier.padding(16.dp)) { FlowView(flow) }
        }
    }

    SettingsGroup {
        SettingsRow(
            stringResource(R.string.adm_numbers_advanced_portal),
            icon = Icons.Filled.Language,
            subtitle = stringResource(R.string.adm_numbers_advanced_portal_hint),
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PORTAL_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            testTag = "number-portal-link",
        )
    }
}

const val PORTAL_URL = "https://fullstackstudio.nl/portal/voip"

// MARK: - A step of a number

/** The state of a step form: the draft, the baseline it started from, and the message of the last save. */
class ChainForm<D : ChainDraft<D>>(initial: D, private val make: (NumberChain) -> D) {
    var draft by mutableStateOf(initial)
    var baseline by mutableStateOf(initial)
    var message by mutableStateOf<ChainEditorMessage?>(null)

    val isDirty: Boolean get() = draft != baseline

    /** What a form does with the answer of a save: `true` = the form can close. */
    fun apply(outcome: ChainSaveOutcome, fresh: NumberChain?): Boolean {
        when (outcome) {
            ChainSaveOutcome.Saved, ChainSaveOutcome.Unchanged -> {
                message = null
                return true
            }
            ChainSaveOutcome.Stale -> {
                if (fresh != null) {
                    val (rebasedDraft, newBaseline) = draft.rebaseOnto(fresh, baseline, make)
                    draft = rebasedDraft
                    baseline = newBaseline
                }

                message = ChainEditorMessage.Stale
            }
            is ChainSaveOutcome.Failed -> message = ChainEditorMessage.Failure(outcome.failure)
            is ChainSaveOutcome.CostRequired -> message = ChainEditorMessage.Failure(PbxFailure.CostRequired(outcome.cost))
        }

        return false
    }
}

@Composable
private fun <D : ChainDraft<D>> rememberChainForm(chain: NumberChain, make: (NumberChain) -> D): ChainForm<D> =
    remember(chain.id) { ChainForm(make(chain), make) }

/** The sounds a picker can offer: those the chain names, plus the others of the PBX. */
private fun soundOptions(chain: NumberChain, state: PbxSectionState): ChainOptions {
    val known = chain.options.sounds.map { it.id }.toSet()
    val extra = state.sounds.filter { it.id !in known }.map { ChainOptions.Named(it.id, it.name) }

    return chain.options.copy(sounds = chain.options.sounds + extra)
}

@Composable
fun NumberStepScreen(section: PbxSectionModel, numberId: String, step: NumberStepKind, nav: PbxNav) {
    val state by section.state.collectAsStateWithLifecycle()
    val chain = state.chains[numberId]

    LaunchedEffect(numberId) {
        section.loadChainIfNeeded(numberId)
        section.loadSounds()
    }

    if (chain == null) {
        if (state.chainFailures[numberId] != null) LaunchedEffect(numberId) { nav.pop() } else PbxPage(title = "", onBack = { nav.pop() }) { LoadingBlock() }
        return
    }

    when (step) {
        NumberStepKind.NAME -> NumberNameEditor(section, state, chain, nav)
        NumberStepKind.HOURS -> NumberHoursEditor(section, state, chain, nav)
        NumberStepKind.WELCOME -> NumberWelcomeEditor(section, state, chain, nav)
        NumberStepKind.FORWARDING -> NumberForwardingEditor(section, state, chain, nav)
        NumberStepKind.RECORDING -> NumberRecordingEditor(section, state, chain, nav)
    }
}

/** "Ook voor …": a part of the chain that other numbers use as well (it is not copied: a change counts there too). */
@Composable
private fun SharedNote(names: List<String>) {
    if (names.isNotEmpty()) {
        NoticeCard(Icons.Filled.Link, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.adm_numbers_shared_title, names.joinToString(", ")), message = stringResource(R.string.adm_numbers_shared_message))
    }
}

// MARK: Name

@Composable
private fun NumberNameEditor(section: PbxSectionModel, state: PbxSectionState, chain: NumberChain, nav: PbxNav) {
    val form = rememberChainForm(chain, ::NumberNameDraft)
    val scope = rememberCoroutineScope()

    fun save() {
        scope.launch {
            val current = section.state.value.chains[chain.id] ?: return@launch
            val outcome = section.saveChainStep(chain.id, form.draft.step(form.baseline, current))

            if (form.apply(outcome, section.state.value.chains[chain.id])) nav.pop()
        }
    }

    EditorPage(state, stringResource(R.string.adm_numbers_name_title), form.isDirty, onBack = { nav.pop() }, onSave = { save() }, message = form.message, testTag = "number-step-name") {
        SettingsGroup(footer = stringResource(R.string.adm_numbers_name_footer)) {
            FieldRow(form.draft.name, { form.draft = form.draft.copy(name = it) }, PbxVocabulary.formatNumber(chain.number), testTag = "number-name-field")
        }
    }
}

// MARK: Welcome

@Composable
private fun NumberWelcomeEditor(section: PbxSectionModel, state: PbxSectionState, chain: NumberChain, nav: PbxNav) {
    val form = rememberChainForm(chain, ::NumberWelcomeDraft)
    val scope = rememberCoroutineScope()
    val live = state.chains[chain.id] ?: chain
    val options = soundOptions(live, state)

    fun save() {
        scope.launch {
            val current = section.state.value.chains[chain.id] ?: return@launch
            val outcome = section.saveChainStep(chain.id, form.draft.step(form.baseline, current))

            if (form.apply(outcome, section.state.value.chains[chain.id])) nav.pop()
        }
    }

    EditorPage(state, stringResource(R.string.adm_numbers_welcome_title), form.isDirty, onBack = { nav.pop() }, onSave = { save() }, canSave = form.draft.canSave, message = form.message, testTag = "number-step-welcome") {
        SharedNote(live.welcome?.sharedWith ?: emptyList())

        SettingsGroup {
            ToggleRow(stringResource(R.string.adm_numbers_welcome_toggle), form.draft.enabled, { form.draft = form.draft.copy(enabled = it) }, explanation = stringResource(R.string.adm_numbers_welcome_explanation), testTag = "welcome-toggle")
        }

        if (form.draft.enabled) {
            SettingsGroup(title = stringResource(R.string.adm_numbers_welcome_sound), footer = if (form.draft.soundId == null) stringResource(R.string.adm_numbers_welcome_soundNeeded) else null) {
                SoundRow(stringResource(R.string.adm_numbers_welcome_sound), form.draft.soundId, options, { form.draft = form.draft.copy(soundId = it) })
            }
        }
    }
}

// MARK: Hours

private enum class HoursTab { WEEK, EXCEPTIONS }

@Composable
private fun NumberHoursEditor(section: PbxSectionModel, state: PbxSectionState, chain: NumberChain, nav: PbxNav) {
    val form = rememberChainForm(chain, ::NumberHoursDraft)
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(HoursTab.WEEK) }
    var editingDay by remember { mutableStateOf<Int?>(null) }
    val live = state.chains[chain.id] ?: chain
    val options = soundOptions(live, state)

    fun save() {
        scope.launch {
            val current = section.state.value.chains[chain.id] ?: return@launch
            val outcome = section.saveChainStep(chain.id, form.draft.step(form.baseline, current))

            if (form.apply(outcome, section.state.value.chains[chain.id])) nav.pop()
        }
    }

    EditorPage(state, stringResource(R.string.adm_numbers_hours_title), form.isDirty, onBack = { nav.pop() }, onSave = { save() }, canSave = form.draft.isWellFormed, message = form.message, testTag = "number-step-hours") {
        SegmentedBar(
            listOf(HoursTab.WEEK to stringResource(R.string.adm_numbers_hours_tab_week), HoursTab.EXCEPTIONS to stringResource(R.string.adm_numbers_hours_tab_exceptions)),
            tab,
            { tab = it },
            Modifier.padding(bottom = 16.dp),
            testTag = "hours-tabs",
        )

        SharedNote(live.hours?.sharedWith ?: emptyList())

        when (tab) {
            HoursTab.WEEK -> HoursWeekTab(form, options, readOnly = state.isReadOnly, onDay = { editingDay = it })
            HoursTab.EXCEPTIONS -> HoursExceptionsTab(form, options)
        }
    }

    editingDay?.let { day ->
        DaySlotsPage(
            day = day,
            slots = form.draft.week.slots(day),
            enabled = !state.isReadOnly,
            onChange = { slots -> form.draft = form.draft.copy(week = form.draft.week.with(day, slots)) },
            onBack = { editingDay = null },
        )
    }
}

@Composable
private fun HoursWeekTab(form: ChainForm<NumberHoursDraft>, options: ChainOptions, readOnly: Boolean, onDay: (Int) -> Unit) {
    val draft = form.draft

    SettingsGroup {
        ToggleRow(stringResource(R.string.adm_numbers_hours_toggle), draft.enabled, { form.draft = draft.copy(enabled = it) }, explanation = stringResource(R.string.adm_numbers_hours_explanation), testTag = "hours-toggle")
    }

    if (draft.enabled) {
        ChipRow {
            for ((preset, label) in listOf(
                WeekPlan.Preset.OFFICE to stringResource(R.string.adm_numbers_hours_preset_office),
                WeekPlan.Preset.ALWAYS to stringResource(R.string.adm_numbers_hours_preset_always),
                WeekPlan.Preset.CLOSED to stringResource(R.string.adm_numbers_hours_preset_closed),
            )) {
                PresetChip(label, draft.week.matchingPreset == preset, { form.draft = draft.copy(week = WeekPlan.preset(preset)) }, testTag = "hours-preset-${preset.name.lowercase()}")
            }
        }

        SettingsGroup(footer = stringResource(R.string.adm_numbers_hours_bars_footer)) {
            DayBars(draft.week, onSelect = onDay, enabled = !readOnly)
        }

        SettingsGroup(title = stringResource(R.string.adm_numbers_hours_closedSettings)) {
            FallbackRow(stringResource(R.string.adm_numbers_hours_closed), draft.closed, options, { form.draft = draft.copy(closed = it) }, icon = Icons.Filled.PhoneDisabled)
        }
    } else {
        Text(stringResource(R.string.adm_numbers_hours_off_message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

/** "Uitzonderingen": national holidays, own closed days, and what happens on such a day. */
@Composable
private fun HoursExceptionsTab(form: ChainForm<NumberHoursDraft>, options: ChainOptions) {
    val draft = form.draft

    if (!draft.enabled) {
        EmptyState(Icons.Filled.CalendarMonth, stringResource(R.string.adm_numbers_hours_exceptions_off_title), message = stringResource(R.string.adm_numbers_hours_exceptions_off_message))
        return
    }

    val today = remember { LocalDate.now() }
    var from by remember { mutableStateOf(today) }
    var until by remember { mutableStateOf(today) }
    var reason by remember { mutableStateOf("") }
    var tooMany by remember { mutableStateOf(false) }
    val defaultName = stringResource(R.string.adm_pbx_closed_defaultName)
    val locale = remember { java.util.Locale.getDefault() }
    val formatter = remember { java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(locale) }

    fun display(date: String): String = runCatching { LocalDate.parse(date).format(formatter) }.getOrDefault(date)

    SettingsGroup {
        ToggleRow(stringResource(R.string.adm_numbers_hours_national), draft.national, { form.draft = draft.copy(national = it) }, explanation = stringResource(R.string.adm_numbers_hours_national_explanation), testTag = "hours-national")
        GroupDivider()
        OptionalFallbackRow(stringResource(R.string.adm_numbers_hours_onHoliday), draft.holiday, stringResource(R.string.adm_numbers_hours_onHoliday_sameAsClosed), options, { form.draft = draft.copy(holiday = it) }, icon = Icons.Filled.CalendarMonth)
    }

    SettingsGroup(title = stringResource(R.string.adm_pbx_closed_header), footer = stringResource(R.string.adm_pbx_closed_limit, NumberHoursDraft.OWN_DATE_LIMIT)) {
        DateRow(stringResource(R.string.adm_pbx_closed_from), from, { from = it; if (until.isBefore(it)) until = it }, testTag = "hours-closed-from")
        GroupDivider()
        DateRow(stringResource(R.string.adm_pbx_closed_until), until, { until = it }, min = from, testTag = "hours-closed-until")
        GroupDivider()
        FieldRow(reason, { reason = it }, stringResource(R.string.adm_pbx_closed_reason), testTag = "hours-closed-reason")
        GroupDivider()
        SettingsRow(
            stringResource(R.string.adm_numbers_hours_addClosed),
            icon = Icons.Filled.Add,
            chevron = false,
            onClick = {
                val dates = form.draft.adding(TemporaryClosure.dates(from, until), reason, defaultName)

                if (dates == null) {
                    tooMany = true
                } else {
                    tooMany = false
                    form.draft = form.draft.copy(dates = dates)
                    reason = ""
                }
            },
            testTag = "hours-add-closed",
        )
    }

    if (tooMany) {
        NoticeCard(Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.error, stringResource(R.string.adm_numbers_hours_tooMany, NumberHoursDraft.OWN_DATE_LIMIT), testTag = "hours-too-many")
    }

    if (draft.dates.isNotEmpty()) {
        SettingsGroup(title = stringResource(R.string.adm_pbx_closed_current)) {
            GroupItems(draft.dates) { _, date ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(display(date.date), style = MaterialTheme.typography.bodyLarge)
                        Text(date.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    val label = stringResource(R.string.adm_pbx_closed_reopen_label, display(date.date))
                    TextButton(
                        onClick = { form.draft = form.draft.copy(dates = form.draft.dates.filter { it.date != date.date }) },
                        modifier = Modifier.semantics { contentDescription = label },
                    ) {
                        Text(stringResource(R.string.adm_pbx_closed_reopen), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// MARK: Forwarding

@Composable
private fun NumberForwardingEditor(section: PbxSectionModel, state: PbxSectionState, chain: NumberChain, nav: PbxNav) {
    val form = rememberChainForm(chain, ::NumberForwardingDraft)
    val scope = rememberCoroutineScope()
    val live = state.chains[chain.id] ?: chain
    val options = soundOptions(live, state)
    val draft = form.draft
    val baseline = form.baseline
    val shared = when (val f = live.forwarding) {
        is ChainForwarding.Standard -> f.value.sharedWith
        is ChainForwarding.Menu -> f.value.sharedWith
        else -> emptyList()
    }

    fun save() {
        scope.launch {
            val current = section.state.value.chains[chain.id] ?: return@launch
            val outcome = section.saveChainStep(chain.id, form.draft.step(form.baseline, current))

            if (form.apply(outcome, section.state.value.chains[chain.id])) nav.pop()
        }
    }

    EditorPage(state, stringResource(R.string.adm_numbers_forwarding_title), form.isDirty, onBack = { nav.pop() }, onSave = { save() }, canSave = draft.canSave, message = form.message, testTag = "number-step-forwarding") {
        Text(stringResource(R.string.adm_numbers_forwarding_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))

        SegmentedBar(
            listOf(NumberForwardingDraft.Kind.STANDARD to stringResource(R.string.adm_numbers_forwarding_standard), NumberForwardingDraft.Kind.MENU to stringResource(R.string.adm_numbers_forwarding_menu)),
            draft.kind,
            { form.draft = draft.copy(kind = it) },
            Modifier.padding(bottom = 16.dp),
            testTag = "forwarding-kind",
        )

        if (draft.kind != baseline.kind) {
            Text(
                stringResource(if (draft.kind == NumberForwardingDraft.Kind.MENU) R.string.adm_numbers_forwarding_switch_toMenu else R.string.adm_numbers_forwarding_switch_toStandard),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        SharedNote(if (draft.kind == baseline.kind) shared else emptyList())

        when (draft.kind) {
            NumberForwardingDraft.Kind.STANDARD -> StandardForwarding(form, live, options)
            NumberForwardingDraft.Kind.MENU -> MenuForwarding(form, options)
        }
    }
}

@Composable
private fun StandardForwarding(form: ChainForm<NumberForwardingDraft>, chain: NumberChain, options: ChainOptions) {
    val draft = form.draft
    val vocabulary = rememberPbxVocabulary()
    val text = rememberPbxText()
    var logicOpen by remember { mutableStateOf(false) }
    val deviceIds = chain.options.devices.map { it.id }.toSet()
    val logicValue = vocabulary.strategy(draft.strategy).let { strategy -> draft.ringSeconds?.let { "$strategy · ${text.get(R.string.adm_numbers_forwarding_seconds, it)}" } ?: strategy }

    SettingsGroup(title = stringResource(R.string.adm_numbers_forwarding_members), footer = if (draft.members.isEmpty()) stringResource(R.string.adm_numbers_forwarding_members_none) else null) {
        GroupItems(chain.options.devices) { _, device ->
            ToggleRow(
                device.name,
                draft.isMember(device.id),
                { form.draft = form.draft.withMember(device.id, it) },
                explanation = device.extensionNumber?.let { stringResource(R.string.adm_pbx_account_extension, it) },
                testTag = "member-${device.id}",
            )
        }

        val others = draft.membersNotIn(deviceIds)

        for (member in others) {
            GroupDivider()
            SettingsRow(
                stringResource(R.string.adm_numbers_forwarding_members_other),
                icon = Icons.Filled.Phone,
                subtitle = stringResource(R.string.adm_numbers_forwarding_members_other_timing, member.delaySeconds, member.timeoutSeconds),
                chevron = false,
                testTag = "member-other-${member.deviceId}",
            )
        }
    }

    SettingsGroup(title = stringResource(R.string.adm_numbers_forwarding_settings)) {
        SettingsRow(stringResource(R.string.adm_numbers_forwarding_logic), icon = Icons.Filled.AltRoute, value = logicValue, onClick = { logicOpen = true }, testTag = "forwarding-logic")
        GroupDivider()
        FallbackRow(stringResource(R.string.adm_numbers_forwarding_unanswered), draft.unanswered, options, { form.draft = form.draft.copy(unanswered = it) }, icon = Icons.Filled.PhoneDisabled)
    }

    if (logicOpen) {
        PbxSubPage(title = stringResource(R.string.adm_numbers_forwarding_logic), onBack = { logicOpen = false }, testTag = "forwarding-logic-page") {
            val current = form.draft

            SettingsGroup(title = stringResource(R.string.adm_numbers_forwarding_strategy)) {
                GroupItems(listOf(RingStrategy.ALL, RingStrategy.SEQUENCE, RingStrategy.ROUND)) { _, strategy ->
                    ChoiceRow(vocabulary.strategy(strategy), selected = current.strategy == strategy, subtitle = vocabulary.strategyHint(strategy), onClick = { form.draft = form.draft.copy(strategy = strategy) }, testTag = "strategy-${strategy.wire}")
                }
            }

            SettingsGroup(
                title = stringResource(R.string.adm_numbers_forwarding_ringTime),
                footer = if (current.ringSeconds == null && current.members.isNotEmpty()) stringResource(R.string.adm_numbers_forwarding_ringTime_mixed) else null,
            ) {
                StepperRow(
                    stringResource(R.string.adm_numbers_forwarding_seconds, current.ringSeconds ?: 25),
                    current.ringSeconds ?: 25,
                    NumberForwardingDraft.ringSecondsRange,
                    5,
                    { form.draft = form.draft.withRingSeconds(it) },
                    enabled = current.members.isNotEmpty(),
                    testTag = "forwarding-ringtime",
                )
            }
        }
    }
}

/** "Keuzemenu": the instruction callers hear, how often it repeats, what counts when nothing is pressed, and the keys 1-9 and 0. */
@Composable
private fun MenuForwarding(form: ChainForm<NumberForwardingDraft>, options: ChainOptions) {
    val draft = form.draft
    val words = rememberNumberWords()
    var defaultKeyOpen by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf<String?>(null) }

    SettingsGroup(title = stringResource(R.string.adm_numbers_menu_instruction), footer = stringResource(R.string.adm_numbers_menu_instruction_footer)) {
        SoundRow(stringResource(R.string.adm_numbers_menu_instruction), draft.greetingSoundId, options, { form.draft = form.draft.copy(greetingSoundId = it) })
    }

    SettingsGroup(title = stringResource(R.string.adm_numbers_menu_repeats), footer = stringResource(R.string.adm_numbers_menu_repeats_footer)) {
        StepperRow(stringResource(R.string.adm_numbers_menu_repeats_value, draft.repeats), draft.repeats, NumberForwardingDraft.repeatsRange, 1, { form.draft = form.draft.copy(repeats = it) }, testTag = "menu-repeats")
    }

    SettingsGroup(title = stringResource(R.string.adm_numbers_menu_noChoiceGroup)) {
        SettingsRow(stringResource(R.string.adm_numbers_menu_defaultKey), icon = Icons.Filled.TouchApp, value = draft.defaultKey ?: stringResource(R.string.adm_numbers_menu_defaultKey_none), onClick = { defaultKeyOpen = true }, testTag = "menu-default-key")

        if (draft.defaultKey == null) {
            GroupDivider()
            FallbackRow(stringResource(R.string.adm_numbers_menu_noChoice), draft.noChoice, options, { form.draft = form.draft.copy(noChoice = it) }, icon = Icons.Filled.Schedule)
        }
    }

    SettingsGroup(title = stringResource(R.string.adm_numbers_menu_keys), footer = if (draft.keys.isEmpty()) stringResource(R.string.adm_numbers_menu_keys_none) else null) {
        GroupItems(NumberForwardingDraft.menuDigits) { _, digit ->
            val key = draft.key(digit)
            val label = stringResource(R.string.adm_numbers_menu_key_label, digit)
            val value = key?.let { words.target(it.target, options) } ?: stringResource(R.string.adm_numbers_menu_key_off)

            Row(
                Modifier.fillMaxWidth().clickable { editingKey = digit }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("menu-key-$digit").semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                androidx.compose.foundation.layout.Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape), contentAlignment = Alignment.Center) {
                    Text(digit, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                }
                Text(value, color = if (key == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
            }
        }
    }

    if (defaultKeyOpen) {
        PbxSubPage(title = stringResource(R.string.adm_numbers_menu_defaultKey), onBack = { defaultKeyOpen = false }, testTag = "default-key-page") {
            SettingsGroup(footer = stringResource(R.string.adm_numbers_menu_defaultKey_footer)) {
                ChoiceRow(stringResource(R.string.adm_numbers_menu_defaultKey_none), selected = form.draft.defaultKey == null, onClick = { form.draft = form.draft.copy(defaultKey = null); defaultKeyOpen = false })

                for (key in form.draft.keys) {
                    GroupDivider()
                    ChoiceRow(
                        stringResource(R.string.adm_numbers_menu_key_label, key.digit),
                        selected = form.draft.defaultKey == key.digit,
                        subtitle = words.target(key.target, options),
                        onClick = { form.draft = form.draft.copy(defaultKey = key.digit); defaultKeyOpen = false },
                    )
                }
            }
        }
    }

    editingKey?.let { digit ->
        MenuKeyPage(digit, form.draft.key(digit), options, onChoose = { target -> form.draft = form.draft.withKey(digit, target); editingKey = null }, onBack = { editingKey = null })
    }
}

/**
 * One key of the menu: off, a phone, a ring group, a voicemail box, or an outside number. A key that leads somewhere the app cannot
 * show (set up in the portal) is shown, not changed.
 */
@Composable
private fun MenuKeyPage(digit: String, key: ChainKey?, options: ChainOptions, onChoose: (PbxTarget?) -> Unit, onBack: () -> Unit) {
    val words = rememberNumberWords()
    var number by remember { mutableStateOf(if (key?.target?.type == TargetType.EXTERNAL) key.target.number ?: "" else "") }
    val cleaned = number.filter { it.isDigit() || it == '+' }

    PbxSubPage(title = stringResource(R.string.adm_numbers_menu_key_label, digit), onBack = onBack, testTag = "menu-key-page") {
        if (key != null && !key.editable) {
            NoticeCard(Icons.Filled.Lock, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.adm_numbers_portalOnly), message = stringResource(R.string.adm_numbers_menu_key_portal), testTag = "menu-key-readonly")

            SettingsGroup {
                SettingsRow(words.target(key.target, options), chevron = false)
            }
        } else {
            SettingsGroup {
                ChoiceRow(stringResource(R.string.adm_numbers_menu_key_off), selected = key == null, onClick = { onChoose(null) }, testTag = "menu-key-choice-off")
            }

            SettingsGroup(title = stringResource(R.string.adm_numbers_fallback_group_device)) {
                GroupItems(options.devices) { _, device ->
                    val target = PbxTarget.obj(TargetType.DEVICE, device.id)
                    ChoiceRow(device.name, selected = key?.target == target, onClick = { onChoose(target) })
                }
            }

            if (options.groups.isNotEmpty()) {
                SettingsGroup(title = stringResource(R.string.adm_pbx_type_ringGroup)) {
                    GroupItems(options.groups) { _, group ->
                        val target = PbxTarget.obj(TargetType.RING_GROUP, group.id)
                        ChoiceRow(group.name, selected = key?.target == target, onClick = { onChoose(target) })
                    }
                }
            }

            SettingsGroup(title = stringResource(R.string.adm_numbers_fallback_group_voicemail)) {
                GroupItems(options.devices) { _, device ->
                    val target = PbxTarget.obj(TargetType.VOICEMAIL, device.id)
                    ChoiceRow(stringResource(R.string.adm_numbers_fallback_voicemailOf, device.name), selected = key?.target == target, onClick = { onChoose(target) })
                }
            }

            SettingsGroup(title = stringResource(R.string.adm_numbers_fallback_group_forward), footer = stringResource(R.string.adm_numbers_fallback_forward_footer)) {
                FieldRow(number, { number = it }, stringResource(R.string.adm_numbers_fallback_forward_placeholder), keyboard = KeyboardType.Phone, testTag = "menu-key-number")
                GroupDivider()
                SettingsRow(stringResource(R.string.adm_numbers_fallback_forward_use), chevron = false, enabled = cleaned.length >= 3, onClick = { onChoose(PbxTarget.external(cleaned)) }, testTag = "menu-key-use")
            }
        }
    }
}

// MARK: Recording

@Composable
private fun NumberRecordingEditor(section: PbxSectionModel, state: PbxSectionState, chain: NumberChain, nav: PbxNav) {
    val form = rememberChainForm(chain, ::NumberRecordingDraft)
    val scope = rememberCoroutineScope()
    val locale = remember { java.util.Locale.getDefault() }
    val text = rememberPbxText()
    val live = state.chains[chain.id] ?: chain
    val options = soundOptions(live, state)
    val recording = live.recording
    // The price the server asked consent for (a 422 `cost_not_accepted`), when it differs from the chain's.
    var serverCost by remember { mutableStateOf<RecordingCost?>(null) }
    val draft = form.draft
    val cost = serverCost ?: recording.cost
    val needsConsent = (draft.enabled && serverCost != null) || draft.needsConsent(recording)
    val canSave = !needsConsent || draft.costAccepted

    fun save() {
        scope.launch {
            val current = section.state.value.chains[chain.id] ?: return@launch
            val outcome = section.saveRecording(chain.id, form.draft.patch(form.baseline, current))

            if (outcome is ChainSaveOutcome.CostRequired) {
                // The server wants consent this app did not ask for (a price came in): show it and let the user agree.
                serverCost = outcome.cost ?: current.recording.cost
                form.draft = form.draft.copy(costAccepted = false)
                form.message = null
                return@launch
            }

            if (form.apply(outcome, section.state.value.chains[chain.id])) nav.pop()
        }
    }

    EditorPage(
        state,
        stringResource(R.string.adm_numbers_recording_title),
        form.draft.differsFrom(form.baseline),
        onBack = { nav.pop() },
        onSave = { save() },
        canSave = canSave,
        message = form.message,
        testTag = "number-step-recording",
    ) {
        if (!recording.available && !recording.enabled) {
            EmptyState(Icons.Filled.MicOff, stringResource(R.string.adm_numbers_recording_unavailable_title), message = stringResource(R.string.adm_numbers_recording_unavailable_message))
        } else {
            SettingsGroup(footer = stringResource(R.string.adm_numbers_recording_legal)) {
                ToggleRow(stringResource(R.string.adm_numbers_recording_toggle), draft.enabled, { form.draft = draft.copy(enabled = it) }, explanation = stringResource(R.string.adm_numbers_recording_explanation), testTag = "recording-toggle")
            }

            if (draft.enabled) {
                if (needsConsent && cost != null) {
                    SettingsGroup(title = stringResource(R.string.adm_numbers_recording_cost)) {
                        Text(RecordingPrice.text(cost, text, locale), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp).testTag("recording-price"))
                        GroupDivider()
                        ToggleRow(stringResource(R.string.adm_numbers_recording_consent), draft.costAccepted, { form.draft = draft.copy(costAccepted = it) }, explanation = stringResource(R.string.adm_numbers_recording_consent_explanation), testTag = "recording-consent")
                    }
                }

                SettingsGroup {
                    ToggleRow(
                        stringResource(R.string.adm_numbers_recording_announce),
                        draft.announcementSoundId != null,
                        { on -> form.draft = draft.copy(announcementSoundId = if (on) (draft.announcementSoundId ?: options.sounds.firstOrNull()?.id) else null) },
                        explanation = stringResource(R.string.adm_numbers_recording_announce_explanation),
                        enabled = options.sounds.isNotEmpty() || draft.announcementSoundId != null,
                        testTag = "recording-announce",
                    )

                    if (draft.announcementSoundId != null) {
                        GroupDivider()
                        SoundRow(stringResource(R.string.adm_numbers_recording_announce_sound), draft.announcementSoundId, options, { form.draft = draft.copy(announcementSoundId = it) }, allowsNone = true)
                    }
                }

                if (options.sounds.isEmpty()) {
                    Text(stringResource(R.string.adm_numbers_sound_empty_message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

// MARK: - Choosing a sound

/** A row "label ... chosen sound >" that opens the sound picker (choose only: listening and recording is the sounds section). */
@Composable
fun SoundRow(title: String, soundId: String?, options: ChainOptions, onChange: (String?) -> Unit, allowsNone: Boolean = false) {
    val words = rememberNumberWords()
    var open by remember { mutableStateOf(false) }

    SettingsRow(title, icon = Icons.Filled.GraphicEq, value = words.soundName(soundId, options) ?: stringResource(R.string.adm_numbers_sound_none), onClick = { open = true }, testTag = "chain-sound-row")

    if (open) {
        PbxSubPage(title = title, onBack = { open = false }, testTag = "sound-picker-page") {
            SettingsGroup(footer = stringResource(R.string.adm_numbers_sound_footer)) {
                if (allowsNone) {
                    ChoiceRow(stringResource(R.string.adm_numbers_sound_none), selected = soundId == null, onClick = { onChange(null); open = false })
                    if (options.sounds.isNotEmpty()) GroupDivider()
                }

                if (options.sounds.isEmpty() && !allowsNone) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.adm_numbers_sound_empty_title), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.adm_numbers_sound_empty_message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                GroupItems(options.sounds) { _, sound ->
                    ChoiceRow(sound.name, selected = soundId == sound.id, onClick = { onChange(sound.id); open = false }, testTag = "sound-option")
                }
            }
        }
    }
}

// MARK: - Choosing a fallback

/**
 * A row "Gesloten ... Voicemail >" that opens the choice of what happens: voicemail (shared, or of a phone), play a message, forward to
 * a number, hang up, or a phone. Something the portal set up (`other`) is shown read-only.
 */
@Composable
fun FallbackRow(title: String, fallback: Fallback, options: ChainOptions, onChange: (Fallback) -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val words = rememberNumberWords()
    var open by remember { mutableStateOf(false) }

    if (fallback.isSelectable) {
        SettingsRow(title, icon = icon, value = words.fallback(fallback, options), onClick = { open = true }, testTag = "fallback-row")

        if (open) {
            FallbackPickerPage(title, fallback, options, noneTitle = null, onPick = { picked -> open = false; picked?.let(onChange) }, onBack = { open = false })
        }
    } else {
        SettingsRow(title, icon = icon, subtitle = stringResource(R.string.adm_numbers_portalOnly_hint), value = words.fallback(fallback, options), chevron = false, testTag = "fallback-row-readonly")
    }
}

/** Like [FallbackRow], with "the same as when closed" as first choice (`null`), for holidays. */
@Composable
fun OptionalFallbackRow(title: String, fallback: Fallback?, noneTitle: String, options: ChainOptions, onChange: (Fallback?) -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val words = rememberNumberWords()
    var open by remember { mutableStateOf(false) }

    if (fallback?.isSelectable ?: true) {
        SettingsRow(title, icon = icon, value = fallback?.let { words.fallback(it, options) } ?: noneTitle, onClick = { open = true }, testTag = "fallback-row-optional")

        if (open) {
            FallbackPickerPage(title, fallback, options, noneTitle = noneTitle, onPick = { picked -> open = false; onChange(picked) }, onBack = { open = false })
        }
    } else {
        SettingsRow(title, icon = icon, subtitle = stringResource(R.string.adm_numbers_portalOnly_hint), value = stringResource(R.string.adm_numbers_portalOnly), chevron = false)
    }
}

@Composable
private fun FallbackPickerPage(title: String, selection: Fallback?, options: ChainOptions, noneTitle: String?, onPick: (Fallback?) -> Unit, onBack: () -> Unit) {
    var number by remember { mutableStateOf((selection as? Fallback.Forward)?.number ?: "") }
    val cleaned = number.filter { it.isDigit() || it == '+' }
    val sharedVoicemailSelected = (selection as? Fallback.Voicemail)?.let { !it.ofDevice } == true

    PbxSubPage(title = title, onBack = onBack, testTag = "fallback-picker-page") {
        if (noneTitle != null) {
            SettingsGroup {
                ChoiceRow(noneTitle, selected = selection == null, onClick = { onPick(null) }, testTag = "fallback-none")
            }
        }

        SettingsGroup(title = stringResource(R.string.adm_numbers_fallback_group_voicemail)) {
            ChoiceRow(
                stringResource(R.string.adm_numbers_fallback_voicemail_shared),
                selected = sharedVoicemailSelected,
                onClick = {
                    // The shared box the chain already points at stays the same box; a fresh choice lets the server pick it.
                    if (selection is Fallback.Voicemail && !selection.ofDevice) onPick(selection) else onPick(Fallback.Voicemail(null, false))
                },
                testTag = "fallback-voicemail-shared",
            )

            for (device in options.devices) {
                GroupDivider()
                ChoiceRow(stringResource(R.string.adm_numbers_fallback_voicemailOf, device.name), selected = selection == Fallback.Voicemail(device.id, true), onClick = { onPick(Fallback.Voicemail(device.id, true)) })
            }
        }

        SettingsGroup(title = stringResource(R.string.adm_numbers_fallback_group_message), footer = if (options.sounds.isEmpty()) stringResource(R.string.adm_numbers_sound_empty_message) else null) {
            GroupItems(options.sounds) { _, sound ->
                ChoiceRow(sound.name, selected = selection == Fallback.Message(sound.id), onClick = { onPick(Fallback.Message(sound.id)) })
            }

            if (options.sounds.isEmpty()) {
                Text(stringResource(R.string.adm_numbers_sound_empty_title), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
        }

        SettingsGroup(title = stringResource(R.string.adm_numbers_fallback_group_device)) {
            GroupItems(options.devices) { _, device ->
                ChoiceRow(device.name, selected = selection == Fallback.Device(device.id), subtitle = device.extensionNumber?.let { stringResource(R.string.adm_pbx_account_extension, it) }, onClick = { onPick(Fallback.Device(device.id)) })
            }
        }

        SettingsGroup(title = stringResource(R.string.adm_numbers_fallback_group_forward), footer = stringResource(R.string.adm_numbers_fallback_forward_footer)) {
            FieldRow(number, { number = it }, stringResource(R.string.adm_numbers_fallback_forward_placeholder), keyboard = KeyboardType.Phone, testTag = "fallback-forward-field")
            GroupDivider()
            SettingsRow(stringResource(R.string.adm_numbers_fallback_forward_use), chevron = false, enabled = cleaned.length >= 3, onClick = { onPick(Fallback.Forward(cleaned)) }, testTag = "fallback-forward-use")
        }

        SettingsGroup {
            ChoiceRow(stringResource(R.string.adm_numbers_fallback_hangup), selected = selection == Fallback.Hangup, onClick = { onPick(Fallback.Hangup) }, testTag = "fallback-hangup")
        }
    }
}
