// SPDX-License-Identifier: AGPL-3.0-or-later
//
// What the "Geluiden" screen and the audio picker need from the API, for ONE paired account. One implementation talks to the server;
// tests and the demo mode bring their own. Every method throws [ApiException]; [SoundFailure.classify] turns that into words.

package nl.fullstackstudio.fsvoip.core

interface SoundServicing {
    suspend fun sounds(): List<Sound>

    /** Uploads [data] as a new sound; [progress] is the fraction sent (0...1). Cancelling the calling coroutine cancels the request. */
    suspend fun upload(name: String, fileName: String, data: ByteArray, progress: ((Double) -> Unit)?): String

    suspend fun rename(id: String, name: String)

    suspend fun delete(id: String)

    /** The audio of a sound (`playable == true`). */
    suspend fun audio(id: String): ByteArray
}

class LiveSoundService(private val client: FsVoipApiClient) : SoundServicing {
    override suspend fun sounds(): List<Sound> = client.sounds().sounds

    override suspend fun upload(name: String, fileName: String, data: ByteArray, progress: ((Double) -> Unit)?): String =
        client.uploadSound(name, fileName, data, progress).soundId

    override suspend fun rename(id: String, name: String) {
        client.renameSound(id, name)
    }

    override suspend fun delete(id: String) {
        client.deleteSound(id)
    }

    override suspend fun audio(id: String): ByteArray = client.soundAudio(id)
}

/** Why a sound could not be added, renamed, deleted or loaded, in the user's terms. */
sealed interface SoundFailure {
    /** 400 `invalid_audio`: not WAV, MP3 or M4A. */
    data object InvalidAudio : SoundFailure

    /** 413 `too_large` (or a file above 20 MB before any request). */
    data object TooLarge : SoundFailure

    /** 409 `too_many`: the limit of sounds per centrale is reached. */
    data object TooMany : SoundFailure

    /** 409 `in_use`: the sound is still used here; nothing was deleted. */
    data class InUse(val places: List<ApiErrorPlace>) : SoundFailure

    data class RateLimited(val retryAfterSeconds: Int?) : SoundFailure

    /** 403: this pairing may not manage sounds (any more). */
    data object AccessDenied : SoundFailure

    /** 401: the pairing is gone. */
    data object Revoked : SoundFailure

    data object ReadOnly : SoundFailure

    data object NotFound : SoundFailure

    data object Offline : SoundFailure

    data object Unavailable : SoundFailure

    data object Other : SoundFailure

    /** A call is going on: nothing plays or records (the call owns the audio). Never from the server. */
    data object CallInProgress : SoundFailure

    val isRetryable: Boolean
        get() = this is RateLimited || this == Offline || this == Unavailable || this == Other

    companion object {
        fun classify(error: Throwable): SoundFailure = when (error) {
            is ApiException.InvalidAudio -> InvalidAudio
            is ApiException.TooLarge, is ApiException.PayloadTooLarge -> TooLarge
            is ApiException.TooMany -> TooMany
            is ApiException.InUse -> InUse(error.places)
            is ApiException.RateLimited -> RateLimited(error.retryAfterSeconds)
            is ApiException.Forbidden -> AccessDenied
            is ApiException.Unauthorized -> Revoked
            is ApiException.ReadOnly -> ReadOnly
            is ApiException.Conflict -> if (error.code == "read_only") ReadOnly else Other
            is ApiException.NotFound -> NotFound
            is ApiException.Transport -> Offline
            is ApiException.Unavailable, is ApiException.UnexpectedStatus -> Unavailable
            else -> Other
        }
    }
}
