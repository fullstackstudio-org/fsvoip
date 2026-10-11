// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The contact routes of `FsVoipApiClient` (`/contacts`, `/contact-lists`), as extension functions next to their models.

package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.json.JsonElement

/** One page of `GET /contacts`. */
suspend fun FsVoipApiClient.contactsPage(since: String? = null, cursor: String? = null, limit: Int? = null): ContactsPage {
    val query = buildList {
        since?.let { add("since" to it) }
        cursor?.let { add("cursor" to it) }
        limit?.let { add("limit" to it.toString()) }
    }

    return get("contacts", ContactsPage.serializer(), query)
}

/**
 * A whole sync run: follows `nextCursor` until the last page. Without `since` it is the complete set (`isFull`).
 * [ApiException.Resync] (a `since` older than 90 days) is thrown as is: call again without `since`.
 *
 * The server gives every page of a run the same `serverTime`; the result carries it. The app stores it as its next `since`
 * only after this method returned (all pages in), never halfway: a run that fails midway keeps the old `since`.
 */
suspend fun FsVoipApiClient.syncContacts(since: String? = null, pageSize: Int? = null, maxPages: Int = 200): ContactsSyncResult {
    val byId = LinkedHashMap<String, Contact>()
    val deleted = ArrayList<String>()
    val seenDeleted = HashSet<String>()
    var cursor: String? = null
    var serverTime: String? = null

    repeat(maxPages) {
        val page = contactsPage(since, cursor, pageSize)
        serverTime = serverTime ?: page.serverTime

        // The last version of a contact wins; its position is where it first appeared.
        for (contact in page.contacts) byId[contact.id] = contact

        for (id in page.deleted) if (seenDeleted.add(id)) deleted += id

        val next = page.nextCursor ?: run {
            // The 120 s overlap can send a contact twice. If an id is both changed and deleted, the contact exists (it was
            // re-created): the tombstone is stale.
            val contacts = byId.values.toList()
            val alive = contacts.map { it.id }.toSet()

            return ContactsSyncResult(contacts, deleted.filter { it !in alive }, serverTime ?: page.serverTime, isFull = since == null)
        }

        cursor = next
    }

    throw ApiException.Decoding("contacts: too many pages")
}

/** An id comes from the server, but it still goes in the URL as one segment: nothing that could climb out of `contacts/`. */
private fun pathSegment(id: String): String {
    if (id.isEmpty() || id == "." || id == "..") throw ApiException.NotFound()

    return id.replace("/", "%2F").replace("\\", "%5C")
}

suspend fun FsVoipApiClient.contact(id: String): ContactDetail =
    get("contacts/${pathSegment(id)}", ContactSingleResponse.serializer()).contact

suspend fun FsVoipApiClient.createContact(contact: ContactCreate): ContactDetail =
    perform("POST", "contacts", contact.toJson(), authenticated = true, ContactSingleResponse.serializer()).contact

/** `409 stale` = [ApiException.Stale]: someone else changed it; reload and let the user merge. */
suspend fun FsVoipApiClient.updateContact(id: String, update: ContactUpdate): ContactUpdateResponse =
    perform("PATCH", "contacts/${pathSegment(id)}", update.toJson(), authenticated = true, ContactUpdateResponse.serializer())

/** Admin only ([ApiException.Forbidden] for a `user`). Returns how many were deleted. */
suspend fun FsVoipApiClient.deleteContact(id: String): Int =
    perform("DELETE", "contacts/${pathSegment(id)}", null as JsonElement?, authenticated = true, ContactDeleteResponse.serializer()).deleted

suspend fun FsVoipApiClient.contactLists(): List<ContactListInfo> = get("contact-lists", ContactListsResponse.serializer()).lists

/**
 * One page of the snapshot of a list. `etag` = the ETag you stored with the previous snapshot ([ContactListETag.make]): an
 * unchanged list answers [ContactListSnapshotOutcome.NotModified] and nothing is downloaded. The server ignores `If-None-Match`
 * on a `cursor` page, so it is not sent there.
 */
suspend fun FsVoipApiClient.contactListSnapshot(listId: String, cursor: String? = null, etag: String? = null): ContactListSnapshotOutcome {
    val headers = if (etag != null && cursor == null) mapOf("If-None-Match" to etag) else emptyMap()
    val query = cursor?.let { listOf("cursor" to it) } ?: emptyList()
    val path = "contact-lists/${pathSegment(listId)}/contacts"
    val response = send("GET", path, null, authenticated = true, query = query, headers = headers, acceptNotModified = true)
    val responseEtag = response.header("ETag")

    if (response.status == 304) return ContactListSnapshotOutcome.NotModified(responseEtag ?: etag)

    return ContactListSnapshotOutcome.Snapshot(decode(path, ContactListSnapshot.serializer(), response), responseEtag)
}

/** `GET /contacts/{id}/timeline?cursor=&limit=` (1...100). */
suspend fun FsVoipApiClient.contactTimeline(contactId: String, cursor: String? = null, limit: Int = 30): TimelinePage {
    val query = buildList {
        add("limit" to limit.coerceIn(1, 100).toString())
        cursor?.let { add("cursor" to it) }
    }

    return get("contacts/${pathSegment(contactId)}/timeline", TimelinePage.serializer(), query)
}

/** What this pairing may do with contacts (`GET /me`). Also the way to find out the pairing was revoked ([ApiException.Unauthorized]). */
suspend fun FsVoipApiClient.contactCapabilities(): ContactCapabilities = me().capabilities?.contacts ?: ContactCapabilities()
