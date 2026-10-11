// SPDX-License-Identifier: AGPL-3.0-or-later
//
// What the "Voicemail", "Recent" and "Opnames" screens need from the API, per paired account. One implementation talks to the server,
// tests and the demo mode (through the demo transport) bring their own. Every method throws [ApiException]; [MediaFailure.classify]
// turns that into words.

package nl.fullstackstudio.fsvoip.core

interface MediaServicing {
    suspend fun calls(accountId: String, month: String?): CallsPage

    suspend fun voicemail(accountId: String, box: String?): VoicemailPage

    suspend fun deleteVoicemail(accountId: String, boxId: String, ref: String)

    fun recordingSource(accountId: String, callId: String): AudioSource

    fun voicemailSource(accountId: String, boxId: String, ref: String): AudioSource

    /** The whole audio in one request. */
    suspend fun download(accountId: String, media: MediaRequest): MediaDownload
}

class LiveMediaService(
    private val clientFor: (accountId: String) -> FsVoipApiClient?,
    private val locale: () -> String = { currentLanguage() },
) : MediaServicing {
    private fun client(accountId: String): FsVoipApiClient = clientFor(accountId) ?: throw ApiException.MissingDeviceToken()

    override suspend fun calls(accountId: String, month: String?): CallsPage = client(accountId).calls(month, locale())

    override suspend fun voicemail(accountId: String, box: String?): VoicemailPage = client(accountId).voicemail(box, locale())

    override suspend fun deleteVoicemail(accountId: String, boxId: String, ref: String) {
        client(accountId).deleteVoicemail(boxId, ref)
    }

    override fun recordingSource(accountId: String, callId: String): AudioSource =
        AudioSource.Stream(client(accountId).recordingMedia(callId))

    override fun voicemailSource(accountId: String, boxId: String, ref: String): AudioSource =
        AudioSource.Stream(client(accountId).voicemailMedia(boxId, ref))

    override suspend fun download(accountId: String, media: MediaRequest): MediaDownload = client(accountId).downloadMedia(media)

    companion object {
        /** `nl` or `en`: the languages the server formats its labels in. */
        fun currentLanguage(): String = if (java.util.Locale.getDefault().language.equals("nl", ignoreCase = true)) "nl" else "en"
    }
}
