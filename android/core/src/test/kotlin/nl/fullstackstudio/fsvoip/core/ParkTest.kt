// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testAccount() = StoredAccount(
    id = "acc", label = "L", pbxName = "P", extensionName = "Jan", extensionNumber = "102", customerName = "C", deviceToken = Secret("t"), installId = "i",
    sip = SipCredentials("102", Secret("p"), "d", "x", 5060, ServerTransport.TCP, true), pairedAtMillis = 0,
)

private class FakePark : ParkServicing {
    var parkError: Throwable? = null
    var parkCalls = 0
    var listed = ParkedCallsPage(calls = emptyList(), available = true)
    var hangupError: Throwable? = null

    override suspend fun park(account: StoredAccount, callId: String): ParkedCall {
        parkCalls++
        parkError?.let { throw it }
        return ParkedCall(id = "p1", slot = 1, retrieveNumber = "*5901", mine = true)
    }

    override suspend fun parked(account: StoredAccount) = listed

    override suspend fun hangup(account: StoredAccount, id: String) {
        hangupError?.let { throw it }
    }
}

class ParkTest {
    private val account = testAccount()

    @Test
    fun parkAddsTheCall() = runTest {
        val service = FakePark()
        val model = ParkModel(service)
        val outcome = model.park(account, "abc@host")
        assertTrue(outcome is ParkOutcome.Parked)
        assertEquals("*5901", model.calls.value[account.id]!!.single().retrieveNumber)
    }

    @Test
    fun uncertainRefreshesAndNeverRepeats() = runTest {
        val service = FakePark().apply {
            parkError = ApiException.ParkUncertain()
            listed = ParkedCallsPage(calls = listOf(ParkedCall(id = "x", slot = 2)), available = true)
        }
        val model = ParkModel(service)
        assertEquals(ParkOutcome.Uncertain, model.park(account, "abc@host"))
        assertEquals(1, service.parkCalls)
        assertEquals("x", model.calls.value[account.id]!!.single().id)
    }

    @Test
    fun noFreeSlotIsAFailure() = runTest {
        val service = FakePark().apply { parkError = ApiException.Conflict("no_free_slot", null, null) }
        val outcome = ParkModel(service).park(account, "abc@host")
        assertEquals(ParkOutcome.Failed(ParkFailure.NoFreeSlot), outcome)
    }

    @Test
    fun hangupOfAGoneCallCountsAsDone() = runTest {
        val service = FakePark().apply { hangupError = ApiException.NotFound() }
        val model = ParkModel(service)
        assertNull(model.hangup(account, ParkedCall(id = "p1", slot = 1)))
    }
}
