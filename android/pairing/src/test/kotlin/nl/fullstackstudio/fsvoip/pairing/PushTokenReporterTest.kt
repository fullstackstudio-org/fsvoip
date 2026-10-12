// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.pairing

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.fullstackstudio.fsvoip.core.AccountStore
import nl.fullstackstudio.fsvoip.core.FsJson
import nl.fullstackstudio.fsvoip.core.InMemoryKeyValueStore
import nl.fullstackstudio.fsvoip.core.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushTokenReporterTest {
    private suspend fun setUp(): Triple<FakeTransport, AccountStore, PushTokenLedger> {
        val transport = FakeTransport { response(201, fixture("pair-response.json")) }
        val store = AccountStore(InMemorySecretStore())
        AccountService(client(transport), store).pair(PairingLink(GOOD_TOKEN), DeviceDescriptor(null, null, null, "a1b2c3d4"))
        transport.requests.clear()
        transport.respond = { response(200, fixture("ok-response.json")) }

        return Triple(transport, store, PushTokenLedger(InMemoryKeyValueStore()))
    }

    @Test
    fun nothingIsSentBeforeFirebaseAnswered() = runTest {
        val (transport, store, ledger) = setUp()
        val reporter = PushTokenReporter(client(transport), store, ledger)

        assertEquals(PushTokenReport(), reporter.report())
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun sendsOncePerLaunchAndOnChange() = runTest {
        val (transport, store, ledger) = setUp()
        val reporter = PushTokenReporter(client(transport), store, ledger)

        reporter.setToken("fcm-1")
        assertEquals(1, reporter.report().sent.size)
        assertEquals(0, reporter.report().sent.size)

        val body = FsJson.default.parseToJsonElement(transport.requests.single().body!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals("fcm", body["pushKind"]!!.jsonPrimitive.content)
        assertEquals("fcm-1", body["pushToken"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("alertPushToken"))
        assertFalse(body.containsKey("pushEnv"))

        reporter.setToken("fcm-2")
        assertEquals(1, reporter.report().sent.size)

        // A new launch sends again even when unchanged.
        val nextLaunch = PushTokenReporter(client(transport), store, ledger)
        nextLaunch.setToken("fcm-2")
        assertEquals(1, nextLaunch.report().sent.size)
    }

    @Test
    fun clearsOnlyWhatWasRegistered() = runTest {
        val (transport, store, ledger) = setUp()
        val reporter = PushTokenReporter(client(transport), store, ledger)

        reporter.setToken(null)
        assertEquals(0, reporter.report().sent.size)

        reporter.setToken("fcm-1")
        reporter.report()
        reporter.setToken(null)
        assertEquals(1, reporter.report().sent.size)
        val body = FsJson.default.parseToJsonElement(transport.requests.last().body!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals(JsonNull, body["pushToken"])
    }

    @Test
    fun revokedAndFailedAreReported() = runTest {
        val (transport, store, ledger) = setUp()
        val reporter = PushTokenReporter(client(transport), store, ledger)
        reporter.setToken("fcm-1")

        transport.respond = { response(503, fixture("error-unavailable-retryable.json")) }
        assertEquals(1, reporter.report().failed.size)

        transport.respond = { response(401, fixture("error-unauthorized.json")) }
        assertEquals(1, reporter.report().revoked.size)
    }
}
