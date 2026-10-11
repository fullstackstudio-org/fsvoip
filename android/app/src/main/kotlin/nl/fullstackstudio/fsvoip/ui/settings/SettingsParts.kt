// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Building blocks of the settings pages on top of the list helpers in `Settings.kt` (section, rows).

package nl.fullstackstudio.fsvoip.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.ui.Brand

/** An inline notice (an error or a hint) at the top of a page. */
@Composable
fun NoticeCard(text: String, isError: Boolean = true, title: String? = null, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(if (isError) Icons.Filled.Error else Icons.Filled.Info, contentDescription = null, tint = if (isError) Brand.hangUp else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                if (title != null) Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** A row with a check on the chosen one (title and an optional second line). Reads as one item. */
@Composable
fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(
            if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        )
    }
}

/** A row "title ... value >" that opens a page. */
@Composable
fun ValueLinkRow(title: String, value: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** The bottom of a page that edits something: cancel and save. */
@Composable
fun SaveFooter(canSave: Boolean, onCancel: () -> Unit, onSave: () -> Unit, saveTitle: String = stringResource(R.string.action_save), isSaving: Boolean = false, cancelTitle: String = stringResource(R.string.action_cancel)) {
    Surface(tonalElevation = 3.dp) {
        Column {
            HorizontalDivider()
            Row(Modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel, enabled = !isSaving, modifier = Modifier.weight(1f).testTag("footer-cancel")) { Text(cancelTitle) }
                Button(
                    onClick = onSave,
                    enabled = canSave && !isSaving,
                    modifier = Modifier.weight(1f).testTag("footer-save"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.lime, contentColor = Brand.ink, disabledContainerColor = Brand.lime.copy(alpha = 0.4f), disabledContentColor = Brand.ink.copy(alpha = 0.6f)),
                ) { Text(saveTitle, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/**
 * Leaving a page with unsaved changes asks first ("Niet-opgeslagen wijzigingen weggooien?"). Returns the function to call instead of
 * `back`; the system back button is wired to it as well.
 */
@Composable
fun rememberDiscardGuard(isDirty: Boolean, isSaving: Boolean, back: () -> Unit): () -> Unit {
    var asking by remember { mutableStateOf(false) }
    val leave = { if (isDirty && !isSaving) asking = true else back() }

    BackHandler(enabled = isDirty && !isSaving) { asking = true }

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.snd_sheet_discard_title)) },
            confirmButton = { TextButton(onClick = { asking = false; back() }) { Text(stringResource(R.string.snd_sheet_discard_confirm), color = Brand.hangUp) } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.snd_sheet_discard_keep)) } },
        )
    }

    return leave
}

@Composable
fun roleDescription(text: String): Modifier = Modifier.semantics { contentDescription = text }
