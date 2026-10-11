// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Kotlin mirror of `/contacts` and `/contact-lists` in `shared/openapi.yaml`: the customer's own address book (not the
// contacts of the phone), as the portal shows it.
//
// - `updatedAt` and `serverTime` are OPAQUE TIMESTAMP STRINGS. They go back to the server unchanged (`expectedUpdatedAt`, `since`);
//   parsing and re-formatting them could shift a millisecond and turn an edit into a false `stale` or a sync into a gap.
// - Sync: `serverTime` is the same on every page of one sync run; store it as the next `since` only after the LAST page
//   (`nextCursor == null`). The server counts 120 s of overlap, so a contact can arrive twice: upsert on `id`.

package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable(with = ContactPhoneLabel.Serializer::class)
enum class ContactPhoneLabel(override val wire: String) : WireEnum {
    MOBILE("mobile"),
    WORK("work"),
    HOME("home"),
    MAIN("main"),
    FAX("fax"),
    OTHER("other"),

    /** A label this app version does not know; shown as "other" and never sent. */
    UNKNOWN("unknown"),
    ;

    val isKnown: Boolean get() = this != UNKNOWN

    object Serializer : TolerantEnumSerializer<ContactPhoneLabel>("ContactPhoneLabel", entries, UNKNOWN)

    companion object {
        fun fromWire(value: String?): ContactPhoneLabel? = entries.firstOrNull { it.wire == value }
    }
}

@Serializable
data class ContactPhone(
    /** E.164 with a plus (`+31612345678`) as the server stores it. */
    val number: String,
    val label: ContactPhoneLabel = ContactPhoneLabel.OTHER,
    val isPrimary: Boolean = false,
)

/** A contact as in the sync (no notes, no lists). */
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
) {
    /** The updated-at as an instant (for display only; never send this back). */
    val updatedAtInstant: Instant? get() = FsJson.parseTimestamp(updatedAt)
}

/** One contact in full: `GET /contacts/{id}` and the answer of POST/PATCH. The JSON is the contact with `notes` and `listIds` next to it. */
@Serializable(with = ContactDetailSerializer::class)
data class ContactDetail(
    val contact: Contact,
    val notes: String? = null,
    /** The lists this contact is in. */
    val listIds: List<String> = emptyList(),
) {
    val id: String get() = contact.id
}

object ContactDetailSerializer : KSerializer<ContactDetail> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("nl.fullstackstudio.fsvoip.ContactDetail")

    override fun deserialize(decoder: Decoder): ContactDetail {
        val input = decoder as? JsonDecoder ?: throw SerializationException("ContactDetail is JSON only")
        val obj = input.decodeJsonElement().jsonObject
        val contact = input.json.decodeFromJsonElement(Contact.serializer(), obj)
        val notes = (obj["notes"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        val listIds = (obj["listIds"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

        return ContactDetail(contact, notes, listIds)
    }

    override fun serialize(encoder: Encoder, value: ContactDetail) = throw SerializationException("ContactDetail is decode only")
}

/** `GET /contacts` (a page of the sync). */
@Serializable
data class ContactsPage(
    val contacts: List<Contact> = emptyList(),
    /** Ids deleted in the period (only with `since`). */
    val deleted: List<String> = emptyList(),
    /** `null` = last page. */
    val nextCursor: String? = null,
    /** Opaque instant of the server clock. Identical on all pages of one run; keep the one of the last page as the next `since`. */
    val serverTime: String,
)

/** The result of a whole sync run (all pages of `GET /contacts`). */
data class ContactsSyncResult(
    /** Added or changed contacts; a contact that came in twice appears once (the last version). */
    val contacts: List<Contact>,
    val deleted: List<String>,
    /** Store this as the next `since`. */
    val serverTime: String,
    /** `true` = there was no `since`: this is the complete set; drop locally what is not in `contacts`. */
    val isFull: Boolean,
)

@Serializable
data class ContactSingleResponse(val contact: ContactDetail)

/** The answer of `PATCH /contacts/{id}`. `changed == false`: nothing was different, nothing was written. */
@Serializable
data class ContactUpdateResponse(val contact: ContactDetail, val changed: Boolean = true)

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

// MARK: Requests

/** A field of a request: not mentioned, cleared (`null`) or set. */
sealed interface Change<out T> {
    data object Keep : Change<Nothing>

    data object Clear : Change<Nothing>

    data class To<T>(val value: T) : Change<T>

    companion object {
        /** `null` clears, a value sets. */
        fun <T : Any> of(value: T?): Change<T> = if (value == null) Clear else To(value)
    }
}

private fun putChange(builder: kotlinx.serialization.json.JsonObjectBuilder, key: String, change: Change<String>) {
    when (change) {
        Change.Keep -> Unit
        Change.Clear -> builder.put(key, JsonNull)
        is Change.To -> builder.put(key, JsonPrimitive(change.value))
    }
}

private fun phonesJson(phones: List<ContactPhone>): JsonArray =
    JsonArray(phones.map { FsJson.default.encodeToJsonElement(ContactPhone.serializer(), it) })

private fun stringsJson(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

/** `POST /contacts`. A kept field is left out; `Clear` is not meaningful on create but allowed by the server. */
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
    fun toJson(): JsonObject = buildJsonObject {
        name?.let { put("name", JsonPrimitive(it)) }
        putChange(this, "firstName", firstName)
        putChange(this, "lastName", lastName)
        putChange(this, "company", company)
        putChange(this, "email", email)
        putChange(this, "notes", notes)
        phones?.let { put("phones", phonesJson(it)) }
        tags?.let { put("tags", stringsJson(it)) }
        listIds?.let { put("listIds", stringsJson(it)) }
    }
}

/**
 * `PATCH /contacts/{id}`. `expectedUpdatedAt` is REQUIRED (the `updatedAt` you showed): without it the server answers `400`.
 * A kept field stays out; `Clear` sends `null` (wipes the text). Lists, phones and tags REPLACE the whole set.
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
    fun toJson(): JsonObject = buildJsonObject {
        put("expectedUpdatedAt", JsonPrimitive(expectedUpdatedAt))
        name?.let { put("name", JsonPrimitive(it)) }
        putChange(this, "firstName", firstName)
        putChange(this, "lastName", lastName)
        putChange(this, "company", company)
        putChange(this, "email", email)
        putChange(this, "notes", notes)
        phones?.let { put("phones", phonesJson(it)) }
        tags?.let { put("tags", stringsJson(it)) }
        listIds?.let { put("listIds", stringsJson(it)) }
    }
}
