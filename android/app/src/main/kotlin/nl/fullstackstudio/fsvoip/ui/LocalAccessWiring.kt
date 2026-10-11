// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui

import android.content.Context
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.LocalAccessOutcome
import nl.fullstackstudio.fsvoip.ui.media.GateReason
import nl.fullstackstudio.fsvoip.ui.media.GateResult
import nl.fullstackstudio.fsvoip.ui.media.MediaAccessGate
import nl.fullstackstudio.fsvoip.ui.media.MediaWiring
import nl.fullstackstudio.fsvoip.ui.pbx.PbxGate

/**
 * Plugs the one local access gate ([LocalAccess]) into the slices that only describe their need for it: the media screens (recordings,
 * voicemail of colleagues) and the changes in the "Centrale" section. One prompt, one five minute window for all of them.
 */
object LocalAccessWiring {
    fun install(context: Context) {
        val app = context.applicationContext
        MediaWiring.gateFactory = { model -> MediaGateAdapter(app, model) }
    }

    /** The check before a change in the "Centrale" section. */
    fun pbxGate(context: Context, model: AppModel): PbxGate {
        val app = context.applicationContext

        return PbxGate {
            when (LocalAccess.gate(app, model).ensureUnlocked(app.getString(R.string.snd_pbx_lock_reason))) {
                LocalAccessOutcome.Unlocked -> PbxGate.Result.UNLOCKED
                LocalAccessOutcome.Cancelled -> PbxGate.Result.CANCELLED
                LocalAccessOutcome.Failed -> PbxGate.Result.FAILED
                is LocalAccessOutcome.Unavailable -> PbxGate.Result.UNAVAILABLE
            }
        }
    }
}

private class MediaGateAdapter(private val context: Context, private val model: AppModel) : MediaAccessGate {
    private val gate get() = LocalAccess.gate(context, model)

    override val isUnlocked: Boolean get() = gate.isUnlocked

    override suspend fun ensureUnlocked(reason: GateReason): GateResult {
        val text = context.getString(if (reason == GateReason.RECORDINGS) R.string.snd_media_lock_reason_recordings else R.string.snd_media_lock_reason_voicemail)

        return when (gate.ensureUnlocked(text)) {
            LocalAccessOutcome.Unlocked -> GateResult.UNLOCKED
            LocalAccessOutcome.Cancelled -> GateResult.CANCELLED
            LocalAccessOutcome.Failed -> GateResult.FAILED
            is LocalAccessOutcome.Unavailable -> GateResult.UNAVAILABLE
        }
    }

    override fun touch() = gate.touch()

    override fun lock() = gate.lock()
}
