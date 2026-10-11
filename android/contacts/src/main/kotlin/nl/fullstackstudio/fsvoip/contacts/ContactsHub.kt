// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.fullstackstudio.fsvoip.core.AccountContactsData
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.ContactCapabilities
import nl.fullstackstudio.fsvoip.core.ContactDetail
import nl.fullstackstudio.fsvoip.core.ContactListInfo
import nl.fullstackstudio.fsvoip.core.ContactsFileStore
import nl.fullstackstudio.fsvoip.core.ContactsFileStoring
import nl.fullstackstudio.fsvoip.core.FsLogger
import nl.fullstackstudio.fsvoip.core.InMemoryContactsStore
import nl.fullstackstudio.fsvoip.core.InternalContact
import nl.fullstackstudio.fsvoip.core.StoredContact
import nl.fullstackstudio.fsvoip.core.StoredContactList

/** Where the "use the phone's contacts" choice is kept. */
interface ContactsSettingsStoring {
    var usesDeviceContacts: Boolean
}

class InMemoryContactsSettings : ContactsSettingsStoring {
    @Volatile
    override var usesDeviceContacts: Boolean = false
}

/** What the screens need to know about one account's address book. */
data class ContactsAccountState(
    val isEnabled: Boolean,
    val canWrite: Boolean,
    val canDelete: Boolean,
    val lists: List<StoredContactList>,
    val contactCount: Int,
    val lastSyncedAtMillis: Long?,
    /** The last attempt to sync failed (offline, server trouble); what is shown is the last known state. */
    val lastSyncFailed: Boolean,
)

enum class ContactsSyncOutcome {
    SYNCED,

    /** Not needed (too soon after the previous sync, another sync is running, or this address book is switched off). */
    SKIPPED,

    /** The pairing is revoked on the server; the app removes the account. */
    REVOKED,

    FAILED,
}

/**
 * Keeps the address books of the paired accounts, the phone's contacts and the colleagues together and answers "who is this number".
 *
 * Call it from the main thread; reading and writing files and building the number index happen off it (on [workDispatcher]).
 * The state is exposed as flows.
 */
class ContactsHub(
    private val store: ContactsFileStoring = InMemoryContactsStore(),
    private val api: (accountId: String) -> ContactsApi? = { null },
    private val device: DeviceContactsReading = NoDeviceContacts,
    private val settings: ContactsSettingsStoring = InMemoryContactsSettings(),
    private val scope: CoroutineScope,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = System::currentTimeMillis,
    private val minimumIntervalMillis: Long = 60_000,
    private val logger: FsLogger = FsLogger("contacts"),
) {
    private val _entries = MutableStateFlow<List<ContactEntry>>(emptyList())
    private val _accountStates = MutableStateFlow<Map<String, ContactsAccountState>>(emptyMap())
    private val _syncingAccountIds = MutableStateFlow<Set<String>>(emptySet())
    private val _deviceAccess = MutableStateFlow(device.access)
    private val _usesDeviceContacts = MutableStateFlow(settings.usesDeviceContacts && device.access == DeviceContactsAccess.AUTHORIZED)
    private val _hasLoaded = MutableStateFlow(false)
    private val _revision = MutableStateFlow(0)

    val entries: StateFlow<List<ContactEntry>> = _entries.asStateFlow()
    val accountStates: StateFlow<Map<String, ContactsAccountState>> = _accountStates.asStateFlow()
    val syncingAccountIds: StateFlow<Set<String>> = _syncingAccountIds.asStateFlow()
    val deviceAccess: StateFlow<DeviceContactsAccess> = _deviceAccess.asStateFlow()
    val usesDeviceContacts: StateFlow<Boolean> = _usesDeviceContacts.asStateFlow()

    /** The stored data has been read (a first lookup before that finds nothing). */
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    /** Goes up whenever [entries] or a list's members changed; screens that derive something from them watch this one number. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Called when a request says the pairing is revoked (`401`); the app model removes the account. */
    @Volatile
    var onUnauthorized: (String) -> Unit = {}

    private var knownAccountIds: List<String> = emptyList()
    private val data = HashMap<String, AccountContactsData>()
    private val failed = HashSet<String>()
    private val lastAttempt = HashMap<String, Long>()
    private val internalContacts = HashMap<String, List<InternalContact>>()

    /** What the app's own `GET /me` said a moment ago (see [apply]). */
    private val knownCapabilities = HashMap<String, Pair<ContactCapabilities, Long>>()
    private var deviceEntries: List<ContactEntry> = emptyList()

    @Volatile
    private var index: ContactIndex = ContactIndex.EMPTY
    private var generation = 0
    private val saveSequence = AtomicInteger()
    private var rebuildJob: Job? = null
    private val persistJobs = mutableListOf<Job>()
    private val configureLock = Mutex()
    private val persistLock = Mutex()
    private val latestSaved = HashMap<String, Int>()

    // MARK: Accounts

    /**
     * Tell the hub which accounts are paired. Data of an account that is gone is dropped from memory; data of new accounts is read
     * from disk. Deleting a file is [forget]'s job.
     */
    suspend fun configure(accountIds: List<String>) = configureLock.withLock {
        // An account that dropped out of the list is forgotten in memory only. Deleting its file is `forget`'s job, called when
        // the account is really unpaired: a list that is empty because the secrets could not be read must not wipe the stored contacts.
        for (id in knownAccountIds) {
            if (id !in accountIds) {
                data.remove(id)
                internalContacts.remove(id)
            }
        }

        knownAccountIds = accountIds

        // Files of accounts that are no longer paired (an app that was reinstalled, a crash halfway through unpairing).
        if (accountIds.isNotEmpty()) {
            val stems = accountIds.map(ContactsFileStore::fileNameStem).toSet()

            withContext(workDispatcher) {
                for (orphan in store.storedAccountIds()) if (orphan !in stems) store.remove(orphan)
            }
        }

        val toLoad = accountIds.filter { it !in data }

        if (toLoad.isNotEmpty()) {
            val loaded = withContext(workDispatcher) { toLoad.map { it to store.load(it) } }

            for ((id, stored) in loaded) {
                if (id in knownAccountIds && id !in data) data[id] = stored ?: AccountContactsData()
            }
        }

        _hasLoaded.value = true
        refreshStates()
        rebuild()

        if (_usesDeviceContacts.value) refreshDeviceContacts()
    }

    /** Remove everything of one account (it was unpaired or revoked). */
    fun forget(accountId: String) {
        store.remove(accountId)
        data.remove(accountId)
        failed.remove(accountId)
        lastAttempt.remove(accountId)
        internalContacts.remove(accountId)
        knownCapabilities.remove(accountId)
        knownAccountIds = knownAccountIds.filter { it != accountId }
        refreshStates()
        scheduleRebuild()
    }

    fun setInternalContacts(contacts: List<InternalContact>, accountId: String) {
        if (internalContacts[accountId] == contacts) return

        internalContacts[accountId] = contacts
        scheduleRebuild()
    }

    /**
     * The rights of this pairing from the `GET /me` the app just did for the account. A sync that follows within
     * [CAPABILITIES_FRESHNESS_MILLIS] uses them instead of asking `/me` a second time; a later sync (the timer) asks for itself.
     */
    fun apply(capabilities: ContactCapabilities, accountId: String) {
        knownCapabilities[accountId] = capabilities to now()

        // Only when the stored data is already loaded: this must never create an empty book in front of the one on disk.
        if (data[accountId] == null) return

        update(accountId) { it.copy(canRead = capabilities.read, canWrite = capabilities.write, canDelete = capabilities.delete) }
        refreshStates()
    }

    private suspend fun currentCapabilities(accountId: String, api: ContactsApi): ContactCapabilities {
        knownCapabilities[accountId]?.let { (value, at) -> if (now() - at < CAPABILITIES_FRESHNESS_MILLIS) return value }

        return api.contactCapabilities()
    }

    // MARK: Lookup

    fun entry(number: String): ContactEntry? = index.entry(number)

    fun name(number: String): String? = index.name(number)

    fun state(accountId: String): ContactsAccountState? = _accountStates.value[accountId]

    fun canWrite(entry: ContactEntry): Boolean = entry.source == ContactSource.Customer && entry.accountIds.any { _accountStates.value[it]?.canWrite == true }

    fun canDelete(entry: ContactEntry): Boolean = entry.source == ContactSource.Customer && entry.accountIds.any { _accountStates.value[it]?.canDelete == true }

    /** The account to write a contact to / edit it through. */
    fun writeAccountId(entry: ContactEntry): String? = entry.accountIds.firstOrNull { _accountStates.value[it]?.canWrite == true }

    /** The account to delete a contact through. */
    fun deleteAccountId(entry: ContactEntry): String? = entry.accountIds.firstOrNull { _accountStates.value[it]?.canDelete == true }

    /** The accounts that may add a contact. */
    val writableAccountIds: List<String>
        get() = knownAccountIds.filter { _accountStates.value[it]?.isEnabled == true && _accountStates.value[it]?.canWrite == true }

    /** The ids of the contacts in a list (empty for a list that is off or not downloaded yet). */
    fun memberIds(listId: String, accountId: String): Set<String> = data[accountId]?.lists?.firstOrNull { it.id == listId }?.memberIds?.toSet() ?: emptySet()

    // MARK: Sync

    suspend fun syncAll(force: Boolean = false) {
        for (id in knownAccountIds) sync(id, force)
    }

    suspend fun sync(accountId: String, force: Boolean = false): ContactsSyncOutcome {
        val api = api(accountId)

        if (accountId !in knownAccountIds || data[accountId]?.isEnabled != true || api == null) return ContactsSyncOutcome.SKIPPED
        if (accountId in _syncingAccountIds.value) return ContactsSyncOutcome.SKIPPED

        if (!force) {
            val last = lastAttempt[accountId]

            if (last != null && now() - last < minimumIntervalMillis) return ContactsSyncOutcome.SKIPPED
        }

        _syncingAccountIds.value += accountId
        lastAttempt[accountId] = now()

        try {
            val capabilities = currentCapabilities(accountId, api)
            update(accountId) { it.copy(canRead = capabilities.read, canWrite = capabilities.write, canDelete = capabilities.delete) }

            if (capabilities.read) {
                val result = try {
                    api.syncAddressBook(data[accountId]?.serverTime)
                } catch (_: ApiException.Resync) {
                    // `since` is too old: one complete run, which also drops what is gone on the server.
                    api.syncAddressBook(null)
                }

                val stamp = now()
                update(accountId) {
                    it.copy(
                        contacts = ContactsSync.apply(result, it.contacts),
                        // Stored only now, after the LAST page: a run that failed halfway keeps the old `since`.
                        serverTime = result.serverTime,
                        lastSyncedAtMillis = stamp,
                    )
                }
            }

            failed.remove(accountId)
            commit(accountId)

            if (capabilities.read) syncLists(accountId, api)

            return ContactsSyncOutcome.SYNCED
        } catch (_: ApiException.Unauthorized) {
            onUnauthorized(accountId)

            return ContactsSyncOutcome.REVOKED
        } catch (error: CancellationException) {
            // A pull to refresh that was let go is not "no connection". A really cancelled caller stays cancelled.
            currentCoroutineContext().ensureActive()

            return ContactsSyncOutcome.SKIPPED
        } catch (error: Exception) {
            logger.notice("Contacts sync of an account failed: ${error.javaClass.simpleName}")
            failed += accountId
            refreshStates()

            return ContactsSyncOutcome.FAILED
        } finally {
            _syncingAccountIds.value -= accountId
        }
    }

    private suspend fun syncLists(accountId: String, api: ContactsApi) {
        val infos: List<ContactListInfo> = api.contactLists()

        update(accountId) { it.copy(lists = ContactsSync.mergeLists(infos, it.lists)) }

        for (info in infos) {
            val list = data[accountId]?.lists?.firstOrNull { it.id == info.id }

            if (list == null || !ContactsSync.needsSnapshot(list, info.version)) continue

            try {
                val outcome = ContactsSync.fetchMembers(api, list)

                update(accountId) { stored ->
                    val position = stored.lists.indexOfFirst { it.id == info.id }

                    if (position < 0) return@update stored

                    val changed = when (outcome) {
                        ContactsSync.MembersOutcome.NotModified -> stored.lists[position].copy(version = info.version)
                        is ContactsSync.MembersOutcome.Members -> stored.lists[position].copy(memberIds = outcome.ids, version = outcome.version, etag = outcome.etag)
                    }

                    stored.copy(lists = stored.lists.toMutableList().also { it[position] = changed })
                }
            } catch (error: ApiException.Unauthorized) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // One list that cannot be fetched does not stop the others; the next sync tries again.
                logger.notice("A contact list could not be fetched: ${error.javaClass.simpleName}")
            }
        }

        commit(accountId)
    }

    // MARK: Choices

    suspend fun setAddressBookEnabled(enabled: Boolean, accountId: String) {
        update(accountId) { it.copy(isEnabled = enabled) }
        commit(accountId)

        if (enabled) sync(accountId, force = true)
    }

    /** A list that is switched off is hidden and not downloaded; its members are dropped from the phone until it is switched on again. */
    suspend fun setListEnabled(enabled: Boolean, listId: String, accountId: String) {
        update(accountId) { stored ->
            val position = stored.lists.indexOfFirst { it.id == listId }

            if (position < 0) return@update stored

            val list = stored.lists[position].copy(isEnabled = enabled)
            val changed = if (enabled) list else list.copy(memberIds = emptyList(), etag = null)

            stored.copy(lists = stored.lists.toMutableList().also { it[position] = changed })
        }
        commit(accountId)

        if (enabled) sync(accountId, force = true)
    }

    /**
     * Switch the phone's own contacts on (the screen asks the system for permission first; Android needs an activity for that) or off.
     * Returns `false` when they cannot be used (permission refused).
     */
    suspend fun setDeviceContactsEnabled(enabled: Boolean): Boolean {
        if (!enabled) {
            settings.usesDeviceContacts = false
            _usesDeviceContacts.value = false
            deviceEntries = emptyList()
            scheduleRebuild()

            return true
        }

        if (device.access == DeviceContactsAccess.NOT_DETERMINED) device.requestAccess()

        _deviceAccess.value = device.access

        if (_deviceAccess.value != DeviceContactsAccess.AUTHORIZED) {
            settings.usesDeviceContacts = false
            _usesDeviceContacts.value = false

            return false
        }

        settings.usesDeviceContacts = true
        _usesDeviceContacts.value = true
        refreshDeviceContacts()

        return true
    }

    /** The screens refused the system question for good (or the user took the permission away): show the "open settings" state. */
    fun markDeviceAccessDenied() {
        _deviceAccess.value = DeviceContactsAccess.DENIED
        settings.usesDeviceContacts = false
        _usesDeviceContacts.value = false
        deviceEntries = emptyList()
        scheduleRebuild()
    }

    /**
     * The "use the phone's contacts" switch was changed elsewhere (the settings screen): follow it. Cheap when nothing changed.
     */
    suspend fun deviceSettingChanged() {
        val wanted = settings.usesDeviceContacts && device.access == DeviceContactsAccess.AUTHORIZED

        _deviceAccess.value = device.access

        if (wanted == _usesDeviceContacts.value) return

        _usesDeviceContacts.value = wanted

        if (wanted) refreshDeviceContacts() else {
            deviceEntries = emptyList()
            scheduleRebuild()
        }
    }

    /** Read the phone's contacts again (at launch, when the app comes to the front). */
    suspend fun refreshDeviceContacts() {
        _deviceAccess.value = device.access

        if (!_usesDeviceContacts.value || _deviceAccess.value != DeviceContactsAccess.AUTHORIZED) {
            if (deviceEntries.isNotEmpty()) {
                deviceEntries = emptyList()
                scheduleRebuild()
            }

            return
        }

        try {
            deviceEntries = device.fetch()
            rebuild()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.notice("The phone's contacts could not be read: ${error.javaClass.simpleName}")
        }
    }

    // MARK: Changes

    /** One contact in full (notes and lists); also refreshes the stored copy. */
    suspend fun detail(accountId: String, contactId: String): ContactDetail {
        val detail = requireApi(accountId).contact(contactId)
        upsert(detail, accountId)

        return detail
    }

    suspend fun create(draft: ContactDraft, accountId: String): ContactDetail {
        val detail = requireApi(accountId).createContact(draft.createRequest())
        upsert(detail, accountId)

        return detail
    }

    /** `ApiException.Stale` = the contact changed in the meantime; the caller reloads it with [detail] and lets the user try again. */
    suspend fun update(draft: ContactDraft, contactId: String, expectedUpdatedAt: String, accountId: String): ContactDetail {
        val response = requireApi(accountId).updateContact(contactId, draft.updateRequest(expectedUpdatedAt))
        upsert(response.contact, accountId)

        return response.contact
    }

    /** Admin pairings only (`ApiException.Forbidden` otherwise). */
    suspend fun delete(contactId: String, accountId: String) {
        requireApi(accountId).deleteContact(contactId)

        update(accountId) { stored ->
            stored.copy(
                contacts = stored.contacts.filter { it.id != contactId },
                lists = stored.lists.map { list -> list.copy(memberIds = list.memberIds.filter { it != contactId }) },
            )
        }
        commit(accountId)
    }

    /** The timeline of a contact of one account's address book. */
    suspend fun timeline(accountId: String, contactId: String, cursor: String?, limit: Int) = requireApi(accountId).contactTimeline(contactId, cursor, limit)

    private fun requireApi(accountId: String): ContactsApi {
        if (accountId !in knownAccountIds) throw ApiException.MissingDeviceToken()

        return api(accountId) ?: throw ApiException.MissingDeviceToken()
    }

    private fun upsert(detail: ContactDetail, accountId: String) {
        val stored = StoredContact.of(detail.contact)
        val before = data[accountId]

        update(accountId) { account ->
            val contacts = account.contacts.toMutableList()
            val position = contacts.indexOfFirst { it.id == stored.id }

            if (position >= 0) contacts[position] = stored else contacts += stored

            // The detail knows which lists the contact is in; keep the downloaded members of the enabled lists in step.
            val lists = account.lists.map { list ->
                if (!list.isEnabled) return@map list

                val members = list.memberIds.filter { it != stored.id }

                list.copy(memberIds = if (list.id in detail.listIds) members + stored.id else members)
            }

            account.copy(contacts = contacts, lists = lists)
        }

        // Opening a contact fetches it again; nothing to store, nothing to redraw when it is unchanged.
        if (data[accountId] != before) commit(accountId)
    }

    // MARK: Bookkeeping

    private fun update(accountId: String, change: (AccountContactsData) -> AccountContactsData) {
        // An account that was removed while a request was under way must not come back.
        if (accountId !in knownAccountIds) return

        data[accountId] = change(data[accountId] ?: AccountContactsData())
    }

    private fun commit(accountId: String) {
        val stored = data[accountId] ?: return
        val sequence = saveSequence.incrementAndGet()

        // Written one at a time; a write that was overtaken by a newer one is dropped.
        val job = scope.launch(workDispatcher) {
            persistLock.withLock {
                if (sequence < (latestSaved[accountId] ?: 0)) return@withLock

                latestSaved[accountId] = sequence

                try {
                    store.save(stored, accountId)
                } catch (error: Exception) {
                    logger.error("Contacts could not be stored: ${error.javaClass.simpleName}")
                }
            }
        }

        synchronized(persistJobs) {
            persistJobs.removeAll { it.isCompleted }
            persistJobs += job
        }

        refreshStates()
        scheduleRebuild()
    }

    private fun refreshStates() {
        val states = HashMap<String, ContactsAccountState>()

        for (id in knownAccountIds) {
            val stored = data[id] ?: continue

            states[id] = ContactsAccountState(
                isEnabled = stored.isEnabled,
                canWrite = stored.canWrite,
                canDelete = stored.canDelete,
                lists = stored.lists,
                contactCount = stored.contacts.size,
                lastSyncedAtMillis = stored.lastSyncedAtMillis,
                lastSyncFailed = id in failed,
            )
        }

        _accountStates.value = states
        _revision.value += 1
    }

    private fun scheduleRebuild() {
        rebuildJob = scope.launch { rebuild() }
    }

    /** Rebuild the combined list and the number index off the main thread. */
    private suspend fun rebuild() {
        generation += 1
        val mine = generation
        val input = BuildInput(knownAccountIds.mapNotNull { id -> data[id]?.let { id to it } }, HashMap(internalContacts), deviceEntries)
        val result = withContext(workDispatcher) { build(input) }

        // A newer rebuild started while this one worked: its result is the one that counts.
        if (mine != generation) return

        _entries.value = result.first
        index = result.second
        _revision.value += 1
    }

    /** Wait until the list and the index reflect every change made so far (tests). */
    suspend fun settle() {
        rebuildJob?.join()
        rebuild()
        val pending = synchronized(persistJobs) { persistJobs.toList() }
        joinAll(*pending.toTypedArray())
    }

    /** Suspends until the stored data of the accounts has been read. */
    suspend fun awaitLoaded() {
        _hasLoaded.first { it }
    }

    class BuildInput(val accounts: List<Pair<String, AccountContactsData>>, val internalContacts: Map<String, List<InternalContact>>, val device: List<ContactEntry>)

    companion object {
        /** How long the rights from the app's `/me` count for a sync. */
        const val CAPABILITIES_FRESHNESS_MILLIS = 30_000L

        fun build(input: BuildInput): Pair<List<ContactEntry>, ContactIndex> {
            val customer = LinkedHashMap<String, ContactEntry>()

            for ((accountId, account) in input.accounts) {
                if (!account.isEnabled || !account.canRead) continue

                for (contact in account.contacts) {
                    val existing = customer[contact.id]

                    if (existing != null) {
                        if (accountId !in existing.accountIds) customer[contact.id] = existing.copy(accountIds = existing.accountIds + accountId)
                        continue
                    }

                    customer[contact.id] = ContactEntry(
                        id = "customer:${contact.id}",
                        name = contact.name,
                        company = contact.company,
                        email = contact.email,
                        phones = contact.phones.map { ContactPhoneEntry(it.number, it.phoneLabel, it.isPrimary) },
                        source = ContactSource.Customer,
                        accountIds = listOf(accountId),
                        contactId = contact.id,
                        updatedAt = contact.updatedAt,
                    )
                }
            }

            val internalEntries = ArrayList<ContactEntry>()
            val seenInternal = HashSet<String>()
            val accountIds = input.accounts.map { it.first }.toSet()

            for ((accountId, contacts) in input.internalContacts.entries.sortedBy { it.key }) {
                if (accountId !in accountIds) continue

                for (contact in contacts) {
                    if (seenInternal.add("${contact.number}|${contact.name}")) {
                        internalEntries += ContactEntry.withNumbers("internal:$accountId:${contact.number}", contact.name, listOf(contact.number), ContactSource.InternalExtensions)
                    }
                }
            }

            val customerEntries = customer.values.sortedWith { a, b -> ContactText.nameOrder.compare(a.displayName, b.displayName) }
            val all = customerEntries + input.device + internalEntries

            return all to ContactIndex(all)
        }
    }
}
