// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import nl.fullstackstudio.fsvoip.core.ContactCapabilities
import nl.fullstackstudio.fsvoip.core.ContactCreate
import nl.fullstackstudio.fsvoip.core.ContactDetail
import nl.fullstackstudio.fsvoip.core.ContactListInfo
import nl.fullstackstudio.fsvoip.core.ContactListSnapshotOutcome
import nl.fullstackstudio.fsvoip.core.ContactUpdate
import nl.fullstackstudio.fsvoip.core.ContactUpdateResponse
import nl.fullstackstudio.fsvoip.core.ContactsSyncResult
import nl.fullstackstudio.fsvoip.core.FsVoipApiClient
import nl.fullstackstudio.fsvoip.core.TimelinePage
import nl.fullstackstudio.fsvoip.core.contact
import nl.fullstackstudio.fsvoip.core.contactCapabilities
import nl.fullstackstudio.fsvoip.core.contactListSnapshot
import nl.fullstackstudio.fsvoip.core.contactLists
import nl.fullstackstudio.fsvoip.core.contactTimeline
import nl.fullstackstudio.fsvoip.core.createContact
import nl.fullstackstudio.fsvoip.core.deleteContact
import nl.fullstackstudio.fsvoip.core.syncContacts
import nl.fullstackstudio.fsvoip.core.updateContact

/** What the contacts need from the FullStack Studio API. [ClientContactsApi] is the implementation; tests use their own. */
interface ContactsApi {
    /** What this pairing may do with contacts (`GET /me`). Also the way to find out the pairing was revoked (`ApiException.Unauthorized`). */
    suspend fun contactCapabilities(): ContactCapabilities

    /** All pages of `GET /contacts`. `since == null`: everything. Throws `ApiException.Resync` for a `since` that is too old. */
    suspend fun syncAddressBook(since: String?): ContactsSyncResult

    suspend fun contact(id: String): ContactDetail

    suspend fun createContact(contact: ContactCreate): ContactDetail

    suspend fun updateContact(id: String, update: ContactUpdate): ContactUpdateResponse

    suspend fun deleteContact(id: String): Int

    suspend fun contactLists(): List<ContactListInfo>

    suspend fun contactListSnapshot(listId: String, cursor: String?, etag: String?): ContactListSnapshotOutcome

    /** `GET /contacts/{id}/timeline`. */
    suspend fun contactTimeline(contactId: String, cursor: String?, limit: Int): TimelinePage
}

class ClientContactsApi(private val client: FsVoipApiClient) : ContactsApi {
    override suspend fun contactCapabilities() = client.contactCapabilities()

    override suspend fun syncAddressBook(since: String?) = client.syncContacts(since = since, pageSize = null, maxPages = 200)

    override suspend fun contact(id: String) = client.contact(id)

    override suspend fun createContact(contact: ContactCreate) = client.createContact(contact)

    override suspend fun updateContact(id: String, update: ContactUpdate) = client.updateContact(id, update)

    override suspend fun deleteContact(id: String) = client.deleteContact(id)

    override suspend fun contactLists() = client.contactLists()

    override suspend fun contactListSnapshot(listId: String, cursor: String?, etag: String?) = client.contactListSnapshot(listId, cursor, etag)

    override suspend fun contactTimeline(contactId: String, cursor: String?, limit: Int) = client.contactTimeline(contactId, cursor, limit)
}
