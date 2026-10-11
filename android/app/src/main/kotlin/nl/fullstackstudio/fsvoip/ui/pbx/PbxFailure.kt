// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.pbx

import androidx.annotation.StringRes
import android.content.Context
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.RecordingCost
import nl.fullstackstudio.fsvoip.core.recordingCost

/**
 * Where the words come from. The screens use the app resources ([ContextPbxText]); the unit tests read the Dutch strings file, so
 * the same sentences are checked as the user sees.
 */
interface PbxText {
    fun get(@StringRes id: Int, vararg args: Any): String
}

class ContextPbxText(private val context: Context) : PbxText {
    override fun get(id: Int, vararg args: Any): String = context.getString(id, *args)
}

/**
 * Why a "Centrale" call did not work, as the screens need to tell it. The server decides what is allowed; this only sorts the
 * answer into what the user can do about it.
 */
sealed class PbxFailure {
    /** 403: the role was taken away. The whole section disappears. */
    data object AccessLost : PbxFailure()

    /** 401: the pairing is gone. The app removes the account. */
    data object Revoked : PbxFailure()

    /** 409 `read_only`: the centrale is frozen or still being set up. */
    data object ReadOnly : PbxFailure()

    /** 409 `stale`: someone else changed it in the meantime. */
    data object Stale : PbxFailure()

    /** 409 `in_use`: names of what still uses it. */
    data class InUse(val places: List<String>) : PbxFailure()

    /** 422: a block list stops these external numbers. */
    data class BlockedDestination(val numbers: List<String>) : PbxFailure()

    data class RateLimited(val retryAfterSeconds: Int?) : PbxFailure()

    data object Unavailable : PbxFailure()

    data object Offline : PbxFailure()

    /** 400 and other "this cannot be saved like this" answers. */
    data object Invalid : PbxFailure()

    data class Conflict(val code: String) : PbxFailure()

    /** The unlock check was cancelled or failed: nothing was sent. */
    data object Authentication : PbxFailure()

    /** The phone has no screen lock to unlock with. */
    data object NotAvailable : PbxFailure()

    /** 409 `advanced`: the number is set up in more detail than the app can show; only name and recording here. */
    data object Advanced : PbxFailure()

    /** A welcome message or menu needs a sound first. */
    data object GreetingRequired : PbxFailure()

    /** Switching on call recording costs money: show the price and ask first. */
    data class CostRequired(val cost: RecordingCost?) : PbxFailure()

    data object Other : PbxFailure()

    /** The sentence for the user. No server text, no telecom words. */
    fun message(t: PbxText): String = when (this) {
        AccessLost -> t.get(R.string.adm_pbx_error_accessLost)
        Revoked -> t.get(R.string.adm_pbx_error_revoked)
        ReadOnly -> t.get(R.string.adm_pbx_error_readOnly)
        Stale -> t.get(R.string.adm_pbx_error_stale)
        is InUse -> t.get(R.string.adm_pbx_error_inUse, if (places.isEmpty()) t.get(R.string.adm_pbx_error_inUse_somewhere) else places.joinToString(", "))
        is BlockedDestination -> t.get(R.string.adm_pbx_error_blocked, numbers.joinToString(", "))
        is RateLimited -> if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            t.get(R.string.adm_pbx_error_rateLimited_seconds, retryAfterSeconds)
        } else {
            t.get(R.string.adm_pbx_error_rateLimited)
        }
        Unavailable -> t.get(R.string.adm_pbx_error_unavailable)
        Offline -> t.get(R.string.adm_pbx_error_offline)
        Invalid -> t.get(R.string.adm_pbx_error_invalid)
        is Conflict -> if (code == "busy") t.get(R.string.adm_pbx_error_busy) else t.get(R.string.adm_pbx_error_conflict)
        Authentication -> t.get(R.string.adm_pbx_error_authentication)
        NotAvailable -> t.get(R.string.adm_pbx_error_notAvailable)
        Advanced -> t.get(R.string.adm_pbx_error_advanced)
        GreetingRequired -> t.get(R.string.adm_pbx_error_greetingRequired)
        is CostRequired -> t.get(R.string.adm_pbx_error_costRequired)
        Other -> t.get(R.string.error_generic)
    }

    companion object {
        fun classify(error: Throwable): PbxFailure {
            val api = error as? ApiException ?: return Other

            return when (api) {
                is ApiException.Forbidden -> AccessLost
                is ApiException.Unauthorized -> Revoked
                is ApiException.ReadOnly -> ReadOnly
                is ApiException.Stale, is ApiException.StaleChain -> Stale
                is ApiException.InUse -> InUse(api.places.map { it.name })
                is ApiException.BlockedDestination -> BlockedDestination(api.numbers)
                is ApiException.RateLimited -> RateLimited(api.retryAfterSeconds)
                is ApiException.Unavailable, is ApiException.UnexpectedStatus -> Unavailable
                is ApiException.Transport -> Offline
                is ApiException.GreetingRequired -> GreetingRequired
                is ApiException.Invalid, is ApiException.InvalidRequest, is ApiException.PayloadTooLarge, is ApiException.Resync,
                is ApiException.InvalidAudio, is ApiException.TooLarge,
                -> Invalid
                is ApiException.Conflict -> Conflict(api.code)
                is ApiException.Advanced -> Advanced
                is ApiException.CostNotAccepted -> CostRequired(api.recordingCost())
                is ApiException.TooMany -> Conflict("too_many")
                is ApiException.NoFreeSlot -> Conflict("no_free_slot")
                is ApiException.ParkUnavailable -> Conflict("park_unavailable")
                is ApiException.ParkUncertain -> Unavailable
                // The object is gone: reload and see.
                is ApiException.NotFound, is ApiException.Gone, is ApiException.CallNotFound -> Stale
                is ApiException.MissingDeviceToken, is ApiException.Decoding -> Other
            }
        }
    }
}

/** What a save did. */
sealed class PbxSaveOutcome {
    data object Saved : PbxSaveOutcome()

    /** Nothing differed from what the server has: nothing was sent. */
    data object Unchanged : PbxSaveOutcome()

    /** Changed in the meantime: the newest data has been loaded, the form should close. */
    data object Stale : PbxSaveOutcome()

    data class Failed(val failure: PbxFailure) : PbxSaveOutcome()

    /** The form can close (it saved, had nothing to save, or its data is out of date anyway). */
    val closesForm: Boolean get() = this !is Failed
}

/** What a step of a number's chain did. */
sealed class ChainSaveOutcome {
    data object Saved : ChainSaveOutcome()

    /** Nothing differed: nothing was sent. */
    data object Unchanged : ChainSaveOutcome()

    /** Changed in the meantime: the fresh chain is in the model; the form stays open and keeps what was typed. */
    data object Stale : ChainSaveOutcome()

    /** Switching recording on costs money: show the price (this one, or the one of the chain) and ask. */
    data class CostRequired(val cost: RecordingCost?) : ChainSaveOutcome()

    data class Failed(val failure: PbxFailure) : ChainSaveOutcome()

    val closesForm: Boolean get() = this is Saved || this is Unchanged
}
