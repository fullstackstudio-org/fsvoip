// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Models of `/pbx/sounds` and `/pbx/devices/{id}/app-pairing` in `shared/openapi.yaml` (admin pairings only): the sounds of the PBX
// (welcome messages, music, greetings) and the invitation link for a colleague.

package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * Where a sound is used. The kinds are the server's (`voicemail_greeting`, `extension_voicemail_greeting`, `queue_music`,
 * `queue_welcome`, `menu_greeting`, `closed_message`); others may follow, so it stays text.
 */
@Serializable
data class SoundUse(
    val kind: String,
    /** The name of the object that uses it (a name the customer chose). */
    val name: String,
)

@Serializable
data class Sound(
    val id: String,
    val name: String,
    /** Always null for now: the app reads the duration from the audio. */
    val durationSeconds: Double? = null,
    /** False = there is no copy to play (a sound from before this feature); the PBX still uses it. */
    val playable: Boolean = false,
    val uses: List<SoundUse> = emptyList(),
    /** `audio/wav`, `audio/mpeg` or `audio/mp4`; null without a copy. */
    val contentType: String? = null,
    val byteSize: Long? = null,
    val sync: SyncState = SyncState.UNKNOWN,
)

/** `GET /pbx/sounds`. */
@Serializable
data class SoundsPage(val sounds: List<Sound> = emptyList())

/** `POST /pbx/sounds`: the id of the new sound. */
@Serializable
data class SoundUploadResponse(val soundId: String)

/** `PATCH /pbx/sounds/{id}`. */
@Serializable
data class SoundRenameRequest(val name: String)

/**
 * `POST /pbx/devices/{id}/app-pairing`: a universal link that pairs FSVoip on a colleague's phone with that extension (role `user`).
 * 🚨 The link is a credential: show it once, never log it (it is a [Secret]).
 */
@Serializable
data class AppPairingResponse(
    val url: Secret,
    @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
)

/** The sound formats the server accepts (it reads the type from the first bytes; this is only for the picker and the part header). */
object SoundUpload {
    /** Up to 20 MB; a bigger file is `413 too_large` without being read. */
    const val MAX_BYTES: Int = 20 * 1024 * 1024

    /** The content type for the multipart part, from the file extension. The server ignores it (it reads the magic bytes). */
    fun contentType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a", "mp4" -> "audio/mp4"
        else -> "application/octet-stream"
    }
}
