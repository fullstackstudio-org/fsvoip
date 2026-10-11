// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.Contact
import nl.fullstackstudio.fsvoip.core.ContactCapabilities
import nl.fullstackstudio.fsvoip.core.ContactCreate
import nl.fullstackstudio.fsvoip.core.ContactDetail
import nl.fullstackstudio.fsvoip.core.ContactListHeader
import nl.fullstackstudio.fsvoip.core.ContactListInfo
import nl.fullstackstudio.fsvoip.core.ContactListSnapshot
import nl.fullstackstudio.fsvoip.core.ContactListSnapshotOutcome
import nl.fullstackstudio.fsvoip.core.ContactPhone
import nl.fullstackstudio.fsvoip.core.ContactPhoneLabel
import nl.fullstackstudio.fsvoip.core.ContactUpdate
import nl.fullstackstudio.fsvoip.core.ContactUpdateResponse
import nl.fullstackstudio.fsvoip.core.ContactsSyncResult
import nl.fullstackstudio.fsvoip.core.SnapshotContact
import nl.fullstackstudio.fsvoip.core.TimelinePage

/** Builds API models the way the server sends them. */
object Make {
    fun contact(id: String, name: String, phones: List<String> = emptyList(), updatedAt: String = "2026-10-09T10:00:00.000Z", company: String? = null, email: String? = null) =
        Contact(
            id = id,
            name = name,
            company = company,
            email = email,
            phones = phones.mapIndexed { index, number -> ContactPhone(number, ContactPhoneLabel.MOBILE, index == 0) },
            updatedAt = updatedAt,
        )

    fun detail(contact: Contact, notes: String? = null, listIds: List<String> = emptyList()) = ContactDetail(contact, notes, listIds)

    fun run(contacts: List<Contact>, deleted: List<String> = emptyList(), serverTime: String, full: Boolean) = ContactsSyncResult(contacts, deleted, serverTime, full)

    fun list(id: String, name: String, version: Int, count: Int = 0) = ContactListInfo(id, name, version, count)

    fun snapshot(id: String, version: Int, contactIds: List<String>, nextCursor: String? = null) =
        ContactListSnapshot(ContactListHeader(id, "L", version), contactIds.map { SnapshotContact(it, "N") }, nextCursor)

    fun capabilities(read: Boolean = true, write: Boolean = true, delete: Boolean = false) = ContactCapabilities(read, write, delete)
}

/** A scripted server. */
class FakeContactsApi : ContactsApi {
    var capabilities = Make.capabilities()

    /** Answers by call: the next [syncAddressBook] takes the first one off the queue. */
    val syncResults = ArrayDeque<Result<ContactsSyncResult>>()
    var meError: Throwable? = null
    var lists: List<ContactListInfo> = emptyList()

    /** Per list id: the snapshot pages, in order (the first has no cursor). */
    val snapshots = HashMap<String, ArrayDeque<Result<ContactListSnapshotOutcome>>>()
    var createResult: Result<ContactDetail>? = null
    var updateResult: Result<ContactUpdateResponse>? = null
    var detailResult: Result<ContactDetail>? = null
    var deleteResult: Result<Int> = Result.success(1)
    var timelinePages = ArrayDeque<Result<TimelinePage>>()

    var capabilityCalls = 0
    val syncSinces = mutableListOf<String?>()
    data class SnapshotCall(val listId: String, val cursor: String?, val etag: String?)

    val snapshotCalls = mutableListOf<SnapshotCall>()
    val updates = mutableListOf<Pair<String, ContactUpdate>>()
    val creates = mutableListOf<ContactCreate>()
    val deletes = mutableListOf<String>()
    val timelineCursors = mutableListOf<String?>()

    fun sync(vararg results: ContactsSyncResult) = results.forEach { syncResults += Result.success(it) }

    @Suppress("UNCHECKED_CAST")
    fun snapshotPages(listId: String, vararg results: Any) {
        snapshots[listId] = ArrayDeque(results.map { it as Result<ContactListSnapshotOutcome> })
    }

    override suspend fun contactCapabilities(): ContactCapabilities {
        capabilityCalls++
        meError?.let { throw it }

        return capabilities
    }

    override suspend fun syncAddressBook(since: String?): ContactsSyncResult {
        syncSinces += since

        return (if (syncResults.isEmpty()) Result.failure(ApiException.Transport("no scripted answer")) else syncResults.removeFirst()).getOrThrow()
    }

    override suspend fun contact(id: String) = (detailResult ?: Result.failure(ApiException.NotFound())).getOrThrow()

    override suspend fun createContact(contact: ContactCreate): ContactDetail {
        creates += contact

        return (createResult ?: Result.failure(ApiException.NotFound())).getOrThrow()
    }

    override suspend fun updateContact(id: String, update: ContactUpdate): ContactUpdateResponse {
        updates += id to update

        return (updateResult ?: Result.failure(ApiException.NotFound())).getOrThrow()
    }

    override suspend fun deleteContact(id: String): Int {
        deletes += id

        return deleteResult.getOrThrow()
    }

    override suspend fun contactLists() = lists

    override suspend fun contactListSnapshot(listId: String, cursor: String?, etag: String?): ContactListSnapshotOutcome {
        snapshotCalls += SnapshotCall(listId, cursor, etag)
        val queue = snapshots[listId]

        if (queue == null || queue.isEmpty()) throw ApiException.NotFound()

        return queue.removeFirst().getOrThrow()
    }

    override suspend fun contactTimeline(contactId: String, cursor: String?, limit: Int): TimelinePage {
        timelineCursors += cursor

        return (if (timelinePages.isEmpty()) Result.failure(ApiException.NotFound()) else timelinePages.removeFirst()).getOrThrow()
    }
}

class FakeDevice : DeviceContactsReading {
    override var access: DeviceContactsAccess = DeviceContactsAccess.NOT_DETERMINED
    var grants = true
    var entries: List<ContactEntry> = emptyList()

    override suspend fun requestAccess(): Boolean {
        access = if (grants) DeviceContactsAccess.AUTHORIZED else DeviceContactsAccess.DENIED

        return grants
    }

    override suspend fun fetch() = entries
}
