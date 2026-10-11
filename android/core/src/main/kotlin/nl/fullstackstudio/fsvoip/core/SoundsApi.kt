// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The sounds routes (`/pbx/sounds/**`, admin only), the invitation link for a colleague (`/pbx/devices/{id}/app-pairing`) and the
// own extension (`/me/extension`, every role).

package nl.fullstackstudio.fsvoip.core

import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

suspend fun FsVoipApiClient.sounds(): SoundsPage = get("pbx/sounds", SoundsPage.serializer())

/**
 * `POST /pbx/sounds` as `multipart/form-data` with exactly the parts `name` and `file`. [progress] reports the fraction sent (0...1).
 * A file above [SoundUpload.MAX_BYTES] is [ApiException.TooLarge] without any request. Other failures: [ApiException.InvalidAudio]
 * (not WAV/MP3/M4A), [ApiException.TooMany], [ApiException.RateLimited] (20 uploads per hour).
 */
suspend fun FsVoipApiClient.uploadSound(name: String, fileName: String, data: ByteArray, progress: ((Double) -> Unit)? = null): SoundUploadResponse {
    if (data.size > SoundUpload.MAX_BYTES) throw ApiException.TooLarge()

    val form = MultipartForm()
    val body = form.body(
        listOf(
            MultipartForm.Part.Text("name", name),
            MultipartForm.Part.File("file", fileName, SoundUpload.contentType(fileName), data),
        ),
    )
    val response = send("POST", "pbx/sounds", null, authenticated = true, rawBody = body, contentType = form.contentType, progress = progress)

    return decode("pbx/sounds", SoundUploadResponse.serializer(), response)
}

suspend fun FsVoipApiClient.renameSound(id: String, name: String): OkResponse =
    perform("PATCH", "pbx/sounds/$id", FsJson.default.encodeToJsonElement(SoundRenameRequest.serializer(), SoundRenameRequest(name)), true, OkResponse.serializer())

/** `409 in_use` = [ApiException.InUse] while the sound is still used: nothing is deleted. */
suspend fun FsVoipApiClient.deleteSound(id: String): OkResponse = perform("DELETE", "pbx/sounds/$id", null, true, OkResponse.serializer())

/** The audio of a sound (`playable == true`), whole (a sound is at most 20 MB). */
suspend fun FsVoipApiClient.soundAudio(id: String): ByteArray =
    send("GET", "pbx/sounds/$id/audio", null, authenticated = true, headers = mapOf("Accept" to "audio/*")).body

/**
 * `POST /pbx/devices/{id}/app-pairing`: an invitation link for the extension [deviceId] (role `user`, valid 10 minutes, once). Not for
 * the own extension ([ApiException.Conflict]). 🚨 The link is a credential.
 */
suspend fun FsVoipApiClient.createAppPairing(deviceId: String): AppPairingResponse =
    perform("POST", "pbx/devices/$deviceId/app-pairing", null, true, AppPairingResponse.serializer())

/** `GET /me/extension`. Only when `AppCapabilities.selfExtension` (an older server has no such route). */
suspend fun FsVoipApiClient.selfExtension(): SelfExtension = get("me/extension", SelfExtension.serializer())

/**
 * `PATCH /me/extension`. [ApiException.Stale] = reload and let the user choose again. A field a `user` may not change is
 * [ApiException.Forbidden] (with its `field`).
 */
suspend fun FsVoipApiClient.updateSelfExtension(patch: SelfExtensionPatch): SelfExtensionPatchResponse {
    val response = send("PATCH", "me/extension", patch.toJson(), authenticated = true)
    val root: JsonObject = try {
        FsJson.default.parseToJsonElement(response.body.toString(Charsets.UTF_8)).jsonObject
    } catch (error: Exception) {
        throw ApiException.Decoding("me/extension: ${error.javaClass.simpleName}")
    }
    val ok = root["ok"]?.jsonPrimitive?.booleanOrNull ?: throw ApiException.Decoding("me/extension: ok")
    val state = (root["extension"] as? JsonObject)?.let { runCatching { FsJson.default.decodeFromJsonElement(SelfExtension.serializer(), it) }.getOrNull() }

    return SelfExtensionPatchResponse(ok, state)
}

/** The extensions of the PBX, with only the fields the invitation list needs (`GET /pbx/devices`; the pbx slice decodes the rest). */
@kotlinx.serialization.Serializable
data class InviteDevice(
    val id: String,
    val name: String,
    @kotlinx.serialization.SerialName("extension") val extensionNumber: String? = null,
)

@kotlinx.serialization.Serializable
data class InviteDevicesPage(val devices: List<InviteDevice> = emptyList())

suspend fun FsVoipApiClient.inviteDevices(): InviteDevicesPage = get("pbx/devices", InviteDevicesPage.serializer())

/** A `multipart/form-data` body. The server wants exactly the parts it names; the boundary is random per form. */
class MultipartForm(val boundary: String = "fsvoip-" + UUID.randomUUID()) {
    sealed interface Part {
        data class Text(val name: String, val value: String) : Part

        class File(val name: String, val fileName: String, val contentType: String, val data: ByteArray) : Part
    }

    val contentType: String get() = "multipart/form-data; boundary=$boundary"

    fun body(parts: List<Part>): ByteArray {
        val out = java.io.ByteArrayOutputStream()

        fun write(text: String) = out.write(text.toByteArray(Charsets.UTF_8))

        for (part in parts) {
            write("--$boundary\r\n")

            when (part) {
                is Part.Text -> {
                    write("Content-Disposition: form-data; name=\"${header(part.name)}\"\r\n\r\n")
                    write(part.value)
                    write("\r\n")
                }
                is Part.File -> {
                    write("Content-Disposition: form-data; name=\"${header(part.name)}\"; filename=\"${header(part.fileName)}\"\r\n")
                    write("Content-Type: ${header(part.contentType)}\r\n\r\n")
                    out.write(part.data)
                    write("\r\n")
                }
            }
        }

        write("--$boundary--\r\n")

        return out.toByteArray()
    }

    companion object {
        /** Keeps a value out of trouble inside a header: no quotes, backslashes, line breaks or invisible characters. */
        fun header(value: String): String = value.filter { it != '"' && it != '\\' && it != '\r' && it != '\n' && Character.getType(it) != Character.FORMAT.toInt() }
    }
}
