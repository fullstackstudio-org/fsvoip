// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation
import Security

public enum SecretStoreError: Error, Equatable, Sendable {
    /// `OSStatus` of the failing Keychain call.
    case keychain(Int32)
    case corrupt
}

/// Minimal key/value store for secrets. The production implementation is the Keychain.
public protocol SecretStore: Sendable {
    func set(_ data: Data, for key: String) throws
    func get(_ key: String) throws -> Data?
    func remove(_ key: String) throws
    func allKeys() throws -> [String]
}

/// Keychain-backed store (generic passwords).
///
/// Items are `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`: readable after the first unlock so a
/// push can wake the app and register after a reboot, but never synced to iCloud or restored on another
/// phone (a restored phone must pair again; the server tied the SIP password to this installation).
public struct KeychainSecretStore: SecretStore {
    public static let defaultService = "nl.fullstackstudio.fsvoip"

    private let service: String
    private let accessGroup: String?

    public init(service: String = KeychainSecretStore.defaultService, accessGroup: String? = nil) {
        self.service = service
        self.accessGroup = accessGroup
    }

    private func baseQuery(_ key: String? = nil) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrSynchronizable as String: false,
        ]

        if let key {
            query[kSecAttrAccount as String] = key
        }

        if let accessGroup {
            query[kSecAttrAccessGroup as String] = accessGroup
        }

        return query
    }

    public func set(_ data: Data, for key: String) throws {
        var update: [String: Any] = [kSecValueData as String: data]
        update[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

        let status = SecItemUpdate(baseQuery(key) as CFDictionary, update as CFDictionary)

        if status == errSecItemNotFound {
            var add = baseQuery(key)
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

            let addStatus = SecItemAdd(add as CFDictionary, nil)

            guard addStatus == errSecSuccess else {
                throw SecretStoreError.keychain(addStatus)
            }

            return
        }

        guard status == errSecSuccess else {
            throw SecretStoreError.keychain(status)
        }
    }

    public func get(_ key: String) throws -> Data? {
        var query = baseQuery(key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        if status == errSecItemNotFound {
            return nil
        }

        guard status == errSecSuccess, let data = result as? Data else {
            throw SecretStoreError.keychain(status)
        }

        return data
    }

    public func remove(_ key: String) throws {
        let status = SecItemDelete(baseQuery(key) as CFDictionary)

        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw SecretStoreError.keychain(status)
        }
    }

    public func allKeys() throws -> [String] {
        var query = baseQuery()
        query[kSecReturnAttributes as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitAll

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        if status == errSecItemNotFound {
            return []
        }

        guard status == errSecSuccess, let items = result as? [[String: Any]] else {
            throw SecretStoreError.keychain(status)
        }

        return items.compactMap { $0[kSecAttrAccount as String] as? String }
    }
}

/// In-memory store for tests and previews. Never use it in the shipping app.
public final class InMemorySecretStore: SecretStore, @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [String: Data] = [:]

    public init() {}

    public func set(_ data: Data, for key: String) throws {
        lock.lock()
        storage[key] = data
        lock.unlock()
    }

    public func get(_ key: String) throws -> Data? {
        lock.lock()
        defer { lock.unlock() }

        return storage[key]
    }

    public func remove(_ key: String) throws {
        lock.lock()
        storage[key] = nil
        lock.unlock()
    }

    public func allKeys() throws -> [String] {
        lock.lock()
        defer { lock.unlock() }

        return Array(storage.keys)
    }
}

/// Everything the app keeps about one paired account. The whole record lives in the Keychain: it holds the
/// SIP password and the device token.
public struct StoredAccount: Codable, Equatable, Sendable, Identifiable {
    /// `AccountInfo.id` (the app pairing id).
    public var id: String
    public var label: String
    public var labelOverride: String?
    public var pbxName: String
    public var extensionName: String
    public var extensionNumber: String?
    public var customerName: String
    public var deviceToken: Secret
    public var installId: String
    public var sip: SIPCredentials
    public var pairedAt: Date

    public init(
        id: String,
        label: String,
        labelOverride: String? = nil,
        pbxName: String,
        extensionName: String,
        extensionNumber: String?,
        customerName: String,
        deviceToken: Secret,
        installId: String,
        sip: SIPCredentials,
        pairedAt: Date
    ) {
        self.id = id
        self.label = label
        self.labelOverride = labelOverride
        self.pbxName = pbxName
        self.extensionName = extensionName
        self.extensionNumber = extensionNumber
        self.customerName = customerName
        self.deviceToken = deviceToken
        self.installId = installId
        self.sip = sip
        self.pairedAt = pairedAt
    }

    /// Build the record from a successful `POST /pair`.
    public init(pairing response: PairResponse, pairedAt: Date = Date()) {
        self.init(
            id: response.account.id,
            label: response.account.label,
            labelOverride: response.account.labelOverride,
            pbxName: response.account.pbxName,
            extensionName: response.account.extensionName,
            extensionNumber: response.account.extensionNumber,
            customerName: response.account.customerName,
            deviceToken: response.deviceToken,
            installId: response.device.installId,
            sip: response.sip,
            pairedAt: pairedAt
        )
    }
}

/// The paired accounts of this installation (several accounts in one app).
public struct AccountStore: Sendable {
    private static let prefix = "account."
    private let secrets: SecretStore

    public init(secrets: SecretStore = KeychainSecretStore()) {
        self.secrets = secrets
    }

    public func save(_ account: StoredAccount) throws {
        try secrets.set(try FSVoipJSON.encoder().encode(account), for: Self.prefix + account.id)
    }

    public func account(id: String) throws -> StoredAccount? {
        guard let data = try secrets.get(Self.prefix + id) else {
            return nil
        }

        do {
            return try FSVoipJSON.decoder().decode(StoredAccount.self, from: data)
        } catch {
            throw SecretStoreError.corrupt
        }
    }

    /// All accounts, oldest pairing first. A corrupt item is skipped, not fatal.
    public func accounts() throws -> [StoredAccount] {
        try secrets.allKeys()
            .filter { $0.hasPrefix(Self.prefix) }
            .compactMap { try? account(id: String($0.dropFirst(Self.prefix.count))) }
            .sorted { $0.pairedAt < $1.pairedAt }
    }

    public func remove(id: String) throws {
        try secrets.remove(Self.prefix + id)
    }
}
