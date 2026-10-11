// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Kotlin mirror of `/contacts` and `/contact-lists`: the customer's own address book (not the contacts of the phone), as the
// portal shows it.
//
// - `updatedAt` and `serverTime` are OPAQUE TIMESTAMP STRINGS. They go back to the server unchanged (`expectedUpdatedAt`,
//   `since`); parsing and re-formatting could shift a millisecond and turn an edit into a false `stale` or a sync into a gap.
// - Sync: `serverTime` is the same on every page of one run; store it as the next `since` only after the LAST page
//   (`nextCursor == null`). The server counts 120 s of overlap, so a contact can arrive twice: upsert on `id`.

package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
enum class ContactPhoneLabel(val wire: String) {
    @SerialName("mobile") MOBILE("mobile"),
    @SerialName("work") WORK("work"),
    @SerialName("home") HOME("home"),
    @SerialName("main") MAIN("main"),
    @SerialName("fax") FAX("fax"),
    @SerialName("other") OTHER("other"),
}

@Serializable
data class ContactPhone(
    /** E.164 with a plus (`+31612345678`) as the server stores it. */
    val number: String,
    val label: ContactPhoneLabel = ContactPhoneLabel.OTHER,
    val isPrimary: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("number", JsonPrimitive(number))
        put("label", JsonPrimitive(label.wire))
        put("isPrimary", JsonPrimitive(isPrimary))
    }
}

/** A contact. In the sync `notes` and `listIds` are absent; the detail (`GET /contacts/{id}`, POST, PATCH) carries them. */
@Serializable
data class Contact(
    val id: String,
    /** The display name (`name`, not `displayName`). */
    val name: String = "",
    val firstName: String? = null,
    val lastName: String? = null,
    val company: String? = null,
    val email: String? = null,
    val phones: List<ContactPhone> = emptyList(),
    val tags: List<String> = emptyList(),
    /** Opaque: send it back as `expectedUpdatedAt` when editing. */
    val updatedAt: String,
    val notes: String? = null,
    /** The lists this contact is in (detail only). */
    val listIds: List<String> = emptyList(),
) {
    /** The updated-at as an instant (display only; never send this back). */
    val updatedAtInstant: Instant?
        get() = FsJson.parseTimestamp(updatedAt)
}

/** `GET /contacts` (a page of the sync). */
@Serializable
data class ContactsPage(
    val contacts: List<Contact> = emptyList(),
    /** Ids deleted in the period (only with `since`). */
    val deleted: List<String> = emptyList(),
    /** `null` = last page. */
    val nextCursor: String? = null,
    /** Opaque instant of the server clock. Identical on all pages of one run. */
    val serverTime: String,
)

@Serializable
data class ContactSingleResponse(val contact: Contact)

/** The answer of `PATCH /contacts/{id}`. `changed == false`: nothing was different, nothing was written. */
@Serializable
data class ContactUpdateResponse(val contact: Contact, val changed: Boolean = true)

@Serializable
data class ContactDeleteResponse(val deleted: Int)

// MARK: Lists

@Serializable
data class ContactListInfo(
    val id: String,
    val name: String,
    /** The version is the ETag of the snapshot. */
    val version: Int,
    val contactCount: Int = 0,
)

@Serializable
data class ContactListsResponse(val lists: List<ContactListInfo> = emptyList())

@Serializable
data class ContactListHeader(val id: String, val name: String, val version: Int)

@Serializable
data class SnapshotPhone(val number: String, val label: ContactPhoneLabel = ContactPhoneLabel.OTHER)

/** A contact in a list snapshot: only what the dialler needs. */
@Serializable
data class SnapshotContact(
    val id: String,
    val name: String = "",
    val company: String? = null,
    val phones: List<SnapshotPhone> = emptyList(),
    val email: String? = null,
)

@Serializable
data class ContactListSnapshot(
    val list: ContactListHeader,
    val contacts: List<SnapshotContact> = emptyList(),
    /** `null` = last page (lists above 500 contacts come in pages). */
    val nextCursor: String? = null,
)

/** The answer to a conditional snapshot request. */
sealed interface ContactListSnapshotOutcome {
    /** `304`: the version equals the one sent in `If-None-Match`; nothing to download. */
    data class NotModified(val etag: String?) : ContactListSnapshotOutcome

    data class Snapshot(val snapshot: ContactListSnapshot, val etag: String?) : ContactListSnapshotOutcome
}

object ContactListETag {
    /** The ETag of a list version, as the server writes it (`"3"`). */
    fun make(version: Int): String = "\"$version\""
}

// MARK: Who is calling (`/contacts/lookup`, `/contacts/{id}/timeline`)

@Serializable
data class CustomerCounters(val openRequests: Int = 0, val openOrders: Int = 0)

@Serializable
data class TimelineItem(
    val id: String,
    val kind: String = "",
    val title: String = "",
    val summary: String? = null,
    val occurredAt: String? = null,
    val ref: String? = null,
)

@Serializable
data class ContactLookup(
    val contact: Contact? = null,
    val ambiguous: Boolean = false,
    val doNotContact: Boolean = false,
    val counters: CustomerCounters = CustomerCounters(),
    val timeline: List<TimelineItem> = emptyList(),
)

@Serializable
data class ContactTimelinePage(val items: List<TimelineItem> = emptyList(), val nextCursor: String? = null)

// MARK: Requests

/** `POST /contacts`. A key left [Change.Keep] is not sent. */
data class ContactCreate(
    val name: String? = null,
    val firstName: Change<String> = Change.Keep,
    val lastName: Change<String> = Change.Keep,
    val company: Change<String> = Change.Keep,
    val email: Change<String> = Change.Keep,
    val notes: Change<String> = Change.Keep,
    val phones: List<ContactPhone>? = null,
    val tags: List<String>? = null,
    val listIds: List<String>? = null,
) {
    fun toJson(): JsonObject = contactBody(null, name, firstName, lastName, company, email, notes, phones, tags, listIds)
}

/**
 * `PATCH /contacts/{id}`. `expectedUpdatedAt` is REQUIRED (the `updatedAt` you showed): without it the server answers 400.
 * Lists, phones and tags REPLACE the whole set; [Change.Clear] sends `null` and wipes the text.
 */
data class ContactUpdate(
    val expectedUpdatedAt: String,
    val name: String? = null,
    val firstName: Change<String> = Change.Keep,
    val lastName: Change<String> = Change.Keep,
    val company: Change<String> = Change.Keep,
    val email: Change<String> = Change.Keep,
    val notes: Change<String> = Change.Keep,
    val phones: List<ContactPhone>? = null,
    val tags: List<String>? = null,
    val listIds: List<String>? = null,
) {
    fun toJson(): JsonObject = contactBody(expectedUpdatedAt, name, firstName, lastName, company, email, notes, phones, tags, listIds)
}

private fun contactBody(
    expectedUpdatedAt: String?,
    name: String?,
    firstName: Change<String>,
    lastName: Change<String>,
    company: Change<String>,
    email: Change<String>,
    notes: Change<String>,
    phones: List<ContactPhone>?,
    tags: List<String>?,
    listIds: List<String>?,
): JsonObject = buildJsonObject {
    expectedUpdatedAt?.let { put("expectedUpdatedAt", JsonPrimitive(it)) }
    name?.let { put("name", JsonPrimitive(it)) }
    putText("firstName", firstName)
    putText("lastName", lastName)
    putText("company", company)
    putText("email", email)
    putText("notes", notes)
    phones?.let { list -> put("phones", JsonArray(list.map { it.toJson() })) }
    tags?.let { list -> put("tags", JsonArray(list.map(::JsonPrimitive))) }
    listIds?.let { list -> put("listIds", JsonArray(list.map(::JsonPrimitive))) }
}
