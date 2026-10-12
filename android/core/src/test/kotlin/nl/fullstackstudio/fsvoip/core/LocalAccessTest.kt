// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeAuth(var availability: LocalAuthAvailability = LocalAuthAvailability.AVAILABLE, var result: LocalAuthResult = LocalAuthResult.SUCCESS) : LocalAuthenticating {
    var prompts = 0

    override fun availability() = availability

    override suspend fun evaluate(reason: String): LocalAuthResult {
        prompts++
        kotlinx.coroutines.yield()
        return result
    }
}

class LocalAccessTest {
    @Test
    fun asksOnceAndStaysOpenForFiveMinutes() = runBlocking {
        var clock = 1_000L
        val auth = FakeAuth()
        val gate = LocalAccessGate(auth, now = { clock })

        assertEquals(LocalAccessOutcome.Unlocked, gate.ensureUnlocked("x"))
        clock += 4 * 60_000
        assertEquals(LocalAccessOutcome.Unlocked, gate.ensureUnlocked("x"))
        assertEquals(1, auth.prompts)

        // Using the screen extends the window ...
        clock += 4 * 60_000
        gate.touch()
        clock += 4 * 60_000
        assertTrue(gate.isUnlocked)

        // ... five idle minutes do not.
        clock += 6 * 60_000
        assertFalse(gate.isUnlocked)
        gate.touch()
        assertFalse(gate.isUnlocked)
        gate.ensureUnlocked("x")
        assertEquals(2, auth.prompts)
    }

    @Test
    fun concurrentCallsShareOnePrompt() = runBlocking {
        val auth = FakeAuth()
        val gate = LocalAccessGate(auth)

        val results = listOf(async { gate.ensureUnlocked("a") }, async { gate.ensureUnlocked("b") }, async { gate.ensureUnlocked("c") })
        results.forEach { assertEquals(LocalAccessOutcome.Unlocked, it.await()) }
        assertEquals(1, auth.prompts)
    }

    @Test
    fun noScreenLockKeepsTheSectionClosed() = runBlocking {
        val auth = FakeAuth(availability = LocalAuthAvailability.NO_SCREEN_LOCK)
        val gate = LocalAccessGate(auth)

        assertEquals(LocalAccessOutcome.Unavailable(LocalAuthAvailability.NO_SCREEN_LOCK), gate.ensureUnlocked("x"))
        assertEquals(0, auth.prompts)
        assertFalse(gate.isUnlocked)
    }

    @Test
    fun cancelledAndFailedDoNotUnlock() = runBlocking {
        val auth = FakeAuth(result = LocalAuthResult.CANCELLED)
        val gate = LocalAccessGate(auth)

        assertEquals(LocalAccessOutcome.Cancelled, gate.ensureUnlocked("x"))
        auth.result = LocalAuthResult.FAILED
        assertEquals(LocalAccessOutcome.Failed, gate.ensureUnlocked("x"))
        assertFalse(gate.isUnlocked)

        auth.result = LocalAuthResult.SUCCESS
        assertEquals(LocalAccessOutcome.Unlocked, gate.ensureUnlocked("x"))
        gate.lock()
        assertFalse(gate.isUnlocked)
    }
}
