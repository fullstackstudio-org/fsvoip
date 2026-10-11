// SPDX-License-Identifier: AGPL-3.0-or-later
//
// "Gebruiker uitnodigen" (admins): pick a colleague's extension, get a QR code and a link that pairs FSVoip on their phone, valid for
// ten minutes and once. 🚨 The link is a credential: it lives in the state of this page only (never in a log, a preference or a
// file) and is not drawn while the app is not on screen (the recents thumbnail would show it).

package nl.fullstackstudio.fsvoip.ui.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.InviteDevice
import nl.fullstackstudio.fsvoip.core.InviteModel
import nl.fullstackstudio.fsvoip.ui.Brand
import nl.fullstackstudio.fsvoip.ui.InitialsAvatar
import nl.fullstackstudio.fsvoip.ui.PrimaryButton
import nl.fullstackstudio.fsvoip.ui.SecondaryButton
import nl.fullstackstudio.fsvoip.ui.SubpageScaffold
import nl.fullstackstudio.fsvoip.ui.section

/** The modules of a QR code of [text] (true = dark), or null for an empty text. Pure, so a test can check it. */
object QrMatrix {
    class Grid(val size: Int, private val cells: BooleanArray) {
        operator fun get(x: Int, y: Int): Boolean = cells[y * size + x]
    }

    fun make(text: String): Grid? {
        if (text.isEmpty()) return null

        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)

        return Grid(matrix.width, BooleanArray(matrix.width * matrix.height) { matrix[it % matrix.width, it / matrix.width] })
    }
}

@Composable
fun InviteScreen(model: AppModel, accountId: String, back: () -> Unit) {
    val context = LocalContext.current
    val account = model.account(accountId)

    if (account == null) {
        LaunchedEffect(Unit) { back() }
        return
    }

    val hubs = remember { model.settingsHubs(context) }
    val hub = hubs.selfExtension
    val invite = remember(accountId) { InviteModel(hub, accountId, account.extensionNumber) }
    val phase by invite.phase.collectAsStateWithLifecycle()
    val selected by invite.selectedId.collectAsStateWithLifecycle()
    val meByAccount by model.meByAccount.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<InviteDevice>?>(null) }
    var loading by remember { mutableStateOf(true) }
    val readOnly = meByAccount[accountId]?.pbx?.readOnly == true

    suspend fun loadDevices() {
        loading = true
        hub.load(accountId)
        devices = hub.devices(accountId)
        loading = false
    }

    LaunchedEffect(accountId) { loadDevices() }
    // Leaving the page drops the link.
    DisposableEffect(invite) { onDispose { invite.reset() } }

    val shown = (phase as? InviteModel.Phase.Shown)?.invitation

    if (shown != null) {
        BackHandler { invite.reset() }

        SubpageScaffold(
            stringResource(R.string.snd_settings_admin_invite),
            { invite.reset() },
            footer = {
                InviteFooter(
                    primary = stringResource(R.string.snd_action_done),
                    onPrimary = back,
                    secondary = stringResource(R.string.snd_invite_another),
                    onSecondary = { invite.reset() },
                )
            },
        ) {
            InviteLinkCard(invite, shown)
        }

        return
    }

    val candidates = devices?.let { invite.candidates(it) }
    val failure = (phase as? InviteModel.Phase.Failed)?.failure
    val creating = phase == InviteModel.Phase.Creating

    SubpageScaffold(
        stringResource(R.string.snd_settings_admin_invite),
        back,
        footer = {
            nl.fullstackstudio.fsvoip.ui.settings.SaveFooter(
                canSave = selected != null && !readOnly && candidates?.any { it.id == selected } == true,
                onCancel = back,
                onSave = { scope.launch { invite.create { id -> devices?.firstOrNull { it.id == id }?.name ?: "" } } },
                saveTitle = stringResource(R.string.snd_invite_create),
                isSaving = creating,
            )
        },
    ) {
        if (failure != null) NoticeCard(selfFailureText(failure), modifier = Modifier.testTag("invite-error"))

        if (readOnly) NoticeCard(stringResource(R.string.snd_pbx_readOnly_message), isError = false, title = stringResource(R.string.snd_pbx_readOnly_title))

        when {
            candidates == null && loading -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator() }
            candidates == null -> EmptyNotice(Icons.Filled.WifiOff, stringResource(R.string.snd_invite_loadFailed), retry = stringResource(R.string.action_retry)) { scope.launch { loadDevices() } }
            candidates.isEmpty() -> EmptyNotice(Icons.Filled.PersonOff, stringResource(R.string.snd_invite_empty_title), message = stringResource(R.string.snd_invite_empty_message))
            else -> {
                Text(
                    stringResource(R.string.snd_invite_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )

                section(stringResource(R.string.snd_invite_pick), stringResource(R.string.snd_invite_pick_footer)) {
                    for (device in candidates) {
                        ChoiceRow(
                            device.name,
                            device.extensionNumber?.let { stringResource(R.string.account_extension, it) },
                            selected = selected == device.id,
                            leading = { InitialsAvatar(device.name, 36) },
                            modifier = Modifier.testTag("invite-device-row"),
                        ) { invite.select(device.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyNotice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String? = null, retry: String? = null, onRetry: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (retry != null) TextButton(onClick = onRetry) { Text(retry) }
    }
}

@Composable
private fun InviteFooter(primary: String, onPrimary: () -> Unit, secondary: String, onSecondary: () -> Unit) {
    SaveFooter(canSave = true, onCancel = onSecondary, onSave = onPrimary, saveTitle = primary, cancelTitle = secondary)
}

/** The QR code, the share button and the countdown. Once the time is up the link is no longer shown. */
@Composable
private fun InviteLinkCard(invite: InviteModel, invitation: InviteModel.Invitation) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Instant.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val active = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    LaunchedEffect(invitation) {
        while (true) {
            now = Instant.now()
            delay(1000)
        }
    }

    val remaining = invite.remaining(invitation, now)
    val qr = remember(invitation) { QrMatrix.make(invitation.url.reveal()) }

    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (remaining > 0) {
            Text(stringResource(R.string.snd_invite_shown_title, invitation.deviceName), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)

            if (!active) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(64.dp).padding(vertical = 88.dp).testTag("invite-hidden"))
            } else if (qr != null) {
                val description = stringResource(R.string.snd_invite_qr_label, invitation.deviceName)

                Canvas(
                    Modifier.size(248.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp).semantics { contentDescription = description }.testTag("invite-qr"),
                ) {
                    val cell = size.width / qr.size

                    for (y in 0 until qr.size) {
                        for (x in 0 until qr.size) {
                            if (qr[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                        }
                    }
                }
            }

            Text(stringResource(R.string.snd_invite_shown_how), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)

            if (active) {
                SecondaryButton(
                    stringResource(R.string.snd_invite_share),
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.snd_invite_share_subject))
                            .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.snd_invite_share_message, invitation.deviceName) + "\n" + invitation.url.reveal())
                        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    icon = Icons.Filled.Share,
                    modifier = Modifier.testTag("invite-share"),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("invite-countdown")) {
                Icon(Icons.Filled.Timer, contentDescription = null, tint = if (remaining < 60) Brand.amber else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.snd_invite_countdown, InviteModel.countdown(remaining)), style = MaterialTheme.typography.bodyMedium, color = if (remaining < 60) Brand.amber else MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text(stringResource(R.string.snd_invite_shown_once), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
        } else {
            Column(Modifier.padding(vertical = 48.dp).testTag("invite-expired"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.TimerOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                Text(stringResource(R.string.snd_invite_expired_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text(stringResource(R.string.snd_invite_expired_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}
