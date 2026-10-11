// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The building blocks of the "Centrale" and "Nummers" screens (the Compose side of the iOS design system: a page with a title and
// back button, grouped rows on raised cards, toggle and choice rows, a segmented bar, notices, day bars). Lime is used only where
// iOS uses it: the open blocks of a day, the selected segment text and the check marks of a choice.

package nl.fullstackstudio.fsvoip.ui.pbx

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.SyncState
import nl.fullstackstudio.fsvoip.ui.Brand

val PbxWarm: Color get() = Brand.amber

@Composable
fun rememberPbxText(): PbxText {
    val context = LocalContext.current

    return remember(context) { ContextPbxText(context) }
}

@Composable
fun rememberPbxVocabulary(): PbxVocabulary {
    val text = rememberPbxText()

    return remember(text) { PbxVocabulary(text, Locale.getDefault()) }
}

@Composable
fun rememberNumberWords(): NumberWords {
    val text = rememberPbxText()
    val vocabulary = rememberPbxVocabulary()

    return remember(text, vocabulary) { NumberWords(text, vocabulary) }
}

// MARK: - Page

/**
 * A page of the section: title and back, content that scrolls, optional footer (Annuleren | Opslaan), and pull to refresh when
 * [onRefresh] is given.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PbxPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: (suspend () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    testTag: String = "pbx-page",
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier.testTag(testTag),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("pbx-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.adm_ui_back))
                    }
                },
            )
        },
        bottomBar = { footer?.invoke() },
    ) { padding ->
        val scope = rememberCoroutineScope()
        var refreshing by remember { mutableStateOf(false) }
        val body: @Composable () -> Unit = {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp),
                content = content,
            )
        }

        if (onRefresh != null) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        try {
                            onRefresh()
                        } finally {
                            refreshing = false
                        }
                    }
                },
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) { body() }
        } else {
            Box(Modifier.padding(padding).fillMaxSize()) { body() }
        }
    }
}

// MARK: - Groups and rows

@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) {
            Text(
                title.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            )
        }

        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }

        if (footer != null) {
            Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

/** Rows of a group with a hairline between them. */
@Composable
fun <T> ColumnScope.GroupItems(items: List<T>, content: @Composable (Int, T) -> Unit) {
    items.forEachIndexed { index, item ->
        if (index > 0) GroupDivider()
        content(index, item)
    }
}

@Composable
fun GroupDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), modifier = Modifier.padding(start = 16.dp))
}

private val minRow = 52.dp

/**
 * A row of a settings list: optional icon, title, optional subtitle, optional value and a chevron. A long value moves under the
 * title instead of being cut off; the row reads as one item.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    value: String? = null,
    chevron: Boolean = true,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val clickable = if (onClick != null) {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }

    Row(
        modifier
            .fillMaxWidth()
            .then(clickable)
            .heightIn(min = minRow)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (destructive) color else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) color else color.copy(alpha = 0.5f))

            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }

        if (chevron && onClick != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
        }
    }
}

/** A switch with the explanation under it. The explanation says what happens, in the words of the person using the app. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    explanation: String? = null,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minRow)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { stateDescription = if (checked) "on" else "off" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)

            if (explanation != null) {
                Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Brand.lime, checkedThumbColor = Brand.ink, checkedBorderColor = Brand.ink.copy(alpha = 0.2f)),
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
        )
    }
}

/** The inside of a choice row (title, subtitle, the check), for rows that put something in front of it. */
@Composable
fun ChoiceRowLabel(title: String, selected: Boolean, modifier: Modifier = Modifier, subtitle: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)

            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        }
    }
}

/** One choice out of a list: a check on the chosen one. */
@Composable
fun ChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    ChoiceRowLabel(
        title,
        selected,
        modifier
            .fillMaxWidth()
            .heightIn(min = minRow)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics(mergeDescendants = true) { this.selected = selected },
        subtitle,
    )
}

/** Two to four options in one row: the selected one on a raised surface. */
@Composable
fun <T> SegmentedBar(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, testTag: String? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, label) in options) {
            val isSelected = value == selected

            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected) Brand.ink else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(value) }
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) { this.selected = isSelected },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) Brand.lime else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/** A row "-  value  +" for a number in a range. */
@Composable
fun StepperRow(label: String, value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, testTag: String? = null) {
    Row(
        modifier.fillMaxWidth().heightIn(min = minRow).padding(horizontal = 16.dp, vertical = 4.dp).then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).semantics { contentDescription = label })

        StepButton(Icons.Filled.Remove, stringResource(R.string.adm_ui_less), enabled && value - step >= range.first) { onChange((value - step).coerceAtLeast(range.first)) }
        StepButton(Icons.Filled.Add, stringResource(R.string.adm_ui_more), enabled && value + step <= range.last) { onChange((value + step).coerceAtMost(range.last)) }
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(40.dp),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

// MARK: - Notices and states

/** One notice on a raised card (frozen, changed in the meantime, an error). */
@Composable
fun NoticeCard(icon: ImageVector, tint: Color, title: String, modifier: Modifier = Modifier, message: String? = null, testTag: String? = null) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp).then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        Row(Modifier.padding(16.dp).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)

                if (message != null) {
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, modifier: Modifier = Modifier, message: String? = null, actionTitle: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(36.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)

        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }

        if (actionTitle != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionTitle) }
        }
    }
}

@Composable
fun LoadingBlock(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.testTag("pbx-loading"))
    }
}

/** "Wordt bijgewerkt" next to an object whose latest settings the centrale has not applied yet. */
@Composable
fun PbxSyncBadge(sync: SyncState, modifier: Modifier = Modifier) {
    when (sync) {
        SyncState.PENDING -> Text(stringResource(R.string.adm_pbx_sync_pending), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = PbxWarm, modifier = modifier)
        SyncState.ERROR -> Text(stringResource(R.string.adm_pbx_sync_error), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error, modifier = modifier)
        SyncState.OK, SyncState.UNKNOWN -> Unit
    }
}

/** The save / cancel footer of a form. */
@Composable
fun PbxFormFooter(canSave: Boolean, isSaving: Boolean, onSave: () -> Unit, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("pbx-cancel"), shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.action_cancel))
            }

            androidx.compose.material3.Button(
                onClick = onSave,
                enabled = canSave && !isSaving,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("pbx-save"),
                shape = RoundedCornerShape(12.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Brand.lime,
                    contentColor = Brand.ink,
                    disabledContainerColor = Brand.lime.copy(alpha = 0.4f),
                    disabledContentColor = Brand.ink.copy(alpha = 0.6f),
                ),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Brand.ink)
                } else {
                    Text(stringResource(R.string.action_save), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Asks before leaving a form that has unsaved changes. */
@Composable
fun DiscardChangesDialog(onDiscard: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.adm_ui_discard_title)) },
        text = { Text(stringResource(R.string.adm_ui_discard_message)) },
        confirmButton = { TextButton(onClick = onDiscard, modifier = Modifier.testTag("pbx-discard")) { Text(stringResource(R.string.adm_ui_discard_confirm), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.adm_ui_discard_keep)) } },
    )
}

/** A circled initial: the person behind a device. */
@Composable
fun DeviceAvatar(name: String, size: Dp = 40.dp, connected: Boolean = false) {
    val initials = nl.fullstackstudio.fsvoip.ui.initialsOf(name)

    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            Text(initials, fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (connected) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(size * 0.3f)
                    .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                    .padding(1.5.dp)
                    .background(Brand.lime, CircleShape),
            )
        }
    }
}

// MARK: - Day bars

/** Per day a bar from 00:00 to 24:00 with the opening hours as lime blocks. A tap on a day opens its time slots. */
@Composable
fun DayBars(week: WeekPlan, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val vocabulary = rememberPbxVocabulary()
    val text = rememberPbxText()

    Column(modifier.fillMaxWidth()) {
        // 00:00 · 06:00 · 12:00 · 18:00 · 24:00 above the bars.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 2.dp).clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.width(DAY_NAME_WIDTH))
            BoxWithConstraints(Modifier.weight(1f).height(16.dp)) {
                for (hour in listOf(0, 6, 12, 18, 24)) {
                    val offset = maxWidth * (hour / 24f)
                    Text(
                        "%02d".format(hour),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = (offset - 6.dp).coerceIn(0.dp, maxWidth - 14.dp)),
                    )
                }
            }
        }

        for (day in WeekPlan.weekdays) {
            val slots = week.slots(day)
            val spoken = DayBarsLogic.segments(slots).let { _ ->
                val valid = slots.filter(DayBarsLogic::isValid)

                if (valid.isEmpty()) {
                    text.get(R.string.adm_numbers_hours_day_closed)
                } else {
                    valid.joinToString(", ") { text.get(R.string.adm_numbers_hours_slot_spoken, it.from, it.to) }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(enabled = enabled, role = Role.Button) { onSelect(day) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("daybar-$day")
                    .semantics(mergeDescendants = true) { contentDescription = "${vocabulary.weekday(day)}: $spoken" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(vocabulary.weekday(day), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(DAY_NAME_WIDTH))
                DayBar(DayBarsLogic.segments(slots), enabled, Modifier.weight(1f))
            }
        }
    }
}

private val DAY_NAME_WIDTH = 96.dp

@Composable
private fun DayBar(segments: List<ClosedFloatingPointRange<Double>>, enabled: Boolean, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    val mark = Brand.ink.copy(alpha = 0.3f)
    val block = Brand.lime.copy(alpha = if (enabled) 0.9f else 0.4f)

    Canvas(modifier.height(28.dp)) {
        val radius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
        drawRoundRect(track, size = size, cornerRadius = radius)

        for (segment in segments) {
            val left = (size.width * segment.start).toFloat()
            val width = maxOf(2.dp.toPx(), (size.width * (segment.endInclusive - segment.start)).toFloat())
            drawRoundRect(block, topLeft = androidx.compose.ui.geometry.Offset(left, 0f), size = androidx.compose.ui.geometry.Size(width, size.height), cornerRadius = radius)
        }

        for (fraction in listOf(0.25f, 0.5f, 0.75f)) {
            drawRect(mark, topLeft = androidx.compose.ui.geometry.Offset(size.width * fraction, 0f), size = androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height))
        }
    }
}

/** A wrapping row of small buttons (presets). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit, testTag: String? = null) {
    Surface(
        shape = CircleShape,
        color = if (selected) Brand.ink else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics { this.selected = selected },
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Brand.lime else MaterialTheme.colorScheme.onSurface, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}
