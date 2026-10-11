// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decodes the fixtures of the sounds / own extension / invitation slice with the Kotlin models. */
class SoundsContractTest {
    private val json = FsJson.default
    private val covered = mutableSetOf<String>()

    private fun fixture(name: String): String {
        covered += name
        return Fixtures.text(name)
    }

    /** Runs every test of this class and returns the fixture names they decoded. */
    fun coverage(): Set<String> {
        selfExtension()
        selfExtensionPatch()
        soundsPage()
        soundRequestsAndResponses()
        appPairing()
        soundErrors()

        return covered.toSet()
    }

    @Test
    fun selfExtension() {
        val state = json.decodeFromString(SelfExtension.serializer(), fixture("self-extension.json"))

        assertEquals("Jan de Vries", state.name)
        assertEquals("102", state.extensionNumber)
        assertFalse(state.dnd)
        assertNull(state.forwardAlways)
        assertEquals(25, state.noAnswerSeconds)
        assertEquals(PbxTarget(TargetType.VOICEMAIL, id = "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57"), state.noAnswerTarget)
        assertTrue(state.voicemailEnabled)
        assertTrue(state.voicemailToEmail)
        assertEquals("jan@voorbeeld-bouw.example", state.email)
        assertEquals(SyncState.OK, state.sync)
        assertEquals(4, state.version)
        assertEquals(2, state.numbers.size)
        assertEquals(SelfNumber("d4e5f6a7-b8c9-4d0e-a1f2-b3c4d5e6f7a8", "0850607848", "Hoofdnummer", true), state.numbers[0])
        assertNull(state.numbers[1].name)
        assertFalse(state.numbers[1].isDefault)
        assertEquals("0850607848", state.defaultNumber)
        assertEquals(5, state.targets.size)
        assertEquals(TargetType.RING_GROUP, state.targets[2].type)
        assertEquals("200", state.targets[2].extensionNumber)
        assertEquals(TargetType.EXTERNAL, state.targets[4].type)
        assertNull(state.targets[4].id)
    }

    @Test
    fun selfExtensionToleratesAMinimalAnswerAndUnknownValues() {
        val minimal = json.decodeFromString(SelfExtension.serializer(), """{"id":"x","name":"Jan","version":1,"sync":"weird","forwardAlways":{"type":"teleport"}}""")

        assertFalse(minimal.dnd)
        assertEquals(25, minimal.noAnswerSeconds)
        assertFalse(minimal.voicemailEnabled)
        assertEquals(SyncState.UNKNOWN, minimal.sync)
        assertEquals(TargetType.UNKNOWN, minimal.forwardAlways!!.type)
        assertTrue(minimal.numbers.isEmpty())
        assertTrue(minimal.targets.isEmpty())
    }

    @Test
    fun selfExtensionPatch() {
        val request = SelfExtensionPatch(version = 4, dnd = true, forwardAlways = Change.Set(PbxTarget.external("+31612345678")))
        // What we send is exactly what the contract shows (absent keys stay absent).
        fixture("self-extension-patch-request.json")
        assertEquals(Fixtures.json("self-extension-patch-request.json"), request.toJson())

        val response = json.decodeFromString(SelfExtension.serializer(), fixture("self-extension-patch-response.json").let { json.parseToJsonElement(it).jsonObject["extension"]!!.toString() })
        assertEquals(5, response.version)
        assertTrue(response.dnd)
        assertEquals(PbxTarget.external("+31612345678"), response.forwardAlways)

        // Keep leaves the key out, Clear sends an explicit null.
        val clear = SelfExtensionPatch(version = 7, forwardAlways = Change.Clear, email = Change.Clear, noAnswerTarget = Change.Keep).toJson()
        assertEquals(JsonNull, clear["forwardAlways"])
        assertEquals(JsonNull, clear["email"])
        assertFalse(clear.containsKey("noAnswerTarget"))
        assertFalse(clear.containsKey("dnd"))
    }

    @Test
    fun soundsPage() {
        val page = json.decodeFromString(SoundsPage.serializer(), fixture("sounds-page.json"))

        assertEquals(3, page.sounds.size)
        val welcome = page.sounds[0]
        assertEquals("Welkom", welcome.name)
        assertTrue(welcome.playable)
        assertNull(welcome.durationSeconds)
        assertEquals(listOf(SoundUse("menu_greeting", "Keuzemenu Support")), welcome.uses)
        assertEquals("audio/mpeg", welcome.contentType)
        assertEquals(184320L, welcome.byteSize)
        assertEquals(SyncState.OK, welcome.sync)
        assertEquals(SyncState.PENDING, page.sounds[1].sync)

        val old = page.sounds[2]
        assertFalse(old.playable)
        assertNull(old.contentType)
        assertNull(old.byteSize)

        // Missing optional values fall back to the restrictive choice: not playable, no uses.
        val bare = json.decodeFromString(SoundsPage.serializer(), """{"sounds":[{"id":"a","name":"b"}]}""").sounds.single()
        assertFalse(bare.playable)
        assertTrue(bare.uses.isEmpty())
        assertEquals(SyncState.UNKNOWN, bare.sync)
        assertTrue(json.decodeFromString(SoundsPage.serializer(), "{}").sounds.isEmpty())
    }

    @Test
    fun soundRequestsAndResponses() {
        val rename = json.decodeFromString(SoundRenameRequest.serializer(), fixture("sound-rename-request.json"))
        assertEquals("Welkomstbericht", rename.name)
        assertEquals(Fixtures.json("sound-rename-request.json"), json.encodeToJsonElement(SoundRenameRequest.serializer(), rename))

        val upload = json.decodeFromString(SoundUploadResponse.serializer(), fixture("sound-upload-response.json"))
        assertEquals("a1b2c3d4-4444-4a2b-8c3d-4e5f6a7b8c9d", upload.soundId)
    }

    @Test
    fun appPairing() {
        val response = json.decodeFromString(AppPairingResponse.serializer(), fixture("app-pairing-response.json"))

        assertTrue(response.url.reveal().startsWith("https://fullstackstudio.nl/fsvoip/pair?t=fss_vpair_"))
        assertEquals(Instant.parse("2026-10-09T08:25:30.123Z"), response.expiresAt)
        // The link is a credential: never in a description.
        assertFalse(response.toString().contains("fss_vpair"))
    }

    @Test
    fun soundErrors() {
        assertTrue(FsVoipApiClient.errorFor(response(400, fixture("error-invalid-audio.json"))) is ApiException.InvalidAudio)
        assertTrue(FsVoipApiClient.errorFor(response(413, fixture("error-too-large.json"))) is ApiException.TooLarge)
        assertTrue(FsVoipApiClient.errorFor(response(409, fixture("error-too-many.json"))) is ApiException.TooMany)
    }
}
