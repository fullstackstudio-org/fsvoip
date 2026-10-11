// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.ContactListETag
import nl.fullstackstudio.fsvoip.core.ContactListInfo
import nl.fullstackstudio.fsvoip.core.ContactListSnapshotOutcome
import nl.fullstackstudio.fsvoip.core.ContactsSyncResult
import nl.fullstackstudio.fsvoip.core.StoredContact
import nl.fullstackstudio.fsvoip.core.StoredContactList

/** The pure parts of keeping the local address book equal to the server's. */
object ContactsSync {
    /**
     * Apply one sync run to the stored contacts.
     *
     * - A full run ([ContactsSyncResult.isFull]) replaces everything: what is not in it is gone on the server.
     * - An incremental run upserts on `id` (a contact can arrive twice because of the server's overlap) and drops the deleted ids.
     */
    fun apply(result: ContactsSyncResult, stored: List<StoredContact>): List<StoredContact> {
        val incoming = result.contacts.map(StoredContact::of)

        if (result.isFull) return incoming

        val contacts = stored.toMutableList()
        val positions = HashMap<String, Int>()
        contacts.forEachIndexed { index, contact -> positions[contact.id] = index }

        for (contact in incoming) {
            val index = positions[contact.id]

            if (index != null) {
                contacts[index] = contact
            } else {
                positions[contact.id] = contacts.size
                contacts += contact
            }
        }

        val deleted = result.deleted.toSet()

        return if (deleted.isEmpty()) contacts else contacts.filter { it.id !in deleted }
    }

    /**
     * The lists after a `GET /contact-lists`: lists that are gone on the server disappear, new ones start enabled, the local choice
     * (`isEnabled`) and the downloaded members stay.
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
     * Download the members of a list. Follows `cursor` pages; an unchanged list answers `304`.
     * A version that changes halfway is `ApiException.Stale`: start again once, then give up (the next sync tries again).
     */
    suspend fun fetchMembers(api: ContactsApi, list: StoredContactList): MembersOutcome = try {
        fetchOnce(api, list)
    } catch (_: ApiException.Stale) {
        fetchOnce(api, list)
    }

    private suspend fun fetchOnce(api: ContactsApi, list: StoredContactList): MembersOutcome {
        val first = api.contactListSnapshot(list.id, cursor = null, etag = list.etag)

        if (first !is ContactListSnapshotOutcome.Snapshot) return MembersOutcome.NotModified

        val ids = first.snapshot.contacts.map { it.id }.toMutableList()
        var cursor = first.snapshot.nextCursor
        var pages = 1

        while (cursor != null && pages < 200) {
            val page = api.contactListSnapshot(list.id, cursor = cursor, etag = null) as? ContactListSnapshotOutcome.Snapshot ?: break

            ids += page.snapshot.contacts.map { it.id }
            cursor = page.snapshot.nextCursor
            pages++
        }

        return MembersOutcome.Members(ids, first.snapshot.list.version, first.etag ?: ContactListETag.make(first.snapshot.list.version))
    }
}
