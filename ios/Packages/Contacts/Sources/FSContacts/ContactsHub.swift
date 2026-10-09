// SPDX-License-Identifier: AGPL-3.0-or-later
import Combine
import Core
import Foundation

/// Where the "use the phone's contacts" choice is kept.
public protocol ContactsSettingsStoring: AnyObject, Sendable {
    var usesDeviceContacts: Bool { get set }
}

public final class UserDefaultsContactsSettings: ContactsSettingsStoring, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key = "fsvoip.contacts.use-device"

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public var usesDeviceContacts: Bool {
        get { defaults.bool(forKey: key) }
        set { defaults.set(newValue, forKey: key) }
    }
}

public final class InMemoryContactsSettings: ContactsSettingsStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var value = false

    public init() {}

    public var usesDeviceContacts: Bool {
        get { lock.withLock { value } }
        set { lock.withLock { value = newValue } }
    }
}

/// What the screens need to know about one account's address book.
public struct ContactsAccountState: Equatable, Sendable {
    public var isEnabled: Bool
    public var canWrite: Bool
    public var canDelete: Bool
    public var lists: [StoredContactList]
    public var contactCount: Int
    public var lastSyncedAt: Date?
    /// The last attempt to sync failed (offline, server trouble); what is shown is the last known state.
    public var lastSyncFailed: Bool
}

public enum ContactsSyncOutcome: Equatable, Sendable {
    case synced
    /// Not needed (too soon after the previous sync, another sync is running, or this address book is switched off).
    case skipped
    /// The pairing is revoked on the server; the app removes the account.
    case revoked
    case failed
}

/// Keeps the address books of the paired accounts, the phone's contacts and the colleagues together and answers "who is this number".
///
/// Everything here runs on the main actor; reading and writing files and building the number index happen off it.
@MainActor
public final class ContactsHub: ObservableObject {
    @Published public private(set) var entries: [ContactEntry] = []
    @Published public private(set) var accountStates: [String: ContactsAccountState] = [:]
    @Published public private(set) var syncingAccountIds: Set<String> = []
    @Published public private(set) var deviceAccess: DeviceContactsAccess
    @Published public private(set) var usesDeviceContacts: Bool
    /// The stored data has been read (a first lookup before that finds nothing).
    @Published public private(set) var hasLoaded = false
    /// Goes up whenever `entries` or a list's members changed; screens that derive something from them watch this one number.
    @Published public private(set) var revision = 0

    /// Called when a request says the pairing is revoked (`401`); the app model removes the account.
    public var onUnauthorized: (String) -> Void = { _ in }

    private let store: ContactsFileStoring
    private let apiFactory: @Sendable (String) -> ContactsAPI?
    private let device: DeviceContactsReading
    private let settings: ContactsSettingsStoring
    private let now: @Sendable () -> Date
    private let minimumInterval: TimeInterval
    private let logger: FSLogger
    private let persister: ContactsPersister

    private var knownAccountIds: [String] = []
    private var data: [String: AccountContactsData] = [:]
    private var failed: Set<String> = []
    private var lastAttempt: [String: Date] = [:]
    private var internalContacts: [String: [InternalContact]] = [:]
    /// What the app's own `GET /me` said a moment ago (see `apply(capabilities:accountId:)`).
    private var knownCapabilities: [String: (value: ContactCapabilities, at: Date)] = [:]
    private var deviceEntries: [ContactEntry] = []
    private var index: ContactIndex = .empty
    private var generation = 0
    private var saveSequence = 0
    private var rebuildTask: Task<Void, Never>?

    public init(
        store: ContactsFileStoring = InMemoryContactsStore(),
        api: @escaping @Sendable (String) -> ContactsAPI? = { _ in nil },
        device: DeviceContactsReading = NoDeviceContacts(),
        settings: ContactsSettingsStoring = InMemoryContactsSettings(),
        now: @escaping @Sendable () -> Date = { Date() },
        minimumInterval: TimeInterval = 60,
        logger: FSLogger = FSLogger(category: "contacts")
    ) {
        self.store = store
        apiFactory = api
        self.device = device
        self.settings = settings
        self.now = now
        self.minimumInterval = minimumInterval
        self.logger = logger
        persister = ContactsPersister(store: store, logger: logger)
        deviceAccess = device.access
        usesDeviceContacts = settings.usesDeviceContacts && device.access == .authorized
    }

    // MARK: Accounts

    /// Tell the hub which accounts are paired. Data of an account that is gone is deleted; data of new accounts is read from disk.
    public func configure(accountIds: [String]) async {
        // An account that dropped out of the list is forgotten in memory only. Deleting its file is `forget(accountId:)`'s job, called when
        // the account is really unpaired: a list that is empty because the Keychain could not be read must not wipe the stored contacts.
        for id in knownAccountIds where !accountIds.contains(id) {
            data[id] = nil
            internalContacts[id] = nil
        }

        knownAccountIds = accountIds

        // Files of accounts that are no longer paired (an app that was reinstalled, a crash halfway through unpairing).
        if !accountIds.isEmpty {
            let stems = Set(accountIds.map(ContactsFileStore.fileNameStem(for:)))

            for orphan in store.storedAccountIds() where !stems.contains(orphan) {
                store.remove(accountId: orphan)
            }
        }

        let toLoad = accountIds.filter { data[$0] == nil }

        if !toLoad.isEmpty {
            let store = store
            let loaded = await Task.detached(priority: .userInitiated) {
                toLoad.map { ($0, store.load(accountId: $0)) }
            }.value

            for (id, stored) in loaded where knownAccountIds.contains(id) && data[id] == nil {
                data[id] = stored ?? AccountContactsData()
            }
        }

        hasLoaded = true
        refreshStates()
        await rebuild()

        if usesDeviceContacts {
            await refreshDeviceContacts()
        }
    }

    /// Remove everything of one account (it was unpaired or revoked).
    public func forget(accountId: String) {
        store.remove(accountId: accountId)
        data[accountId] = nil
        failed.remove(accountId)
        lastAttempt[accountId] = nil
        internalContacts[accountId] = nil
        knownCapabilities[accountId] = nil
        knownAccountIds.removeAll { $0 == accountId }
        refreshStates()
        scheduleRebuild()
    }

    public func setInternalContacts(_ contacts: [InternalContact], accountId: String) {
        guard internalContacts[accountId] != contacts else {
            return
        }

        internalContacts[accountId] = contacts
        scheduleRebuild()
    }

    /// The rights of this pairing from the `GET /me` the app just did for the account. A sync that follows within `capabilitiesFreshness`
    /// uses them instead of asking `/me` a second time; a later sync (the timer) asks for itself.
    public func apply(capabilities: ContactCapabilities, accountId: String) {
        knownCapabilities[accountId] = (capabilities, now())

        // Only when the stored data is already loaded: this must never create an empty book in front of the one on disk.
        guard data[accountId] != nil else {
            return
        }

        update(accountId) {
            $0.canRead = capabilities.read
            $0.canWrite = capabilities.write
            $0.canDelete = capabilities.delete
        }
        refreshStates()
    }

    private func currentCapabilities(accountId: String, api: ContactsAPI) async throws -> ContactCapabilities {
        if let known = knownCapabilities[accountId], now().timeIntervalSince(known.at) < Self.capabilitiesFreshness {
            return known.value
        }

        return try await api.contactCapabilities()
    }

    /// How long the rights from the app's `/me` count for a sync.
    static let capabilitiesFreshness: TimeInterval = 30

    // MARK: Lookup

    public func entry(forNumber number: String) -> ContactEntry? {
        index.entry(forNumber: number)
    }

    public func name(forNumber number: String) -> String? {
        index.name(forNumber: number)
    }

    public func state(for accountId: String) -> ContactsAccountState? {
        accountStates[accountId]
    }

    public func canWrite(_ entry: ContactEntry) -> Bool {
        entry.source == .customer && entry.accountIds.contains { accountStates[$0]?.canWrite == true }
    }

    public func canDelete(_ entry: ContactEntry) -> Bool {
        entry.source == .customer && entry.accountIds.contains { accountStates[$0]?.canDelete == true }
    }

    /// The account to write a contact to / edit it through.
    public func writeAccountId(for entry: ContactEntry) -> String? {
        entry.accountIds.first { accountStates[$0]?.canWrite == true }
    }

    /// The accounts that may add a contact.
    public var writableAccountIds: [String] {
        knownAccountIds.filter { accountStates[$0]?.isEnabled == true && accountStates[$0]?.canWrite == true }
    }

    /// The ids of the contacts in a list (empty for a list that is off or not downloaded yet).
    public func memberIds(listId: String, accountId: String) -> Set<String> {
        Set(data[accountId]?.lists.first { $0.id == listId }?.memberIds ?? [])
    }

    // MARK: Sync

    public func syncAll(force: Bool = false) async {
        for id in knownAccountIds {
            _ = await sync(accountId: id, force: force)
        }
    }

    @discardableResult
    public func sync(accountId: String, force: Bool = false) async -> ContactsSyncOutcome {
        guard knownAccountIds.contains(accountId), data[accountId]?.isEnabled == true, let api = apiFactory(accountId) else {
            return .skipped
        }

        if syncingAccountIds.contains(accountId) {
            return .skipped
        }

        if !force, let last = lastAttempt[accountId], now().timeIntervalSince(last) < minimumInterval {
            return .skipped
        }

        syncingAccountIds.insert(accountId)
        lastAttempt[accountId] = now()
        defer { syncingAccountIds.remove(accountId) }

        do {
            let capabilities = try await currentCapabilities(accountId: accountId, api: api)
            update(accountId) {
                $0.canRead = capabilities.read
                $0.canWrite = capabilities.write
                $0.canDelete = capabilities.delete
            }

            if capabilities.read {
                let result: ContactsSyncResult

                do {
                    result = try await api.syncAddressBook(since: data[accountId]?.serverTime)
                } catch APIError.resync {
                    result = try await api.syncAddressBook(since: nil)
                }

                let stamp = now()
                update(accountId) {
                    $0.contacts = ContactsSync.apply(result, to: $0.contacts)
                    // Stored only now, after the LAST page: a run that failed halfway keeps the old `since`.
                    $0.serverTime = result.serverTime
                    $0.lastSyncedAt = stamp
                }
            }

            failed.remove(accountId)
            commit(accountId)

            if capabilities.read {
                try await syncLists(accountId: accountId, api: api)
            }

            return .synced
        } catch APIError.unauthorized {
            onUnauthorized(accountId)

            return .revoked
        } catch {
            logger.notice("Contacts sync of an account failed: \(error)")
            failed.insert(accountId)
            refreshStates()

            return .failed
        }
    }

    private func syncLists(accountId: String, api: ContactsAPI) async throws {
        let infos = try await api.contactLists()

        update(accountId) { $0.lists = ContactsSync.mergeLists(infos, into: $0.lists) }

        for info in infos {
            guard let list = data[accountId]?.lists.first(where: { $0.id == info.id }), ContactsSync.needsSnapshot(list, serverVersion: info.version) else {
                continue
            }

            do {
                let outcome = try await ContactsSync.fetchMembers(api: api, list: list)

                update(accountId) { stored in
                    guard let position = stored.lists.firstIndex(where: { $0.id == info.id }) else {
                        return
                    }

                    switch outcome {
                    case .notModified:
                        stored.lists[position].version = info.version
                    case let .members(ids, version, etag):
                        stored.lists[position].memberIds = ids
                        stored.lists[position].version = version
                        stored.lists[position].etag = etag
                    }
                }
            } catch APIError.unauthorized {
                throw APIError.unauthorized
            } catch {
                // One list that cannot be fetched does not stop the others; the next sync tries again.
                logger.notice("A contact list could not be fetched: \(error)")
            }
        }

        commit(accountId)
    }

    // MARK: Choices

    public func setAddressBookEnabled(_ enabled: Bool, accountId: String) async {
        update(accountId) { $0.isEnabled = enabled }
        commit(accountId)

        if enabled {
            await sync(accountId: accountId, force: true)
        }
    }

    /// A list that is switched off is hidden and not downloaded; its members are dropped from the phone until it is switched on again.
    public func setListEnabled(_ enabled: Bool, listId: String, accountId: String) async {
        update(accountId) { stored in
            guard let position = stored.lists.firstIndex(where: { $0.id == listId }) else {
                return
            }

            stored.lists[position].isEnabled = enabled

            if !enabled {
                stored.lists[position].memberIds = []
                stored.lists[position].etag = nil
            }
        }
        commit(accountId)

        if enabled {
            await sync(accountId: accountId, force: true)
        }
    }

    /// Switch the phone's own contacts on (asks the system for permission the first time) or off.
    /// Returns `false` when they cannot be used (permission refused).
    @discardableResult
    public func setDeviceContactsEnabled(_ enabled: Bool) async -> Bool {
        if !enabled {
            settings.usesDeviceContacts = false
            usesDeviceContacts = false
            deviceEntries = []
            scheduleRebuild()

            return true
        }

        if device.access == .notDetermined {
            _ = await device.requestAccess()
        }

        deviceAccess = device.access

        guard deviceAccess == .authorized else {
            settings.usesDeviceContacts = false
            usesDeviceContacts = false

            return false
        }

        settings.usesDeviceContacts = true
        usesDeviceContacts = true
        await refreshDeviceContacts()

        return true
    }

    /// Read the phone's contacts again (at launch, when the app comes to the front).
    public func refreshDeviceContacts() async {
        deviceAccess = device.access

        guard usesDeviceContacts, deviceAccess == .authorized else {
            if deviceEntries.isEmpty == false {
                deviceEntries = []
                scheduleRebuild()
            }

            return
        }

        do {
            deviceEntries = try await device.fetch()
            await rebuild()
        } catch {
            logger.notice("The phone's contacts could not be read: \(error)")
        }
    }

    // MARK: Changes

    /// One contact in full (notes and lists); also refreshes the stored copy.
    public func detail(accountId: String, contactId: String) async throws -> ContactDetail {
        let api = try requireAPI(accountId)
        let detail = try await api.contact(id: contactId)
        upsert(detail, accountId: accountId)

        return detail
    }

    @discardableResult
    public func create(_ draft: ContactDraft, accountId: String) async throws -> ContactDetail {
        let api = try requireAPI(accountId)
        let detail = try await api.createContact(draft.createRequest())
        upsert(detail, accountId: accountId)

        return detail
    }

    /// `APIError.stale` = the contact changed in the meantime; the caller reloads it with `detail` and lets the user try again.
    @discardableResult
    public func update(_ draft: ContactDraft, contactId: String, expectedUpdatedAt: String, accountId: String) async throws -> ContactDetail {
        let api = try requireAPI(accountId)
        let response = try await api.updateContact(id: contactId, draft.updateRequest(expectedUpdatedAt: expectedUpdatedAt))
        upsert(response.contact, accountId: accountId)

        return response.contact
    }

    /// Admin pairings only (`APIError.forbidden` otherwise).
    public func delete(contactId: String, accountId: String) async throws {
        let api = try requireAPI(accountId)
        _ = try await api.deleteContact(id: contactId)

        update(accountId) { stored in
            stored.contacts.removeAll { $0.id == contactId }

            for position in stored.lists.indices {
                stored.lists[position].memberIds.removeAll { $0 == contactId }
            }
        }
        commit(accountId)
    }

    private func requireAPI(_ accountId: String) throws -> ContactsAPI {
        guard knownAccountIds.contains(accountId), let api = apiFactory(accountId) else {
            throw APIError.missingDeviceToken
        }

        return api
    }

    private func upsert(_ detail: ContactDetail, accountId: String) {
        let stored = StoredContact(detail.contact)
        let before = data[accountId]

        update(accountId) { account in
            if let position = account.contacts.firstIndex(where: { $0.id == stored.id }) {
                account.contacts[position] = stored
            } else {
                account.contacts.append(stored)
            }

            // The detail knows which lists the contact is in; keep the downloaded members of the enabled lists in step.
            for position in account.lists.indices where account.lists[position].isEnabled {
                var members = account.lists[position].memberIds.filter { $0 != stored.id }

                if detail.listIds.contains(account.lists[position].id) {
                    members.append(stored.id)
                }

                account.lists[position].memberIds = members
            }
        }

        // Opening a contact fetches it again; nothing to store, nothing to redraw when it is unchanged.
        if data[accountId] != before {
            commit(accountId)
        }
    }

    // MARK: Bookkeeping

    private func update(_ accountId: String, _ change: (inout AccountContactsData) -> Void) {
        // An account that was removed while a request was under way must not come back.
        guard knownAccountIds.contains(accountId) else {
            return
        }

        var stored = data[accountId] ?? AccountContactsData()
        change(&stored)
        data[accountId] = stored
    }

    private func commit(_ accountId: String) {
        guard let stored = data[accountId] else {
            return
        }

        saveSequence += 1
        let sequence = saveSequence
        let persister = persister

        Task { await persister.save(stored, accountId: accountId, sequence: sequence) }

        refreshStates()
        scheduleRebuild()
    }

    private func refreshStates() {
        var states: [String: ContactsAccountState] = [:]

        for id in knownAccountIds {
            guard let stored = data[id] else {
                continue
            }

            states[id] = ContactsAccountState(
                isEnabled: stored.isEnabled,
                canWrite: stored.canWrite,
                canDelete: stored.canDelete,
                lists: stored.lists,
                contactCount: stored.contacts.count,
                lastSyncedAt: stored.lastSyncedAt,
                lastSyncFailed: failed.contains(id)
            )
        }

        accountStates = states
        revision += 1
    }

    private func scheduleRebuild() {
        rebuildTask = Task { await rebuild() }
    }

    /// Rebuild the combined list and the number index off the main actor.
    private func rebuild() async {
        generation += 1
        let mine = generation
        let input = BuildInput(accounts: knownAccountIds.compactMap { id in data[id].map { (id, $0) } }, internalContacts: internalContacts, device: deviceEntries)

        let result = await Task.detached(priority: .userInitiated) { Self.build(input) }.value

        guard mine == generation else {
            return
        }

        entries = result.entries
        index = result.index
        revision += 1
    }

    /// Wait until the list and the index reflect every change made so far (tests).
    public func settle() async {
        await rebuildTask?.value
        await rebuild()
    }

    struct BuildInput: Sendable {
        var accounts: [(String, AccountContactsData)]
        var internalContacts: [String: [InternalContact]]
        var device: [ContactEntry]
    }

    nonisolated static func build(_ input: BuildInput) -> (entries: [ContactEntry], index: ContactIndex) {
        var customer: [String: ContactEntry] = [:]
        var order: [String] = []

        for (accountId, account) in input.accounts where account.isEnabled && account.canRead {
            for contact in account.contacts {
                if var existing = customer[contact.id] {
                    if !existing.accountIds.contains(accountId) { existing.accountIds.append(accountId) }
                    customer[contact.id] = existing
                    continue
                }

                order.append(contact.id)
                customer[contact.id] = ContactEntry(
                    id: "customer:\(contact.id)",
                    name: contact.name,
                    company: contact.company,
                    email: contact.email,
                    phones: contact.phones.map { ContactPhoneEntry(number: $0.number, label: $0.phoneLabel, isPrimary: $0.isPrimary) },
                    source: .customer,
                    accountIds: [accountId],
                    contactId: contact.id,
                    updatedAt: contact.updatedAt
                )
            }
        }

        var internalEntries: [ContactEntry] = []
        var seenInternal = Set<String>()

        for (accountId, contacts) in input.internalContacts.sorted(by: { $0.key < $1.key }) where input.accounts.contains(where: { $0.0 == accountId }) {
            for contact in contacts where seenInternal.insert("\(contact.number)|\(contact.name)").inserted {
                internalEntries.append(ContactEntry(id: "internal:\(accountId):\(contact.number)", name: contact.name, numbers: [contact.number], source: .internalExtensions))
            }
        }

        let customerEntries = order.compactMap { customer[$0] }.sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
        let all = customerEntries + input.device + internalEntries

        return (all, ContactIndex(entries: all))
    }
}

/// Writes the files one at a time and drops a write that was overtaken by a newer one.
actor ContactsPersister {
    private let store: ContactsFileStoring
    private let logger: FSLogger
    private var latest: [String: Int] = [:]

    init(store: ContactsFileStoring, logger: FSLogger) {
        self.store = store
        self.logger = logger
    }

    func save(_ data: AccountContactsData, accountId: String, sequence: Int) {
        guard sequence >= latest[accountId] ?? 0 else {
            return
        }

        latest[accountId] = sequence

        do {
            try store.save(data, accountId: accountId)
        } catch {
            logger.error("Contacts could not be stored: \(error)")
        }
    }
}
