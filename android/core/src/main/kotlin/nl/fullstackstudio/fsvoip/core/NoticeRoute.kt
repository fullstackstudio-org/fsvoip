// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Where a tap on a `notice` notification goes. The server sends an absolute portal link (`href`); the app never opens it. It reads the
// PATH only, and maps the paths it has a screen for. Everything else opens the home screen (the dialler).
//
//   /portal/voip/<pbx>/calls       → Recent (history)
//   /portal/voip/<pbx>/voicemail   → Voicemail
//   /portal/contacts[/…]           → Contacten
//   /portal/content/<p>/tickets…   → no ticket screen in the app: home (the portal has it)

package nl.fullstackstudio.fsvoip.core

import java.net.URLDecoder

enum class NoticeDestination { HOME, RECENTS, VOICEMAIL, CONTACTS }

object NoticeRoute {
    /** [NoticeDestination.HOME] for anything unknown, empty or malformed. Never throws and never returns a URL. */
    fun destination(href: String): NoticeDestination {
        val path = segments(href)?.takeIf { it.firstOrNull() == "portal" } ?: return NoticeDestination.HOME
        val rest = path.drop(1)

        return when (rest.firstOrNull()) {
            "voip" -> {
                // /portal/voip/<pbx>/<section>
                if (rest.size < 3) {
                    NoticeDestination.HOME
                } else {
                    when (rest[2]) {
                        "calls" -> NoticeDestination.RECENTS
                        "voicemail" -> NoticeDestination.VOICEMAIL
                        else -> NoticeDestination.HOME
                    }
                }
            }
            "contacts" -> NoticeDestination.CONTACTS
            else -> NoticeDestination.HOME
        }
    }

    /**
     * The decoded path segments of an absolute URL or a bare path; `null` when it is not a plain path (control characters,
     * `.` / `..` segments, an encoded slash).
     */
    internal fun segments(href: String): List<String>? {
        val trimmed = href.trim()

        if (trimmed.isEmpty() || trimmed.length > 500 || trimmed.any { it.isISOControl() }) return null

        val rawPath = when {
            trimmed.startsWith("/") -> trimmed
            else -> {
                val match = ABSOLUTE.matchEntire(trimmed) ?: return null
                // Group 1 = the path (with its leading slash, or empty), query and fragment excluded below.
                match.groupValues[1]
            }
        }

        val withoutQuery = rawPath.substringBefore('?').substringBefore('#')
        val result = mutableListOf<String>()

        for (piece in withoutQuery.split('/').filter { it.isNotEmpty() }) {
            val decoded = try {
                URLDecoder.decode(piece.replace("+", "%2B"), "UTF-8")
            } catch (_: IllegalArgumentException) {
                return null
            }

            if (decoded.contains('/') || decoded == "." || decoded == "..") return null

            result += decoded.lowercase()
        }

        return result
    }

    // https://host[:port]/path... or http://; the host (and port) are ignored: the app never opens the link.
    private val ABSOLUTE = Regex("^(?i:https?)://[^/?#]*([^?#]*)(?:[?#].*)?$")
}
