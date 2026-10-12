// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import kotlinx.coroutines.test.runTest
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.ContactListHeader
import nl.fullstackstudio.fsvoip.core.ContactListInfo
import nl.fullstackstudio.fsvoip.core.ContactListSnapshot
import nl.fullstackstudio.fsvoip.core.ContactListSnapshotOutcome
import nl.fullstackstudio.fsvoip.core.ContactListsResponse
import nl.fullstackstudio.fsvoip.core.ContactsPage
import nl.fullstackstudio.fsvoip.core.InternalContact
import nl.fullstackstudio.fsvoip.core.SnapshotContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsHubTest {
    private val api = FakeContactsApi()
    private val store = InMemoryContactsStore()
    private var clock = 1_000_000L

    private fun hub() = ContactsHub(store, api = { api }, now = { clock }, minimumIntervalMs = 60_000)

    @Test
    fun syncStoresEverythingAndBuildsTheNameIndex() = runTest {
        api.pages += ContactsPage(listOf(contact("1", "Bakkerij Smit", "+31701234567"), contact("2", "Pieter", "0612345678")), emptyList(), null, "T1")
        api.lists = ContactListsResponse(listOf(ContactListInfo("L", "Klanten", 3, 1)))
        api.snapshots["L"] = listOf(
            ContactListSnapshotOutcome.Snapshot(ContactListSnapshot(ContactListHeader("L", "Klanten", 3), listOf(SnapshotContact("1", "Bakkerij Smit")), null), "\"3\""),
        )
        val hub = hub()
        hub.setAccounts(listOf("acc"))
        hub.setInternal("acc", listOf(InternalContact("100", "Receptie")))

        assertEquals(ContactsSyncOutcome.SYNCED, hub.sync("acc"))

        // A caller in another notation is still found; so is a colleague.
        assertEquals("Bakkerij Smit", hub.name("070 123 45 67"))
        assertEquals("Pieter", hub.name("+31612345678"))
        assertEquals("Receptie", hub.name("100"))
        assertNull(hub.name("0201234567"))

        assertEquals(setOf("1"), hub.members("acc", "L"))
        assertEquals(2, hub.accountStates.value.getValue("acc").contactCount)

        // The stored copy is what a restart reads: the next run asks for what changed since T1.
        assertEquals("T1", store.load("acc")!!.since)
        val restarted = hub()
        restarted.setAccounts(listOf("acc"))
        assertEquals("Bakkerij Smit", restarted.name("0701234567"))
    }

    @Test
    fun asyncRunNeverAsksTooSoonAfterTheLastOne() = runTest {
        api.pages += ContactsPage(listOf(contact("1", "A", "0612345678")), emptyList(), null, "T1")
        val hub = hub()
        hub.setAccounts(listOf("acc"))
        hub.sync("acc")

        clock += 10_000
        assertEquals(ContactsSyncOutcome.SKIPPED, hub.sync("acc"))
        assertEquals(1, api.requests.size)

        clock += 60_000
        api.pages += ContactsPage(emptyList(), listOf("1"), null, "T2")
        assertEquals(ContactsSyncOutcome.SYNCED, hub.sync("acc"))
        assertEquals("T1", api.requests.last().first)
        assertNull(hub.name("0612345678"))
    }

    @Test
    fun withoutPermissionNothingIsAsked() = runTest {
        val hub = hub()
        hub.setAccounts(listOf("acc"))

        assertEquals(ContactsSyncOutcome.SKIPPED, hub.sync("acc", canRead = false))
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun aRevokedPairingIsReportedAndAFailureKeepsTheOldData() = runTest {
        api.pages += ContactsPage(listOf(contact("1", "A", "0612345678")), emptyList(), null, "T1")
        val hub = hub()
        hub.setAccounts(listOf("acc"))
        hub.sync("acc")

        clock += 120_000
        api.pages += ApiException.Transport("Timeout")
        assertEquals(ContactsSyncOutcome.FAILED, hub.sync("acc"))
        assertTrue(hub.accountStates.value.getValue("acc").lastSyncFailed)
        assertEquals("A", hub.name("0612345678"))

        var revoked: String? = null
        hub.onUnauthorized = { revoked = it }
        clock += 120_000
        api.pages += ApiException.Unauthorized()
        assertEquals(ContactsSyncOutcome.REVOKED, hub.sync("acc"))
        assertEquals("acc", revoked)
    }

    @Test
    fun removingAnAccountRemovesItsContacts() = runTest {
        api.pages += ContactsPage(listOf(contact("1", "A", "0612345678")), emptyList(), null, "T1")
        val hub = hub()
        hub.setAccounts(listOf("acc"))
        hub.sync("acc")
        assertEquals("A", hub.name("0612345678"))

        hub.setAccounts(emptyList())
        assertNull(hub.name("0612345678"))
        assertNull(store.load("acc"))
    }

    @Test
    fun aListThatIsSwitchedOffHasNoMembers() = runTest {
        api.pages += ContactsPage(listOf(contact("1", "A", "0612345678")), emptyList(), null, "T1")
        api.lists = ContactListsResponse(listOf(ContactListInfo("L", "Klanten", 3, 1)))
        api.snapshots["L"] = listOf(
            ContactListSnapshotOutcome.Snapshot(ContactListSnapshot(ContactListHeader("L", "Klanten", 3), listOf(SnapshotContact("1", "A")), null), "\"3\""),
        )
        val hub = hub()
        hub.setAccounts(listOf("acc"))
        hub.sync("acc")
        assertEquals(setOf("1"), hub.members("acc", "L"))

        hub.setListEnabled("acc", "L", false)
        assertTrue(hub.members("acc", "L").isEmpty())
    }
}

class PhoneNumberMatcherTest {
    @Test
    fun theSameNumberInEveryNotation() {
        for (form in listOf("06 12 34 56 78", "+31612345678", "0031612345678", "31612345678", "sip:0612345678@pbx.example", "+31 (0)6 12345678", "tel:+31612345678;ext=1")) {
            assertEquals(form, "+31612345678", PhoneNumberMatcher.key(form)!!.exact)
        }
    }

    @Test
    fun shortNumbersOnlyMatchExactly() {
        assertEquals("101", PhoneNumberMatcher.key("101")!!.exact)
        assertNull(PhoneNumberMatcher.key("101")!!.tail)
        assertTrue(PhoneNumberMatcher.same("101", "101"))
        assertTrue(!PhoneNumberMatcher.same("101", "1010"))
        assertEquals("*97", PhoneNumberMatcher.key("*97")!!.exact)
        assertNull(PhoneNumberMatcher.key("abc"))
        assertNull(PhoneNumberMatcher.e164("101"))
        assertEquals("+31612345678", PhoneNumberMatcher.e164("0612345678"))
    }

    @Test
    fun anotherCountryDoesNotMatchADutchNumberWithTheSameTail() {
        val index = ContactIndex(listOf(ContactEntry("1", "Piet", phones = listOf(ContactEntryPhone("+31612345678")), source = ContactSource.AddressBook)))

        assertEquals("Piet", index.name("0612345678"))
        // A Belgian number states its country: it must not fall back on a Dutch contact with the same last digits.
        assertNull(index.name("+32612345678"))
    }

    @Test
    fun twoPeopleOnOneTailAnswerUnknown() {
        val index = ContactIndex(
            listOf(
                ContactEntry("1", "Piet", phones = listOf(ContactEntryPhone("+32612345678")), source = ContactSource.AddressBook),
                ContactEntry("2", "Klaas", phones = listOf(ContactEntryPhone("+49612345678")), source = ContactSource.AddressBook),
            ),
        )

        // Nothing matches exactly; the last nine digits belong to two different people.
        assertNull(index.name("0612345678"))

        // The same person twice is fine.
        val same = ContactIndex(
            listOf(
                ContactEntry("1", "Piet", phones = listOf(ContactEntryPhone("+32612345678")), source = ContactSource.AddressBook),
                ContactEntry("2", "piet", phones = listOf(ContactEntryPhone("+49612345678")), source = ContactSource.AddressBook),
            ),
        )
        assertEquals("Piet", same.name("0612345678"))
    }
}
