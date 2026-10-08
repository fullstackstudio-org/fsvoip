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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
}

/** One HTTP exchange, without any client library in the way (tests use a fake). */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
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
        val body = request.body?.toRequestBody("application/json".toMediaType())
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

    private suspend fun <T> perform(
        method: String,
        path: String,
        body: JsonElement?,
        authenticated: Boolean,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val headers = linkedMapOf(
            "Accept" to "application/json",
            "User-Agent" to userAgent,
            "Cache-Control" to "no-store",
        )

        if (authenticated) {
            val token = deviceToken ?: throw ApiException.MissingDeviceToken()
            headers["Authorization"] = "Bearer ${token.reveal()}"
        }

        val bytes = body?.let {
            headers["Content-Type"] = "application/json"
            FsJson.default.encodeToString(JsonElement.serializer(), it).toByteArray(Charsets.UTF_8)
        }

        // Never log bodies or headers: the pair response carries the SIP password.
        logger.debug("$method /$path")

        val response = try {
            transport.send(HttpRequest(method, "${baseUrl.trimEnd('/')}/$path", headers, bytes))
        } catch (error: ApiException) {
            throw error
        } catch (error: IOException) {
            throw ApiException.Transport(error.javaClass.simpleName)
        }

        logger.debug("$method /$path -> ${response.status}")

        if (response.status !in 200..299) {
            throw errorFor(response)
        }

        return try {
            FsJson.default.decodeFromString(deserializer, response.body.toString(Charsets.UTF_8))
        } catch (error: SerializationException) {
            throw ApiException.Decoding("$path: ${error.javaClass.simpleName}")
        } catch (error: IllegalArgumentException) {
            throw ApiException.Decoding("$path: ${error.javaClass.simpleName}")
        }
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

            return when (response.status) {
                400 -> ApiException.InvalidRequest(body?.message)
                401 -> ApiException.Unauthorized()
                404 -> ApiException.NotFound()
                413 -> ApiException.PayloadTooLarge()
                429 -> ApiException.RateLimited(retryAfter)
                503 -> ApiException.Unavailable(body?.retryable ?: true, retryAfter)
                else -> ApiException.UnexpectedStatus(response.status)
            }
        }
    }
}
