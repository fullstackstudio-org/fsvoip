// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Keeping the local address book equal to the server's (contract: `shared/README.md` "Sync rules (contacts)").

package nl.fullstackstudio.fsvoip.contacts

import kotlinx.coroutines.CancellationException
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.Contact
import nl.fullstackstudio.fsvoip.core.ContactListETag
import nl.fullstackstudio.fsvoip.core.ContactListInfo
import nl.fullstackstudio.fsvoip.core.ContactListSnapshotOutcome
import nl.fullstackstudio.fsvoip.core.ContactListsResponse
import nl.fullstackstudio.fsvoip.core.ContactsPage

/** What the sync needs from the API for ONE account. One implementation talks to the server; tests bring their own. */
interface ContactsApi {
    suspend fun contacts(since: String?, cursor: String?, limit: Int?): ContactsPage

    suspend fun contactLists(): ContactListsResponse

    suspend fun contactListSnapshot(listId: String, cursor: String?, etag: String?): ContactListSnapshotOutcome
}

/** The result of a whole sync run (all pages of `GET /contacts`). */
data class ContactsSyncResult(
    /** Added or changed contacts; a contact that came in twice appears once (the last version). */
    val contacts: List<Contact>,
    val deleted: List<String>,
    /** Store this as the next `since`. */
    val serverTime: String,
    /** There was no `since`: this is the complete set; drop locally what is not in [contacts]. */
    val isFull: Boolean,
)

object ContactsSync {
    const val PAGE_SIZE = 500
    private const val MAX_PAGES = 200

    /**
     * One run: every page of `GET /contacts`, following `nextCursor`. [since] `null` = the complete set. `serverTime` is the same on
     * every page of one run; it is taken from the FIRST page and only handed back when the LAST page succeeded (a run that fails
     * halfway throws, so nothing is stored). A `since` older than 90 days answers `resync`: do a full run.
     */
    suspend fun run(api: ContactsApi, since: String?): ContactsSyncResult = try {
        runOnce(api, since)
    } catch (error: ApiException.InvalidRequest) {
        if (since != null && error.code == "resync") runOnce(api, null) else throw error
    }

    private suspend fun runOnce(api: ContactsApi, since: String?): ContactsSyncResult {
        val contacts = LinkedHashMap<String, Contact>()
        val deleted = LinkedHashSet<String>()
        var serverTime: String? = null
        var cursor: String? = null
        var pages = 0

        do {
            val page = api.contacts(since, cursor, PAGE_SIZE)
            serverTime = serverTime ?: page.serverTime
            page.contacts.forEach { contacts[it.id] = it }
            deleted += page.deleted
            cursor = page.nextCursor
            pages++
        } while (cursor != null && pages < MAX_PAGES)

        // A contact that was changed and then deleted in one run is gone.
        deleted.forEach { contacts.remove(it) }

        return ContactsSyncResult(contacts.values.toList(), deleted.toList(), serverTime!!, isFull = since == null)
    }

    /**
     * Apply one run to the stored contacts. A full run replaces everything (what is not in it is gone on the server); an incremental
     * run upserts on `id` and drops the deleted ids.
     */
    fun apply(result: ContactsSyncResult, stored: List<StoredContact>): List<StoredContact> {
        val incoming = result.contacts.map(::StoredContact)

        if (result.isFull) {
            return incoming
        }

        val positions = HashMap<String, Int>()
        val merged = ArrayList(stored)
        merged.forEachIndexed { index, contact -> positions[contact.id] = index }

        for (contact in incoming) {
            val index = positions[contact.id]

            if (index != null) {
                merged[index] = contact
            } else {
                positions[contact.id] = merged.size
                merged += contact
            }
        }

        val gone = result.deleted.toSet()

        return if (gone.isEmpty()) merged else merged.filterNot { it.id in gone }
    }

    /**
     * The lists after a `GET /contact-lists`: lists that are gone on the server disappear, new ones start enabled, the local choice
     * ([StoredContactList.isEnabled]) and the downloaded members stay.
     */
    fun mergeLists(server: List<ContactListInfo>, stored: List<StoredContactList>): List<StoredContactList> {
        val known = stored.associateBy { it.id }

        return server.map { info ->
            (known[info.id] ?: StoredContactList(info.id, info.name, info.version, etag = null, isEnabled = true, memberIds = emptyList(), contactCount = info.contactCount))
                .copy(name = info.name, contactCount = info.contactCount)
        }
    }

    /** Whether the members of a list have to be fetched: never downloaded, or the server's version differs from ours. */
    fun needsSnapshot(list: StoredContactList, serverVersion: Int): Boolean = list.isEnabled && (list.etag == null || list.version != serverVersion)

    sealed interface MembersOutcome {
        /** `304`: the stored members are still right. */
        data object NotModified : MembersOutcome

        data class Members(val ids: List<String>, val version: Int, val etag: String?) : MembersOutcome
    }

    /**
     * Download the members of a list. Follows `cursor` pages; an unchanged list answers `304`. A version that changes halfway is
     * `409 stale`: start again once, then give up (the next sync tries again).
     */
    suspend fun fetchMembers(api: ContactsApi, list: StoredContactList): MembersOutcome = try {
        fetchOnce(api, list)
    } catch (error: ApiException.Conflict) {
        if (error.error == "stale") fetchOnce(api, list) else throw error
    }

    private suspend fun fetchOnce(api: ContactsApi, list: StoredContactList): MembersOutcome {
        val first = api.contactListSnapshot(list.id, cursor = null, etag = list.etag)

        if (first !is ContactListSnapshotOutcome.Snapshot) {
            return MembersOutcome.NotModified
        }

        val ids = first.snapshot.contacts.map { it.id }.toMutableList()
        var cursor = first.snapshot.nextCursor
        var pages = 1

        while (cursor != null && pages < MAX_PAGES) {
            val next = api.contactListSnapshot(list.id, cursor, etag = null) as? ContactListSnapshotOutcome.Snapshot ?: break
            ids += next.snapshot.contacts.map { it.id }
            cursor = next.snapshot.nextCursor
            pages++
        }

        return MembersOutcome.Members(ids, first.snapshot.list.version, first.etag ?: ContactListETag.make(first.snapshot.list.version))
    }
}

/** [ContactsApi] on the real API client of one paired account. */
class LiveContactsApi(private val client: nl.fullstackstudio.fsvoip.core.FsVoipApiClient) : ContactsApi {
    override suspend fun contacts(since: String?, cursor: String?, limit: Int?): ContactsPage = client.contacts(since, cursor, limit)

    override suspend fun contactLists(): ContactListsResponse = client.contactLists()

    override suspend fun contactListSnapshot(listId: String, cursor: String?, etag: String?): ContactListSnapshotOutcome =
        client.contactListSnapshot(listId, cursor, etag)
}
