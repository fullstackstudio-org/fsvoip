// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.demo

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.fullstackstudio.fsvoip.core.FsJson
import nl.fullstackstudio.fsvoip.core.HttpRequest
import nl.fullstackstudio.fsvoip.core.HttpResponse
import nl.fullstackstudio.fsvoip.core.HttpTransport

/** One request to the pretend API: the path after `/api/voip-app/v1`, split query, and the parsed JSON body. */
class DemoRequest(val method: String, val path: String, val query: Map<String, String>, val body: JsonElement?, val rawBody: ByteArray?, val headers: Map<String, String>) {
    val segments: List<String> = path.trim('/').split('/').filter { it.isNotEmpty() }

    fun bodyObject(): JsonObject = body?.jsonObject ?: JsonObject(emptyMap())

    fun bodyString(key: String): String? = bodyObject()[key]?.jsonPrimitive?.contentOrNull

    fun matches(method: String, vararg pattern: String): Boolean {
        if (this.method != method || segments.size != pattern.size) return false

        return pattern.indices.all { pattern[it] == "*" || pattern[it] == segments[it] }
    }
}

/**
 * A feature's slice of the pretend API. Return `null` when the request is not yours. Keep state (a sound you added, a
 * contact you edited) in the handler object, so the screens behave like they do against the real server.
 */
fun interface DemoHandler {
    fun handle(request: DemoRequest): HttpResponse?
}

fun demoJson(status: Int = 200, body: String, headers: Map<String, String> = emptyMap()): HttpResponse =
    HttpResponse(status, mapOf("Content-Type" to "application/json") + headers, body.toByteArray(Charsets.UTF_8))

fun demoJson(status: Int = 200, element: JsonElement, headers: Map<String, String> = emptyMap()): HttpResponse =
    demoJson(status, FsJson.default.encodeToString(JsonElement.serializer(), element), headers)

fun demoOk(): HttpResponse = demoJson(200, """{"ok":true}""")

fun demoError(status: Int, error: String, extra: String = ""): HttpResponse = demoJson(status, """{"error":"$error"$extra}""")

fun demoBytes(contentType: String, bytes: ByteArray): HttpResponse = HttpResponse(200, mapOf("Content-Type" to contentType), bytes)

/** Dispatches a request to the handlers in order; the first one that answers wins. Nothing here uses the network. */
class DemoTransport(private val handlers: () -> List<DemoHandler>, private val latencyMillis: Long = 120) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse {
        kotlinx.coroutines.delay(latencyMillis)

        val url = java.net.URI(request.url)
        val path = url.rawPath.substringAfter("/api/voip-app/v1", url.rawPath).let { java.net.URLDecoder.decode(it, "UTF-8") }
        val query = (url.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate {
            val (name, value) = (it.split('=', limit = 2) + "")
            java.net.URLDecoder.decode(name, "UTF-8") to java.net.URLDecoder.decode(value, "UTF-8")
        }
        val json = request.body?.takeIf { request.contentType.startsWith("application/json") && it.isNotEmpty() }?.let {
            runCatching { FsJson.default.parseToJsonElement(it.toString(Charsets.UTF_8)) }.getOrNull()
        }
        val demo = DemoRequest(request.method, path, query, json, request.body, request.headers)

        for (handler in handlers()) {
            handler.handle(demo)?.let { return it }
        }

        return demoError(404, "not_found")
    }
}

internal fun jsonString(value: String) = JsonPrimitive(value).toString()
