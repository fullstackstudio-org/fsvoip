// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import kotlinx.coroutines.test.runTest
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.ContactListSnapshotOutcome
import nl.fullstackstudio.fsvoip.core.StoredContact
import nl.fullstackstudio.fsvoip.core.StoredContactList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContactsSyncTest {
    private fun stored(id: String, name: String, updatedAt: String = "t0") = StoredContact.of(Make.contact(id, name, listOf("+31612345678"), updatedAt))

    @Test
    fun aFullRunReplacesEverything() {
        val result = Make.run(listOf(Make.contact("b", "Bea")), serverTime = "s1", full = true)

        val contacts = ContactsSync.apply(result, listOf(stored("a", "Anna"), stored("b", "Oud")))

        assertEquals(listOf("b"), contacts.map { it.id })
        assertEquals("Bea", contacts.first().name)
    }

    @Test
    fun anIncrementalRunUpsertsOnId() {
        val result = Make.run(listOf(Make.contact("b", "Bea (nieuw)", updatedAt = "t1"), Make.contact("c", "Cor")), serverTime = "s2", full = false)

        val contacts = ContactsSync.apply(result, listOf(stored("a", "Anna"), stored("b", "Bea")))

        assertEquals("an existing contact keeps its place, a new one goes last", listOf("a", "b", "c"), contacts.map { it.id })
        assertEquals("Bea (nieuw)", contacts[1].name)
        assertEquals("t1", contacts[1].updatedAt)
    }

    @Test
    fun tombstonesRemoveContacts() {
        val result = Make.run(emptyList(), deleted = listOf("a", "unknown"), serverTime = "s2", full = false)

        assertEquals(listOf("b"), ContactsSync.apply(result, listOf(stored("a", "Anna"), stored("b", "Bea"))).map { it.id })
    }

    @Test
    fun theSameContactTwiceIsStoredOnce() {
        val result = Make.run(listOf(Make.contact("a", "Anna", updatedAt = "t2")), serverTime = "s2", full = false)
        val once = ContactsSync.apply(result, listOf(stored("a", "Anna", "t2")))
        val twice = ContactsSync.apply(result, once)

        assertEquals(once, twice)
        assertEquals(1, twice.size)
    }

    @Test
    fun listsKeepTheLocalChoiceAndMembersAndDropGoneLists() {
        val existing = listOf(
            StoredContactList("l1", "Oud", 2, "\"2\"", false, listOf("a"), 1),
            StoredContactList("gone", "Weg", 1),
        )

        val merged = ContactsSync.mergeLists(listOf(Make.list("l1", "Klanten", 3, 4), Make.list("l2", "Leveranciers", 1, 2)), existing)

        assertEquals(listOf("l1", "l2"), merged.map { it.id })
        assertEquals("Klanten", merged[0].name)
        assertFalse(merged[0].isEnabled)
        assertEquals(listOf("a"), merged[0].memberIds)
        assertEquals("the version is only updated by a download", 2, merged[0].version)
        assertTrue("a new list starts on", merged[1].isEnabled)
    }

    @Test
    fun needsSnapshot() {
        val list = StoredContactList("l", "L", 3, "\"3\"")

        assertFalse(ContactsSync.needsSnapshot(list, 3))
        assertTrue(ContactsSync.needsSnapshot(list, 4))
        assertTrue("never downloaded", ContactsSync.needsSnapshot(StoredContactList("l", "L", 3), 3))
        assertFalse("a list that is off is not downloaded", ContactsSync.needsSnapshot(StoredContactList("l", "L", 0, isEnabled = false), 3))
    }

    @Test
    fun fetchMembersFollowsCursorsAndKeepsTheETag() = runTest {
        val api = FakeContactsApi()
        api.snapshotPages(
            "l",
            Result.success(ContactListSnapshotOutcome.Snapshot(Make.snapshot("l", 5, listOf("a", "b"), "p2"), "\"5\"")),
            Result.success(ContactListSnapshotOutcome.Snapshot(Make.snapshot("l", 5, listOf("c")), null)),
        )

        val outcome = ContactsSync.fetchMembers(api, StoredContactList("l", "L", 4, "\"4\""))

        assertEquals(ContactsSync.MembersOutcome.Members(listOf("a", "b", "c"), 5, "\"5\""), outcome)
        assertEquals(listOf(null, "p2"), api.snapshotCalls.map { it.cursor })
        assertEquals("the stored ETag goes in If-None-Match", "\"4\"", api.snapshotCalls[0].etag)
        assertNull("not on a cursor page", api.snapshotCalls[1].etag)
    }

    @Test
    fun a304MeansTheStoredMembersAreStillRight() = runTest {
        val api = FakeContactsApi()
        api.snapshotPages("l", Result.success(ContactListSnapshotOutcome.NotModified("\"4\"")))

        val outcome = ContactsSync.fetchMembers(api, StoredContactList("l", "L", 4, "\"4\"", memberIds = listOf("a")))

        assertEquals(ContactsSync.MembersOutcome.NotModified, outcome)
        assertEquals("nothing else was downloaded", 1, api.snapshotCalls.size)
    }

    @Test
    fun aVersionChangeHalfwayStartsOnceAgain() = runTest {
        val api = FakeContactsApi()
        api.snapshotPages(
            "l",
            Result.success(ContactListSnapshotOutcome.Snapshot(Make.snapshot("l", 5, listOf("a"), "p2"), "\"5\"")),
            Result.failure<ContactListSnapshotOutcome>(ApiException.Stale(null)),
            Result.success(ContactListSnapshotOutcome.Snapshot(Make.snapshot("l", 6, listOf("a", "z")), "\"6\"")),
        )

        val outcome = ContactsSync.fetchMembers(api, StoredContactList("l", "L", 4))

        assertEquals(ContactsSync.MembersOutcome.Members(listOf("a", "z"), 6, "\"6\""), outcome)
    }

    @Test
    fun staleTwiceGivesUp() = runTest {
        val api = FakeContactsApi()
        api.snapshotPages("l", Result.failure<ContactListSnapshotOutcome>(ApiException.Stale(null)), Result.failure<ContactListSnapshotOutcome>(ApiException.Stale(null)))

        try {
            ContactsSync.fetchMembers(api, StoredContactList("l", "L", 4))
            fail("expected a throw")
        } catch (_: ApiException.Stale) {
        }
    }

    @Test
    fun aSnapshotWithoutAnETagHeaderGetsTheOneFromItsVersion() = runTest {
        val api = FakeContactsApi()
        api.snapshotPages("l", Result.success(ContactListSnapshotOutcome.Snapshot(Make.snapshot("l", 7, listOf("a")), null)))

        val outcome = ContactsSync.fetchMembers(api, StoredContactList("l", "L", 4)) as ContactsSync.MembersOutcome.Members

        assertEquals("\"7\"", outcome.etag)
    }
}
