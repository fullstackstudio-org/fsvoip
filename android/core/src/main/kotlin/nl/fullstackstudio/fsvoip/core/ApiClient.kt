// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed class ApiException(message: String) : Exception(message) {
    /** 404 on `/pair`: unknown, expired, used or malformed pairing token (one neutral answer). */
    class NotFound : ApiException("not_found")

    /** 401: the device token is unknown or revoked. The app must remove the account. */
    class Unauthorized : ApiException("unauthorized")

    class RateLimited(val retryAfterSeconds: Int?) : ApiException("rate_limited")

    /** 400: the app sent something the server does not accept (a bug in the app). `code`: `resync`, `invalid_phone`, ... */
    class InvalidRequest(val detail: String?, val code: String? = null, val field: String? = null, val error: String = "invalid_request") : ApiException("invalid_request")

    /** 403: this pairing may not do that. `required` = the role that is needed, `field` = the extension field a `user` may not change. */
    class Forbidden(val required: String?, val field: String?) : ApiException("forbidden")

    /** 404 `call_not_found`: no running call of this extension has that Call-ID. */
    class CallNotFound : ApiException("call_not_found")

    /** 409: `stale`, `read_only`, `no_free_slot`, `park_unavailable`, `park_busy`, `in_use`, `conflict` (`code`), ... */
    class Conflict(
        val error: String,
        val code: String?,
        val version: Int?,
        /** `in_use`: where the object is still used. */
        val places: JsonElement? = null,
        /** `stale` of a number chain: the fresh chain, best effort. */
        val chain: JsonElement? = null,
    ) : ApiException(error)

    /** 422 `blocked_destination`: a block list stops these external numbers. */
    class BlockedDestination(val numbers: List<String>) : ApiException("blocked_destination")

    /** 422 `cost_not_accepted`: the call recording costs money and was not accepted yet. */
    class CostNotAccepted(val cost: RecordingCost?) : ApiException("cost_not_accepted")

    /** 410: the audio is past its retention period (recordings 90 days, voicemail 30 days). */
    class Gone : ApiException("gone")

    /** 416: the `Range` of a media request does not fit the audio. */
    class RangeNotSatisfiable : ApiException("range_not_satisfiable")

    /** 503 `park_uncertain`: the call MAY be parked. Never park again; look at the list. */
    class ParkUncertain : ApiException("park_uncertain")

    class PayloadTooLarge : ApiException("payload_too_large")

    /** 503 (or other temporary trouble). `retryable` = the same request may work shortly. */
    class Unavailable(val retryable: Boolean, val retryAfterSeconds: Int?) : ApiException("unavailable")

    class MissingDeviceToken : ApiException("missing_device_token")

    class Transport(detail: String) : ApiException(detail)

    class Decoding(detail: String) : ApiException(detail)

    class UnexpectedStatus(val status: Int) : ApiException("status $status")
}

/** A body that is not JSON (a multipart upload). */
class RawBody(val bytes: ByteArray, val contentType: String)

/** Where a media player fetches audio from, and the headers it must send. Never print it: the headers hold the device token. */
class MediaSource(val url: String, val headers: Map<String, String>) {
    override fun toString(): String = "MediaSource(•••)"
}

/** One HTTP exchange, without any client library in the way (tests use a fake). */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    /** `null` = JSON. Set for a multipart upload (with its boundary). */
    val contentType: String? = null,
) {
    // Never print headers or the body: they carry the device token or the SIP password.
    override fun toString(): String = "HttpRequest($method $url)"

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

/** OkHttp without a cache or cookies: this API is stateless and `no-store`. */
class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build(),
) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        // POST, PUT and PATCH need a body even when there is nothing to send (`POST /unpair`).
        val body = (request.body ?: if (request.method in setOf("POST", "PUT", "PATCH")) ByteArray(0) else null)
            ?.toRequestBody((request.contentType ?: "application/json").toMediaType())
        val builder = Request.Builder().url(request.url).method(request.method, body)
        request.headers.forEach { (name, value) -> builder.header(name, value) }

        try {
            client.newCall(builder.build()).execute().use { response ->
                HttpResponse(
                    status = response.code,
                    headers = response.headers.associate { it.first to it.second },
                    body = response.body?.bytes() ?: ByteArray(0),
                )
            }
        } catch (error: IOException) {
            throw ApiException.Transport(error.javaClass.simpleName)
        }
    }
}

/** Client of `/api/voip-app/v1`. One instance per paired account (the device token is part of it). */
class FsVoipApiClient(
    private val baseUrl: String = PRODUCTION_BASE_URL,
    private val deviceToken: Secret? = null,
    private val transport: HttpTransport = OkHttpTransport(),
    private val userAgent: String = "FSVoip",
    private val logger: FsLogger = FsLogger("api"),
) {
    /** The same client, authenticated with a device token. */
    fun authenticated(token: Secret): FsVoipApiClient = FsVoipApiClient(baseUrl, token, transport, userAgent, logger)

    /** `POST /pair`. 🚨 The response holds the SIP password: keep it in the encrypted store, never log it. */
    suspend fun pair(request: PairRequest): PairResponse =
        perform("POST", "pair", FsJson.default.encodeToJsonElement(PairRequest.serializer(), request), authenticated = false, PairResponse.serializer())

    suspend fun me(): MeResponse = perform("GET", "me", null, authenticated = true, MeResponse.serializer())

    suspend fun setLabelOverride(label: String?): MePatchResponse =
        perform("PATCH", "me", MePatchRequest(label).toJson(), authenticated = true, MePatchResponse.serializer())

    suspend fun updatePushToken(update: PushTokenUpdate): OkResponse =
        perform("PUT", "push-token", update.toJson(), authenticated = true, OkResponse.serializer())

    suspend fun unpair(): OkResponse = perform("POST", "unpair", null, authenticated = true, OkResponse.serializer())

    // MARK: The own extension

    suspend fun selfExtension(): SelfExtension = perform("GET", "me/extension", null, authenticated = true, SelfExtension.serializer())

    suspend fun patchSelfExtension(patch: SelfExtensionPatch): SelfExtensionPatchResponse =
        perform("PATCH", "me/extension", patch.toJson(), authenticated = true, SelfExtensionPatchResponse.serializer())

    // MARK: Calls, voicemail, parking

    /** `GET /calls?month=YYYY-MM&locale=nl`. */
    suspend fun calls(month: String? = null, locale: String = "nl"): PbxCallsPage =
        perform("GET", "calls" + query("month" to month, "locale" to locale), null, authenticated = true, PbxCallsPage.serializer())

    suspend fun voicemail(boxId: String? = null, locale: String = "nl"): VoicemailPage =
        perform("GET", "voicemail" + query("box" to boxId, "locale" to locale), null, authenticated = true, VoicemailPage.serializer())

    suspend fun deleteVoicemail(boxId: String, ref: String): OkResponse =
        perform("DELETE", "voicemail/${segment(boxId)}/${segment(ref)}", null, authenticated = true, OkResponse.serializer())

    /** `POST /calls/park` with the SIP Call-ID of the running call. 🚨 `ParkUncertain`: look at the list, never repeat. */
    suspend fun park(callId: String): ParkedCall =
        perform("POST", "calls/park", ParkRequest(callId).toJson(), authenticated = true, ParkedCall.serializer())

    suspend fun parked(): ParkedCallsPage = perform("GET", "parked", null, authenticated = true, ParkedCallsPage.serializer())

    suspend fun hangupParked(id: String): OkResponse =
        perform("DELETE", "parked/${segment(id)}", null, authenticated = true, OkResponse.serializer())

    // MARK: Live calls

    /** `GET /pbx/live`. `Conflict("live_unavailable")` = treat as "off". */
    suspend fun liveCalls(): LiveCallsPage = perform("GET", "pbx/live", null, authenticated = true, LiveCallsPage.serializer())

    suspend fun liveTargets(): LiveTargetsPage = perform("GET", "pbx/live/targets", null, authenticated = true, LiveTargetsPage.serializer())

    /** Always the OWN extension; send the `ref` of the latest list. */
    suspend fun pickupLiveCall(ref: String): LivePickupResponse =
        perform("POST", "pbx/live/pickup", LivePickupRequest(ref).toJson(), authenticated = true, LivePickupResponse.serializer())

    /** Blind transfer to an internal target. */
    suspend fun transferLiveCall(ref: String, target: LiveTarget): LiveTransferResponse =
        perform("POST", "pbx/live/transfer", LiveTransferRequest(ref, target.type, target.id).toJson(), authenticated = true, LiveTransferResponse.serializer())

    // MARK: The PBX (admin pairings)

    suspend fun pbxDevices(): PbxDevices = perform("GET", "pbx/devices", null, authenticated = true, PbxDevices.serializer())

    suspend fun patchPbxDevice(id: String, patch: PbxDevicePatch): OkResponse =
        perform("PATCH", "pbx/devices/${segment(id)}", patch.toJson(), authenticated = true, OkResponse.serializer())

    suspend fun pbxRingGroups(): PbxRingGroups = perform("GET", "pbx/ring-groups", null, authenticated = true, PbxRingGroups.serializer())

    suspend fun createPbxRingGroup(create: PbxRingGroupCreate): PbxRingGroupCreated =
        perform("POST", "pbx/ring-groups", create.toJson(), authenticated = true, PbxRingGroupCreated.serializer())

    suspend fun patchPbxRingGroup(id: String, patch: PbxRingGroupPatch): OkResponse =
        perform("PATCH", "pbx/ring-groups/${segment(id)}", patch.toJson(), authenticated = true, OkResponse.serializer())

    suspend fun pbxOverview(): PbxOverview = perform("GET", "pbx/overview", null, authenticated = true, PbxOverview.serializer())

    suspend fun pbxHours(): PbxHours = perform("GET", "pbx/hours", null, authenticated = true, PbxHours.serializer())

    suspend fun patchPbxHours(id: String, patch: PbxHoursPatch): OkResponse =
        perform("PATCH", "pbx/hours/${segment(id)}", patch.toJson(), authenticated = true, OkResponse.serializer())

    suspend fun patchPbxNumberRouting(id: String, patch: PbxRoutingPatch): OkResponse =
        perform("PATCH", "pbx/numbers/${segment(id)}/routing", patch.toJson(), authenticated = true, OkResponse.serializer())

    suspend fun pbxNumbers(): PbxNumbersPage = perform("GET", "pbx/numbers", null, authenticated = true, PbxNumbersPage.serializer())

    suspend fun pbxNumberChain(id: String): NumberChain = perform("GET", "pbx/numbers/${segment(id)}/chain", null, authenticated = true, NumberChain.serializer())

    /** One step of a chain; answers with the fresh chain. `409 stale` carries the fresh chain in [ApiException.Conflict.chain]. */
    suspend fun putPbxNumberChainStep(id: String, step: ChainStep, chain: NumberChain): NumberChain =
        perform("PUT", "pbx/numbers/${segment(id)}/chain/${step.path}", step.request(chain), authenticated = true, NumberChain.serializer())

    suspend fun patchPbxNumberRecording(id: String, patch: NumberRecordingPatch): NumberChain =
        perform("PATCH", "pbx/numbers/${segment(id)}/recording", patch.toJson(), authenticated = true, NumberChain.serializer())

    suspend fun pbxSounds(): PbxSoundsPage = perform("GET", "pbx/sounds", null, authenticated = true, PbxSoundsPage.serializer())

    /** `POST /pbx/sounds`: exactly the parts `name` and `file`. The server reads the type from the first bytes. */
    suspend fun uploadPbxSound(name: String, fileName: String, contentType: String, bytes: ByteArray): SoundUploadResponse {
        val body = Multipart.build(listOf(Multipart.Part.Text("name", name), Multipart.Part.File("file", fileName, contentType, bytes)))

        return decode("pbx/sounds", SoundUploadResponse.serializer(), execute("POST", "pbx/sounds", null, authenticated = true, raw = body))
    }

    suspend fun renamePbxSound(id: String, name: String): OkResponse =
        perform("PATCH", "pbx/sounds/${segment(id)}", kotlinx.serialization.json.buildJsonObject { put("name", JsonPrimitive(name)) }, authenticated = true, OkResponse.serializer())

    suspend fun deletePbxSound(id: String): OkResponse = perform("DELETE", "pbx/sounds/${segment(id)}", null, authenticated = true, OkResponse.serializer())

    /** The audio of a sound (admin only): the URL and the headers a player needs. */
    fun soundAudio(id: String): MediaSource {
        val token = deviceToken ?: throw ApiException.MissingDeviceToken()

        return MediaSource(
            url = "${baseUrl.trimEnd('/')}/pbx/sounds/${segment(id)}/audio",
            headers = mapOf("Authorization" to "Bearer ${token.reveal()}", "User-Agent" to userAgent, "Accept" to "audio/*"),
        )
    }

    /** Invite a colleague: a link that pairs their phone with the extension [deviceId] (role `user`). 🚨 A credential: show it once. */
    suspend fun createPbxDeviceAppPairing(deviceId: String): AppPairingResponse =
        perform("POST", "pbx/devices/${segment(deviceId)}/app-pairing", null, authenticated = true, AppPairingResponse.serializer())

    // MARK: Media

    /**
     * What a player needs to fetch the audio of a voicemail message: the URL and the headers. The token only ever goes in
     * the `Authorization` header (never in the URL); the player adds its own `Range`.
     */
    fun voicemailAudio(boxId: String, ref: String): MediaSource {
        val token = deviceToken ?: throw ApiException.MissingDeviceToken()

        return MediaSource(
            url = "${baseUrl.trimEnd('/')}/voicemail/${segment(boxId)}/${segment(ref)}/audio",
            headers = mapOf("Authorization" to "Bearer ${token.reveal()}", "User-Agent" to userAgent, "Accept" to "audio/*"),
        )
    }

    /** The audio of a call recording (admin only): the URL and the headers a player needs. */
    fun recordingAudio(callId: String): MediaSource {
        val token = deviceToken ?: throw ApiException.MissingDeviceToken()

        return MediaSource(
            url = "${baseUrl.trimEnd('/')}/calls/${segment(callId)}/recording",
            headers = mapOf("Authorization" to "Bearer ${token.reveal()}", "User-Agent" to userAgent, "Accept" to "audio/*"),
        )
    }

    // MARK: Contacts

    /** One page of the sync. Without [since] the complete set. */
    suspend fun contacts(since: String? = null, cursor: String? = null, limit: Int? = null): ContactsPage =
        perform("GET", "contacts" + query("since" to since, "cursor" to cursor, "limit" to limit?.toString()), null, authenticated = true, ContactsPage.serializer())

    suspend fun contact(id: String): Contact =
        perform("GET", "contacts/${segment(id)}", null, authenticated = true, ContactSingleResponse.serializer()).contact

    suspend fun createContact(create: ContactCreate): Contact =
        perform("POST", "contacts", create.toJson(), authenticated = true, ContactSingleResponse.serializer()).contact

    suspend fun updateContact(id: String, update: ContactUpdate): ContactUpdateResponse =
        perform("PATCH", "contacts/${segment(id)}", update.toJson(), authenticated = true, ContactUpdateResponse.serializer())

    suspend fun deleteContact(id: String): ContactDeleteResponse =
        perform("DELETE", "contacts/${segment(id)}", null, authenticated = true, ContactDeleteResponse.serializer())

    /** Who is calling? Only for an external number; the app waits at most a second for this on an incoming call. */
    suspend fun lookupContact(number: String): ContactLookup =
        perform("GET", "contacts/lookup" + query("number" to number), null, authenticated = true, ContactLookup.serializer())

    suspend fun contactTimeline(id: String, cursor: String? = null): ContactTimelinePage =
        perform("GET", "contacts/${segment(id)}/timeline" + query("cursor" to cursor), null, authenticated = true, ContactTimelinePage.serializer())

    suspend fun contactLists(): ContactListsResponse =
        perform("GET", "contact-lists", null, authenticated = true, ContactListsResponse.serializer())

    /**
     * The snapshot of a list. With [etag] (the version in quotes, [ContactListETag.make]) a list that did not change answers
     * `304`: [ContactListSnapshotOutcome.NotModified]. The `If-None-Match` is ignored by the server on a cursor page.
     */
    suspend fun contactListSnapshot(id: String, cursor: String? = null, etag: String? = null): ContactListSnapshotOutcome {
        val extra = if (etag != null && cursor == null) mapOf("If-None-Match" to etag) else emptyMap()
        val response = execute("GET", "contact-lists/${segment(id)}/contacts" + query("cursor" to cursor), null, authenticated = true, extraHeaders = extra, accept = setOf(304))

        if (response.status == 304) {
            return ContactListSnapshotOutcome.NotModified(response.header("ETag"))
        }

        return ContactListSnapshotOutcome.Snapshot(decode("contact-lists", ContactListSnapshot.serializer(), response), response.header("ETag"))
    }

    private suspend fun <T> perform(
        method: String,
        path: String,
        body: JsonElement?,
        authenticated: Boolean,
        deserializer: DeserializationStrategy<T>,
    ): T = decode(path.substringBefore('?'), deserializer, execute(method, path, body, authenticated))

    private fun <T> decode(path: String, deserializer: DeserializationStrategy<T>, response: HttpResponse): T {
        // Only the first segment goes into the message: later segments are ids (a contact, a sealed voicemail reference).
        val label = path.split('/').firstOrNull { it.isNotEmpty() } ?: ""

        return try {
            FsJson.default.decodeFromString(deserializer, response.body.toString(Charsets.UTF_8))
        } catch (error: SerializationException) {
            throw ApiException.Decoding("$label: ${error.javaClass.simpleName}")
        } catch (error: IllegalArgumentException) {
            throw ApiException.Decoding("$label: ${error.javaClass.simpleName}")
        }
    }

    /** One exchange. A non-2xx answer (other than the statuses in [accept]) becomes an [ApiException]. */
    private suspend fun execute(
        method: String,
        path: String,
        body: JsonElement?,
        authenticated: Boolean,
        extraHeaders: Map<String, String> = emptyMap(),
        accept: Set<Int> = emptySet(),
        raw: RawBody? = null,
    ): HttpResponse {
        val headers = linkedMapOf(
            "Accept" to "application/json",
            "User-Agent" to userAgent,
            "Cache-Control" to "no-store",
        )
        headers.putAll(extraHeaders)

        if (authenticated) {
            val token = deviceToken ?: throw ApiException.MissingDeviceToken()
            headers["Authorization"] = "Bearer ${token.reveal()}"
        }

        val bytes = raw?.bytes ?: body?.let {
            headers["Content-Type"] = "application/json"
            FsJson.default.encodeToString(JsonElement.serializer(), it).toByteArray(Charsets.UTF_8)
        }

        if (raw != null) {
            headers["Content-Type"] = raw.contentType
        }

        // Never log bodies, queries or headers: the pair response carries the SIP password, a path can hold a message reference.
        val label = path.substringBefore('?').let { if (it.startsWith("voicemail/") || it.startsWith("parked/") || it.contains("app-pairing") || it.startsWith("calls/") && it.endsWith("/recording")) it.substringBefore('/') + "/…" else it }
        logger.debug("$method /$label")

        val response = try {
            transport.send(HttpRequest(method, "${baseUrl.trimEnd('/')}/$path", headers, bytes, raw?.contentType))
        } catch (error: ApiException) {
            throw error
        } catch (error: IOException) {
            throw ApiException.Transport(error.javaClass.simpleName)
        }

        logger.debug("$method /$label -> ${response.status}")

        if (response.status !in 200..299 && response.status !in accept) {
            throw errorFor(response)
        }

        return response
    }

    companion object {
        /** A path segment, percent-encoded (a voicemail `ref` and a parked `id` are opaque and may hold `.`, `-`, `_`). */
        internal fun segment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /** `?a=1&b=2` of the pairs that have a value, or an empty string. */
        internal fun query(vararg pairs: Pair<String, String?>): String {
            val parts = pairs.mapNotNull { (key, value) -> value?.let { "$key=${URLEncoder.encode(it, "UTF-8").replace("+", "%20")}" } }

            return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
        }

        const val PRODUCTION_BASE_URL = "https://fullstackstudio.nl/api/voip-app/v1"

        fun errorFor(response: HttpResponse): ApiException {
            val body = try {
                FsJson.default.decodeFromString(ApiErrorBody.serializer(), response.body.toString(Charsets.UTF_8))
            } catch (_: Exception) {
                null
            }
            val retryAfter = response.header("Retry-After")?.trim()?.toIntOrNull()

            return when (response.status) {
                400 -> ApiException.InvalidRequest(body?.message, body?.code, body?.field, body?.error ?: "invalid_request")
                401 -> ApiException.Unauthorized()
                403 -> ApiException.Forbidden(body?.required, body?.field)
                404 -> if (body?.error == "call_not_found") ApiException.CallNotFound() else ApiException.NotFound()
                409 -> ApiException.Conflict(body?.error ?: "conflict", body?.code, body?.version, body?.places, body?.chain)
                410 -> ApiException.Gone()
                413 -> ApiException.PayloadTooLarge()
                416 -> ApiException.RangeNotSatisfiable()
                422 -> when (body?.error) {
                    "blocked_destination" -> ApiException.BlockedDestination((body.blocked as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty())
                    "cost_not_accepted" -> ApiException.CostNotAccepted(body.cost?.let { runCatching { FsJson.default.decodeFromJsonElement(RecordingCost.serializer(), it) }.getOrNull() })
                    else -> ApiException.UnexpectedStatus(422)
                }
                429 -> ApiException.RateLimited(retryAfter)
                503 -> if (body?.error == "park_uncertain") ApiException.ParkUncertain() else ApiException.Unavailable(body?.retryable ?: true, retryAfter)
                else -> ApiException.UnexpectedStatus(response.status)
            }
        }
    }
}
