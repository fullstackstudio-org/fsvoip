// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PbxApiTest {
    private val base = "https://example.test/v1"
    private val id = "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57"

    private fun client(vararg replies: HttpResponse): Pair<FsVoipApiClient, FakeTransport> {
        val queue = replies.toMutableList()
        val transport = FakeTransport { queue.removeFirst() }

        return FsVoipApiClient(base, transport = transport).authenticated(Secret("fss_vapp_token")) to transport
    }

    private fun reply(status: Int, fixture: String) = response(status, Fixtures.text("$fixture.json"))

    private fun body(request: HttpRequest) = FsJson.default.parseToJsonElement(request.body!!.toString(Charsets.UTF_8))

    @Test
    fun readRoutes() = runTest {
        val (api, transport) = client(reply(200, "pbx-overview"), reply(200, "pbx-devices"), reply(200, "pbx-ring-groups"), reply(200, "pbx-hours"), reply(200, "numbers-page"))

        api.pbxOverview()
        api.pbxDevices()
        api.pbxRingGroups()
        api.pbxHours()
        api.pbxNumbers()

        assertEquals(listOf("$base/pbx/overview", "$base/pbx/devices", "$base/pbx/ring-groups", "$base/pbx/hours", "$base/pbx/numbers"), transport.requests.map { it.url })
        assertTrue(transport.requests.all { it.method == "GET" && it.body == null })
        assertTrue(transport.requests.all { it.headers["Authorization"] == "Bearer fss_vapp_token" })
    }

    @Test
    fun writeRoutes() = runTest {
        val (api, transport) = client(
            reply(200, "ok-response"), reply(201, "pbx-ring-group-created"), reply(200, "ok-response"), reply(200, "ok-response"), reply(200, "ok-response"),
        )

        api.updatePbxDevice(id, PbxDevicePatch(version = 4, dnd = true))
        val created = api.createPbxRingGroup(PbxRingGroupCreate("Support"))
        api.updatePbxRingGroup(id, PbxRingGroupPatch(version = 2, name = "Nieuw"))
        api.updatePbxHours(id, PbxHoursPatch(version = 6, closedTarget = Change.Clear))
        api.setPbxNumberRouting(id, PbxRoutingPatch(null, 3))

        assertEquals("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", created.ringGroupId)
        assertEquals(listOf("PATCH", "POST", "PATCH", "PATCH", "PATCH"), transport.requests.map { it.method })
        assertEquals(
            listOf("$base/pbx/devices/$id", "$base/pbx/ring-groups", "$base/pbx/ring-groups/$id", "$base/pbx/hours/$id", "$base/pbx/numbers/$id/routing"),
            transport.requests.map { it.url },
        )
        assertEquals(FsJson.default.parseToJsonElement("""{"dnd":true,"version":4}"""), body(transport.requests[0]))
        assertEquals(FsJson.default.parseToJsonElement("""{"closedTarget":null,"version":6}"""), body(transport.requests[3]))
        assertEquals(JsonNull, body(transport.requests[4]).jsonObject["target"])
        assertEquals(JsonPrimitive(3), body(transport.requests[4]).jsonObject["version"])
    }

    @Test
    fun numberRoutes() = runTest {
        val replies = Array(7) { reply(200, "number-chain-simple") }
        val (api, transport) = client(*replies)

        api.numberChain(id)
        api.saveChainStep(id, ChainNameStep("Hoofdnummer"))
        api.saveChainStep(id, ChainHoursStep(enabled = false))
        api.saveChainStep(id, ChainWelcomeStep(enabled = false))
        api.saveChainStep(id, ChainMenuForwardingStep(repeats = 1))
        api.saveChainStep(id, ChainClosedStep(closed = Fallback.Hangup))
        api.setNumberRecording(id, NumberRecordingPatch(enabled = false, version = 4))

        assertEquals(listOf("GET", "PUT", "PUT", "PUT", "PUT", "PUT", "PATCH"), transport.requests.map { it.method })
        assertEquals(
            listOf(
                "$base/pbx/numbers/$id/chain", "$base/pbx/numbers/$id/chain/name", "$base/pbx/numbers/$id/chain/hours",
                "$base/pbx/numbers/$id/chain/welcome", "$base/pbx/numbers/$id/chain/forwarding", "$base/pbx/numbers/$id/chain/closed",
                "$base/pbx/numbers/$id/recording",
            ),
            transport.requests.map { it.url },
        )
        assertEquals(FsJson.default.parseToJsonElement("""{"kind":"menu","repeats":1}"""), body(transport.requests[4]))
        assertEquals(FsJson.default.parseToJsonElement("""{"closed":{"mode":"hangup"}}"""), body(transport.requests[5]))
        assertEquals(FsJson.default.parseToJsonElement("""{"enabled":false,"version":4}"""), body(transport.requests[6]))
    }

    @Test
    fun aStepWithAReadOnlyFallbackNeverLeavesTheApp() = runTest {
        val (api, transport) = client()

        try {
            api.saveChainStep(id, ChainClosedStep(closed = Fallback.Other(null)))
            fail("expected an encoding refusal")
        } catch (_: IllegalArgumentException) {
        }

        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun soundChoicesReadIdAndName() = runTest {
        val (api, transport) = client(reply(200, "sounds-page"))

        val sounds = api.pbxSoundChoices()

        assertEquals("$base/pbx/sounds", transport.requests.single().url)
        assertEquals(listOf("Welkom", "Buiten kantoortijd", "Oud bericht"), sounds.map { it.name })
        assertNull(sounds.firstOrNull { it.id == "nope" })
    }

    @Test
    fun serverErrorsOfTheChainReachTheCaller() = runTest {
        val (api, _) = client(reply(409, "error-stale-chain"), reply(422, "error-cost-not-accepted"), reply(409, "error-advanced"))

        try {
            api.saveChainStep(id, ChainNameStep("x"))
            fail("expected a stale answer")
        } catch (error: ApiException.StaleChain) {
            assertEquals(5, error.freshChain()?.version)
        }

        try {
            api.setNumberRecording(id, NumberRecordingPatch(enabled = true, version = 4))
            fail("expected a cost answer")
        } catch (error: ApiException.CostNotAccepted) {
            assertEquals(20000, error.recordingCost()?.priceE4)
        }

        try {
            api.saveChainStep(id, ChainNameStep("x"))
            fail("expected advanced")
        } catch (_: ApiException.Advanced) {
        }
    }
}
