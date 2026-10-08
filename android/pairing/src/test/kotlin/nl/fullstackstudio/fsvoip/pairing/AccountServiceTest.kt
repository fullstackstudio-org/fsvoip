// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.pairing

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.fullstackstudio.fsvoip.core.AccountStore
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.FsJson
import nl.fullstackstudio.fsvoip.core.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountServiceTest {
    private val device = DeviceDescriptor(model = "Pixel 9", osVersion = "16", appVersion = "1.0 (1)", installId = "a1b2c3d4e5f60718", sipInstanceId = "urn:uuid:x", pushToken = "fcm:token")

    @Test
    fun pairStoresTheAccountAndSendsAndroidDeviceInfo() = runTest {
        val transport = FakeTransport { response(201, fixture("pair-response.json")) }
        val store = AccountStore(InMemorySecretStore())
        val account = AccountService(client(transport), store).pair(PairingLink(GOOD_TOKEN), device)

        assertEquals(account, store.account(account.id))
        val body = FsJson.default.parseToJsonElement(transport.requests.single().body!!.toString(Charsets.UTF_8)).jsonObject
        val sentDevice = body["device"]!!.jsonObject
        assertEquals("android", sentDevice["platform"]!!.jsonPrimitive.content)
        assertEquals("fcm", sentDevice["pushKind"]!!.jsonPrimitive.content)
        assertEquals("a1b2c3d4e5f60718", sentDevice["installId"]!!.jsonPrimitive.content)
        assertFalse(sentDevice.containsKey("alertPushToken"))
        assertFalse(testSink.messages.any { it.contains("fixture-not-a-real-password") })
    }

    @Test
    fun pairWithoutPushTokenSendsNoPushKind() {
        val info = device.copy(pushToken = null).toDeviceInfo()
        assertNull(info.pushKind)
        assertNull(info.pushToken)
    }

    @Test
    fun refreshUpdatesOrRemoves() = runTest {
        val transport = FakeTransport { response(201, fixture("pair-response.json")) }
        val store = AccountStore(InMemorySecretStore())
        val service = AccountService(client(transport), store)
        val account = service.pair(PairingLink(GOOD_TOKEN), device)

        transport.respond = { response(200, fixture("me-response.json")) }
        val result = service.refresh(account) as AccountRefreshResult.Updated
        assertEquals("Jan (balie)", store.account(account.id)!!.displayLabel)
        assertEquals(3, result.internalContacts.size)

        transport.respond = { response(401, fixture("error-unauthorized.json")) }
        assertEquals(AccountRefreshResult.Revoked, service.refresh(account))
        assertNull(store.account(account.id))
    }

    @Test
    fun unpairToleratesAGoneServerSidePairing() = runTest {
        val transport = FakeTransport { response(201, fixture("pair-response.json")) }
        val store = AccountStore(InMemorySecretStore())
        val service = AccountService(client(transport), store)
        val account = service.pair(PairingLink(GOOD_TOKEN), device)

        transport.respond = { response(404, fixture("error-not-found.json")) }
        service.unpair(account)
        assertTrue(store.accounts().isEmpty())
    }

    @Test
    fun failuresMapToWhatTheUserCanDo() {
        assertEquals(PairingFailure.CodeExpiredOrUsed, PairingFailure.from(ApiException.NotFound()))
        assertEquals(PairingFailure.Revoked, PairingFailure.from(ApiException.Unauthorized()))
        assertEquals(PairingFailure.TemporarilyUnavailable, PairingFailure.from(ApiException.Unavailable(true, null)))
        assertEquals(PairingFailure.Network, PairingFailure.from(ApiException.Transport("x")))
        assertEquals(PairingFailure.TooManyAttempts(5), PairingFailure.from(ApiException.RateLimited(5)))
        assertTrue(PairingFailure.TemporarilyUnavailable.isRetryable)
        assertFalse(PairingFailure.CodeExpiredOrUsed.isRetryable)
    }

    @Test
    fun aliasCleaning() {
        assertNull(AccountService.cleanAlias("   "))
        assertEquals("Jan balie", AccountService.cleanAlias(" Jan\nbalie "))
        assertEquals(60, AccountService.cleanAlias("x".repeat(80))!!.length)
    }
}
