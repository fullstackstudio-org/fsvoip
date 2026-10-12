// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import kotlinx.coroutines.test.runTest
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.Contact
import nl.fullstackstudio.fsvoip.core.ContactListETag
import nl.fullstackstudio.fsvoip.core.ContactListHeader
import nl.fullstackstudio.fsvoip.core.ContactListInfo
import nl.fullstackstudio.fsvoip.core.ContactListSnapshot
import nl.fullstackstudio.fsvoip.core.ContactListSnapshotOutcome
import nl.fullstackstudio.fsvoip.core.ContactListsResponse
import nl.fullstackstudio.fsvoip.core.ContactPhone
import nl.fullstackstudio.fsvoip.core.ContactPhoneLabel
import nl.fullstackstudio.fsvoip.core.ContactsPage
import nl.fullstackstudio.fsvoip.core.SnapshotContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

internal fun contact(id: String, name: String, vararg numbers: String, updatedAt: String = "2026-10-07T09:30:12.345Z") =
    Contact(id = id, name = name, phones = numbers.map { ContactPhone(it, ContactPhoneLabel.MOBILE) }, updatedAt = updatedAt)

/** A server with scripted pages. `contactPages` are consumed in order per `contacts()` call. */
internal class FakeContactsApi : ContactsApi {
    val requests = mutableListOf<Triple<String?, String?, Int?>>()
    var pages = ArrayDeque<Any>()
    var lists = ContactListsResponse()
    var snapshots = mutableMapOf<String, List<ContactListSnapshotOutcome>>()
    val snapshotRequests = mutableListOf<Triple<String, String?, String?>>()

    override suspend fun contacts(since: String?, cursor: String?, limit: Int?): ContactsPage {
        requests += Triple(since, cursor, limit)

        return when (val next = pages.removeFirst()) {
            is Exception -> throw next
            is ContactsPage -> next
            else -> error("script")
        }
    }

    override suspend fun contactLists(): ContactListsResponse = lists

    override suspend fun contactListSnapshot(listId: String, cursor: String?, etag: String?): ContactListSnapshotOutcome {
        snapshotRequests += Triple(listId, cursor, etag)
        val queue = snapshots.getValue(listId).toMutableList()
        val next = queue.removeAt(0)
        snapshots[listId] = queue

        return next
    }
}

class ContactsSyncTest {
    @Test
    fun aFullRunFollowsTheCursorAndTakesTheServerTimeOfTheFirstPage() = runTest {
        val api = FakeContactsApi()
        api.pages += ContactsPage(listOf(contact("1", "A")), emptyList(), nextCursor = "c1", serverTime = "T1")
        api.pages += ContactsPage(listOf(contact("2", "B")), emptyList(), nextCursor = null, serverTime = "T2-later")

        val result = ContactsSync.run(api, since = null)

        assertEquals(listOf("1", "2"), result.contacts.map { it.id })
        assertEquals("T1", result.serverTime)
        assertTrue(result.isFull)
        assertEquals(listOf(Triple(null, null, 500), Triple(null, "c1", 500)), api.requests)
    }

    @Test
    fun anIncrementalRunUpsertsAndDropsTheDeleted() = runTest {
        val api = FakeContactsApi()
        // The overlap of the server: contact 1 arrives again, unchanged, and 3 is new.
        api.pages += ContactsPage(listOf(contact("1", "A new name"), contact("3", "C")), listOf("2"), null, "T2")

        val result = ContactsSync.run(api, since = "T1")
        val stored = listOf(contact("1", "A"), contact("2", "B")).map(::StoredContact)
        val merged = ContactsSync.apply(result, stored)

        assertFalse(result.isFull)
        assertEquals("T1", api.requests.single().first)
        assertEquals(listOf("1", "3"), merged.map { it.id })
        assertEquals("A new name", merged.first().name)
    }

    @Test
    fun aContactThatArrivesTwiceAppearsOnce() = runTest {
        val api = FakeContactsApi()
        api.pages += ContactsPage(listOf(contact("1", "Old")), emptyList(), "c", "T")
        api.pages += ContactsPage(listOf(contact("1", "Newer")), emptyList(), null, "T")

        val result = ContactsSync.run(api, since = null)

        assertEquals(listOf("Newer"), result.contacts.map { it.name })
    }

    @Test
    fun aFullRunReplacesEverything() {
        val result = ContactsSyncResult(listOf(contact("9", "Z")), emptyList(), "T", isFull = true)

        assertEquals(listOf("9"), ContactsSync.apply(result, listOf(contact("1", "A")).map(::StoredContact)).map { it.id })
    }

    @Test
    fun aTooOldSinceStartsOverWithAFullRun() = runTest {
        val api = FakeContactsApi()
        api.pages += ApiException.InvalidRequest("resync", code = "resync")
        api.pages += ContactsPage(listOf(contact("1", "A")), emptyList(), null, "T9")

        val result = ContactsSync.run(api, since = "2026-01-01T00:00:00.000Z")

        assertTrue(result.isFull)
        assertEquals(listOf("2026-01-01T00:00:00.000Z", null), api.requests.map { it.first })
    }

    @Test
    fun anotherBadRequestIsNotSwallowed() = runTest {
        val api = FakeContactsApi()
        api.pages += ApiException.InvalidRequest("nope", code = "invalid_phone")

        try {
            ContactsSync.run(api, since = "T")
            fail()
        } catch (_: ApiException.InvalidRequest) {
        }
    }

    @Test
    fun aRunThatFailsHalfwayStoresNothing() = runTest {
        val api = FakeContactsApi()
        api.pages += ContactsPage(listOf(contact("1", "A")), emptyList(), "c", "T")
        api.pages += ApiException.Unavailable(true, null)

        try {
            ContactsSync.run(api, since = null)
            fail()
        } catch (_: ApiException.Unavailable) {
        }
    }
}

class ContactListsTest {
    private fun list(id: String = "L", version: Int = 3, etag: String? = "\"3\"", enabled: Boolean = true, members: List<String> = listOf("1")) =
        StoredContactList(id, "Klanten", version, etag, enabled, members, members.size)

    @Test
    fun listsFollowTheServerButKeepTheLocalChoice() {
        val stored = listOf(list("L", enabled = false), list("gone"))
        val merged = ContactsSync.mergeLists(listOf(ContactListInfo("L", "Nieuwe naam", 4, 7), ContactListInfo("N", "Nieuw", 1, 0)), stored)

        assertEquals(listOf("L", "N"), merged.map { it.id })
        assertFalse(merged[0].isEnabled)
        assertEquals("Nieuwe naam", merged[0].name)
        assertEquals(listOf("1"), merged[0].memberIds)
        assertTrue(merged[1].isEnabled)
        assertNull(merged[1].etag)
    }

    @Test
    fun onlyAnEnabledListWithAnotherVersionIsDownloaded() {
        assertFalse(ContactsSync.needsSnapshot(list(), serverVersion = 3))
        assertTrue(ContactsSync.needsSnapshot(list(), serverVersion = 4))
        assertTrue(ContactsSync.needsSnapshot(list(etag = null), serverVersion = 3))
        assertFalse(ContactsSync.needsSnapshot(list(enabled = false, etag = null), serverVersion = 9))
    }

    private fun snapshot(ids: List<String>, version: Int = 4, cursor: String? = null) =
        ContactListSnapshotOutcome.Snapshot(ContactListSnapshot(ContactListHeader("L", "Klanten", version), ids.map { SnapshotContact(it, "n$it") }, cursor), ContactListETag.make(version))

    @Test
    fun theMembersComeInPagesAndAnUnchangedListAnswers304() = runTest {
        val api = FakeContactsApi()
        api.snapshots["L"] = listOf(snapshot(listOf("1", "2"), cursor = "next"), snapshot(listOf("3")))

        val outcome = ContactsSync.fetchMembers(api, list(version = 3)) as ContactsSync.MembersOutcome.Members

        assertEquals(listOf("1", "2", "3"), outcome.ids)
        assertEquals(4, outcome.version)
        assertEquals("\"4\"", outcome.etag)
        // The first request carries the ETag we have; the cursor page does not.
        assertEquals(listOf(Triple("L", null, "\"3\""), Triple("L", "next", null)), api.snapshotRequests)

        api.snapshots["L"] = listOf(ContactListSnapshotOutcome.NotModified("\"3\""))
        assertEquals(ContactsSync.MembersOutcome.NotModified, ContactsSync.fetchMembers(api, list()))
    }

    @Test
    fun aVersionThatChangesHalfwayStartsOnceAgain() = runTest {
        val api = FakeContactsApi()
        var calls = 0
        val flaky = object : ContactsApi by api {
            override suspend fun contactListSnapshot(listId: String, cursor: String?, etag: String?): ContactListSnapshotOutcome {
                calls++
                if (calls == 1) throw ApiException.Conflict("stale", null, null)
                return snapshot(listOf("7"))
            }
        }

        val outcome = ContactsSync.fetchMembers(flaky, list()) as ContactsSync.MembersOutcome.Members
        assertEquals(listOf("7"), outcome.ids)
        assertEquals(2, calls)
    }

    @Test
    fun aRunThatNeverEndsIsAnErrorNotAFullCopy() = runTest {
        val api = FakeContactsApi()

        repeat(ContactsSync.MAX_PAGES_FOR_TEST + 5) { api.pages += ContactsPage(listOf(contact("c$it", "N$it", "0612345678")), emptyList(), "more", "T1") }

        try {
            ContactsSync.run(api, null)
            fail("a run with a cursor left must not count as complete")
        } catch (_: IllegalStateException) {
        }
    }
}
