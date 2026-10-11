// SPDX-License-Identifier: AGPL-3.0-or-later
//
// "Gebruiker uitnodigen": an administrator picks the extension of a colleague, gets a QR code and a link, and shares it. The link is a
// credential (it pairs a phone with that extension): it lives only in this screen's state while it is shown, never in a log, a
// preference or a file, and it works once. Android port of the iOS `InviteUserPage`.

package nl.fullstackstudio.fsvoip.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import nl.fullstackstudio.fsvoip.core.AppPairingResponse
import nl.fullstackstudio.fsvoip.core.InviteRules
import nl.fullstackstudio.fsvoip.core.PbxFailure

/** What the page shows. [Shown] is the only place the link exists. */
private sealed interface InvitePhase {
    data object Choosing : InvitePhase

    data object Creating : InvitePhase

    class Shown(val deviceName: String, val response: AppPairingResponse) : InvitePhase

    data class Failed(val failure: PbxFailure) : InvitePhase
}

@Composable
fun InviteScreen(model: AppModel, accountId: String, back: () -> Unit) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val all by model.pbx.devices.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    val scope = rememberCoroutineScope()

    var loadFailure by remember { mutableStateOf<PbxFailure?>(null) }
    var loading by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf<InvitePhase>(InvitePhase.Choosing) }

    LaunchedEffect(accountId, attempt) {
        account ?: return@LaunchedEffect
        loading = true
        loadFailure = model.pbx.loadDevices(account)
        loading = false
    }

    AdminPage(model, stringResource(R.string.settings_admin_invite), back, reasonRes = R.string.invite_lock_reason, messageRes = R.string.invite_lock_message) {
        val page = all[accountId]

        loadFailure?.let { NoteCard(pbxFailureText(it), isError = true) }

        if (page == null) {
            if (loading) Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
            else if (loadFailure != null) TextButton(onClick = { attempt++ }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.invite_loadFailed) + " · " + stringResource(R.string.action_retry)) }
            return@AdminPage
        }

        val ownId = model.selfExtension.state(accountId)?.id
        val candidates = InviteRules.candidates(page.devices, ownId, account?.extensionNumber)

        when (val current = phase) {
            is InvitePhase.Shown -> InviteShown(current) {
                // Back to the list; the link is gone.
                phase = InvitePhase.Choosing
            }
            else -> {
                if (candidates.isEmpty()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.invite_empty_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.invite_empty_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                    return@AdminPage
                }

                Text(stringResource(R.string.invite_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

                if (current is InvitePhase.Failed) {
                    NoteCard(if (current.failure is PbxFailure.Conflict) stringResource(R.string.invite_error_ownDevice) else pbxFailureText(current.failure), isError = true)
                }

                section(stringResource(R.string.invite_pick), stringResource(R.string.invite_pick_footer)) {
                    Column(Modifier.selectableGroup()) {
                        for (device in candidates) {
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = selected == device.id, role = Role.RadioButton, onClick = { selected = device.id }).padding(horizontal = 16.dp, vertical = 4.dp).testTag("invite-device"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected == device.id, onClick = null)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(device.name, style = MaterialTheme.typography.bodyLarge)
                                    device.extensionNumber?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                }

                Column(Modifier.padding(16.dp)) {
                    if (current == InvitePhase.Creating) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        PrimaryButton(
                            stringResource(R.string.invite_create),
                            enabled = selected != null && !model.isPbxReadOnly(accountId),
                            modifier = Modifier.testTag("invite-create"),
                            onClick = {
                                val target = account ?: return@PrimaryButton
                                val deviceId = selected ?: return@PrimaryButton
                                val name = candidates.firstOrNull { it.id == deviceId }?.name.orEmpty()
                                phase = InvitePhase.Creating

                                scope.launch {
                                    val (response, failure) = model.pbx.invite(target, deviceId)
                                    phase = if (response != null) InvitePhase.Shown(name, response) else InvitePhase.Failed(failure ?: PbxFailure.Other)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InviteShown(shown: InvitePhase.Shown, another: () -> Unit) {
    val context = LocalContext.current
    var remaining by remember { mutableLongStateOf(InviteRules.remainingSeconds(shown.response.expiresAt, Instant.now())) }
    val expired = remaining <= 0

    LaunchedEffect(shown) {
        while (remaining > 0) {
            delay(1000)
            remaining = InviteRules.remainingSeconds(shown.response.expiresAt, Instant.now())
        }
    }

    val qr = remember(shown) { qrBitmap(shown.response.url) }
    val label = stringResource(R.string.invite_qr_label, shown.deviceName)
    val message = stringResource(R.string.invite_share_message, shown.deviceName)
    val subject = stringResource(R.string.invite_share_subject)

    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.invite_shown_title, shown.deviceName), style = MaterialTheme.typography.titleLarge)

        if (expired) {
            Text(stringResource(R.string.invite_expired_title), style = MaterialTheme.typography.titleMedium, color = Brand.hangUp)
            Text(stringResource(R.string.invite_expired_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            qr?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(240.dp).semantics { contentDescription = label }.testTag("invite-qr")) }
            Text(stringResource(R.string.invite_shown_how), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.invite_countdown, InviteRules.countdown(remaining)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("invite-countdown"))
            Text(stringResource(R.string.invite_shown_once), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SecondaryButton(
                stringResource(R.string.invite_share),
                icon = Icons.Filled.Share,
                modifier = Modifier.testTag("invite-share"),
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, message + "\n" + shown.response.url)
                    context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            )
        }

        TextButton(onClick = another) { Text(stringResource(R.string.invite_another)) }
    }
}

/** A crisp black-on-white QR code of [text], or `null` for an empty text. */
private fun qrBitmap(text: String, size: Int = 640): Bitmap? {
    if (text.isEmpty()) return null

    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
    val pixels = IntArray(size * size) { index -> if (matrix.get(index % size, index / size)) AndroidColor.BLACK else AndroidColor.WHITE }

    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
