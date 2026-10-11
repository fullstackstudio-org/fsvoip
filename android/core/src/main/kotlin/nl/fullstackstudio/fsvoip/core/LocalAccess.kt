// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Local access control for the sensitive screens (the "Centrale" section, call recordings): fingerprint, face or the screen lock.
// It is a comfort against a phone that is picked up while unlocked, not a server safeguard (the server enforces the role on every
// route). Nothing here is stored: not the result, not a token. Android port of the iOS `LocalAccessGate`.

package nl.fullstackstudio.fsvoip.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LocalAuthAvailability {
    AVAILABLE,

    /** The phone has no screen lock: without one there is nothing to check, so the sections stay closed. */
    NO_SCREEN_LOCK,
    UNAVAILABLE,
}

enum class LocalAuthResult { SUCCESS, CANCELLED, FAILED, UNAVAILABLE }

/** The system check, replaceable in tests. */
interface LocalAuthenticating {
    fun availability(): LocalAuthAvailability

    /** Shows the system prompt (biometrics first, the screen lock as the fallback). */
    suspend fun evaluate(reason: String): LocalAuthResult
}

sealed interface LocalAccessOutcome {
    data object Unlocked : LocalAccessOutcome

    data object Cancelled : LocalAccessOutcome

    data object Failed : LocalAccessOutcome

    /** The phone has no screen lock (or no check is possible): the section is not available. */
    data class Unavailable(val availability: LocalAuthAvailability) : LocalAccessOutcome
}

/**
 * Keeps the check "open" for five minutes of inactivity, so a user who edits several things in a row is asked once. Every use of a
 * protected screen ([touch]) extends the window; after five idle minutes the next use asks again.
 */
class LocalAccessGate(
    private val authenticator: LocalAuthenticating,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var lastActivity: Long? = null
    private var inFlight: CompletableDeferred<LocalAccessOutcome>? = null

    val availability: LocalAuthAvailability
        get() = authenticator.availability()

    /** Inside the five-minute window. */
    val isUnlocked: Boolean
        get() = lastActivity?.let { now() - it <= VALIDITY_MS } ?: false

    /** The user is still working in a protected screen: keep it open. Does nothing when it is locked. */
    fun touch() {
        if (isUnlocked) lastActivity = now()
    }

    fun lock() {
        lastActivity = null
    }

    /** Open already (and extended), or ask the system. Concurrent calls share one prompt. */
    suspend fun ensureUnlocked(reason: String): LocalAccessOutcome {
        if (isUnlocked) {
            lastActivity = now()
            return LocalAccessOutcome.Unlocked
        }

        val (deferred, owner) = lock.withLock {
            inFlight?.let { it to false } ?: CompletableDeferred<LocalAccessOutcome>().also { inFlight = it }.let { it to true }
        }

        if (!owner) return deferred.await()

        val outcome = try {
            when (val availability = authenticator.availability()) {
                LocalAuthAvailability.AVAILABLE -> when (authenticator.evaluate(reason)) {
                    LocalAuthResult.SUCCESS -> LocalAccessOutcome.Unlocked
                    LocalAuthResult.CANCELLED -> LocalAccessOutcome.Cancelled
                    LocalAuthResult.FAILED -> LocalAccessOutcome.Failed
                    LocalAuthResult.UNAVAILABLE -> LocalAccessOutcome.Unavailable(authenticator.availability())
                }
                else -> LocalAccessOutcome.Unavailable(availability)
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            lock.withLock { inFlight = null }
            deferred.complete(LocalAccessOutcome.Cancelled)
            throw error
        }

        if (outcome == LocalAccessOutcome.Unlocked) lastActivity = now()

        lock.withLock { inFlight = null }
        deferred.complete(outcome)

        return outcome
    }

    companion object {
        const val VALIDITY_MS: Long = 5 * 60 * 1000
    }
}
