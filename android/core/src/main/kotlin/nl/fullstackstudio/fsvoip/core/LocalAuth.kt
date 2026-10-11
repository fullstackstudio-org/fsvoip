// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Local access control for the sensitive screens (the "Beheer" section, recordings, voicemail of a colleague): fingerprint, face or
// the device PIN/pattern/password. It is a comfort against a phone that is picked up while unlocked, not a server safeguard (the
// server enforces the role on every route). Nothing here is ever stored: not the result, not a token.

package nl.fullstackstudio.fsvoip.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async

enum class LocalAuthAvailability {
    AVAILABLE,

    /** The phone has no screen lock: without one there is nothing to check, so the sections stay closed. */
    NO_PASSCODE,

    UNAVAILABLE,
}

enum class LocalAuthResult {
    SUCCESS,

    /** The user cancelled (or the system did, e.g. the app went to the background). */
    CANCELLED,

    FAILED,

    /** No screen lock on this phone (or the check cannot run at all). */
    UNAVAILABLE,
}

/** The system check, replaceable in tests and the demo. */
interface LocalAuthenticating {
    fun availability(): LocalAuthAvailability

    suspend fun evaluate(reason: String): LocalAuthResult
}

/** What [LocalAccessGate.ensureUnlocked] found. */
sealed interface LocalAccessOutcome {
    data object Unlocked : LocalAccessOutcome

    data object Cancelled : LocalAccessOutcome

    data object Failed : LocalAccessOutcome

    /** The phone has no screen lock (or no check is possible): the section is not available. */
    data class Unavailable(val availability: LocalAuthAvailability) : LocalAccessOutcome
}

/**
 * Keeps the check "open" for five minutes of inactivity, so a user who edits several things in a row is asked once. Every use of a
 * protected screen ([touch]) extends the window; after five idle minutes the next use asks again. Main thread only.
 */
class LocalAccessGate(
    private val authenticator: LocalAuthenticating,
    private val scope: CoroutineScope = MainScope(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var lastActivity: Long? = null
    private var inFlight: Deferred<LocalAccessOutcome>? = null

    val availability: LocalAuthAvailability get() = authenticator.availability()

    /** Inside the five-minute window. */
    val isUnlocked: Boolean
        get() = lastActivity?.let { now() - it <= VALIDITY_MILLIS } ?: false

    /** The user is still working in a protected screen: keep it open. Does nothing when it is locked. */
    fun touch() {
        if (isUnlocked) lastActivity = now()
    }

    fun lock() {
        lastActivity = null
    }

    /** Open already (and extend), or ask the system. Concurrent calls share one prompt. */
    suspend fun ensureUnlocked(reason: String): LocalAccessOutcome {
        if (isUnlocked) {
            lastActivity = now()
            return LocalAccessOutcome.Unlocked
        }

        inFlight?.let { return it.await() }

        val availability = authenticator.availability()
        if (availability != LocalAuthAvailability.AVAILABLE) return LocalAccessOutcome.Unavailable(availability)

        val task = scope.async {
            when (authenticator.evaluate(reason)) {
                LocalAuthResult.SUCCESS -> LocalAccessOutcome.Unlocked
                LocalAuthResult.CANCELLED -> LocalAccessOutcome.Cancelled
                LocalAuthResult.FAILED -> LocalAccessOutcome.Failed
                LocalAuthResult.UNAVAILABLE -> LocalAccessOutcome.Unavailable(authenticator.availability())
            }
        }
        inFlight = task

        val outcome = try {
            task.await()
        } finally {
            inFlight = null
        }

        if (outcome == LocalAccessOutcome.Unlocked) lastActivity = now()

        return outcome
    }

    companion object {
        const val VALIDITY_MILLIS: Long = 5 * 60 * 1000
    }
}
