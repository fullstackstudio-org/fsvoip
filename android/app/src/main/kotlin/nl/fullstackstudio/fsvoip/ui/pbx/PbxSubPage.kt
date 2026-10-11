// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.pbx

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import nl.fullstackstudio.fsvoip.R

/**
 * A full-screen page on top of the current one (a picker, the slots of a day, one step of "volg mij"): the iOS pushed page. The
 * page below keeps its state, so what the user typed is still there when the page closes.
 */
@Composable
fun PbxSubPage(
    title: String,
    onBack: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
    testTag: String = "pbx-subpage",
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize().imePadding(), color = MaterialTheme.colorScheme.surface) {
            BackHandler(onBack = onBack)
            PbxPage(title = title, onBack = onBack, footer = footer, testTag = testTag, content = content)
        }
    }
}

// MARK: - Time and date

/** `HH:MM` as a time picker. Midnight at the end of a day is `24:00` on the wire and shows as 23:59 in the picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(label: String, text: String, isEnd: Boolean, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }

    TextButton(onClick = { open = true }, modifier = modifier.testTag("time-$label")) {
        Text(text.ifEmpty { "--:--" }, style = MaterialTheme.typography.titleMedium)
    }

    if (open) {
        val parts = text.split(":").mapNotNull { it.toIntOrNull() }
        var hour = parts.getOrElse(0) { 0 }
        var minute = parts.getOrElse(1) { 0 }

        if (hour >= 24) {
            hour = 23
            minute = 59
        }

        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)

        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    onChange(if (isEnd && state.hour == 23 && state.minute == 59) "24:00" else "%02d:%02d".format(state.hour, state.minute))
                }) { Text(stringResource(R.string.adm_ui_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}


/** A row "label ... date" that opens a date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRow(label: String, date: LocalDate, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier, min: LocalDate? = null, testTag: String? = null) {
    var open by remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()) }

    SettingsRow(label, modifier = modifier, value = date.format(formatter), onClick = { open = true }, chevron = false, testTag = testTag)

    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())

        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    state.selectedDateMillis?.let { millis ->
                        val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(if (min != null && picked.isBefore(min)) min else picked)
                    }
                }) { Text(stringResource(R.string.adm_ui_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            DatePicker(state)
        }
    }
}

/** A text field inside a settings card (a name, a phone number). */
@Composable
fun FieldRow(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    testTag: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.weight(1f).then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        )

        trailing?.invoke()
    }
}
