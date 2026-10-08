// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Decodes every file in `shared/fixtures` with the Kotlin models. A fixture without a test here fails
 * [everyFixtureIsCovered]: a new server field or message type cannot slip past the Android app unnoticed.
 */
class ContractTest {
    private val json = FsJson.default
    private val covered = mutableSetOf<String>()

    private fun fixture(name: String): String {
        covered += name
        return Fixtures.text(name)
    }

    @Test
    fun everyFixtureIsCovered() {
        val tests = ContractTest()
        tests.pairRequests()
        tests.pairResponses()
        tests.meResponses()
        tests.mePatch()
        tests.pushTokenRequests()
        tests.okResponse()
        tests.errors()
        tests.pushMessages()
        tests.fcmMessage()
        tests.apnsBodies()

        assertEquals(Fixtures.all(), tests.covered)
    }

    @Test
    fun pairRequests() {
        for (name in listOf("pair-request.json", "pair-request-minimal.json")) {
            val request = json.decodeFromString(PairRequest.serializer(), fixture(name))
            // Round trip: what we send is exactly what the contract shows (absent fields stay absent).
            assertEquals(Fixtures.json(name), json.encodeToJsonElement(PairRequest.serializer(), request))
        }

        // What Android sends: platform android, FCM, no alert token.
        val android = PairRequest(
            token = "fss_vpair_FIXTUREpairingFIXTUREpairingFIXTUREpairingF",
            device = DeviceInfo(platform = ApiPlatform.ANDROID, installId = "a1b2c3d4e5f60718", pushToken = "t", pushKind = PushKind.FCM),
        )
        val encoded = json.encodeToJsonElement(PairRequest.serializer(), android).jsonObject["device"]!!.jsonObject
        assertEquals("android", encoded["platform"]!!.jsonPrimitive.content)
        assertEquals("fcm", encoded["pushKind"]!!.jsonPrimitive.content)
        assertFalse(encoded.containsKey("alertPushToken"))
        assertFalse(encoded.containsKey("pushEnv"))
    }

    @Test
    fun pairResponses() {
        val tcp = json.decodeFromString(PairResponse.serializer(), fixture("pair-response.json"))
        assertEquals("3f0c2b1e-8a4d-4d6f-9b7a-1c2d3e4f5a6b", tcp.account.id)
        assertEquals("102", tcp.sip.username)
        assertEquals("fixture-not-a-real-password", tcp.sip.password.reveal())
        assertEquals("voorbeeld-bouw.powervoip.nl", tcp.sip.domain)
        assertEquals("sip.powervoip.nl", tcp.sip.proxy)
        assertEquals(ServerTransport.TCP, tcp.sip.transport)
        assertEquals(5060, tcp.sip.port)
        assertTrue(tcp.sip.srv)
        assertEquals("a1b2c3d4e5f60718", tcp.device.installId)
        assertEquals(2, tcp.contacts.listsAvailable)
        assertTrue(tcp.contacts.hasInternal)
        assertFalse(tcp.toString().contains("fixture-not-a-real-password"))

        val tls = json.decodeFromString(PairResponse.serializer(), fixture("pair-response-tls.json"))
        assertEquals(ServerTransport.TLS, tls.sip.transport)
        assertEquals(5061, tls.sip.port)
        assertNull(tls.account.extensionNumber)
    }

    @Test
    fun meResponses() {
        val me = json.decodeFromString(MeResponse.serializer(), fixture("me-response.json"))
        assertEquals("Jan (balie)", me.account.labelOverride)
        assertEquals(3, me.internalContacts.size)
        assertEquals(InternalContact("100", "Receptie"), me.internalContacts.first())
        assertEquals(PushKind.APNS_VOIP, me.push.kind)
        assertEquals(PushEnvironment.SANDBOX, me.push.env)
        assertEquals(Instant.parse("2026-10-08T12:34:56.789Z"), me.serverTime)
        assertEquals(ServerTransport.TCP, me.sip!!.transport)

        val noSip = json.decodeFromString(MeResponse.serializer(), fixture("me-response-no-sip.json"))
        assertNull(noSip.sip)
        assertNull(noSip.push.kind)
        assertNull(noSip.account.labelOverride)
        assertTrue(noSip.internalContacts.isEmpty())
    }

    @Test
    fun meTolerantOfUnknownValues() {
        val text = Fixtures.text("me-response.json")
            .replace("\"apns_voip\"", "\"future_kind\"")
            .replace("\"serverTime\"", "\"newField\": {\"x\": 1}, \"serverTime\"")
        val me = json.decodeFromString(MeResponse.serializer(), text)
        assertNull(me.push.kind)
    }

    @Test
    fun mePatch() {
        assertEquals(Fixtures.json(fixtureName("me-patch-request.json")), MePatchRequest("Jan (balie)").toJson())
        assertEquals(Fixtures.json(fixtureName("me-patch-request-clear.json")), MePatchRequest(null).toJson())

        val response = json.decodeFromString(MePatchResponse.serializer(), fixture("me-patch-response.json"))
        assertEquals("Jan (balie)", response.label)
        assertEquals("Jan (balie)", response.labelOverride)
    }

    @Test
    fun pushTokenRequests() {
        val ios = PushTokenUpdate(
            pushKind = PushKind.APNS_VOIP,
            pushToken = "ab".repeat(32),
            pushEnv = PushEnvironment.SANDBOX,
            alertPushToken = "cd".repeat(32),
        )
        assertEquals(Fixtures.json(fixtureName("push-token-request.json")), ios.toJson())
        assertEquals(Fixtures.json(fixtureName("push-token-request-clear.json")), PushTokenUpdate.CLEAR.toJson())

        // Android: FCM, no alert token.
        val android = PushTokenUpdate(pushKind = PushKind.FCM, pushToken = "fcm-token").toJson()
        assertEquals("fcm", android["pushKind"]!!.jsonPrimitive.content)
        assertFalse(android.containsKey("alertPushToken"))
    }

    @Test
    fun okResponse() {
        assertTrue(json.decodeFromString(OkResponse.serializer(), fixture("ok-response.json")).ok)
    }

    @Test
    fun errors() {
        val invalid = json.decodeFromString(ApiErrorBody.serializer(), fixture("error-invalid-request.json"))
        assertEquals("invalid_request", invalid.error)
        assertEquals("device.platform moet ios of android zijn.", invalid.message)

        assertEquals("not_found", json.decodeFromString(ApiErrorBody.serializer(), fixture("error-not-found.json")).error)
        assertEquals("rate_limited", json.decodeFromString(ApiErrorBody.serializer(), fixture("error-rate-limited.json")).error)
        assertEquals("unauthorized", json.decodeFromString(ApiErrorBody.serializer(), fixture("error-unauthorized.json")).error)

        val unavailable = json.decodeFromString(ApiErrorBody.serializer(), fixture("error-unavailable-retryable.json"))
        assertEquals("not_ready", unavailable.error)
        assertEquals(true, unavailable.retryable)
    }

    @Test
    fun pushMessages() {
        val ring = PushMessage.decode(fixture("push-ring.json")) as PushMessage.Ring
        assertEquals("9d3f5c52-7b1e-4a0c-8e6d-2f4a6b8c0d1e", ring.ring.callRef)
        assertEquals("+31701234567", ring.ring.from.number)
        assertEquals("Bakkerij Smit", ring.ring.from.name)
        assertEquals(Instant.parse("2026-10-08T12:35:08Z"), ring.ring.expiresAt)

        val anonymous = PushMessage.decode(fixture("push-ring-anonymous.json")) as PushMessage.Ring
        assertNull(anonymous.ring.from.number)
        assertNull(anonymous.ring.from.name)

        val revoked = PushMessage.decode(fixture("push-revoked.json")) as PushMessage.Revoked
        assertEquals("Voorbeeld Bouw · Jan de Vries", revoked.revoked.accountLabel)

        val refresh = PushMessage.decode(fixture("push-refresh.json")) as PushMessage.Refresh
        assertEquals("3f0c2b1e-8a4d-4d6f-9b7a-1c2d3e4f5a6b", refresh.refresh.accountId)
    }

    @Test
    fun fcmMessage() {
        val message = Fixtures.json(fixtureName("fcm-message.json")).jsonObject["message"]!!.jsonObject
        assertEquals("HIGH", message["android"]!!.jsonObject["priority"]!!.jsonPrimitive.content)

        val data = message["data"]!!.jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" }
        val decoded = PushMessage.decodeFcm(data) as PushMessage.Ring
        assertEquals("9d3f5c52-7b1e-4a0c-8e6d-2f4a6b8c0d1e", decoded.ring.callRef)
        assertEquals("Voorbeeld Bouw · Jan de Vries", decoded.ring.accountLabel)
    }

    @Test
    fun apnsBodies() {
        // iOS envelopes; the `fsvoip` object inside is the same one Android decodes.
        val voip = Fixtures.json(fixtureName("apns-voip-body.json")).jsonObject["fsvoip"]!!.jsonObject
        assertTrue(PushMessage.decode(voip) is PushMessage.Ring)

        val alert = Fixtures.json(fixtureName("apns-alert-body.json")).jsonObject["fsvoip"]!!.jsonObject
        assertTrue(PushMessage.decode(alert) is PushMessage.Revoked)
    }

    @Test
    fun unusablePushes() {
        expect<PushMessageException.MissingPayload> { PushMessage.decodeFcm(emptyMap()) }
        expect<PushMessageException.MissingPayload> { PushMessage.decode("not json") }
        expect<PushMessageException.UnsupportedVersion> { PushMessage.decode("""{"v":2,"type":"ring"}""") }
        expect<PushMessageException.UnknownType> { PushMessage.decode("""{"v":1,"type":"party"}""") }
        expect<PushMessageException.Malformed> { PushMessage.decode("""{"v":1,"type":"ring","callRef":"x"}""") }
    }

    private fun fixtureName(name: String): String {
        covered += name
        return name
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) {
                throw AssertionError("Expected ${T::class.simpleName}, got $error")
            }
        }
    }
}
