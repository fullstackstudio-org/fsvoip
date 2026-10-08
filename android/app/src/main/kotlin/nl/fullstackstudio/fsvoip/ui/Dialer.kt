// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.RecentCall
import nl.fullstackstudio.fsvoip.core.StoredAccount
import nl.fullstackstudio.fsvoip.sipengine.RegistrationState

@Composable
fun DialerScreen(model: AppModel) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val registrations by model.phone.registrations.collectAsStateWithLifecycle()
    val recents by model.recents.collectAsStateWithLifecycle()
    model.settingsRevision.collectAsStateWithLifecycle().value
    var input by remember { mutableStateOf(DialerInput()) }
    var chosenAccountId by remember { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current

    val accountId = chosenAccountId?.takeIf { id -> accounts.any { it.id == id } } ?: model.defaultOutgoingAccountId

    fun placeCall() {
        if (input.isEmpty) {
            // Like a desk phone: the call button on an empty display brings back the last number dialled.
            recents.firstOrNull { it.direction == RecentCall.Direction.OUTGOING && it.number.isNotEmpty() }?.let { input = input.paste(it.number) }
            return
        }

        if (model.call(input.number, accountId)) {
            input = input.clear()
        } else {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val key = keySize(maxWidth, maxHeight)

        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            LineSelector(accounts, registrations, accountId) { chosenAccountId = it }
            Spacer(Modifier.weight(1f))
            NumberDisplay(model, input, height = key * 1.15f) { input = it }
            Spacer(Modifier.weight(1f))
            Keypad(keySize = key, onKey = { input = input.press(it) }, onLongPressZero = { input = input.longPressZero() })

            Row(
                Modifier.padding(top = key * 0.28f, bottom = 28.dp).size(width = key * 3 + key * 0.56f, height = key),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Spacer(Modifier.size(key))
                CallButton(size = key, enabled = accountId != null, onClick = ::placeCall)
                DeleteButton(size = key, visible = !input.isEmpty, onDelete = { input = input.deleteLast() }, onClear = { input = input.clear() })
            }
        }
    }
}

private fun keySize(width: Dp, height: Dp): Dp {
    // Fit four rows of keys plus the call row and the display into the height; never wider than the screen.
    val byHeight = (height - 120.dp) / 6.4f
    val byWidth = (width - 96.dp) / 3

    return maxOf(56.dp, minOf(84.dp, byHeight, byWidth))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NumberDisplay(model: AppModel, input: DialerInput, height: Dp, onChange: (DialerInput) -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val emptyLabel = stringResource(R.string.dialer_empty)

    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { menu = true }),
        ) {
            Text(
                if (input.isEmpty) " " else input.number,
                fontSize = if (input.number.length > 13) 30.sp else 40.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp).testTag("dialed-number").semantics { contentDescription = if (input.isEmpty) emptyLabel else input.number },
            )

            val name = model.name(input.number)
            when {
                name != null -> Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                input.isEmpty -> Text(stringResource(R.string.dialer_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                else -> Text(" ", style = MaterialTheme.typography.bodyMedium)
            }
        }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.dialer_paste)) }, onClick = {
                menu = false
                clipboardText(context)?.let { onChange(input.paste(it)) }
            })

            if (!input.isEmpty) {
                DropdownMenuItem(text = { Text(stringResource(R.string.dialer_copy)) }, onClick = {
                    menu = false
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("number", input.number))
                })
            }
        }
    }
}

fun clipboardText(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()

/** Which line (paired account) the call goes out on, with its light. */
@Composable
private fun LineSelector(accounts: List<StoredAccount>, registrations: Map<String, RegistrationState>, selectedId: String?, select: (String) -> Unit) {
    val account = accounts.firstOrNull { it.id == selectedId } ?: return
    val state = registrations[account.id] ?: RegistrationState.Unregistered
    var open by remember { mutableStateOf(false) }
    val several = accounts.size > 1
    val description = stringResource(R.string.dialer_lineAccessibility, account.displayLabel, state.label())

    Box {
        Row(
            Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .combinedClickableIf(several) { open = true }
                .padding(horizontal = 14.dp, vertical = 9.dp)
                .testTag("line-selector")
                .semantics(mergeDescendants = true) { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusLight(state)
            Text(account.displayLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)

            if (state != RegistrationState.Registered) {
                Text(state.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }

            if (several) {
                Icon(Icons.Filled.UnfoldMore, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in accounts) {
                val optionState = registrations[option.id] ?: RegistrationState.Unregistered
                DropdownMenuItem(
                    text = { Text("${option.displayLabel} · ${optionState.label()}") },
                    leadingIcon = { Icon(if (option.id == selectedId) Icons.Filled.Check else Icons.Filled.Phone, contentDescription = null) },
                    onClick = {
                        open = false
                        select(option.id)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableIf(enabled: Boolean, onClick: () -> Unit): Modifier =
    if (enabled) this.then(Modifier.combinedClickable(onClick = onClick)) else this

private val KEY_ROWS = listOf("123", "456", "789", "*0#")

@Composable
fun Keypad(keySize: Dp, onKey: (Char) -> Unit, onLongPressZero: (() -> Unit)? = null, dark: Boolean = false) {
    val haptics = LocalHapticFeedback.current

    Column(verticalArrangement = Arrangement.spacedBy(keySize * 0.17f)) {
        for (row in KEY_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(keySize * 0.28f)) {
                for (key in row) {
                    KeypadKey(
                        key = key,
                        size = keySize,
                        dark = dark,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onKey(key)
                        },
                        onLongClick = if (key == '0' && onLongPressZero != null) {
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongPressZero()
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeypadKey(key: Char, size: Dp, dark: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val letters = DialerInput.letters(key)
    val foreground = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    val background = if (dark) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant

    Box(
        Modifier
            .size(size)
            .scale(if (pressed) 0.94f else 1f)
            .background(if (pressed) background.copy(alpha = 0.6f) else background, CircleShape)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
            .testTag("key-$key")
            .semantics { contentDescription = key.toString() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(key.toString(), color = foreground, fontSize = (size.value * if (key == '*') 0.5f else 0.42f).sp, fontWeight = FontWeight.Light)
            Text(
                letters.ifEmpty { " " },
                color = foreground.copy(alpha = if (letters.isEmpty()) 0f else 0.7f),
                fontSize = (size.value * 0.13f).sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (size.value * 0.025f).sp,
            )
        }
    }
}

/** The lime call button: the one place the accent is at full strength. */
@Composable
fun CallButton(size: Dp, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.dialer_call)

    Box(
        Modifier
            .size(size)
            .background(if (enabled) Brand.lime else Brand.lime.copy(alpha = 0.4f), CircleShape)
            .combinedClickableIf(enabled, onClick)
            .testTag("call-button")
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Phone, contentDescription = null, tint = Brand.ink, modifier = Modifier.size(size * 0.4f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeleteButton(size: Dp, visible: Boolean, onDelete: () -> Unit, onClear: () -> Unit) {
    val description = stringResource(R.string.dialer_delete)

    Box(
        Modifier
            .size(size)
            .then(if (visible) Modifier.combinedClickable(onClick = onDelete, onLongClick = onClear) else Modifier)
            .testTag("delete-button")
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (visible) {
            Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.3f))
        }
    }
}
