// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Where a tap on a `notice` notification goes. The server sends an absolute portal link (`href`); the app never opens it. It
// reads the PATH only, and maps the paths it has a screen for. Everything else opens the home screen (the dialler).
//
//   /portal/voip/<pbx>/calls       -> Geschiedenis
//   /portal/voip/<pbx>/voicemail   -> Voicemail
//   /portal/contacts[/...]         -> Contacten
//   anything else                  -> home

package nl.fullstackstudio.fsvoip.core

import java.net.URI
import java.net.URLDecoder

enum class NoticeDestination { HOME, RECENTS, VOICEMAIL, CONTACTS }

object NoticeRoute {
    /** [NoticeDestination.HOME] for anything unknown, empty or malformed. Never throws and never returns a URL. */
    fun destination(href: String): NoticeDestination {
        val path = segments(href)

        if (path == null || path.firstOrNull() != "portal") {
            return NoticeDestination.HOME
        }

        val rest = path.drop(1)

        return when (rest.firstOrNull()) {
            "voip" -> when (rest.getOrNull(2)) {
                "calls" -> NoticeDestination.RECENTS
                "voicemail" -> NoticeDestination.VOICEMAIL
                else -> NoticeDestination.HOME
            }
            "contacts" -> NoticeDestination.CONTACTS
            else -> NoticeDestination.HOME
        }
    }

    /** The decoded, lower-cased path segments of an absolute URL or a bare path; `null` when it is not a plain path. */
    internal fun segments(href: String): List<String>? {
        val trimmed = href.trim()

        if (trimmed.isEmpty() || trimmed.length > 500 || trimmed.any { it.isISOControl() }) {
            return null
        }

        val rawPath = when {
            trimmed.startsWith("/") -> trimmed
            else -> {
                val uri = try {
                    URI(trimmed)
                } catch (_: Exception) {
                    return null
                }
                val scheme = uri.scheme?.lowercase()

                if (scheme != "https" && scheme != "http") {
                    return null
                }

                uri.rawPath ?: ""
            }
        }

        val path = rawPath.substringBefore('?').substringBefore('#')
        val result = mutableListOf<String>()

        for (piece in path.split('/').filter { it.isNotEmpty() }) {
            val decoded = try {
                URLDecoder.decode(piece.replace("+", "%2B"), "UTF-8")
            } catch (_: Exception) {
                return null
            }

            if (decoded.contains('/') || decoded == "." || decoded == "..") {
                return null
            }

            result += decoded.lowercase()
        }

        return result
    }
}

/** `POST /pbx/devices/{id}/app-pairing` (invite a colleague): a link that is a credential. Show it once, never log it. */
@kotlinx.serialization.Serializable
data class AppPairingResponse(val url: String, val expiresAt: String) {
    override fun toString(): String = "AppPairingResponse(expiresAt=$expiresAt)"
}
