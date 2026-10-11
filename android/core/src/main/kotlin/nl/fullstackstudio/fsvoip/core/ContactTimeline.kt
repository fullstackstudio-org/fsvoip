// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The timeline of a contact: `GET /contacts/{id}/timeline` (newest first, paged with an opaque cursor).
//
// The server only sends what the app may show: a title and an optional summary per line (no event data), a human reference
// (order / request number). The app shows exactly that and nothing it derives itself.

package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.serialization.Serializable

/** One line of a contact's timeline. */
@Serializable
data class TimelineItem(
    val id: String,
    /** The kind the server gave (`order`, `ticket`, `call`, ...). Only used to pick an icon; an unknown kind is fine. */
    val kind: String = "",
    val title: String = "",
    val summary: String? = null,
    /** Opaque instant; see [occurredAtInstant]. */
    val occurredAt: String,
    /** Human number such as an order number, when there is one. */
    val ref: String? = null,
) {
    val occurredAtInstant: Instant? get() = FsJson.parseTimestamp(occurredAt)
}

/** `GET /contacts/{id}/timeline`. */
@Serializable
data class TimelinePage(
    val items: List<TimelineItem> = emptyList(),
    /** `null` = last page. */
    val nextCursor: String? = null,
)
