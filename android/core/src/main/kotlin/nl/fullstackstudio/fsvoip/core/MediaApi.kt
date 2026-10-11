// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The routes of calls, voicemail and the audio of both. Extension functions on [FsVoipApiClient]; errors are the [ApiException]s the
// client already maps from the status and body.

package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.json.JsonElement

/**
 * A request for call-recording or voicemail audio. The server accepts the device token ONLY as `Authorization: Bearer` (no token in
 * the URL), supports `Range` (206/416) and answers HEAD like GET without a body. The Android player downloads the audio through
 * [FsVoipApiClient.downloadMedia]; [headers] is for a player that fetches by itself.
 *
 * 🚨 Never log the URL together with the headers, and never put the headers in a log at all.
 */
class MediaRequest(
    /** The path after the API base (`calls/<id>/recording`); each segment is percent-encoded when the URL is built. */
    val path: String,
    /** The full URL (holds no token). */
    val url: String,
    private val headerProvider: () -> Map<String, String>,
) {
    /** The headers every request for this media needs (contains the bearer token). */
    fun headers(): Map<String, String> = headerProvider()

    override fun toString(): String = "MediaRequest($path)"
}

/** `GET /calls?month=YYYY-MM&locale=nl|en`. No month = the current one. A `user` gets the whole team's calls (no recordings). */
suspend fun FsVoipApiClient.calls(month: String? = null, locale: String? = null): CallsPage {
    val query = buildList {
        month?.let { add("month" to it) }
        locale?.let { add("locale" to it) }
    }

    return get("calls", CallsPage.serializer(), query)
}

/** `GET /voicemail?box=<boxId>&locale=`. No box = the own box (`user`) or all boxes (admin). */
suspend fun FsVoipApiClient.voicemail(box: String? = null, locale: String? = null): VoicemailPage {
    val query = buildList {
        box?.let { add("box" to it) }
        locale?.let { add("locale" to it) }
    }

    return get("voicemail", VoicemailPage.serializer(), query)
}

/** `DELETE /voicemail/{boxId}/{ref}` (a `user` only in the own box). */
suspend fun FsVoipApiClient.deleteVoicemail(boxId: String, ref: String): OkResponse =
    perform("DELETE", "voicemail/$boxId/$ref", null as JsonElement?, authenticated = true, OkResponse.serializer())

/** The audio of a call recording (admin only; `410` = past retention). */
fun FsVoipApiClient.recordingMedia(callId: String): MediaRequest = mediaRequest("calls/$callId/recording")

/** The audio of a voicemail message. */
fun FsVoipApiClient.voicemailMedia(boxId: String, ref: String): MediaRequest = mediaRequest("voicemail/$boxId/$ref/audio")

private fun FsVoipApiClient.mediaRequest(path: String): MediaRequest {
    // Fails now (not when the player starts) when this client has no device token.
    authorizationHeaders()

    return MediaRequest(path, url(path)) { authorizationHeaders() }
}

/** Audio is a few MB at most (a recording of an hour is the extreme); more than this is not audio we want in memory. */
const val MAX_MEDIA_DOWNLOAD_BYTES = 64 * 1024 * 1024

private const val MEDIA_TIMEOUT_SECONDS = 120

/**
 * The whole audio in one request. Errors map like every other route, so a `410` is [ApiException.Gone] and a `429` carries its
 * `Retry-After`.
 */
suspend fun FsVoipApiClient.downloadMedia(media: MediaRequest): MediaDownload {
    val response = send(
        "GET",
        media.path,
        null,
        authenticated = true,
        headers = mapOf("Accept" to "audio/*, application/octet-stream;q=0.5"),
        timeoutSeconds = MEDIA_TIMEOUT_SECONDS,
    )

    if (response.body.size > MAX_MEDIA_DOWNLOAD_BYTES) throw ApiException.PayloadTooLarge()

    return MediaDownload(response.body, response.header("Content-Type"))
}
