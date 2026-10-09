// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Local copy of the customer's address book, one file per paired account.
//
// - Lives in Application Support (not the Keychain: it can be tens of thousands of rows), protected with
//   `completeUntilFirstUserAuthentication` (a push that wakes the app after a reboot can still look up a caller's name once the phone
//   was unlocked once) and excluded from backups (an iCloud backup must not carry a customer's address book to a new phone; it syncs again).
// - The file is plain JSON of `AccountContactsData`. It never holds a token or password.
// - Removing an account removes its file.

import Foundation

/// One phone number of a stored contact. The label is kept as the raw server string so an unknown future label survives a round trip.
public struct StoredContactPhone: Codable, Equatable, Sendable {
    public var number: String
    public var label: String
    public var isPrimary: Bool

    public init(number: String, label: String = "other", isPrimary: Bool = false) {
        self.number = number
        self.label = label
        self.isPrimary = isPrimary
    }

    public init(_ phone: ContactPhone) {
        number = phone.number
        label = phone.label.isKnown ? phone.label.rawValue : ContactPhoneLabel.other.rawValue
        isPrimary = phone.isPrimary
    }

    /// The label as this app version understands it (`other` for anything unknown).
    public var phoneLabel: ContactPhoneLabel {
        ContactPhoneLabel(rawValue: label) ?? .other
    }
}

public struct StoredContact: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var firstName: String?
    public var lastName: String?
    public var company: String?
    public var email: String?
    public var phones: [StoredContactPhone]
    public var tags: [String]
    /// Opaque server timestamp; goes back as `expectedUpdatedAt`.
    public var updatedAt: String

    public init(
        id: String,
        name: String,
        firstName: String? = nil,
        lastName: String? = nil,
        company: String? = nil,
        email: String? = nil,
        phones: [StoredContactPhone] = [],
        tags: [String] = [],
        updatedAt: String
    ) {
        self.id = id
        self.name = name
        self.firstName = firstName
        self.lastName = lastName
        self.company = company
        self.email = email
        self.phones = phones
        self.tags = tags
        self.updatedAt = updatedAt
    }

    public init(_ contact: Contact) {
        self.init(
            id: contact.id,
            name: contact.name,
            firstName: contact.firstName,
            lastName: contact.lastName,
            company: contact.company,
            email: contact.email,
            phones: contact.phones.map(StoredContactPhone.init),
            tags: contact.tags,
            updatedAt: contact.updatedAt
        )
    }
}

/// A contact list as known on this phone: its version, which contacts are in it and whether the user wants it shown.
public struct StoredContactList: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var version: Int
    /// The ETag of the last snapshot we downloaded (`"3"`); `nil` = never downloaded.
    public var etag: String?
    /// Local choice (default on): a list that is off is neither downloaded nor offered as a filter.
    public var isEnabled: Bool
    /// Ids of the customer contacts in the list (from the snapshot).
    public var memberIds: [String]
    public var contactCount: Int

    public init(id: String, name: String, version: Int, etag: String? = nil, isEnabled: Bool = true, memberIds: [String] = [], contactCount: Int = 0) {
        self.id = id
        self.name = name
        self.version = version
        self.etag = etag
        self.isEnabled = isEnabled
        self.memberIds = memberIds
        self.contactCount = contactCount
    }
}

/// Everything stored for one account.
public struct AccountContactsData: Codable, Equatable, Sendable {
    public static let currentSchema = 1

    public var schema: Int
    /// The address book of this account is used (synced, shown, used for caller names). Default on.
    public var isEnabled: Bool
    /// `serverTime` of the last complete sync run: the next `since`. `nil` = never synced, or a full sync is due.
    public var serverTime: String?
    public var lastSyncedAt: Date?
    public var contacts: [StoredContact]
    public var lists: [StoredContactList]
    /// What the server allowed at the last sync (`GET /me`). Defaults are the restrictive ones for delete.
    public var canRead: Bool
    public var canWrite: Bool
    public var canDelete: Bool

    public init(
        isEnabled: Bool = true,
        serverTime: String? = nil,
        lastSyncedAt: Date? = nil,
        contacts: [StoredContact] = [],
        lists: [StoredContactList] = [],
        canRead: Bool = true,
        canWrite: Bool = true,
        canDelete: Bool = false
    ) {
        schema = Self.currentSchema
        self.isEnabled = isEnabled
        self.serverTime = serverTime
        self.lastSyncedAt = lastSyncedAt
        self.contacts = contacts
        self.lists = lists
        self.canRead = canRead
        self.canWrite = canWrite
        self.canDelete = canDelete
    }

    private enum CodingKeys: String, CodingKey {
        case schema, isEnabled, serverTime, lastSyncedAt, contacts, lists, canRead, canWrite, canDelete
    }

    /// Tolerant: a field added in a later app version is missing in an older file and takes its default.
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        schema = try container.decodeIfPresent(Int.self, forKey: .schema) ?? Self.currentSchema
        isEnabled = try container.decodeIfPresent(Bool.self, forKey: .isEnabled) ?? true
        serverTime = try container.decodeIfPresent(String.self, forKey: .serverTime)
        lastSyncedAt = try container.decodeIfPresent(Date.self, forKey: .lastSyncedAt)
        contacts = try container.decodeIfPresent([StoredContact].self, forKey: .contacts) ?? []
        lists = try container.decodeIfPresent([StoredContactList].self, forKey: .lists) ?? []
        canRead = try container.decodeIfPresent(Bool.self, forKey: .canRead) ?? true
        canWrite = try container.decodeIfPresent(Bool.self, forKey: .canWrite) ?? true
        canDelete = try container.decodeIfPresent(Bool.self, forKey: .canDelete) ?? false
    }
}

public protocol ContactsFileStoring: Sendable {
    /// `nil` = nothing stored (or an unreadable file: it is then treated as absent and a full sync follows).
    func load(accountId: String) -> AccountContactsData?
    func save(_ data: AccountContactsData, accountId: String) throws
    func remove(accountId: String)
    /// The account ids that have data.
    func storedAccountIds() -> [String]
}

/// JSON files in `directory` (default `Application Support/FSVoip/contacts`).
public struct ContactsFileStore: ContactsFileStoring {
    private let directory: URL
    private let logger: FSLogger

    public init(directory: URL? = nil, logger: FSLogger = FSLogger(category: "contacts-store")) {
        self.directory = directory ?? Self.defaultDirectory()
        self.logger = logger
    }

    public static func defaultDirectory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory

        return base.appendingPathComponent("FSVoip", isDirectory: true).appendingPathComponent("contacts", isDirectory: true)
    }

    /// Account ids are server ids; keep only characters that are safe in a file name.
    static func fileName(for accountId: String) -> String {
        let safe = accountId.unicodeScalars.map { scalar -> Character in
            let isSafe = (scalar.isASCII && CharacterSet.alphanumerics.contains(scalar)) || scalar == "-" || scalar == "_"

            return isSafe ? Character(scalar) : "_"
        }

        return String(safe) + ".json"
    }

    /// The file name without `.json`, as `storedAccountIds()` reports it.
    public static func fileNameStem(for accountId: String) -> String {
        String(fileName(for: accountId).dropLast(".json".count))
    }

    private func url(for accountId: String) -> URL {
        directory.appendingPathComponent(Self.fileName(for: accountId), isDirectory: false)
    }

    public func load(accountId: String) -> AccountContactsData? {
        guard let data = try? Data(contentsOf: url(for: accountId)) else {
            return nil
        }

        do {
            return try FSVoipJSON.decoder().decode(AccountContactsData.self, from: data)
        } catch {
            logger.error("Stored contacts of an account could not be read; a new sync will rebuild them")
            return nil
        }
    }

    public func save(_ data: AccountContactsData, accountId: String) throws {
        let manager = FileManager.default
        try manager.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        excludeFromBackup(directory)

        let target = url(for: accountId)
        let encoded = try FSVoipJSON.encoder().encode(data)

        #if os(iOS)
        try encoded.write(to: target, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try encoded.write(to: target, options: [.atomic])
        #endif

        excludeFromBackup(target)
    }

    public func remove(accountId: String) {
        try? FileManager.default.removeItem(at: url(for: accountId))
    }

    public func storedAccountIds() -> [String] {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []

        return files.filter { $0.pathExtension == "json" }.map { $0.deletingPathExtension().lastPathComponent }
    }

    private func excludeFromBackup(_ url: URL) {
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutable = url
        try? mutable.setResourceValues(values)
    }
}

/// In-memory store for tests and previews.
public final class InMemoryContactsStore: ContactsFileStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [String: AccountContactsData] = [:]

    public init() {}

    public func load(accountId: String) -> AccountContactsData? {
        lock.withLock { storage[accountId] }
    }

    public func save(_ data: AccountContactsData, accountId: String) throws {
        lock.withLock { storage[accountId] = data }
    }

    public func remove(accountId: String) {
        lock.withLock { storage[accountId] = nil }
    }

    public func storedAccountIds() -> [String] {
        lock.withLock { Array(storage.keys) }
    }
}
