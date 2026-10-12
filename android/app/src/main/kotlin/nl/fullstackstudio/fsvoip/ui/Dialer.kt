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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.stateDescription
import nl.fullstackstudio.fsvoip.core.PbxVocabulary
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
    var showsChooser by remember { mutableStateOf(false) }
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
            accountId?.let { UnavailableBanner(model, it) }
            Spacer(Modifier.weight(1f))
            NumberDisplay(model, input, height = key * 1.15f) { input = it }
            Spacer(Modifier.weight(1f))
            Keypad(keySize = key, onKey = { input = input.press(it) }, onLongPressZero = { input = input.longPressZero() })

            Row(
                Modifier.padding(top = key * 0.28f, bottom = 16.dp).size(width = key * 3 + key * 0.56f, height = key),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Spacer(Modifier.size(key))
                CallButton(size = key, enabled = accountId != null, onClick = ::placeCall)
                DeleteButton(size = key, visible = !input.isEmpty, onDelete = { input = input.deleteLast() }, onClear = { input = input.clear() })
            }

            // The line and the number this call goes out with; the bar opens the chooser when there is something to choose.
            accountId?.let { id ->
                accounts.firstOrNull { it.id == id }?.let { account ->
                    OutboundBar(model, account, registrations[id] ?: RegistrationState.Unregistered, opensChooser = { showsChooser = true })
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    if (showsChooser) {
        OutboundChooserSheet(model, accountId, onAccount = { chosenAccountId = it }, onDismiss = { showsChooser = false })
    }
}

private fun keySize(width: Dp, height: Dp): Dp {
    // Fit four rows of keys plus the call row and the display into the height; never wider than the screen.
    val byHeight = (height - 190.dp) / 6.4f
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

/** The bar under the keypad: which line (paired account) and which number the call goes out with. */
@Composable
private fun OutboundBar(model: AppModel, account: StoredAccount, state: RegistrationState, opensChooser: () -> Unit) {
    model.settingsRevision.collectAsStateWithLifecycle().value
    model.selfExtension.states.collectAsStateWithLifecycle().value
    model.me.collectAsStateWithLifecycle().value
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val choosable = model.canChooseOutbound(account.id) || accounts.size > 1
    val label = model.outboundLabel(account.id)
    val description = stringResource(R.string.outbound_bar_accessibility, label)
    val hint = stringResource(R.string.outbound_bar_hint)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                .combinedClickableIf(choosable, opensChooser)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("outbound-bar")
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    if (choosable) stateDescription = hint
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusLight(state)
            Column(Modifier.weight(1f)) {
                if (accounts.size > 1) {
                    Text(account.displayLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            if (choosable) {
                Icon(Icons.Filled.UnfoldMore, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (state != RegistrationState.Registered) {
            Text(state.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** The sheet behind the bar: with several accounts first the account, then the number. Choosing a number sets it for the next call. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutboundChooserSheet(model: AppModel, accountId: String?, onAccount: (String) -> Unit, onDismiss: () -> Unit) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val registrations by model.phone.registrations.collectAsStateWithLifecycle()
    model.settingsRevision.collectAsStateWithLifecycle().value
    model.selfExtension.states.collectAsStateWithLifecycle().value
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState()).testTag("outbound-sheet")) {
            Text(stringResource(R.string.outbound_sheet_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

            if (accounts.size > 1) {
                section(stringResource(R.string.outbound_sheet_account), null) {
                    for (account in accounts) {
                        val state = registrations[account.id] ?: RegistrationState.Unregistered
                        CheckRow("${account.displayLabel} · ${state.label()}", checked = account.id == accountId) { onAccount(account.id) }
                    }
                }
            }

            if (accountId != null && model.canChooseOutbound(accountId)) {
                val selected = model.selectedOutbound(accountId)

                section(stringResource(R.string.outbound_sheet_number), stringResource(R.string.outbound_sheet_footer)) {
                    for (number in model.outboundNumbers(accountId)) {
                        CheckRow(PbxVocabulary.numberTitle(number), checked = selected?.number == number.number, modifier = Modifier.testTag("outbound-number-${number.number}")) {
                            model.selectOutbound(accountId, number.number)
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

/** "Niet storen staat aan": the dialler says so, with the way out. */
@Composable
private fun UnavailableBanner(model: AppModel, accountId: String) {
    model.selfExtension.states.collectAsStateWithLifecycle().value

    if (model.doNotDisturb(accountId) != true) {
        return
    }

    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(Brand.hangUp.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("unavailable-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.dialer_unavailable_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.dialer_unavailable_message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        TextButton(onClick = { model.setAvailable(true, accountId) }) { Text(stringResource(R.string.dialer_unavailable_action)) }
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
