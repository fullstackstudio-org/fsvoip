// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer

sealed class ApiException(message: String) : Exception(message) {
    /** 404 on `/pair`: unknown, expired, used or malformed pairing token (one neutral answer). */
    class NotFound : ApiException("not_found")

    /** 401: the device token is unknown or revoked. The app must remove the account. */
    class Unauthorized : ApiException("unauthorized")

    class RateLimited(val retryAfterSeconds: Int?) : ApiException("rate_limited")

    /** 400: the app sent something the server does not accept (a bug in the app). */
    class InvalidRequest(val detail: String?) : ApiException("invalid_request")

    class PayloadTooLarge : ApiException("payload_too_large")

    /** 503 (or other temporary trouble). `retryable` = the same request may work shortly. */
    class Unavailable(val retryable: Boolean, val retryAfterSeconds: Int?) : ApiException("unavailable")

    class MissingDeviceToken : ApiException("missing_device_token")

    class Transport(detail: String) : ApiException(detail)

    class Decoding(detail: String) : ApiException(detail)

    class UnexpectedStatus(val status: Int) : ApiException("status $status")

    /** 403: this pairing may not do that (role `user`, or the role was taken away). `field`: the extension field a user may not change. */
    class Forbidden(val field: String? = null) : ApiException("forbidden")

    /** 409 `read_only`: the PBX is frozen or still being set up. */
    class ReadOnly : ApiException("read_only")

    /** 409 `stale`: the object changed in the meantime (`version` = the current version when the server tells). */
    class Stale(val version: Int?) : ApiException("stale")

    /** 409 `stale` of a chain step: `chain` is the fresh chain (raw JSON, decode with the number-chain model). */
    class StaleChain(val chain: JsonElement) : ApiException("stale_chain")

    /** 409 `in_use`: names the customer chose of the places that still use the object. */
    class InUse(val places: List<ApiErrorPlace>) : ApiException("in_use")

    /** 409, any other code (`busy`, `not_ready`, `range_full`, `limit_reached`, ...). */
    class Conflict(val code: String) : ApiException("conflict")

    /** 422 `blocked_destination`: a block list stops these external numbers. */
    class BlockedDestination(val numbers: List<String>) : ApiException("blocked_destination")

    /** 410: gone for good (a recording past its retention period). */
    class Gone : ApiException("gone")

    /** 400 with `code: "resync"`: `since` is too old, do a full contacts sync. */
    class Resync : ApiException("resync")

    /** 400 `invalid_request` with a `code` (and the `field` that is wrong), e.g. `invalid_phone`. */
    class Invalid(val code: String, val field: String?) : ApiException("invalid")

    /** 409 `advanced`: the flow behind the number is more than the chain can show. */
    class Advanced : ApiException("advanced")

    /** 400 with `code: "greeting_required"`: a welcome message needs a sound. */
    class GreetingRequired : ApiException("greeting_required")

    /** `cost_not_accepted`: switching a recording on costs money; `cost` is the raw JSON of the price to accept. */
    class CostNotAccepted(val cost: JsonElement?) : ApiException("cost_not_accepted")

    class InvalidAudio : ApiException("invalid_audio")

    class TooLarge : ApiException("too_large")

    class TooMany : ApiException("too_many")

    /** 404 `call_not_found`: no running call of this extension has that Call-ID. */
    class CallNotFound : ApiException("call_not_found")

    class NoFreeSlot : ApiException("no_free_slot")

    class ParkUnavailable : ApiException("park_unavailable")

    /** 503 `park_uncertain`: the call MAY be parked. Never park again: refresh the parked list. */
    class ParkUncertain : ApiException("park_uncertain")
}

/** One HTTP exchange, without any client library in the way (tests use a fake). */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    /** `Content-Type` of [body]. */
    val contentType: String = "application/json",
    /** Upload progress 0...1 (uploads only). */
    val progress: ((Double) -> Unit)? = null,
    val timeoutSeconds: Int? = null,
) {
    // Never print headers or the body: they carry the device token or the SIP password.
    override fun toString(): String = "HttpRequest($method $url)"

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

/** A place that still uses something you try to delete (`in_use`); the name is one the customer chose. */
@kotlinx.serialization.Serializable
data class ApiErrorPlace(val kind: String, val name: String)

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
        var body = request.body?.toRequestBody(request.contentType.toMediaType())
        if (body != null && request.progress != null) body = ProgressBody(body, request.progress)
        // OkHttp insists on a body for POST/PUT/PATCH.
        if (body == null && request.method in setOf("POST", "PUT", "PATCH")) body = ByteArray(0).toRequestBody(null)

        val builder = Request.Builder().url(request.url).method(request.method, body)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val http = if (request.timeoutSeconds != null) {
            client.newBuilder().callTimeout(request.timeoutSeconds.toLong(), TimeUnit.SECONDS).build()
        } else {
            client
        }

        try {
            http.newCall(builder.build()).execute().use { response ->
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

    private class ProgressBody(private val delegate: okhttp3.RequestBody, private val progress: (Double) -> Unit) : okhttp3.RequestBody() {
        override fun contentType() = delegate.contentType()

        override fun contentLength() = delegate.contentLength()

        override fun writeTo(sink: okio.BufferedSink) {
            val total = contentLength().toDouble().coerceAtLeast(1.0)
            val counting = object : okio.ForwardingSink(sink) {
                var written = 0L

                override fun write(source: okio.Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    written += byteCount
                    progress((written / total).coerceIn(0.0, 1.0))
                }
            }.buffer()
            delegate.writeTo(counting)
            counting.flush()
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

    val hasDeviceToken: Boolean get() = deviceToken != null

    /** `POST /pair`. 🚨 The response holds the SIP password: keep it in the encrypted store, never log it. */
    suspend fun pair(request: PairRequest): PairResponse =
        perform("POST", "pair", FsJson.default.encodeToJsonElement(PairRequest.serializer(), request), authenticated = false, PairResponse.serializer())

    suspend fun me(): MeResponse = perform("GET", "me", null, authenticated = true, MeResponse.serializer())

    suspend fun setLabelOverride(label: String?): MePatchResponse =
        perform("PATCH", "me", MePatchRequest(label).toJson(), authenticated = true, MePatchResponse.serializer())

    suspend fun updatePushToken(update: PushTokenUpdate): OkResponse =
        perform("PUT", "push-token", update.toJson(), authenticated = true, OkResponse.serializer())

    suspend fun unpair(): OkResponse = perform("POST", "unpair", null, authenticated = true, OkResponse.serializer())

    // MARK: Plumbing (the feature calls live in extension functions next to their models)

    /** Typed request. `path` is the part after the base URL; every segment is percent-encoded on its own. */
    suspend fun <T> perform(
        method: String,
        path: String,
        body: JsonElement?,
        authenticated: Boolean,
        deserializer: DeserializationStrategy<T>,
        query: List<Pair<String, String>> = emptyList(),
        headers: Map<String, String> = emptyMap(),
    ): T {
        val response = send(method, path, body, authenticated, query, headers)

        return decode(path, deserializer, response)
    }

    suspend fun <T> get(path: String, deserializer: DeserializationStrategy<T>, query: List<Pair<String, String>> = emptyList(), headers: Map<String, String> = emptyMap()): T =
        perform("GET", path, null, authenticated = true, deserializer, query, headers)

    fun <T> decode(path: String, deserializer: DeserializationStrategy<T>, response: HttpResponse): T = try {
        FsJson.default.decodeFromString(deserializer, response.body.toString(Charsets.UTF_8))
    } catch (error: SerializationException) {
        throw ApiException.Decoding("$path: ${error.javaClass.simpleName}")
    } catch (error: IllegalArgumentException) {
        throw ApiException.Decoding("$path: ${error.javaClass.simpleName}")
    }

    /**
     * The raw variant: status and headers (such as `ETag`) are kept. `acceptNotModified`: a 304 is an answer, not an error
     * (conditional GET); the body is then empty. A raw body (multipart upload) goes through [rawBody].
     */
    suspend fun send(
        method: String,
        path: String,
        body: JsonElement?,
        authenticated: Boolean = true,
        query: List<Pair<String, String>> = emptyList(),
        headers: Map<String, String> = emptyMap(),
        acceptNotModified: Boolean = false,
        rawBody: ByteArray? = null,
        contentType: String? = null,
        progress: ((Double) -> Unit)? = null,
        timeoutSeconds: Int? = null,
    ): HttpResponse {
        val allHeaders = linkedMapOf(
            "Accept" to "application/json",
            "User-Agent" to userAgent,
            "Cache-Control" to "no-store",
        )
        allHeaders.putAll(headers)

        if (authenticated) {
            val token = deviceToken ?: throw ApiException.MissingDeviceToken()
            allHeaders["Authorization"] = "Bearer ${token.reveal()}"
        }

        val bytes = rawBody ?: body?.let {
            FsJson.default.encodeToString(JsonElement.serializer(), it).toByteArray(Charsets.UTF_8)
        }

        // Never log bodies, queries or headers: the pair response carries the SIP password and contact queries a sync cursor.
        val label = path.split('/').firstOrNull { it.isNotEmpty() } ?: ""
        logger.debug("$method /$label")

        val response = try {
            transport.send(HttpRequest(method, url(path, query), allHeaders, bytes, contentType ?: "application/json", progress, timeoutSeconds))
        } catch (error: ApiException) {
            throw error
        } catch (error: IOException) {
            throw ApiException.Transport(error.javaClass.simpleName)
        }

        logger.debug("$method /$label -> ${response.status}")

        if (acceptNotModified && response.status == 304) return response
        if (response.status !in 200..299) throw errorFor(response)

        return response
    }

    /** `base/<segments>?query`, every segment percent-encoded on its own (a sealed voicemail reference is opaque text). */
    fun url(path: String, query: List<Pair<String, String>> = emptyList()): String {
        val builder = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
        path.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }

        return builder.build().toString()
    }

    /** The `Authorization` and `User-Agent` a media player needs to fetch audio by itself (never logged). */
    fun authorizationHeaders(): Map<String, String> {
        val token = deviceToken ?: throw ApiException.MissingDeviceToken()

        return mapOf("Authorization" to "Bearer ${token.reveal()}", "User-Agent" to userAgent)
    }

    companion object {
        const val PRODUCTION_BASE_URL = "https://fullstackstudio.nl/api/voip-app/v1"

        fun errorFor(response: HttpResponse): ApiException {
            val body = try {
                FsJson.default.decodeFromString(ApiErrorBody.serializer(), response.body.toString(Charsets.UTF_8))
            } catch (_: Exception) {
                null
            }
            val retryAfter = response.header("Retry-After")?.trim()?.toIntOrNull()

            // The same error names can come with another status on another route, so the ones that need no detail are
            // recognised on the name first.
            when (body?.error) {
                "cost_not_accepted" -> return ApiException.CostNotAccepted(body.cost)
                "advanced" -> return ApiException.Advanced()
                "park_uncertain" -> return ApiException.ParkUncertain()
                "park_unavailable" -> return ApiException.ParkUnavailable()
                "no_free_slot" -> return ApiException.NoFreeSlot()
                "call_not_found" -> return ApiException.CallNotFound()
                "invalid_audio" -> return ApiException.InvalidAudio()
                "too_large" -> return ApiException.TooLarge()
                "too_many" -> return ApiException.TooMany()
            }

            return when (response.status) {
                400 -> when {
                    body?.code == "resync" -> ApiException.Resync()
                    body?.code == "greeting_required" -> ApiException.GreetingRequired()
                    body?.code != null -> ApiException.Invalid(body.code, body.field)
                    else -> ApiException.InvalidRequest(body?.message)
                }
                401 -> ApiException.Unauthorized()
                403 -> ApiException.Forbidden(body?.field)
                404 -> ApiException.NotFound()
                409 -> when (body?.error) {
                    "read_only" -> ApiException.ReadOnly()
                    "stale" -> body.chain?.let { ApiException.StaleChain(it) } ?: ApiException.Stale(body.version)
                    "in_use" -> ApiException.InUse(body.places ?: emptyList())
                    else -> ApiException.Conflict(body?.code ?: body?.error ?: "conflict")
                }
                410 -> ApiException.Gone()
                413 -> ApiException.PayloadTooLarge()
                422 -> if (body?.error == "blocked_destination") ApiException.BlockedDestination(body.blocked ?: emptyList()) else ApiException.UnexpectedStatus(422)
                429 -> ApiException.RateLimited(retryAfter)
                503 -> ApiException.Unavailable(body?.retryable ?: true, retryAfter)
                else -> ApiException.UnexpectedStatus(response.status)
            }
        }
    }
}
