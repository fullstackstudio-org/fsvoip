// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The "Beheer" pages of the settings, each behind the local access gate (fingerprint / face / screen lock, five minutes together).
// The pages themselves live in their own slices; this file only opens them, and closes them when the right goes away.

package nl.fullstackstudio.fsvoip.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.ui.LocalAccessGateScreen
import nl.fullstackstudio.fsvoip.ui.media.RecordingsScreen
import nl.fullstackstudio.fsvoip.ui.pbx.PbxHubScreen
import nl.fullstackstudio.fsvoip.ui.pbx.PbxPart
import nl.fullstackstudio.fsvoip.ui.sounds.SoundsScreen

/** A part of the "Centrale" section. */
@Composable
fun PbxPageGate(model: AppModel, accountId: String, part: PbxPart, back: () -> Unit) {
    model.meByAccount.collectAsStateWithLifecycle().value

    LocalAccessGateScreen(
        model = model,
        title = stringResource(
            when (part) {
                PbxPart.NUMBERS -> R.string.snd_settings_admin_numbers
                PbxPart.DEVICES -> R.string.snd_pbx_devices_title
                PbxPart.RING_GROUPS -> R.string.snd_pbx_ringGroups_title
                PbxPart.OVERVIEW -> R.string.snd_pbx_title
                PbxPart.HOURS -> R.string.snd_pbx_hours_title
            },
        ),
        reason = stringResource(R.string.snd_pbx_lock_reason),
        message = stringResource(R.string.snd_pbx_lock_message),
        allowed = model.canManagePbx(accountId),
        back = back,
    ) {
        PbxHubScreen(model, accountId, part, back)
    }
}

@Composable
fun SoundsPageGate(model: AppModel, accountId: String, back: () -> Unit) {
    val context = LocalContext.current
    val hubs = remember { model.settingsHubs(context) }
    model.meByAccount.collectAsStateWithLifecycle().value
    hubs.soundsDenied.collectAsStateWithLifecycle().value

    LocalAccessGateScreen(
        model = model,
        title = stringResource(R.string.snd_settings_admin_sounds),
        reason = stringResource(R.string.snd_sounds_lock_reason),
        message = stringResource(R.string.snd_sounds_lock_message),
        allowed = hubs.canManageSounds(accountId),
        back = back,
    ) {
        SoundsScreen(model, accountId, back)
    }
}

@Composable
fun InvitePageGate(model: AppModel, accountId: String, back: () -> Unit) {
    model.meByAccount.collectAsStateWithLifecycle().value

    LocalAccessGateScreen(
        model = model,
        title = stringResource(R.string.snd_settings_admin_invite),
        reason = stringResource(R.string.snd_invite_lock_reason),
        message = stringResource(R.string.snd_invite_lock_message),
        allowed = model.canInvite(accountId),
        back = back,
    ) {
        InviteScreen(model, accountId, back)
    }
}

@Composable
fun RecordingsPageGate(model: AppModel, accountId: String, back: () -> Unit) {
    val me = model.meByAccount.collectAsStateWithLifecycle().value

    LocalAccessGateScreen(
        model = model,
        title = stringResource(R.string.snd_media_recordings_title),
        reason = stringResource(R.string.snd_media_lock_reason_recordings),
        message = stringResource(R.string.snd_media_lock_message_recordings),
        allowed = model.canManagePbx(accountId) && me[accountId]?.capabilities?.recordings == true,
        back = back,
    ) {
        RecordingsScreen(model, accountId, back)
    }
}
