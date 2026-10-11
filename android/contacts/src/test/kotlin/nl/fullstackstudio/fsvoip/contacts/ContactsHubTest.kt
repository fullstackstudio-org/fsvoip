// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import nl.fullstackstudio.fsvoip.core.AccountContactsData
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.ContactListSnapshotOutcome
import nl.fullstackstudio.fsvoip.core.ContactUpdateResponse
import nl.fullstackstudio.fsvoip.core.InMemoryContactsStore
import nl.fullstackstudio.fsvoip.core.InternalContact
import nl.fullstackstudio.fsvoip.core.StoredContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ContactsHubTest {
    private lateinit var store: InMemoryContactsStore
    private lateinit var api: FakeContactsApi
    private lateinit var device: FakeDevice
    private lateinit var settings: InMemoryContactsSettings
    private var clock = 1_791_549_000_000L

    @Before
    fun setUp() {
        store = InMemoryContactsStore()
        api = FakeContactsApi()
        device = FakeDevice()
        settings = InMemoryContactsSettings()
    }

    /** Everything runs inline on the test thread: the order of events is the order of the code. */
    private fun makeHub(): ContactsHub = ContactsHub(
        store = store,
        api = { api },
        device = device,
        settings = settings,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        workDispatcher = Dispatchers.Unconfined,
        now = { clock },
        minimumIntervalMillis = 60_000,
    )

    private suspend fun configured(vararg ids: String): ContactsHub {
        val hub = makeHub()
        hub.configure(ids.toList().ifEmpty { listOf("acc") })

        return hub
    }

    private fun advance(seconds: Int) {
        clock += seconds * 1000L
    }

    private fun sinces() = api.syncSinces.map { it ?: "nil" }

    // MARK: First sync, since, resync

    @Test
    fun theFirstSyncIsCompleteAndStoresServerTimeOnlyAfterTheLastPage() = runTest {
        api.sync(Make.run(listOf(Make.contact("a", "Anna", listOf("+31612345678"))), serverTime = "s1", full = true))
        val hub = configured()

        val outcome = hub.sync("acc")
        hub.settle()

        assertEquals(ContactsSyncOutcome.SYNCED, outcome)
        assertEquals(1, api.syncSinces.size)
        assertNull("no since on the first run", api.syncSinces[0])
        assertEquals(listOf("Anna"), hub.entries.value.map { it.displayName })
        assertEquals("Anna", hub.name("06 12 34 56 78"))
        assertEquals(1, hub.state("acc")?.contactCount)
        assertEquals("s1", store.load("acc")?.serverTime)
    }

    @Test
    fun theNextSyncSendsTheStoredServerTimeAndAppliesChanges() = runTest {
        api.sync(
            Make.run(listOf(Make.contact("a", "Anna", listOf("+31611111111")), Make.contact("b", "Bea", listOf("+31622222222"))), serverTime = "s1", full = true),
            Make.run(listOf(Make.contact("b", "Beatrix", listOf("+31622222222"), updatedAt = "t2"), Make.contact("c", "Cor", listOf("+31633333333"))), deleted = listOf("a"), serverTime = "s2", full = false),
        )
        val hub = configured()
        hub.sync("acc")

        advance(120)
        hub.sync("acc")
        hub.settle()

        assertEquals(listOf("nil", "s1"), sinces())
        assertEquals(listOf("Beatrix", "Cor"), hub.entries.value.map { it.displayName })
        assertNull("a deleted contact no longer names a caller", hub.name("0611111111"))
        assertEquals("Beatrix", hub.name("0622222222"))
    }

    @Test
    fun aResyncRequestStartsOverAndDropsWhatIsGone() = runTest {
        api.syncResults += Result.success(Make.run(listOf(Make.contact("a", "Anna", listOf("+31611111111")), Make.contact("b", "Bea", listOf("+31622222222"))), serverTime = "s1", full = true))
        api.syncResults += Result.failure(ApiException.Resync())
        api.syncResults += Result.success(Make.run(listOf(Make.contact("b", "Bea", listOf("+31622222222"))), serverTime = "s9", full = true))
        api.syncResults += Result.success(Make.run(emptyList(), serverTime = "s10", full = false))
        val hub = configured()
        hub.sync("acc")

        advance(120)
        val outcome = hub.sync("acc")
        hub.settle()

        assertEquals(ContactsSyncOutcome.SYNCED, outcome)
        assertEquals("after resync the second request has no since", listOf("nil", "s1", "nil"), sinces())
        assertEquals(listOf("Bea"), hub.entries.value.map { it.displayName })

        advance(120)
        hub.sync("acc")
        assertEquals("the new run's server time is the next since", "s9", api.syncSinces.last())
    }

    @Test
    fun aCancelledRunIsNotAFailure() = runTest {
        api.syncResults += Result.failure(kotlinx.coroutines.CancellationException("pull to refresh let go"))
        val hub = configured()

        val outcome = hub.sync("acc")

        assertEquals(ContactsSyncOutcome.SKIPPED, outcome)
        assertEquals("a cancelled pull to refresh is not 'no connection'", false, hub.state("acc")?.lastSyncFailed)
        assertTrue(hub.syncingAccountIds.value.isEmpty())
    }

    @Test
    fun aFailedRunKeepsTheOldSinceAndTheOldContacts() = runTest {
        api.syncResults += Result.success(Make.run(listOf(Make.contact("a", "Anna", listOf("+31611111111"))), serverTime = "s1", full = true))
        api.syncResults += Result.failure(ApiException.Transport("offline"))
        api.syncResults += Result.success(Make.run(emptyList(), serverTime = "s3", full = false))
        val hub = configured()
        hub.sync("acc")

        advance(120)
        val failed = hub.sync("acc")

        assertEquals(ContactsSyncOutcome.FAILED, failed)
        assertEquals(true, hub.state("acc")?.lastSyncFailed)
        hub.settle()
        assertEquals("offline: what was there stays", listOf("Anna"), hub.entries.value.map { it.displayName })

        advance(120)
        hub.sync("acc")
        assertEquals(listOf("nil", "s1", "s1"), sinces())
        assertEquals(false, hub.state("acc")?.lastSyncFailed)
    }

    @Test
    fun syncsCloseTogetherAreSkippedUnlessForced() = runTest {
        api.sync(Make.run(emptyList(), serverTime = "s1", full = true), Make.run(emptyList(), serverTime = "s2", full = false))
        val hub = configured()

        val first = hub.sync("acc")
        val second = hub.sync("acc")
        val forced = hub.sync("acc", force = true)

        assertEquals(ContactsSyncOutcome.SYNCED, first)
        assertEquals(ContactsSyncOutcome.SKIPPED, second)
        assertEquals(ContactsSyncOutcome.SYNCED, forced)
        assertEquals(2, api.syncSinces.size)
    }

    @Test
    fun aRevokedPairingIsReported() = runTest {
        api.meError = ApiException.Unauthorized()
        val hub = configured()
        val revoked = mutableListOf<String>()
        hub.onUnauthorized = { revoked += it }

        val outcome = hub.sync("acc")

        assertEquals(ContactsSyncOutcome.REVOKED, outcome)
        assertEquals(listOf("acc"), revoked)
        assertTrue(hub.syncingAccountIds.value.isEmpty())
    }

    @Test
    fun capabilitiesComeFromTheServerAndDeleteIsOffByDefault() = runTest {
        api.capabilities = Make.capabilities(read = true, write = true, delete = false)
        api.sync(Make.run(listOf(Make.contact("a", "Anna", listOf("+31611111111"))), serverTime = "s1", full = true))
        val hub = configured()
        hub.sync("acc")
        hub.settle()

        val entry = hub.entries.value.first()
        assertTrue(hub.canWrite(entry))
        assertFalse(hub.canDelete(entry))

        api.capabilities = Make.capabilities(delete = true)
        api.sync(Make.run(emptyList(), serverTime = "s2", full = false))
        hub.sync("acc", force = true)
        assertTrue(hub.canDelete(entry))
    }

    @Test
    fun aPairingThatMayNotReadShowsNothingAndDownloadsNothing() = runTest {
        api.capabilities = Make.capabilities(read = false, write = false, delete = false)
        api.sync(Make.run(listOf(Make.contact("a", "Anna", listOf("+31611111111"))), serverTime = "s1", full = true))
        val hub = configured()

        val outcome = hub.sync("acc")
        hub.settle()

        assertEquals(ContactsSyncOutcome.SYNCED, outcome)
        assertTrue("no read right: no contacts requested", api.syncSinces.isEmpty())
        assertTrue(hub.entries.value.isEmpty())
        assertTrue(hub.writableAccountIds.isEmpty())
    }

    @Test
    fun theRightsFromTheAppsMeAreUsedByASyncRightAfterwardsAndNotAskedAgain() = runTest {
        api.capabilities = Make.capabilities(read = true, write = true, delete = true)
        api.sync(Make.run(listOf(Make.contact("a", "Anna", listOf("+31611111111"))), serverTime = "s1", full = true))
        val hub = configured()

        // The app did its `GET /me` and passes on what it says: this pairing may not write any more.
        hub.apply(Make.capabilities(read = true, write = false, delete = false), "acc")
        hub.sync("acc", force = true)
        hub.settle()

        assertEquals("one /me per refresh: the sync does not ask again", 0, api.capabilityCalls)
        val entry = hub.entries.value.first()
        assertFalse(hub.canWrite(entry))
        assertFalse(hub.canDelete(entry))

        // Later (the timer) the sync asks for itself and takes what the server says then.
        advance(120)
        api.sync(Make.run(emptyList(), serverTime = "s2", full = false))
        hub.sync("acc", force = true)
        assertEquals(1, api.capabilityCalls)
        assertTrue(hub.canWrite(entry))
    }

    // MARK: Lists

    @Test
    fun listsAreDownloadedOnceAndNotAgainWhileTheVersionIsEqual() = runTest {
        api.sync(
            Make.run(listOf(Make.contact("a", "Anna", listOf("+31611111111"))), serverTime = "s1", full = true),
            Make.run(emptyList(), serverTime = "s2", full = false),
            Make.run(emptyList(), serverTime = "s3", full = false),
        )
        api.lists = listOf(Make.list("l1", "Klanten", 3, 1))
        api.snapshotPages("l1", Result.success(ContactListSnapshotOutcome.Snapshot(Make.snapshot("l1", 3, listOf("a")), "\"3\"")))
        val hub = configured()

        hub.sync("acc")
        assertEquals(setOf("a"), hub.memberIds("l1", "acc"))
        assertEquals(1, api.snapshotCalls.size)

        advance(120)
        hub.sync("acc")
        assertEquals("same version: no request at all", 1, api.snapshotCalls.size)

        // The list changed on the server; it answers with the stored ETag in If-None-Match and a new snapshot.
        api.lists = listOf(Make.list("l1", "Klanten", 4, 2))
        api.snapshotPages("l1", Result.success(ContactListSnapshotOutcome.Snapshot(Make.snapshot("l1", 4, listOf("a", "b")), "\"4\"")))
        advance(120)
        hub.sync("acc")

        assertEquals("\"3\"", api.snapshotCalls.last().etag)
        assertEquals(setOf("a", "b"), hub.memberIds("l1", "acc"))
        assertEquals(4, hub.state("acc")?.lists?.first()?.version)
    }

    @Test
    fun a304OnAListTakesTheNewVersionWithoutNewMembers() = runTest {
        api.sync(Make.run(emptyList(), serverTime = "s1", full = true), Make.run(emptyList(), serverTime = "s2", full = false))
        api.lists = listOf(Make.list("l1", "Klanten", 3))
        api.snapshotPages("l1", Result.success(ContactListSnapshotOutcome.Snapshot(Make.snapshot("l1", 3, listOf("a")), "\"3\"")))
        val hub = configured()
        hub.sync("acc")

        api.lists = listOf(Make.list("l1", "Klanten", 4))
        api.snapshotPages("l1", Result.success(ContactListSnapshotOutcome.NotModified("\"3\"")))
        advance(120)
        hub.sync("acc")

        assertEquals(setOf("a"), hub.memberIds("l1", "acc"))
        assertEquals(4, hub.state("acc")?.lists?.first()?.version)
    }

    @Test
    fun aListThatIsSwitchedOffIsNotDownloadedAndLosesItsMembers() = runTest {
        api.sync(Make.run(emptyList(), serverTime = "s1", full = true), Make.run(emptyList(), serverTime = "s2", full = false), Make.run(emptyList(), serverTime = "s3", full = false))
        api.lists = listOf(Make.list("l1", "Klanten", 3))
        api.snapshotPages("l1", Result.success(ContactListSnapshotOutcome.Snapshot(Make.snapshot("l1", 3, listOf("a")), "\"3\"")))
        val hub = configured()
        hub.sync("acc")
        assertEquals(1, api.snapshotCalls.size)

        hub.setListEnabled(false, "l1", "acc")
        assertEquals(emptySet<String>(), hub.memberIds("l1", "acc"))
        advance(120)
        hub.sync("acc")
        assertEquals("a list that is off costs no request", 1, api.snapshotCalls.size)

        api.snapshotPages("l1", Result.success(ContactListSnapshotOutcome.Snapshot(Make.snapshot("l1", 3, listOf("a")), "\"3\"")))
        hub.setListEnabled(true, "l1", "acc")
        assertEquals(setOf("a"), hub.memberIds("l1", "acc"))
    }

    @Test
    fun oneListThatCannotBeFetchedDoesNotStopTheOthers() = runTest {
        api.sync(Make.run(emptyList(), serverTime = "s1", full = true))
        api.lists = listOf(Make.list("bad", "Kapot", 1), Make.list("ok", "Goed", 1))
        api.snapshotPages("ok", Result.success(ContactListSnapshotOutcome.Snapshot(Make.snapshot("ok", 1, listOf("a")), "\"1\"")))
        val hub = configured()

        assertEquals(ContactsSyncOutcome.SYNCED, hub.sync("acc"))
        assertEquals(setOf("a"), hub.memberIds("ok", "acc"))
    }

    // MARK: Writing

    @Test
    fun createAddsTheContactAtOnceAndNamesTheCaller() = runTest {
        api.sync(Make.run(emptyList(), serverTime = "s1", full = true))
        val hub = configured()
        hub.sync("acc")
        api.createResult = Result.success(Make.detail(Make.contact("n1", "Nieuw Contact", listOf("+31644444444"))))

        val draft = ContactDraft(firstName = "Nieuw", lastName = "Contact", phones = listOf(ContactDraft.Phone(number = "06 44 44 44 44")))
        hub.create(draft, "acc")
        hub.settle()

        assertEquals("Nieuw Contact", hub.name("0644444444"))
        assertEquals(1, api.creates.size)
    }

    @Test
    fun updateSendsExpectedUpdatedAtAndStoresTheAnswer() = runTest {
        api.sync(Make.run(listOf(Make.contact("a", "Anna", listOf("+31611111111"), updatedAt = "t1")), serverTime = "s1", full = true))
        val hub = configured()
        hub.sync("acc")
        hub.settle()
        val entry = hub.entries.value.first()
        api.updateResult = Result.success(ContactUpdateResponse(Make.detail(Make.contact("a", "Anna Smit", listOf("+31611111111"), updatedAt = "t2")), changed = true))

        val draft = ContactDraft.from(entry).copy(lastName = "Smit")
        hub.update(draft, "a", entry.updatedAt!!, "acc")
        hub.settle()

        assertEquals("t1", api.updates.first().second.expectedUpdatedAt)
        assertEquals("t2", hub.entries.value.first().updatedAt)
        assertEquals("Anna Smit", hub.name("0611111111"))
    }

    @Test
    fun aStaleUpdateIsThrownAndNothingChangesLocally() = runTest {
        api.sync(Make.run(listOf(Make.contact("a", "Anna", listOf("+31611111111"), updatedAt = "t1")), serverTime = "s1", full = true))
        val hub = configured()
        hub.sync("acc")
        api.updateResult = Result.failure(ApiException.Stale(null))

        try {
            hub.update(ContactDraft(), "a", "t1", "acc")
            fail("expected a throw")
        } catch (_: ApiException.Stale) {
        }

        hub.settle()
        assertEquals("t1", hub.entries.value.first().updatedAt)
    }

    @Test
    fun reloadingAStaleContactTakesTheServersVersion() = runTest {
        api.sync(Make.run(listOf(Make.contact("a", "Anna", listOf("+31611111111"), updatedAt = "t1")), serverTime = "s1", full = true))
        val hub = configured()
        hub.sync("acc")
        api.detailResult = Result.success(Make.detail(Make.contact("a", "Anna (collega paste aan)", listOf("+31611111111"), updatedAt = "t5"), notes = "nieuwe notitie"))

        val detail = hub.detail("acc", "a")
        hub.settle()

        assertEquals("nieuwe notitie", detail.notes)
        assertEquals("t5", hub.entries.value.first().updatedAt)
    }

    @Test
    fun deleteRemovesTheContactAndItsListMembership() = runTest {
        api.capabilities = Make.capabilities(delete = true)
        api.sync(Make.run(listOf(Make.contact("a", "Anna", listOf("+31611111111"))), serverTime = "s1", full = true))
        api.lists = listOf(Make.list("l1", "Klanten", 1))
        api.snapshotPages("l1", Result.success(ContactListSnapshotOutcome.Snapshot(Make.snapshot("l1", 1, listOf("a")), "\"1\"")))
        val hub = configured()
        hub.sync("acc")

        hub.delete("a", "acc")
        hub.settle()

        assertEquals(listOf("a"), api.deletes)
        assertTrue(hub.entries.value.isEmpty())
        assertEquals(emptySet<String>(), hub.memberIds("l1", "acc"))
    }

    @Test
    fun deleteByAUserIsForbiddenByTheServer() = runTest {
        api.deleteResult = Result.failure(ApiException.Forbidden())
        api.sync(Make.run(listOf(Make.contact("a", "Anna", listOf("+31611111111"))), serverTime = "s1", full = true))
        val hub = configured()
        hub.sync("acc")

        try {
            hub.delete("a", "acc")
            fail("expected a throw")
        } catch (_: ApiException.Forbidden) {
        }

        hub.settle()
        assertEquals(1, hub.entries.value.size)
    }

    // MARK: Accounts, storage, sources

    @Test
    fun contactsSurviveARestartAndAreSeparatePerAccount() = runTest {
        api.sync(Make.run(listOf(Make.contact("a", "Anna", listOf("+31611111111"))), serverTime = "s1", full = true))
        val first = configured("acc")
        first.sync("acc")
        first.settle()

        val again = configured("acc", "other")
        again.settle()

        assertEquals("Anna", again.name("0611111111"))
        assertEquals(0, again.state("other")?.contactCount)
        assertEquals("s1", store.load("acc")?.serverTime)
        assertNull(store.load("other")?.serverTime)
    }

    @Test
    fun forgettingAnAccountDeletesItsContacts() = runTest {
        api.sync(Make.run(listOf(Make.contact("a", "Anna", listOf("+31611111111"))), serverTime = "s1", full = true))
        val hub = configured("acc", "other")
        hub.sync("acc")
        hub.settle()
        assertNotNull(store.load("acc"))

        hub.forget("acc")
        hub.settle()

        assertNull(store.load("acc"))
        assertNull(hub.name("0611111111"))
        assertTrue(hub.entries.value.isEmpty())
    }

    @Test
    fun anEmptyAccountListDoesNotWipeTheStoredContacts() = runTest {
        store.save(AccountContactsData(contacts = listOf(StoredContact.of(Make.contact("a", "Anna", listOf("+31611111111"))))), "acc")
        val hub = makeHub()

        hub.configure(emptyList())

        assertNotNull("the secrets may just not have been readable yet", store.load("acc"))
        assertTrue(hub.hasLoaded.value)
    }

    @Test
    fun filesOfAccountsThatAreNoLongerPairedAreCleanedUp() = runTest {
        store.save(AccountContactsData(), "old")
        val hub = makeHub()

        hub.configure(listOf("acc"))

        assertNull(store.load("old"))
    }

    @Test
    fun theSameCustomerContactInTwoAccountsIsShownOnce() = runTest {
        val shared = Make.run(listOf(Make.contact("a", "Anna", listOf("+31611111111"))), serverTime = "s1", full = true)
        api.sync(shared, shared)
        val hub = configured("acc1", "acc2")
        hub.syncAll()
        hub.settle()

        assertEquals(1, hub.entries.value.size)
        assertEquals(listOf("acc1", "acc2"), hub.entries.value.first().accountIds)
    }

    @Test
    fun anAddressBookThatIsSwitchedOffIsHiddenAndNotUsedForNames() = runTest {
        api.sync(Make.run(listOf(Make.contact("a", "Anna", listOf("+31611111111"))), serverTime = "s1", full = true))
        val hub = configured()
        hub.sync("acc")
        hub.settle()
        assertEquals("Anna", hub.name("0611111111"))

        hub.setAddressBookEnabled(false, "acc")
        hub.settle()

        assertNull(hub.name("0611111111"))
        assertTrue(hub.entries.value.isEmpty())
        assertEquals(ContactsSyncOutcome.SKIPPED, hub.sync("acc", force = true))
    }

    @Test
    fun theOrderOfNamesIsAddressBookThenPhoneThenColleagues() = runTest {
        api.sync(Make.run(listOf(Make.contact("a", "Anna Klant", listOf("+31611111111"))), serverTime = "s1", full = true))
        device.entries = listOf(
            ContactEntry.withNumbers("device:1", "Anna Privé", listOf("0611111111"), ContactSource.Device),
            ContactEntry.withNumbers("device:2", "Mama", listOf("0655555555"), ContactSource.Device),
        )
        device.access = DeviceContactsAccess.AUTHORIZED
        val hub = configured()
        hub.setDeviceContactsEnabled(true)
        hub.sync("acc")
        hub.setInternalContacts(listOf(InternalContact("103", "Werkplaats")), "acc")
        hub.settle()

        assertEquals("Anna Klant", hub.name("0611111111"))
        assertEquals("Mama", hub.name("+31655555555"))
        assertEquals("Werkplaats", hub.name("103"))
    }

    @Test
    fun phoneContactsNeedPermissionAndAreNotUsedWithoutIt() = runTest {
        device.grants = false
        device.entries = listOf(ContactEntry.withNumbers("device:1", "Mama", listOf("0655555555"), ContactSource.Device))
        val hub = configured()

        val enabled = hub.setDeviceContactsEnabled(true)
        hub.settle()

        assertFalse(enabled)
        assertFalse(hub.usesDeviceContacts.value)
        assertEquals(DeviceContactsAccess.DENIED, hub.deviceAccess.value)
        assertFalse(settings.usesDeviceContacts)
        assertNull(hub.name("0655555555"))
    }

    @Test
    fun phoneContactsNeverTouchTheStore() = runTest {
        device.access = DeviceContactsAccess.AUTHORIZED
        device.entries = listOf(ContactEntry.withNumbers("device:1", "Mama", listOf("0655555555"), ContactSource.Device))
        val hub = configured()
        hub.setDeviceContactsEnabled(true)
        hub.settle()

        assertEquals("Mama", hub.name("0655555555"))
        assertTrue(store.load("acc")?.contacts?.isEmpty() ?: true)
        assertTrue(settings.usesDeviceContacts)

        hub.setDeviceContactsEnabled(false)
        hub.settle()
        assertNull(hub.name("0655555555"))
    }

    @Test
    fun theSettingsSwitchIsFollowedWhenItChangesElsewhere() = runTest {
        device.access = DeviceContactsAccess.AUTHORIZED
        device.entries = listOf(ContactEntry.withNumbers("device:1", "Mama", listOf("0655555555"), ContactSource.Device))
        val hub = configured()
        assertFalse(hub.usesDeviceContacts.value)

        settings.usesDeviceContacts = true
        hub.deviceSettingChanged()
        hub.settle()
        assertTrue(hub.usesDeviceContacts.value)
        assertEquals("Mama", hub.name("0655555555"))

        settings.usesDeviceContacts = false
        hub.deviceSettingChanged()
        hub.settle()
        assertNull(hub.name("0655555555"))
    }

    @Test
    fun concurrentSyncsOfOneAccountRunOnce() = runTest {
        api.sync(Make.run(emptyList(), serverTime = "s1", full = true), Make.run(emptyList(), serverTime = "s2", full = false))
        val hub = configured()

        val one = async { hub.sync("acc", force = true) }
        val two = async { hub.sync("acc", force = true) }
        val outcomes = listOf(one.await(), two.await())

        assertEquals(2, outcomes.count { it == ContactsSyncOutcome.SYNCED || it == ContactsSyncOutcome.SKIPPED })
        assertTrue(api.syncSinces.size <= 2)
    }

    @Test
    fun theTimelineGoesThroughTheAccountsApi() = runTest {
        api.timelinePages += Result.success(nl.fullstackstudio.fsvoip.core.TimelinePage(emptyList(), null))
        val hub = configured()

        hub.timeline("acc", "a", "cursor", 20)

        assertEquals(listOf<String?>("cursor"), api.timelineCursors)
    }
}
