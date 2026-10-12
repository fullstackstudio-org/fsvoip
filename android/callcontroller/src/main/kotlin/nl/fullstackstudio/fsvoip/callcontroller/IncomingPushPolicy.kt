// SPDX-License-Identifier: AGPL-3.0-or-later
//
// What to do with a `ring` push (plan D1, D9, D16). Pure: no Telecom, no SIP, no clock of its own, so every path is
// unit tested. `PhoneController.handleRingPush` carries the decision out. Same rules as iOS.

package nl.fullstackstudio.fsvoip.callcontroller

import java.time.Duration
import java.time.Instant
import java.util.UUID
import nl.fullstackstudio.fsvoip.core.PushMessage
import nl.fullstackstudio.fsvoip.core.RingPush

/** Why a `ring` push does not become a ringing call. */
enum class RingPushRejection {
    /** Not a readable `ring` message. */
    INVALID_PAYLOAD,

    /** `expiresAt` has passed: the PBX stopped waiting for this phone long ago. */
    EXPIRED,

    /** The push is for an account that is not (or no longer) on this phone. */
    UNKNOWN_ACCOUNT,

    /** The app already has a call (one call at a time in this version). */
    BUSY,

    /** This call is already known: its INVITE came first, the push came twice, or the call already ended. */
    ALREADY_HANDLED,

    /** The system refused the call (another call in a different app, Do Not Disturb policies). */
    REFUSED_BY_SYSTEM,
}

sealed interface RingPushDecision {
    /** Report the call as ringing and wait for its INVITE until [inviteDeadline]. */
    data class Ring(val ring: RingPush, val inviteDeadline: Instant) : RingPushDecision

    /** Do not ring. [ring] is the payload when it could be read (for the recents). */
    data class Reject(val rejection: RingPushRejection, val ring: RingPush?) : RingPushDecision
}

data class IncomingPushPolicy(
    /** How long to wait for the INVITE after the push arrived (the PBX waits at most 10 s for this phone, plan D1). */
    val inviteTimeout: Duration = Duration.ofSeconds(10),
    /** Clocks of the phone and the server differ a little; a push is only "expired" this long after `expiresAt`. */
    val clockTolerance: Duration = Duration.ofSeconds(5),
    /** Never wait less than this, even for a push that arrived late (the INVITE may be on its way). */
    val minimumWait: Duration = Duration.ofSeconds(2),
) {
    fun decide(
        message: PushMessage?,
        now: Instant,
        knownAccounts: Set<String>,
        busy: Boolean,
        isHandled: (String) -> Boolean,
    ): RingPushDecision {
        val ring = (message as? PushMessage.Ring)?.ring

        if (ring == null || callUuid(ring.callRef) == null) {
            return RingPushDecision.Reject(RingPushRejection.INVALID_PAYLOAD, null)
        }

        if (isHandled(ring.callRef)) {
            return RingPushDecision.Reject(RingPushRejection.ALREADY_HANDLED, ring)
        }

        if (now.isAfter(ring.expiresAt.plus(clockTolerance))) {
            return RingPushDecision.Reject(RingPushRejection.EXPIRED, ring)
        }

        if (ring.accountId !in knownAccounts) {
            return RingPushDecision.Reject(RingPushRejection.UNKNOWN_ACCOUNT, ring)
        }

        if (busy) {
            return RingPushDecision.Reject(RingPushRejection.BUSY, ring)
        }

        return RingPushDecision.Ring(ring, inviteDeadline(ring, now))
    }

    /** [inviteTimeout] after now, but not much past `expiresAt` (plus the tolerance), and never less than [minimumWait]. */
    fun inviteDeadline(ring: RingPush, now: Instant): Instant {
        val untilExpiry = Duration.between(now, ring.expiresAt.plus(clockTolerance))
        val wait = maxOf(minimumWait, minOf(inviteTimeout, untilExpiry))

        return now.plus(wait)
    }

    companion object {
        private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /**
         * The call id of a call with this `callRef`. The callRef is the FreeSWITCH call UUID, so the push and its INVITE
         * map onto the SAME call whichever arrives first.
         */
        fun callUuid(callRef: String?): UUID? = callRef?.takeIf { UUID_PATTERN.matches(it) }?.let(UUID::fromString)

        /**
         * Does the caller of an INVITE without `X-FSS-Call` plausibly belong to a push (fallback match, plan D16)? Equal
         * when one side is unknown, or when the last 9 digits agree (`+31612345678` vs `0612345678`).
         */
        fun callersMatch(pushed: String?, invited: String?): Boolean {
            val a = pushed.orEmpty().filter { it in '0'..'9' }
            val b = invited.orEmpty().filter { it in '0'..'9' }

            if (a.isEmpty() || b.isEmpty()) {
                return true
            }

            return a == b || a.takeLast(9) == b.takeLast(9)
        }
    }
}

/** Runs [action] after [delayMillis] on the main thread; the returned function cancels it. Injected so tests control time. */
fun interface CallScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit
}

/** Runs work on the main thread (the engine's callbacks may come from any thread). */
fun interface MainExecutor {
    fun execute(block: () -> Unit)
}
