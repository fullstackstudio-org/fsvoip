// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun account() = StoredAccount(
    id = "acc", label = "L", pbxName = "P", extensionName = "Jan", extensionNumber = "102", customerName = "C", deviceToken = Secret("t"), installId = "i",
    sip = SipCredentials("102", Secret("p"), "d", "x", 5060, ServerTransport.TCP, true), pairedAtMillis = 0,
)

/** A service that counts every call and always fails: what matters is whether it was reached. */
private class CountingService {
    var calls = 0

    val service: PbxServicing = Proxy.newProxyInstance(PbxServicing::class.java.classLoader, arrayOf(PbxServicing::class.java)) { _, _, _ ->
        calls++
        throw IllegalStateException("not reachable in this test")
    } as PbxServicing
}

/** The local check is open: a change goes out. It is not: nothing is sent, not even the invite link. */
class PbxLockTest {
    @Test
    fun nothingChangesWhileTheLocalCheckIsClosed() = runBlocking {
        val fake = CountingService()
        val hub = PbxHub(fake.service, isUnlocked = { false })
        val account = account()

        assertEquals(PbxFailure.Locked, hub.saveDevice(account, "d", PbxDevicePatch(version = 1)))
        assertEquals(PbxFailure.Locked, hub.saveRingGroup(account, "g", PbxRingGroupPatch(version = 1)))
        assertEquals(PbxFailure.Locked, hub.renameSound(account, "s", "Nieuw"))
        assertEquals(PbxFailure.Locked, hub.deleteSound(account, "s"))
        assertEquals(PbxFailure.Locked, hub.uploadSound(account, "n", "f.m4a", "audio/mp4", ByteArray(4)))
        assertEquals(PbxFailure.Locked, hub.invite(account, "d").second)
        assertNull(hub.invite(account, "d").first)

        assertEquals(0, fake.calls)
    }

    @Test
    fun anOpenCheckLetsTheRequestThrough() = runBlocking {
        val fake = CountingService()
        val hub = PbxHub(fake.service, isUnlocked = { true })

        // The fake fails every call; what matters is that this one was sent (and is no `Locked`).
        assertNotEquals(PbxFailure.Locked, hub.renameSound(account(), "s", "Nieuw"))
        assertTrue(fake.calls > 0)
    }

    @Test
    fun theGateClosesAfterFiveIdleMinutesSoAMutationIsRefused() = runBlocking {
        var clock = 0L
        val gate = LocalAccessGate(object : LocalAuthenticating {
            override fun availability() = LocalAuthAvailability.AVAILABLE

            override suspend fun evaluate(reason: String) = LocalAuthResult.SUCCESS
        }, now = { clock })
        val fake = CountingService()
        val hub = PbxHub(fake.service, isUnlocked = { gate.isUnlocked })

        gate.ensureUnlocked("x")
        assertNotEquals(PbxFailure.Locked, hub.renameSound(account(), "s", "a"))

        val before = fake.calls
        clock += LocalAccessGate.VALIDITY_MS + 1
        assertEquals(PbxFailure.Locked, hub.renameSound(account(), "s", "a"))
        assertEquals(before, fake.calls)
    }
}
