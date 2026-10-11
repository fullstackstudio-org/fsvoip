// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.media

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.AudioBackend
import nl.fullstackstudio.fsvoip.core.AudioBackendEvent
import nl.fullstackstudio.fsvoip.core.AudioSource
import nl.fullstackstudio.fsvoip.core.CallsPage
import nl.fullstackstudio.fsvoip.core.FsJson
import nl.fullstackstudio.fsvoip.core.MediaCache
import nl.fullstackstudio.fsvoip.core.MediaDownload
import nl.fullstackstudio.fsvoip.core.MediaRequest
import nl.fullstackstudio.fsvoip.core.MediaServicing
import nl.fullstackstudio.fsvoip.core.MeResponse
import nl.fullstackstudio.fsvoip.core.VoicemailPage

/** A scripted `/calls` and `/voicemail`: every call is recorded and errors can be queued per method. */
class FakeMediaService : MediaServicing {
    val calls = mutableListOf<String>()
    val deleted = mutableListOf<Pair<String, String>>()
    val requestedMonths = mutableListOf<String?>()
    val requestedBoxes = mutableListOf<String?>()
    val failures = mutableMapOf<String, MutableList<Exception>>()
    var voicemailPage: VoicemailPage = MediaFixtures.voicemail
    var callsPages: Map<String, CallsPage> = mapOf("2026-10" to MediaFixtures.callsOctober, "2026-09" to MediaFixtures.callsSeptember)
    var downloadBytes: ByteArray = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x41, 0x56, 0x45)

    private fun enter(name: String) {
        calls += name

        val queue = failures[name]

        if (queue != null && queue.isNotEmpty()) throw queue.removeAt(0)
    }

    fun count(name: String): Int = calls.count { it == name }

    fun fail(name: String, vararg errors: Exception) {
        failures.getOrPut(name) { mutableListOf() }.addAll(errors)
    }

    override suspend fun calls(accountId: String, month: String?): CallsPage {
        enter("calls")
        requestedMonths += month

        return callsPages[month ?: "2026-10"] ?: callsPages.getValue("2026-10")
    }

    override suspend fun voicemail(accountId: String, box: String?): VoicemailPage {
        enter("voicemail")
        requestedBoxes += box

        return voicemailPage
    }

    override suspend fun deleteVoicemail(accountId: String, boxId: String, ref: String) {
        enter("deleteVoicemail")
        deleted += boxId to ref
    }

    override fun recordingSource(accountId: String, callId: String): AudioSource {
        enter("recordingSource")

        return AudioSource.Stream(request("calls/$callId/recording"))
    }

    override fun voicemailSource(accountId: String, boxId: String, ref: String): AudioSource {
        enter("voicemailSource")

        return AudioSource.Stream(request("voicemail/$boxId/$ref/audio"))
    }

    override suspend fun download(accountId: String, media: MediaRequest): MediaDownload {
        enter("download")

        return MediaDownload(downloadBytes, "audio/wav")
    }

    private fun request(path: String) = MediaRequest(path, "https://example.invalid/api/voip-app/v1/$path") { mapOf("Authorization" to "Bearer fss_vapp_test") }
}

object MediaFixtures {
    const val OWN_BOX = "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57"
    const val SHARED_BOX = "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f"
    const val COLLEAGUE_BOX = "5c3b1a09-8d7e-4f6a-b2c1-0e9d8c7b6a51"

    val voicemail: VoicemailPage
        get() = FsJson.default.decodeFromString(
            VoicemailPage.serializer(),
            """
            {"boxes": [
              {"id": "$SHARED_BOX", "name": "Algemeen", "number": "900", "shared": true},
              {"id": "$OWN_BOX", "name": "Jan de Vries", "number": "102", "shared": false},
              {"id": "$COLLEAGUE_BOX", "name": "Pieter Jansen", "number": "101", "shared": false}],
             "boxId": null, "available": true,
             "messages": [
              ${message("own-new", OWN_BOX, "Jan de Vries", "102", "2026-10-09T06:55:00.000Z", 29)},
              ${message("own-old", OWN_BOX, "Jan de Vries", "102", "2026-10-02T09:03:00.000Z", 2)},
              ${message("shared", SHARED_BOX, "Algemeen", "900", "2026-10-07T07:20:00.000Z", 27)},
              ${message("colleague", COLLEAGUE_BOX, "Pieter Jansen", "101", "2026-10-06T15:45:00.000Z", 26)}]}
            """,
        )

    fun message(ref: String, box: String, name: String, number: String, sort: String, days: Int) =
        """{"ref": "v1.$ref", "boxId": "$box", "boxName": "$name", "boxNumber": "$number", "caller": "070 123 45 67", "callerName": null, "receivedLabel": "x", "receivedSort": "$sort", "durationLabel": "0:21", "isNew": false, "transcription": null, "daysLeft": $days}"""

    val callsOctober: CallsPage
        get() = callsPage("2026-10", listOf(call(1, true, false), call(2, false, false), call(3, true, false)))

    val callsSeptember: CallsPage
        get() = callsPage("2026-09", listOf(call(11, false, true)))

    private fun callsPage(month: String, calls: List<String>) = FsJson.default.decodeFromString(
        CallsPage.serializer(),
        """{"month": "$month", "months": ["2026-10", "2026-09"], "truncated": false, "calls": [${calls.joinToString(",")}]}""",
    )

    fun call(n: Int, recording: Boolean, expired: Boolean, direction: String = "inbound", outcome: String = "answered", sort: String? = null): String =
        """{"id": "11111111-aaaa-4bbb-8ccc-0000000000${"%02d".format(n)}", "direction": "$direction", "outcome": "$outcome", "number": "070 123 45 67", "ourNumber": "0850607848", "extension": "102", "extensionName": "Jan de Vries", "startedLabel": "9 okt 09:12", "startedSort": "${sort ?: "2026-10-0${n % 9 + 1}T07:12:41.000Z"}", "durationLabel": "2:05", "durationSeconds": 125, "hasRecording": $recording, "recordingExpired": $expired}"""

    fun item(json: String) = FsJson.default.decodeFromString(nl.fullstackstudio.fsvoip.core.CallItem.serializer(), json)

    private fun me(role: String, voicemail: String, calls: String, recordings: Boolean, readOnly: Boolean = false, includeCapabilities: Boolean = true): MeResponse {
        val capabilities = if (includeCapabilities) {
            """,
            "capabilities": {"pbxManage": ${role == "admin"}, "recordings": $recordings, "voicemail": "$voicemail", "calls": "$calls", "contacts": {"read": true, "write": true, "delete": false}, "selfExtension": true, "sounds": "none", "invite": false, "park": false, "callerChoice": false},
            "pbx": {"name": "Voorbeeld Bouw", "state": "${if (readOnly) "frozen" else "active"}", "readOnly": $readOnly}"""
        } else {
            ""
        }

        return FsJson.default.decodeFromString(
            MeResponse.serializer(),
            """
            {"account": {"id": "a", "label": "Jan", "pbxName": "Voorbeeld Bouw", "extensionName": "Jan", "extensionNumber": "102", "customerName": "Voorbeeld"},
             "internal": [], "contacts": {"internal": true, "listsAvailable": 0}, "push": {"registered": true, "invalid": false},
             "serverTime": "2026-10-08T12:34:56.789Z", "role": "$role"$capabilities}
            """,
        )
    }

    val adminMe get() = me("admin", "all", "all", recordings = true)
    val userMe get() = me("user", "own", "team", recordings = false)
    val frozenMe get() = me("admin", "all", "all", recordings = true, readOnly = true)
    val oldServerMe get() = me("admin", "all", "all", recordings = true, includeCapabilities = false)
}

class FakeAudioBackend : AudioBackend {
    override var onEvent: ((AudioBackendEvent) -> Unit)? = null
    val loaded = mutableListOf<AudioSource>()
    var pauses = 0
    var stops = 0

    override fun load(source: AudioSource) { loaded += source }
    override fun play(rate: Double) = Unit
    override fun pause() { pauses++ }
    override fun seek(seconds: Double) = Unit
    override fun setRate(rate: Double) = Unit
    override fun stop() { stops++ }

    fun emit(event: AudioBackendEvent) = onEvent?.invoke(event)
}

/** A gate whose answers a test scripts; counts the questions (the "Face ID" prompts). */
class FakeGate : MediaAccessGate {
    var open = false
    var evaluations = 0
    val results = mutableListOf<GateResult>()
    var locks = 0
    var touches = 0

    override val isUnlocked: Boolean get() = open

    override suspend fun ensureUnlocked(reason: GateReason): GateResult {
        if (open) return GateResult.UNLOCKED

        evaluations++

        val result = if (results.isNotEmpty()) results.removeAt(0) else GateResult.UNLOCKED

        if (result == GateResult.UNLOCKED) open = true

        return result
    }

    override fun touch() { touches++ }

    override fun lock() {
        locks++
        open = false
    }

    /** The five minutes ran out. */
    fun expire() { open = false }
}

class MediaTestEnvironment {
    val service = FakeMediaService()
    val gate = FakeGate()
    val backend = FakeAudioBackend()
    val directory: File = Files.createTempDirectory("fsvoip-ui-media").toFile()
    var callActive = false
    val hub = MediaHub(service, gate, backend, MediaCache(directory), CoroutineScope(Dispatchers.Unconfined), callActive = { callActive }, io = Dispatchers.Unconfined)

    fun close() {
        directory.deleteRecursively()
    }
}

fun forbidden() = ApiException.Forbidden()
