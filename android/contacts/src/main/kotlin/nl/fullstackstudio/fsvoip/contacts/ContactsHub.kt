// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Keeps the address books of the paired accounts and the colleagues together, syncs them with the server and answers "who is this
// number". Everything that touches a file or builds the number index runs off the main thread.

package nl.fullstackstudio.fsvoip.contacts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.FsLogger
import nl.fullstackstudio.fsvoip.core.InternalContact

/** What the screens need to know about one account's address book. */
data class ContactsAccountState(
    val lists: List<StoredContactList>,
    val contactCount: Int,
    val lastSyncedAtMillis: Long?,
    /** The last attempt to sync failed (offline, server trouble); what is shown is the last known state. */
    val lastSyncFailed: Boolean,
)

enum class ContactsSyncOutcome {
    SYNCED,

    /** Not needed (too soon after the previous sync, another sync is running, or this address book may not be read). */
    SKIPPED,

    /** The pairing is revoked on the server; the app removes the account. */
    REVOKED,
    FAILED,
}

class ContactsHub(
    private val store: ContactsFileStoring,
    private val api: (accountId: String) -> ContactsApi?,
    private val now: () -> Long = System::currentTimeMillis,
    private val minimumIntervalMs: Long = 60_000,
    private val logger: FsLogger = FsLogger("contacts"),
) {
    private val _entries = MutableStateFlow<List<ContactEntry>>(emptyList())
    private val _states = MutableStateFlow<Map<String, ContactsAccountState>>(emptyMap())
    private val _syncing = MutableStateFlow<Set<String>>(emptySet())
    private val _hasLoaded = MutableStateFlow(false)

    /** The address book of the customer (all accounts), then the colleagues, in the order names are looked up. */
    val entries: StateFlow<List<ContactEntry>> = _entries.asStateFlow()
    val accountStates: StateFlow<Map<String, ContactsAccountState>> = _states.asStateFlow()
    val syncingAccountIds: StateFlow<Set<String>> = _syncing.asStateFlow()

    /** The stored data has been read (a first lookup before that finds nothing). */
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    /** Called when a request says the pairing is revoked (`401`); the app model removes the account. */
    var onUnauthorized: (String) -> Unit = {}

    private val lock = Mutex()
    private var accountIds: List<String> = emptyList()
    private val data = HashMap<String, AccountContactsData>()
    private val internal = HashMap<String, List<InternalContact>>()
    private val failed = HashSet<String>()
    private val lastAttempt = HashMap<String, Long>()

    @Volatile
    private var index: ContactIndex = ContactIndex.EMPTY

    // MARK: Accounts

    /** The accounts that are paired now; the data of an account that is gone is removed. Reads the stored data of new accounts. */
    suspend fun setAccounts(ids: List<String>) {
        lock.withLock {
            val removed = accountIds - ids.toSet()
            accountIds = ids

            withContext(Dispatchers.IO) {
                for (id in removed) {
                    store.remove(id)
                }

                for (id in ids) {
                    if (id !in data) {
                        data[id] = store.load(id) ?: AccountContactsData()
                    }
                }
            }

            for (id in removed) {
                data.remove(id)
                internal.remove(id)
                failed.remove(id)
                lastAttempt.remove(id)
            }

            _hasLoaded.value = true
            publish()
        }
    }

    /** The colleagues of an account (from `GET /me`). */
    suspend fun setInternal(accountId: String, contacts: List<InternalContact>) {
        lock.withLock {
            if (internal[accountId] == contacts) return

            internal[accountId] = contacts
            publish()
        }
    }

    // MARK: Looking up

    /** The name for a number, from the address book of the customer and the colleagues; one map lookup, safe on any thread. */
    fun name(number: String): String? = index.name(number)

    fun entry(number: String): ContactEntry? = index.entry(number)

    fun contact(accountId: String, contactId: String): StoredContact? = data[accountId]?.contacts?.firstOrNull { it.id == contactId }

    /** The contact ids of an enabled list. */
    fun members(accountId: String, listId: String): Set<String> =
        data[accountId]?.lists?.firstOrNull { it.id == listId && it.isEnabled }?.memberIds?.toSet().orEmpty()

    // MARK: Syncing

    /**
     * Syncs the address book of one account: the contacts (incremental, or complete the first time), then the lists whose version
     * changed. [canRead] is `capabilities.contacts.read`; without it nothing is asked.
     */
    suspend fun sync(accountId: String, canRead: Boolean = true, force: Boolean = false): ContactsSyncOutcome {
        val client = api(accountId)

        if (!canRead || client == null) return ContactsSyncOutcome.SKIPPED

        val current = lock.withLock {
            if (accountId in _syncing.value || (!force && now() - (lastAttempt[accountId] ?: 0) < minimumIntervalMs)) {
                return ContactsSyncOutcome.SKIPPED
            }

            lastAttempt[accountId] = now()
            _syncing.value = _syncing.value + accountId
            data[accountId] ?: AccountContactsData()
        }

        try {
            val result = ContactsSync.run(client, current.since)
            var updated = current.copy(
                contacts = ContactsSync.apply(result, current.contacts),
                since = result.serverTime,
                lastSyncedAtMillis = now(),
            )
            var listsFailed = false

            // The lists are a second step: a failure there keeps the contacts that just came in.
            try {
                updated = syncLists(client, updated)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException.Unauthorized) {
                throw error
            } catch (error: Exception) {
                logger.notice("Contact lists could not be synced: ${error.javaClass.simpleName}")
                listsFailed = true
            }

            lock.withLock {
                // The account was unpaired while this ran: nothing of it is written back (not to memory, not to disk).
                if (accountId !in accountIds) return ContactsSyncOutcome.SKIPPED

                withContext(Dispatchers.IO) { store.save(accountId, updated) }
                data[accountId] = updated
                if (listsFailed) failed.add(accountId) else failed.remove(accountId)
                publish()
            }

            return ContactsSyncOutcome.SYNCED
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiException.Unauthorized) {
            onUnauthorized(accountId)

            return ContactsSyncOutcome.REVOKED
        } catch (error: Exception) {
            logger.notice("Contacts could not be synced: ${error.javaClass.simpleName}")

            lock.withLock {
                failed.add(accountId)
                publish()
            }

            return ContactsSyncOutcome.FAILED
        } finally {
            lock.withLock { _syncing.value = _syncing.value - accountId }
        }
    }

    private suspend fun syncLists(client: ContactsApi, current: AccountContactsData): AccountContactsData {
        val server = client.contactLists().lists
        var lists = ContactsSync.mergeLists(server, current.lists)
        val versions = server.associate { it.id to it.version }

        lists = lists.map { list ->
            val serverVersion = versions[list.id] ?: return@map list

            if (!ContactsSync.needsSnapshot(list, serverVersion)) return@map list

            when (val outcome = ContactsSync.fetchMembers(client, list)) {
                ContactsSync.MembersOutcome.NotModified -> list.copy(version = serverVersion)
                is ContactsSync.MembersOutcome.Members -> list.copy(memberIds = outcome.ids, version = outcome.version, etag = outcome.etag)
            }
        }

        return current.copy(lists = lists)
    }

    /** Switch a list on or off on this phone. A list that is off is not downloaded and not offered in the filter. */
    suspend fun setListEnabled(accountId: String, listId: String, enabled: Boolean) {
        lock.withLock {
            val current = data[accountId] ?: return
            if (accountId !in accountIds) return
            val next = current.copy(lists = current.lists.map { if (it.id == listId) it.copy(isEnabled = enabled) else it })
            data[accountId] = next
            publish()
            withContext(Dispatchers.IO) { store.save(accountId, next) }
        }
    }

    // MARK: Changes

    /** One contact in full (notes and lists); the stored copy is refreshed with it. */
    suspend fun detail(accountId: String, contactId: String): ContactWrite = write(accountId) { client ->
        client.contact(contactId).also { upsert(accountId, it) }
    }

    suspend fun create(draft: ContactDraft, accountId: String): ContactWrite = write(accountId) { client ->
        client.createContact(draft.createRequest()).also { upsert(accountId, it) }
    }

    /** [ContactFailure.STALE]: the contact changed in the meantime; the caller reloads it with [detail] and lets the user try again. */
    suspend fun update(draft: ContactDraft, contactId: String, expectedUpdatedAt: String, accountId: String): ContactWrite = write(accountId) { client ->
        client.updateContact(contactId, draft.updateRequest(expectedUpdatedAt)).also { upsert(accountId, it) }
    }

    /** Administrator pairings only ([ContactFailure.FORBIDDEN] otherwise). */
    suspend fun delete(contactId: String, accountId: String): ContactFailure? {
        val client = api(accountId) ?: return ContactFailure.OTHER

        return try {
            client.deleteContact(contactId)

            lock.withLock {
                val current = data[accountId] ?: return null
                if (accountId !in accountIds) return null
                val next = current.copy(
                    contacts = current.contacts.filterNot { it.id == contactId },
                    lists = current.lists.map { list -> list.copy(memberIds = list.memberIds - contactId) },
                )
                data[accountId] = next
                publish()
                withContext(Dispatchers.IO) { store.save(accountId, next) }
            }

            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            changeFailed(accountId, error)
        }
    }

    private suspend fun write(accountId: String, action: suspend (ContactsApi) -> nl.fullstackstudio.fsvoip.core.Contact): ContactWrite {
        val client = api(accountId) ?: return ContactWrite.Failed(ContactFailure.OTHER)

        return try {
            ContactWrite.Done(action(client))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ContactWrite.Failed(changeFailed(accountId, error))
        }
    }

    private fun changeFailed(accountId: String, error: Exception): ContactFailure {
        val failure = ContactFailure.of(error)

        logger.notice("A contact change failed: ${failure.name}")
        if (failure == ContactFailure.REVOKED) onUnauthorized(accountId)

        return failure
    }

    /** Puts a contact the server just gave us into the stored copy (and its list memberships, when the answer carries them). */
    private suspend fun upsert(accountId: String, contact: nl.fullstackstudio.fsvoip.core.Contact) {
        lock.withLock {
            // An account that is no longer paired is not written again (the data and file went with setAccounts).
            if (accountId !in accountIds) return

            val current = data[accountId] ?: AccountContactsData()
            val stored = StoredContact(contact)
            val contacts = if (current.contacts.any { it.id == contact.id }) current.contacts.map { if (it.id == contact.id) stored else it } else current.contacts + stored
            // The detail carries `listIds`: the memberships follow it.
            val lists = current.lists.map { list ->
                when {
                    list.id in contact.listIds && contact.id !in list.memberIds -> list.copy(memberIds = list.memberIds + contact.id)
                    list.id !in contact.listIds && contact.id in list.memberIds -> list.copy(memberIds = list.memberIds - contact.id)
                    else -> list
                }
            }
            val next = current.copy(contacts = contacts, lists = lists)
            data[accountId] = next
            publish()
            withContext(Dispatchers.IO) { store.save(accountId, next) }
        }
    }

    // MARK: Publishing

    /** Rebuilds the entries and the number index from what is in memory. Called under [lock]. */
    private suspend fun publish() {
        val entries = withContext(Dispatchers.Default) {
            val customer = accountIds.flatMap { id -> data[id]?.contacts.orEmpty().map { toEntry(id, it) } }
            val colleagues = accountIds.flatMap { id -> InternalContactsProvider(internal[id].orEmpty()).entries }

            customer + colleagues
        }

        index = withContext(Dispatchers.Default) { ContactIndex(entries) }
        _entries.value = entries
        _states.value = accountIds.associateWith { id ->
            val stored = data[id] ?: AccountContactsData()

            ContactsAccountState(stored.lists, stored.contacts.size, stored.lastSyncedAtMillis, lastSyncFailed = id in failed)
        }
    }

    private fun toEntry(accountId: String, contact: StoredContact) = ContactEntry(
        id = "$accountId/${contact.id}",
        name = contact.displayName,
        company = contact.company?.takeIf { it.isNotBlank() && it != contact.displayName },
        phones = contact.phones.map { ContactEntryPhone(it.number, it.label) },
        source = ContactSource.AddressBook,
        email = contact.email,
        accountId = accountId,
    )
}
