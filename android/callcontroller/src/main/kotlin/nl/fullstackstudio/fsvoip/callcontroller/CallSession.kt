// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.callcontroller

import java.time.Instant
import java.util.UUID
import nl.fullstackstudio.fsvoip.sipengine.CallDirection
import nl.fullstackstudio.fsvoip.sipengine.CallEndReason
import nl.fullstackstudio.fsvoip.sipengine.CallId
import nl.fullstackstudio.fsvoip.sipengine.CallState
import nl.fullstackstudio.fsvoip.sipengine.SipAccountId

/** One call as the app shows it: the system side (`id`) joined with the SIP side (`engineCallId`). */
data class CallSession(
    val id: UUID,
    val engineCallId: CallId?,
    val direction: CallDirection,
    val accountId: SipAccountId,
    val accountLabel: String,
    val remoteNumber: String?,
    val remoteName: String?,
    val phase: Phase,
    val createdAt: Instant,
    val isMuted: Boolean = false,
    val isOnHold: Boolean = false,
    val connectedAt: Instant? = null,
    /** `callRef` of the push that announced this call (= the `X-FSS-Call` header of its INVITE). */
    val fssCallRef: String? = null,
    /** Reported to the system from a push; the SIP INVITE has not arrived yet (`engineCallId == null`). */
    val awaitingInvite: Boolean = false,
    /** The user answered before the INVITE arrived: answer it as soon as it does. */
    val answerPending: Boolean = false,
    /** The user ended (or declined) the call on the call UI: the system must not be told again. */
    val endedByUser: Boolean = false,
    val reportedConnected: Boolean = false,
) {
    sealed interface Phase {
        /** Outgoing, waiting for the system to start the call. */
        data object Starting : Phase

        /** Outgoing, INVITE sent / the other side is ringing. */
        data object Ringing : Phase

        /** Incoming, ringing here. */
        data object Incoming : Phase

        /** Answered, media is being set up. */
        data object Connecting : Phase

        data object Active : Phase

        /** We put the call on hold. */
        data object Held : Phase

        /** The other side put us on hold. */
        data object HeldByRemote : Phase

        data class Ended(val reason: CallEndReason) : Phase

        val isEnded: Boolean
            get() = this is Ended

        /** Talking (or on hold): the timer runs. */
        val isConnected: Boolean
            get() = this == Active || this == Held || this == HeldByRemote
    }

    /** Name if known, otherwise the number, otherwise `null` (anonymous). */
    val remoteTitle: String?
        get() = remoteName?.takeIf { it.isNotEmpty() } ?: remoteNumber?.takeIf { it.isNotEmpty() }

    companion object {
        /** Map an engine state onto the phase. */
        fun phaseFor(state: CallState): Phase = when (state) {
            CallState.IncomingRinging -> Phase.Incoming
            CallState.OutgoingInitiated, CallState.OutgoingRinging -> Phase.Ringing
            CallState.Connecting -> Phase.Connecting
            CallState.Active -> Phase.Active
            CallState.Held -> Phase.Held
            CallState.HeldByRemote -> Phase.HeldByRemote
            is CallState.Ended -> Phase.Ended(state.reason)
        }
    }
}

/** Why the phone refused to start a call. */
sealed class PhoneException(message: String) : Exception(message) {
    class InvalidNumber : PhoneException("invalid number")
    class UnknownAccount : PhoneException("unknown account")

    /** The line is not registered right now (no network, wrong credentials). */
    class LineNotConnected : PhoneException("line not connected")

    /** There is already a call. */
    class CallInProgress : PhoneException("call in progress")
}
