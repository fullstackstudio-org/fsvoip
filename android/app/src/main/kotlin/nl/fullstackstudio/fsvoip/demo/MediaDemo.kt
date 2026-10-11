// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.demo

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import nl.fullstackstudio.fsvoip.core.HttpResponse
import nl.fullstackstudio.fsvoip.ui.media.MediaDemoFlags

/**
 * The calls, voicemail and audio part of the pretend API. Stateful: a deleted voicemail message stays gone. The `Authorization`
 * header of a demo request IS the account id: the administrator (`demo-1`) sees every call with recordings and every box, the user
 * (`demo-2`) the team's calls without recordings and its own box only. The audio is a tiny valid WAV generated in code.
 */
class MediaDemoHandler : DemoHandler {
    private class Call(
        val key: Int,
        val minutesAgo: Long,
        val direction: String,
        val outcome: String,
        val number: String?,
        val extension: String,
        val extensionName: String,
        val ourNumber: String,
        val durationSeconds: Int,
        val recorded: Boolean = false,
        val expired: Boolean = false,
        val country: String? = "Nederland",
        val lineType: String? = "fixed",
    ) {
        val id: String get() = "11111111-aaaa-4bbb-8ccc-0000000000" + "%02d".format(key)
    }

    private class Message(val ref: String, val boxId: String, val caller: String?, val callerName: String?, val minutesAgo: Long, val seconds: Int, var isNew: Boolean, val transcription: String?, val daysLeft: Int)

    private class Box(val id: String, val name: String, val number: String, val shared: Boolean)

    private val calls: List<Call> = listOf(
        Call(1, 75, "inbound", "answered", "070 123 45 67", "101", "Pieter Jansen", "0850607848", 252, recorded = true),
        Call(2, 190, "outbound", "answered", "06 12 34 56 78", "100", "Receptie", "0850607848", 48, recorded = true, lineType = "mobile"),
        Call(3, 310, "inbound", "missed", "020 123 45 67", "102", "Jan de Vries", "0850607849", 0),
        Call(4, 1500, "inbound", "answered", "06 98 76 54 32", "102", "Jan de Vries", "0850607848", 125, recorded = true, lineType = "mobile"),
        Call(5, 1700, "internal", "answered", null, "103", "Werkplaats", "0850607848", 31),
        Call(6, 2900, "inbound", "voicemail", "070 123 45 67", "100", "Receptie", "0850607848", 0),
        Call(7, 4300, "outbound", "missed", "030 234 56 78", "101", "Pieter Jansen", "0850607848", 0),
        Call(8, 7000, "inbound", "answered", "010 456 78 90", "100", "Receptie", "0850607849", 640, recorded = true),
        Call(9, 10_000, "inbound", "missed", null, "102", "Jan de Vries", "0850607848", 0, country = null, lineType = null),
        Call(10, 20_000, "outbound", "answered", "013 567 89 01", "102", "Jan de Vries", "0850607848", 96, recorded = true),
        Call(11, 45_000, "inbound", "answered", "070 123 45 67", "101", "Pieter Jansen", "0850607848", 410, recorded = true),
        Call(12, 60_000, "inbound", "answered", "06 11 22 33 44", "100", "Receptie", "0850607848", 75, recorded = true, lineType = "mobile"),
        Call(13, 140_000, "inbound", "answered", "070 123 45 67", "102", "Jan de Vries", "0850607848", 215, recorded = false, expired = true),
    )

    private val boxes = listOf(
        Box("box-900", "Algemeen", "900", true),
        Box("box-102", "Jan de Vries", "102", false),
        Box("box-101", "Pieter Jansen", "101", false),
        Box("box-100", "Receptie", "100", false),
        Box("box-201", "Werkplaats", "201", false),
    )

    private val messages = mutableListOf(
        Message("v1.QWxsZWVuT3BEZW1v1", "box-102", "070 123 45 67", "Bakkerij Smit", 130, 21, true, "Goedemorgen, met Smit. Kunt u mij even terugbellen over de bestelling van vrijdag?", 29),
        Message("v1.QWxsZWVuT3BEZW1v2", "box-102", null, null, 1900, 14, true, null, 28),
        Message("v1.QWxsZWVuT3BEZW1v3", "box-102", "06 12 34 56 78", null, 9000, 62, false, "Bel mij even terug.", 25),
        Message("v1.QWxsZWVuT3BEZW1v4", "box-900", "010 456 78 90", "Installatiebedrijf Wolters", 3000, 33, true, null, 27),
        Message("v1.QWxsZWVuT3BEZW1v5", "box-900", "06 98 76 54 32", null, 12_000, 19, false, "Ik bel later nog een keer.", 23),
        Message("v1.QWxsZWVuT3BEZW1v6", "box-101", "030 234 56 78", "Gemeente Utrecht", 5000, 45, false, null, 26),
        Message("v1.QWxsZWVuT3BEZW1v7", "box-201", "070 123 45 67", "Bakkerij Smit", 400, 18, true, "Mijn busje wil niet starten, kunt u langskomen?", 29),
        Message("v1.QWxsZWVuT3BEZW1v8", "box-201", "06 11 22 33 44", null, 6000, 27, false, null, 20),
    )

    override fun handle(request: DemoRequest): HttpResponse? {
        val accountId = request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        val isAdmin = accountId == DemoMode.ADMIN_ID

        return when {
            request.matches("GET", "calls") -> calls(request, isAdmin)
            request.matches("GET", "calls", "*", "recording") -> recording(request.segments[1], isAdmin)
            request.matches("GET", "voicemail") -> voicemail(request, accountId, isAdmin)
            request.matches("GET", "voicemail", "*", "*", "audio") -> voicemailAudio(request.segments[1], request.segments[2], accountId, isAdmin)
            request.matches("DELETE", "voicemail", "*", "*") -> deleteMessage(request.segments[1], request.segments[2], accountId, isAdmin)
            else -> null
        }
    }

    // MARK: Calls

    private fun calls(request: DemoRequest, isAdmin: Boolean): HttpResponse {
        if (MediaDemoFlags.failCalls) return demoError(503, "unavailable", ""","retryable":true""")

        val english = request.query["locale"] == "en"
        val now = System.currentTimeMillis()
        val months = calls.map { monthKey(instantOf(it, now)) }.distinct()
        val month = request.query["month"] ?: months.first()
        val page = if (MediaDemoFlags.emptyCalls) emptyList() else calls.filter { monthKey(instantOf(it, now)) == month }
        val items = page.joinToString(",") { call -> callJson(call, now, isAdmin, english) }

        return demoJson(200, """{"month":"$month","months":[${months.joinToString(",") { "\"$it\"" }}],"truncated":false,"calls":[$items]}""")
    }

    private fun callJson(call: Call, now: Long, isAdmin: Boolean, english: Boolean): String {
        val instant = instantOf(call, now)
        val recording = isAdmin && call.recorded
        val expired = isAdmin && call.expired
        val parts = buildList {
            add(""""id":${jsonString(call.id)}""")
            add(""""direction":"${call.direction}"""")
            add(""""outcome":"${call.outcome}"""")
            add(""""number":${call.number?.let(::jsonString) ?: "null"}""")
            add(""""ourNumber":${jsonString(call.ourNumber)}""")
            add(""""extension":${jsonString(call.extension)}""")
            add(""""extensionName":${jsonString(call.extensionName)}""")
            add(""""country":${if (call.country != null) "\"NL\"" else "null"}""")
            add(""""countryName":${call.country?.let { jsonString(if (english) "Netherlands" else it) } ?: "null"}""")
            add(""""lineType":${call.lineType?.let { "\"$it\"" } ?: "null"}""")
            add(""""category":${call.lineType?.let { "\"$it\"" } ?: "null"}""")
            add(""""startedLabel":${jsonString(label(instant, english))}""")
            add(""""startedSort":"$instant"""")
            add(""""durationLabel":${jsonString(duration(call.durationSeconds))}""")
            add(""""durationSeconds":${call.durationSeconds}""")
            add(""""hasRecording":$recording""")
            if (isAdmin) add(""""recordingExpired":$expired""")
        }

        return "{${parts.joinToString(",")}}"
    }

    private fun recording(id: String, isAdmin: Boolean): HttpResponse {
        if (!isAdmin) return demoError(403, "forbidden")

        val call = calls.firstOrNull { it.id == id } ?: return demoError(404, "not_found")

        if (call.expired || call.key == GONE_KEY) return demoError(410, "gone")

        return demoBytes("audio/wav", tone(seconds = 2, hertz = 440.0))
    }

    // MARK: Voicemail

    private fun boxesFor(accountId: String?, isAdmin: Boolean): List<Box> =
        if (isAdmin) boxes.filter { it.id != "box-201" } else boxes.filter { it.id == "box-201" }

    private fun voicemail(request: DemoRequest, accountId: String?, isAdmin: Boolean): HttpResponse {
        if (MediaDemoFlags.failVoicemail) {
            MediaDemoFlags.failVoicemail = false

            return demoError(503, "unavailable", ""","retryable":true""")
        }

        val english = request.query["locale"] == "en"
        val visible = boxesFor(accountId, isAdmin)
        val boxJson = visible.joinToString(",") { """{"id":"${it.id}","name":${jsonString(it.name)},"number":"${it.number}","shared":${it.shared}}""" }

        if (MediaDemoFlags.voicemailUnavailable) {
            return demoJson(200, """{"boxes":[$boxJson],"boxId":${visible.firstOrNull()?.let { "\"${it.id}\"" } ?: "null"},"messages":[],"available":false}""")
        }

        val now = System.currentTimeMillis()
        val ids = visible.map { it.id }.toSet()
        val shown = if (MediaDemoFlags.emptyVoicemail) emptyList() else messages.filter { it.boxId in ids }
        val items = shown.joinToString(",") { messageJson(it, now, boxes.first { box -> box.id == it.boxId }, english) }

        return demoJson(200, """{"boxes":[$boxJson],"boxId":null,"messages":[$items],"available":true}""")
    }

    private fun messageJson(message: Message, now: Long, box: Box, english: Boolean): String {
        val instant = Instant.ofEpochMilli(now - message.minutesAgo * 60_000)

        return """{"ref":${jsonString(message.ref)},"boxId":"${box.id}","boxName":${jsonString(box.name)},"boxNumber":"${box.number}","caller":${message.caller?.let(::jsonString) ?: "null"},"callerName":${message.callerName?.let(::jsonString) ?: "null"},"receivedLabel":${jsonString(label(instant, english))},"receivedSort":"$instant","durationLabel":${jsonString(duration(message.seconds))},"isNew":${message.isNew},"transcription":${message.transcription?.let(::jsonString) ?: "null"},"daysLeft":${message.daysLeft}}"""
    }

    private fun allowed(boxId: String, accountId: String?, isAdmin: Boolean) = boxesFor(accountId, isAdmin).any { it.id == boxId }

    private fun voicemailAudio(boxId: String, ref: String, accountId: String?, isAdmin: Boolean): HttpResponse {
        if (!allowed(boxId, accountId, isAdmin)) return demoError(403, "forbidden")

        val message = messages.firstOrNull { it.boxId == boxId && it.ref == ref } ?: return demoError(404, "not_found")

        message.isNew = false

        return demoBytes("audio/wav", tone(seconds = 3, hertz = 330.0))
    }

    private fun deleteMessage(boxId: String, ref: String, accountId: String?, isAdmin: Boolean): HttpResponse {
        if (!allowed(boxId, accountId, isAdmin)) return demoError(403, "forbidden")

        val removed = messages.removeAll { it.boxId == boxId && it.ref == ref }

        return if (removed) demoOk() else demoError(404, "not_found")
    }

    // MARK: Helpers

    private fun instantOf(call: Call, now: Long): Instant = Instant.ofEpochMilli(now - call.minutesAgo * 60_000)

    private fun monthKey(instant: Instant): String = DateTimeFormatter.ofPattern("yyyy-MM").withZone(AMSTERDAM).format(instant)

    private fun label(instant: Instant, english: Boolean): String {
        val locale = if (english) Locale.ENGLISH else Locale("nl", "NL")

        return DateTimeFormatter.ofPattern("d MMM HH:mm", locale).withZone(AMSTERDAM).format(instant).replace(".", "")
    }

    private fun duration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

    companion object {
        private val AMSTERDAM: ZoneId = ZoneId.of("Europe/Amsterdam")

        /** The recording of this call answers 410 (past its retention), to show "niet meer beschikbaar". */
        const val GONE_KEY = 8

        /** A tiny valid WAV: [seconds] of a sine at [hertz], 8 kHz, mono, 16 bits, with a soft fade in and out. */
        fun tone(seconds: Int, hertz: Double): ByteArray {
            val rate = 8000
            val samples = rate * seconds
            val data = ByteArray(samples * 2)

            for (i in 0 until samples) {
                val fade = minOf(1.0, i / 400.0, (samples - i) / 400.0)
                val value = (sin(2 * PI * hertz * i / rate) * 0.35 * fade * Short.MAX_VALUE).toInt().toShort()
                data[i * 2] = (value.toInt() and 0xFF).toByte()
                data[i * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
            }

            fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

            return "RIFF".toByteArray() + le32(36 + data.size) + "WAVE".toByteArray() + "fmt ".toByteArray() + le32(16) + le16(1) + le16(1) +
                le32(rate) + le32(rate * 2) + le16(2) + le16(16) + "data".toByteArray() + le32(data.size) + data
        }
    }
}
