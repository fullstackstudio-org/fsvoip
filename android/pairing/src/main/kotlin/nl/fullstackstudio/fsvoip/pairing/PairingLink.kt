// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.pairing

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/** A pairing token extracted from a QR code, an App Link or a `fsvoip://` deep link. */
class PairingLink(val token: String) {
    init {
        if (!isWellFormed(token)) {
            throw PairingLinkException.MalformedToken()
        }
    }

    // The token is a one-time credential: it never shows up in logs or print-outs.
    override fun toString(): String = "PairingLink(•••)"

    override fun equals(other: Any?): Boolean = other is PairingLink && other.token == token

    override fun hashCode(): Int = token.hashCode()

    companion object {
        /** `fss_vpair_<43 base64url characters>` */
        fun isWellFormed(token: String): Boolean =
            token.startsWith("fss_vpair_") && token.length == 10 + 43 &&
                token.drop(10).all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
    }
}

sealed class PairingLinkException(message: String) : Exception(message) {
    /** Not a URL, or not one of ours. */
    class NotAPairingLink : PairingLinkException("not a pairing link")

    /** One of ours, but without a usable token. */
    class MissingToken : PairingLinkException("missing token")

    class MalformedToken : PairingLinkException("malformed token")
}

object PairingLinkParser {
    /** Hosts that serve the App Link (the intent filter verifies `fullstackstudio.nl`). */
    val appLinkHosts = setOf("fullstackstudio.nl", "www.fullstackstudio.nl")
    const val APP_LINK_PATH = "/fsvoip/pair"
    const val CUSTOM_SCHEME = "fsvoip"

    /** Parse `https://fullstackstudio.nl/fsvoip/pair?t=<token>` or `fsvoip://pair?t=<token>`. */
    fun parse(url: String): PairingLink {
        val uri = try {
            URI(url.trim())
        } catch (_: URISyntaxException) {
            throw PairingLinkException.NotAPairingLink()
        }

        when (uri.scheme?.lowercase()) {
            "https" -> {
                val host = uri.host?.lowercase()
                if (host == null || host !in appLinkHosts || normalizedPath(uri.path ?: "") != APP_LINK_PATH) {
                    throw PairingLinkException.NotAPairingLink()
                }
            }
            CUSTOM_SCHEME -> {
                // fsvoip://pair?t=...  (host = "pair"); tolerate fsvoip:///pair?t=...
                val target = (uri.host ?: "").lowercase() + normalizedPath(uri.path ?: "")
                if (target != "pair" && target != "/pair") {
                    throw PairingLinkException.NotAPairingLink()
                }
            }
            else -> throw PairingLinkException.NotAPairingLink()
        }

        val token = queryParameter(uri.rawQuery, "t")
        if (token.isNullOrEmpty()) {
            throw PairingLinkException.MissingToken()
        }

        return PairingLink(token)
    }

    /** Parse the text a QR scanner delivers (or a pasted link). */
    fun parseScanned(text: String): PairingLink {
        val trimmed = text.trim()

        if (trimmed.isEmpty() || trimmed.length > 512) {
            throw PairingLinkException.NotAPairingLink()
        }

        return parse(trimmed)
    }

    private fun queryParameter(rawQuery: String?, name: String): String? = rawQuery
        ?.split('&')
        ?.map { it.split('=', limit = 2) }
        ?.firstOrNull { it.first() == name }
        ?.let { parts -> parts.getOrNull(1)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() } }

    private fun normalizedPath(path: String): String = if (path.length > 1 && path.endsWith("/")) path.dropLast(1) else path
}
