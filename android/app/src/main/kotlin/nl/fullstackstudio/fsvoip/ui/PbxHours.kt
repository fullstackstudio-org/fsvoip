// SPDX-License-Identifier: AGPL-3.0-or-later
//
// "Openingstijden" of the PBX (the week and where callers go inside and outside the hours) and "Tijdelijk dicht" (own days on which
// callers get what they get outside the opening hours). Android port of the iOS `HoursView` / `TemporaryClosedView`.

package nl.fullstackstudio.fsvoip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.HolidayInput
import nl.fullstackstudio.fsvoip.core.HoursDraft
import nl.fullstackstudio.fsvoip.core.PbxFailure
import nl.fullstackstudio.fsvoip.core.PbxHoursPatch
import nl.fullstackstudio.fsvoip.core.TemporaryClosure
import nl.fullstackstudio.fsvoip.core.WeekDraft
import nl.fullstackstudio.fsvoip.core.WeekSlot

@Composable
fun HoursScreen(model: AppModel, accountId: String, open: (String) -> Unit, back: () -> Unit) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val all by model.pbx.hours.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    var failure by remember { mutableStateOf<PbxFailure?>(null) }
    var loading by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(accountId, attempt) {
        account ?: return@LaunchedEffect
        loading = true
        failure = model.pbx.loadHours(account)
        loading = false
    }

    AdminPage(model, stringResource(R.string.pbx_hours_title), back) {
        ReadOnlyNote(model, accountId)
        failure?.let { NoteCard(pbxFailureText(it), isError = true) }

        val page = all[accountId]

        if (page == null) {
            if (loading) Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
            else if (failure != null) TextButton(onClick = { attempt++ }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.action_retry)) }
            return@AdminPage
        }

        if (page.hours.isEmpty()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.pbx_hours_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.pbx_hours_empty_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }

        section(null, null) {
            for (item in page.hours) {
                val summary = WeekDraft.summary(item.week, stringResource(R.string.pbx_hours_never))
                val holidays = item.holidays.size.takeIf { it > 0 }?.let { stringResource(R.string.pbx_hours_holidays, it) }

                Row(Modifier.fillMaxWidth().clickable { open(item.id) }.padding(horizontal = 16.dp, vertical = 12.dp).testTag("pbx-hours-row"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(listOfNotNull(summary, holidays, syncNote(item.sync)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
fun HoursEditScreen(model: AppModel, accountId: String, hoursId: String, openClosed: () -> Unit, back: () -> Unit) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val all by model.pbx.hours.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    val page = all[accountId]
    val item = page?.hours?.firstOrNull { it.id == hoursId }
    val scope = rememberCoroutineScope()
    val pending = stringResource(R.string.pbx_pending_message)

    var baseline by remember(hoursId) { mutableStateOf<HoursDraft?>(null) }
    var draft by remember(hoursId) { mutableStateOf(HoursDraft()) }
    var failure by remember(hoursId) { mutableStateOf<PbxFailure?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(accountId) { if (account != null && page == null) model.pbx.loadHours(account) }

    LaunchedEffect(item) {
        val fresh = item ?: return@LaunchedEffect
        val freshDraft = HoursDraft.of(fresh)
        val start = baseline

        draft = if (start == null) freshDraft else draft.copy(
            days = if (draft.days == start.days) freshDraft.days else draft.days,
            openTarget = if (draft.openTarget == start.openTarget) freshDraft.openTarget else draft.openTarget,
            closedTarget = if (draft.closedTarget == start.closedTarget) freshDraft.closedTarget else draft.closedTarget,
        )
        baseline = freshDraft
    }

    val readOnly = model.isPbxReadOnly(accountId)
    val valid = WeekDraft.isValid(draft.days)
    val dirty = item != null && valid && draft.patch(item) != null

    AdminPage(model, item?.name ?: stringResource(R.string.pbx_hours_title), back) {
        if (item == null || page == null) {
            InfoRow(stringResource(R.string.pbx_hours_empty_title))
            return@AdminPage
        }

        ReadOnlyNote(model, accountId)
        failure?.let { NoteCard(pbxFailureText(it), isError = true) }
        syncNote(item.sync)?.let { InfoRow(it) }

        ReadOnlyLock(readOnly) {
            Column {
                section(stringResource(R.string.numbers_hours_title), stringResource(R.string.numbers_hours_day_footer)) {
                    WeekEditor(draft.days) { draft = draft.copy(days = it) }
                }

                section(stringResource(R.string.pbx_hours_targets), stringResource(R.string.pbx_hours_targets_footer)) {
                    TargetChoiceRow(stringResource(R.string.pbx_hours_open_then), draft.openTarget, targetChoices(page.targets), stringResource(R.string.pbx_target_none), "pbx-hours-open") { draft = draft.copy(openTarget = it) }
                    TargetChoiceRow(stringResource(R.string.pbx_hours_closed_then), draft.closedTarget, targetChoices(page.targets), stringResource(R.string.pbx_target_none), "pbx-hours-closed") { draft = draft.copy(closedTarget = it) }
                }
            }
        }

        section(null, null) {
            LinkRow(stringResource(R.string.pbx_closed_title), null) { openClosed() }
        }

        if (!readOnly) {
            Column(Modifier.padding(16.dp)) {
                PrimaryButton(
                    stringResource(R.string.action_save),
                    enabled = dirty && !saving,
                    modifier = Modifier.testTag("pbx-hours-save"),
                    onClick = {
                        val target = account ?: return@PrimaryButton
                        val patch = draft.patch(item) ?: return@PrimaryButton
                        saving = true

                        scope.launch {
                            failure = model.pbx.saveHours(target, hoursId, patch)

                            if (failure == null) {
                                model.showNotice(pending, isError = false)
                                back()
                            }

                            saving = false
                        }
                    },
                )
            }
        }
    }
}

/** The week: per day a switch and up to four intervals (`HH:MM`, `24:00` = until midnight). Shared by the PBX hours and the number chain. */
@Composable
fun WeekEditor(days: Map<Int, List<WeekSlot>>, onChange: (Map<Int, List<WeekSlot>>) -> Unit) {
    val locale = Locale.getDefault()

    for (day in WeekDraft.DAYS) {
        val slots = days[day].orEmpty()
        val name = DayOfWeek.of(day).getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.uppercase() }

        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("week-day-$day")) {
            Row(Modifier.fillMaxWidth().clickable { onChange(if (slots.isEmpty()) days + (day to listOf(WeekDraft.DEFAULT_SLOT)) else days - day) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    if (slots.isEmpty()) Text(stringResource(R.string.numbers_hours_day_closed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = slots.isNotEmpty(),
                    onCheckedChange = { on -> onChange(if (on) days + (day to listOf(WeekDraft.DEFAULT_SLOT)) else days - day) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Brand.ink, checkedThumbColor = Brand.lime),
                )
            }

            slots.forEachIndexed { index, slot ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeField(slot.from, stringResource(R.string.pbx_hours_from), !slot.isValid, Modifier.weight(1f)) { value -> onChange(days + (day to slots.mapIndexed { i, s -> if (i == index) s.copy(from = value) else s })) }
                    TimeField(slot.to, stringResource(R.string.pbx_hours_to), !slot.isValid, Modifier.weight(1f)) { value -> onChange(days + (day to slots.mapIndexed { i, s -> if (i == index) s.copy(to = value) else s })) }
                    IconButton(onClick = { onChange(if (slots.size == 1) days - day else days + (day to slots.filterIndexed { i, _ -> i != index })) }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.pbx_hours_removeSlot))
                    }
                }

                if (!slot.isValid) {
                    Text(stringResource(R.string.numbers_hours_slot_invalid), style = MaterialTheme.typography.bodySmall, color = Brand.hangUp, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            if (slots.isNotEmpty() && slots.size < WeekDraft.SLOTS_PER_DAY) {
                TextButton(onClick = { onChange(days + (day to slots + WeekSlot("13:00", "17:00"))) }, modifier = Modifier.padding(start = 8.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.pbx_hours_addSlot), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun TimeField(value: String, label: String, error: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(5)) },
        label = { Text(label) },
        isError = error,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

// MARK: Tijdelijk dicht

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemporaryClosedScreen(model: AppModel, accountId: String, hoursId: String, back: () -> Unit) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val all by model.pbx.hours.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    val item = all[accountId]?.hours?.firstOrNull { it.id == hoursId }
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }
    val defaultName = stringResource(R.string.pbx_closed_defaultName)

    var from by remember { mutableStateOf(today) }
    var until by remember { mutableStateOf(today) }
    var reason by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<Boolean?>(null) }
    var failure by remember { mutableStateOf<PbxFailure?>(null) }
    var plan by remember { mutableStateOf<TemporaryClosure.Plan?>(null) }
    var saving by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    LaunchedEffect(accountId) { if (account != null && item == null) model.pbx.loadHours(account) }

    suspend fun save(holidays: List<HolidayInput>) {
        val target = account ?: return
        val current = all[accountId]?.hours?.firstOrNull { it.id == hoursId } ?: return
        // The newest version of the hours, not the one the screen was opened with.
        failure = model.pbx.saveHours(target, hoursId, PbxHoursPatch(version = current.version, holidays = holidays))
    }

    AdminPage(model, stringResource(R.string.pbx_closed_title), back) {
        if (item == null) {
            InfoRow(stringResource(R.string.pbx_hours_empty_title))
            return@AdminPage
        }

        ReadOnlyNote(model, accountId)
        failure?.let { NoteCard(pbxFailureText(it), isError = true) }

        when (val current = plan) {
            TemporaryClosure.Plan.EmptyRange -> NoteCard(stringResource(R.string.pbx_closed_error_range), isError = true)
            is TemporaryClosure.Plan.TooMany -> NoteCard(stringResource(R.string.pbx_closed_error_tooMany, TemporaryClosure.CUSTOM_DATE_LIMIT, current.room), isError = true)
            else -> Unit
        }

        val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

        section(stringResource(R.string.pbx_closed_header), stringResource(R.string.pbx_closed_footer)) {
            LinkRow(stringResource(R.string.pbx_closed_from), from.format(format)) { picking = true }
            LinkRow(stringResource(R.string.pbx_closed_until), until.format(format)) { picking = false }
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it.take(60) },
                label = { Text(stringResource(R.string.pbx_closed_reason)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("pbx-closed-reason"),
            )
            Column(Modifier.padding(16.dp)) {
                PrimaryButton(
                    stringResource(R.string.pbx_closed_action),
                    enabled = !saving && !model.isPbxReadOnly(accountId),
                    modifier = Modifier.testTag("pbx-closed-action"),
                    onClick = {
                        done = false
                        val dates = TemporaryClosure.dates(from, until)
                        val result = TemporaryClosure.closing(item, reason, dates, defaultName)
                        plan = result
                        failure = null

                        if (result is TemporaryClosure.Plan.Holidays) {
                            saving = true
                            scope.launch {
                                save(result.holidays)
                                done = failure == null
                                saving = false
                            }
                        }
                    },
                )
            }
        }

        if (done) NoteCard(stringResource(R.string.pbx_closed_done))

        val own = item.holidays.filter { it.date != null }.sortedBy { it.date }

        if (own.isNotEmpty()) {
            section(stringResource(R.string.pbx_closed_current), stringResource(R.string.pbx_closed_limit, TemporaryClosure.CUSTOM_DATE_LIMIT)) {
                for (holiday in own) {
                    val date = holiday.date.orEmpty()
                    val shown = runCatching { LocalDate.parse(date).format(format) }.getOrDefault(date)

                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(shown, style = MaterialTheme.typography.bodyLarge)
                            Text(holiday.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (removing == date) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(
                                onClick = {
                                    removing = date
                                    done = false
                                    scope.launch {
                                        save(TemporaryClosure.reopening(item, date))
                                        removing = null
                                    }
                                },
                                enabled = !saving && !model.isPbxReadOnly(accountId),
                            ) { Text(stringResource(R.string.pbx_closed_reopen)) }
                        }
                    }
                }
            }
        }
    }

    picking?.let { isFrom ->
        val state = rememberDatePickerState(initialSelectedDateMillis = (if (isFrom) from else until).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())

        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        if (isFrom) {
                            from = picked
                            if (until.isBefore(picked)) until = picked
                        } else {
                            until = picked
                        }
                    }
                    picking = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = state) }
    }
}
