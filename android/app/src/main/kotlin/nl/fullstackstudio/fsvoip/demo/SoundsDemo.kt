// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The pretend API of the sounds, the own extension and the invitation of a colleague (demo mode). State lives in the handler, so adding
// a sound makes it appear in the list and switching "Niet storen" on survives a reload.

package nl.fullstackstudio.fsvoip.demo

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import nl.fullstackstudio.fsvoip.core.HttpResponse

class SoundsDemoHandler : DemoHandler {
    private class DemoSound(val id: String, var name: String, val playable: Boolean, val uses: List<Pair<String, String>>, val contentType: String?, val byteSize: Int?, var sync: String = "ok")

    private val sounds = mutableListOf(
        DemoSound("snd-welkom", "Welkomstbericht", true, listOf("menu_greeting" to "Keuzemenu Support"), "audio/mpeg", 184_320),
        DemoSound("snd-kantoor", "Buiten kantoortijd", true, listOf("closed_message" to "Kantoortijden"), "audio/wav", 512_044),
        DemoSound("snd-muziek", "Wachtmuziek", true, emptyList(), "audio/mpeg", 2_048_000),
        DemoSound("snd-oud", "Oud bericht", false, emptyList(), null, null),
    )
    private var nextSound = 1

    private val selfStates = mutableMapOf<String, MutableMap<String, JsonElement>>()

    override fun handle(request: DemoRequest): HttpResponse? {
        val token = request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        val accountId = DemoMode.exampleAccounts.firstOrNull { it.deviceToken.reveal() == token }?.id ?: return null
        val admin = accountId == DemoMode.ADMIN_ID

        return when {
            request.segments.firstOrNull() == "pbx" && request.segments.getOrNull(1) == "sounds" -> if (admin) sounds(request) else forbidden()
            request.matches("POST", "pbx", "devices", "*", "app-pairing") -> if (admin) appPairing(request, accountId) else forbidden()
            // Only when no other handler (the pbx slice) answers the device list: the invitation page needs the extensions.
            request.matches("GET", "pbx", "devices") && admin -> if (othersAnswer(request)) null else demoJson(200, devices())
            request.matches("GET", "me", "extension") -> demoJson(200, JsonObject(selfState(accountId)))
            request.matches("PATCH", "me", "extension") -> patchSelf(request, accountId)
            else -> null
        }
    }

    private fun forbidden() = demoJson(403, """{"error":"forbidden","required":"admin"}""")

    private fun othersAnswer(request: DemoRequest): Boolean = DemoMode.handlers.filter { it !== this }.any { it.handle(request) != null }

    // MARK: Sounds

    private fun sounds(request: DemoRequest): HttpResponse? {
        val id = request.segments.getOrNull(2)

        return when {
            request.matches("GET", "pbx", "sounds") -> demoJson(
                200,
                buildJsonObject {
                    put("sounds", buildJsonArray { sounds.forEach { add(json(it)) } })
                },
            )
            request.matches("POST", "pbx", "sounds") -> upload(request)
            id != null && request.matches("PATCH", "pbx", "sounds", "*") -> {
                val sound = sounds.firstOrNull { it.id == id } ?: return demoError(404, "not_found")
                val name = request.bodyString("name")?.trim().orEmpty()

                if (name.isEmpty()) return demoError(400, "invalid_request")

                sound.name = name
                demoOk()
            }
            id != null && request.matches("DELETE", "pbx", "sounds", "*") -> {
                val sound = sounds.firstOrNull { it.id == id } ?: return demoError(404, "not_found")

                if (sound.uses.isNotEmpty()) {
                    return demoJson(
                        409,
                        buildJsonObject {
                            put("error", JsonPrimitive("in_use"))
                            put("places", buildJsonArray { sound.uses.forEach { (kind, name) -> add(buildJsonObject { put("kind", JsonPrimitive(kind)); put("name", JsonPrimitive(name)) }) } })
                        },
                    )
                }

                sounds.remove(sound)
                demoOk()
            }
            id != null && request.matches("GET", "pbx", "sounds", "*", "audio") -> {
                val sound = sounds.firstOrNull { it.id == id }

                if (sound == null || !sound.playable) demoError(404, "not_found") else demoBytes("audio/wav", wav())
            }
            else -> null
        }
    }

    private fun upload(request: DemoRequest): HttpResponse {
        // Nothing of the file is read: the name part is enough for a believable list.
        val text = request.rawBody?.toString(Charsets.ISO_8859_1).orEmpty()
        val name = Regex("name=\"name\"\r\n\r\n(.*?)\r\n").find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: "Nieuw geluid"
        val fileName = Regex("filename=\"(.*?)\"").find(text)?.groupValues?.get(1).orEmpty().lowercase()
        val contentType = when {
            fileName.endsWith(".wav") -> "audio/wav"
            fileName.endsWith(".mp3") -> "audio/mpeg"
            else -> "audio/mp4"
        }

        if (sounds.size >= MAX_SOUNDS) return demoError(409, "too_many")

        val id = "snd-new-${nextSound++}"
        sounds += DemoSound(id, name, true, emptyList(), contentType, (request.rawBody?.size ?: 0), sync = "pending")

        return demoJson(200, """{"soundId":${jsonString(id)}}""")
    }

    private fun json(sound: DemoSound): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(sound.id))
        put("name", JsonPrimitive(sound.name))
        put("durationSeconds", JsonNull)
        put("playable", JsonPrimitive(sound.playable))
        put("uses", buildJsonArray { sound.uses.forEach { (kind, name) -> add(buildJsonObject { put("kind", JsonPrimitive(kind)); put("name", JsonPrimitive(name)) }) } })
        put("contentType", sound.contentType?.let(::JsonPrimitive) ?: JsonNull)
        put("byteSize", sound.byteSize?.let(::JsonPrimitive) ?: JsonNull)
        put("sync", JsonPrimitive(sound.sync))
    }

    /** A tiny valid WAV: 2.4 seconds of a soft two-tone chime, 8-bit mono 8 kHz. */
    private fun wav(): ByteArray {
        val rate = 8000
        val samples = ByteArray((rate * 2.4).toInt()) { index ->
            val t = index / rate.toDouble()
            val tone = if (t < 1.2) 440.0 else 554.37
            val envelope = kotlin.math.exp(-3.0 * (t % 1.2))

            (128 + 70 * envelope * kotlin.math.sin(2 * Math.PI * tone * t)).toInt().toByte()
        }
        val out = java.io.ByteArrayOutputStream()

        fun int32(value: Int) = out.write(byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte()))

        fun int16(value: Int) = out.write(byteArrayOf(value.toByte(), (value shr 8).toByte()))

        out.write("RIFF".toByteArray())
        int32(36 + samples.size)
        out.write("WAVEfmt ".toByteArray())
        int32(16)
        int16(1)
        int16(1)
        int32(rate)
        int32(rate)
        int16(1)
        int16(8)
        out.write("data".toByteArray())
        int32(samples.size)
        out.write(samples)

        return out.toByteArray()
    }

    // MARK: Invitation

    private fun appPairing(request: DemoRequest, accountId: String): HttpResponse {
        val deviceId = request.segments[2]

        // The own extension cannot be invited: it would replace this very app.
        if (deviceId == ownId(accountId)) return demoError(409, "conflict")

        val token = "fss_vpair_DEMO" + UUID.randomUUID().toString().replace("-", "").take(32)

        return demoJson(
            200,
            """{"url":"https://fullstackstudio.nl/fsvoip/pair?t=$token","expiresAt":"${Instant.now().plusSeconds(600)}"}""",
        )
    }

    private fun ownId(accountId: String) = if (accountId == DemoMode.ADMIN_ID) "dev-102" else "dev-201"

    private fun devices(): JsonObject = buildJsonObject {
        put(
            "devices",
            buildJsonArray {
                listOf("dev-100" to ("Receptie" to "100"), "dev-101" to ("Pieter Jansen" to "101"), "dev-102" to ("Jan de Vries" to "102"), "dev-103" to ("Werkplaats" to "103"), "dev-104" to ("Marloes Bakker" to "104"))
                    .forEach { (id, pair) -> add(buildJsonObject { put("id", JsonPrimitive(id)); put("name", JsonPrimitive(pair.first)); put("extension", JsonPrimitive(pair.second)) }) }
            },
        )
    }

    // MARK: The own extension

    private fun selfState(accountId: String): MutableMap<String, JsonElement> = selfStates.getOrPut(accountId) {
        val admin = accountId == DemoMode.ADMIN_ID
        val ownId = ownId(accountId)
        val name = if (admin) "Jan de Vries" else "Werkplaats"

        fun target(type: String, id: String) = buildJsonObject { put("type", JsonPrimitive(type)); put("id", JsonPrimitive(id)) }

        fun option(type: String, id: String?, optionName: String, extension: String?, ofDevice: Boolean = false) = buildJsonObject {
            put("value", JsonPrimitive(if (id == null) type else "$type:$id"))
            put("type", JsonPrimitive(type))
            put("id", id?.let(::JsonPrimitive) ?: JsonNull)
            put("name", JsonPrimitive(optionName))
            put("extension", extension?.let(::JsonPrimitive) ?: JsonNull)
            if (ofDevice) put("ofDevice", JsonPrimitive(true))
        }

        mutableMapOf(
            "id" to JsonPrimitive(ownId),
            "name" to JsonPrimitive(name),
            "extension" to JsonPrimitive(if (admin) "102" else "201"),
            "dnd" to JsonPrimitive(false),
            "forwardAlways" to JsonNull,
            "noAnswerSeconds" to JsonPrimitive(25),
            "noAnswerTarget" to target("voicemail", ownId),
            "voicemailEnabled" to JsonPrimitive(true),
            "voicemailToEmail" to JsonPrimitive(admin),
            "email" to (if (admin) JsonPrimitive("jan@jest-bouw.example") else JsonNull),
            "sync" to JsonPrimitive("ok"),
            "version" to JsonPrimitive(4),
            "numbers" to buildJsonArray {
                add(buildJsonObject { put("id", JsonPrimitive("num-1")); put("number", JsonPrimitive("0850607848")); put("name", JsonPrimitive("Hoofdnummer")); put("isDefault", JsonPrimitive(true)) })
                add(buildJsonObject { put("id", JsonPrimitive("num-2")); put("number", JsonPrimitive("0850607849")); put("name", JsonNull); put("isDefault", JsonPrimitive(false)) })
            },
            "defaultNumber" to JsonPrimitive("0850607848"),
            "targets" to buildJsonArray {
                add(option("extension", "dev-100", "Receptie", "100"))
                add(option("extension", "dev-101", "Pieter Jansen", "101"))
                add(option("extension", "dev-103", "Werkplaats", "103"))
                add(option("ring_group", "grp-200", "Iedereen", "200"))
                add(option("voicemail", ownId, name, "102", ofDevice = true))
                add(option("voicemail", "box-900", "Algemeen", "900"))
                add(option("external", null, "Extern nummer", null))
            },
        )
    }

    private fun patchSelf(request: DemoRequest, accountId: String): HttpResponse {
        val state = selfState(accountId)
        val body = request.bodyObject()
        val current = state["version"]!!.jsonPrimitive.intOrNull ?: 0
        val version = body["version"]?.jsonPrimitive?.intOrNull ?: return demoError(400, "invalid_request")

        if (version != current) return demoJson(409, """{"error":"stale","version":$current}""")

        val keys = listOf("dnd", "forwardAlways", "noAnswerSeconds", "noAnswerTarget", "voicemailEnabled", "voicemailToEmail", "email")
        val given = keys.filter { body.containsKey(it) }

        if (given.isEmpty()) return demoJson(400, """{"error":"invalid_request"}""")

        // A plain user may not change the e-mail address of the voicemail copy in this demo? No: every role may change its own extension.
        for (key in given) state[key] = body[key]!!

        state["version"] = JsonPrimitive(current + 1)

        return demoJson(200, buildJsonObject { put("ok", JsonPrimitive(true)); put("extension", JsonObject(state)) })
    }

    companion object {
        const val MAX_SOUNDS = 12
    }
}


/** The demo screens of this slice (`demo-screen`): one settings page as a full-screen overlay. */
object SoundsDemoScreens {
    fun open(screen: String, model: nl.fullstackstudio.fsvoip.AppModel) {
        val admin = DemoMode.ADMIN_ID
        val user = DemoMode.USER_ID

        model.showOverlay { close ->
            when (screen) {
                "profile" -> nl.fullstackstudio.fsvoip.ui.settings.ProfileScreen(model, admin, close, openLink = {})
                "profile-user" -> nl.fullstackstudio.fsvoip.ui.settings.ProfileScreen(model, user, close, openLink = {})
                "callprefs" -> nl.fullstackstudio.fsvoip.ui.settings.CallPreferencesScreen(model, admin, close)
                "callprefs-user" -> nl.fullstackstudio.fsvoip.ui.settings.CallPreferencesScreen(model, user, close)
                "link" -> nl.fullstackstudio.fsvoip.ui.settings.LinkScreen(model, admin, close)
                "appearance" -> nl.fullstackstudio.fsvoip.ui.settings.AppearanceScreen(close)
                "notifications" -> nl.fullstackstudio.fsvoip.ui.settings.NotificationsScreen(model, close)
                "about" -> nl.fullstackstudio.fsvoip.ui.settings.AboutScreen(close, openLicenses = {})
                "sounds" -> nl.fullstackstudio.fsvoip.ui.settings.SoundsPageGate(model, admin, close)
                "invite" -> nl.fullstackstudio.fsvoip.ui.settings.InvitePageGate(model, admin, close)
                "picker" -> nl.fullstackstudio.fsvoip.ui.sounds.AudioPickerScreen(
                    model, admin, "Welkomstbericht", soundId = "snd-welkom",
                    fallback = listOf(nl.fullstackstudio.fsvoip.ui.sounds.AudioOption("snd-welkom", "Welkomstbericht")),
                    allowsNone = true, onApply = { close() }, back = close,
                )
                // The real prompt of the system (the demo normally has none): set a screen lock on the emulator first.
                "gate" -> {
                    nl.fullstackstudio.fsvoip.ui.LocalAccess.forceRealPrompt = true
                    nl.fullstackstudio.fsvoip.ui.settings.SoundsPageGate(model, admin, close)
                }
            }
        }
    }
}
