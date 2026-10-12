// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Where the device token may go when audio is fetched. The token only ever travels to the host of the API; a redirect to another host,
// or from https to http, never carries it (or is not followed at all).

package nl.fullstackstudio.fsvoip.core

import java.net.URI

object MediaAuthPolicy {
    const val MAX_REDIRECTS = 5

    /** The headers (the `Authorization`) for [url]: all of them on the API host (same scheme and host), none anywhere else. */
    fun headersFor(url: String, apiBaseUrl: String, headers: Map<String, String>): Map<String, String> =
        if (isApiHost(url, apiBaseUrl)) headers else emptyMap()

    fun isApiHost(url: String, apiBaseUrl: String): Boolean {
        val target = parse(url) ?: return false
        val base = parse(apiBaseUrl) ?: return false

        return target.scheme.equals(base.scheme, ignoreCase = true) && target.host.equals(base.host, ignoreCase = true) && port(target) == port(base)
    }

    /** The absolute URL a `Location` points to, or `null` when it must not be followed (not http(s), or https downgraded to http). */
    fun resolveRedirect(from: String, location: String?): String? {
        if (location.isNullOrBlank()) return null

        val origin = parse(from) ?: return null
        val next = try {
            URI(from).resolve(location.trim())
        } catch (_: Exception) {
            return null
        }
        val scheme = next.scheme?.lowercase() ?: return null

        if (scheme != "https" && scheme != "http") return null
        if (origin.scheme.equals("https", ignoreCase = true) && scheme == "http") return null
        if (next.host.isNullOrEmpty()) return null

        return next.toString()
    }

    private fun parse(url: String): URI? = try {
        URI(url.trim()).takeIf { !it.host.isNullOrEmpty() && !it.scheme.isNullOrEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun port(uri: URI): Int = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
}
