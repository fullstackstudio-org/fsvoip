// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class ScriptedAuth : LocalAuthenticating {
    var availabilityValue = LocalAuthAvailability.AVAILABLE
    val results = ArrayDeque<LocalAuthResult>()
    var evaluations = 0

    override fun availability() = availabilityValue

    override suspend fun evaluate(reason: String): LocalAuthResult {
        evaluations++

        return results.removeFirstOrNull() ?: LocalAuthResult.SUCCESS
    }
}

class LocalAccessGateTest {
    private var clock = 1_800_000_000_000L

    private fun gate(auth: LocalAuthenticating, scope: kotlinx.coroutines.CoroutineScope) = LocalAccessGate(auth, scope, now = { clock })

    @Test
    fun asksOnceAndStaysOpenForFiveMinutesOfActivity() = runTest {
        val auth = ScriptedAuth()
        val gate = gate(auth, this)

        assertFalse(gate.isUnlocked)
        assertEquals(LocalAccessOutcome.Unlocked, gate.ensureUnlocked("test"))
        assertEquals(1, auth.evaluations)

        // Four minutes later, still working: no new question, and the window moves on.
        clock += 4 * 60_000
        assertEquals(LocalAccessOutcome.Unlocked, gate.ensureUnlocked("test"))
        assertEquals(1, auth.evaluations)

        clock += 4 * 60_000
        gate.touch()
        assertTrue(gate.isUnlocked)

        // Idle for more than five minutes: locked again, the next use asks.
        clock += 5 * 60_000 + 1
        assertFalse(gate.isUnlocked)
        assertEquals(LocalAccessOutcome.Unlocked, gate.ensureUnlocked("test"))
        assertEquals(2, auth.evaluations)
    }

    @Test
    fun touchDoesNotOpenALockedGate() = runTest {
        val gate = gate(ScriptedAuth(), this)

        gate.touch()

        assertFalse(gate.isUnlocked)
    }

    @Test
    fun cancelledAndFailedStayLocked() = runTest {
        val auth = ScriptedAuth().apply { results += listOf(LocalAuthResult.CANCELLED, LocalAuthResult.FAILED) }
        val gate = gate(auth, this)

        assertEquals(LocalAccessOutcome.Cancelled, gate.ensureUnlocked("test"))
        assertEquals(LocalAccessOutcome.Failed, gate.ensureUnlocked("test"))
        assertFalse(gate.isUnlocked)
    }

    @Test
    fun noScreenLockMeansUnavailableAndNothingIsAsked() = runTest {
        val auth = ScriptedAuth().apply { availabilityValue = LocalAuthAvailability.NO_PASSCODE }
        val gate = gate(auth, this)

        assertEquals(LocalAccessOutcome.Unavailable(LocalAuthAvailability.NO_PASSCODE), gate.ensureUnlocked("test"))
        assertEquals(0, auth.evaluations)
        assertFalse(gate.isUnlocked)
    }

    @Test
    fun lockClosesImmediately() = runTest {
        val gate = gate(ScriptedAuth(), this)
        gate.ensureUnlocked("test")
        assertTrue(gate.isUnlocked)

        gate.lock()

        assertFalse(gate.isUnlocked)
    }

    @Test
    fun concurrentUsersShareOnePrompt() = runTest {
        val auth = ScriptedAuth()
        val gate = gate(auth, this)

        val first = async { gate.ensureUnlocked("a") }
        val second = async { gate.ensureUnlocked("b") }

        assertEquals(LocalAccessOutcome.Unlocked, first.await())
        assertEquals(LocalAccessOutcome.Unlocked, second.await())
        assertEquals(1, auth.evaluations)
    }

    @Test
    fun aCheckThatTurnsOutUnavailableIsReportedAsSuch() = runTest {
        val auth = ScriptedAuth().apply { results += LocalAuthResult.UNAVAILABLE }
        val gate = gate(auth, this)

        assertEquals(LocalAccessOutcome.Unavailable(LocalAuthAvailability.AVAILABLE), gate.ensureUnlocked("test"))
        assertFalse(gate.isUnlocked)
    }
}
