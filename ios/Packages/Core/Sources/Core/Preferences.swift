// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation

/// Settings of one account that are not secret (they live in `UserDefaults`, not in the Keychain).
public struct AccountPreferences: Codable, Equatable, Sendable {
    /// Show the dialled account next to the caller on the incoming call screen ("Bakkerij Smit → Jan (balie)").
    /// `nil` = never chosen: then the default applies (on when several accounts are paired, plan D10).
    public var showCalledAccount: Bool?

    /// The national number the user last chose to call out with ("Uitbellen via"); `nil` = the default number of the PBX.
    /// Only ever read together with `AppCapabilities.callerChoice`. Never sent anywhere except as the header of a call.
    public var outboundNumber: String?

    public init(showCalledAccount: Bool? = nil, outboundNumber: String? = nil) {
        self.showCalledAccount = showCalledAccount
        self.outboundNumber = outboundNumber
    }

    /// The value in effect for this account.
    public func effectiveShowCalledAccount(accountCount: Int) -> Bool {
        showCalledAccount ?? (accountCount > 1)
    }
}

/// Non-secret app and account settings.
public protocol PreferencesStore: AnyObject, Sendable {
    func preferences(for accountId: String) -> AccountPreferences
    func setPreferences(_ preferences: AccountPreferences, for accountId: String)
    func removePreferences(for accountId: String)
    /// The account used for outgoing calls when nothing else was chosen.
    var defaultOutgoingAccountId: String? { get set }
}

/// `UserDefaults`-backed store (one JSON blob per account).
public final class UserDefaultsPreferencesStore: PreferencesStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let prefix = "fsvoip.account-preferences."
    private let defaultAccountKey = "fsvoip.default-outgoing-account"

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func preferences(for accountId: String) -> AccountPreferences {
        guard let data = defaults.data(forKey: prefix + accountId), let value = try? JSONDecoder().decode(AccountPreferences.self, from: data) else {
            return AccountPreferences()
        }

        return value
    }

    public func setPreferences(_ preferences: AccountPreferences, for accountId: String) {
        defaults.set(try? JSONEncoder().encode(preferences), forKey: prefix + accountId)
    }

    public func removePreferences(for accountId: String) {
        defaults.removeObject(forKey: prefix + accountId)

        if defaultOutgoingAccountId == accountId {
            defaultOutgoingAccountId = nil
        }
    }

    public var defaultOutgoingAccountId: String? {
        get { defaults.string(forKey: defaultAccountKey) }
        set { defaults.set(newValue, forKey: defaultAccountKey) }
    }
}

/// In-memory store for tests and previews.
public final class InMemoryPreferencesStore: PreferencesStore, @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: AccountPreferences] = [:]
    private var defaultAccount: String?

    public init() {}

    public func preferences(for accountId: String) -> AccountPreferences {
        lock.withLock { values[accountId] ?? AccountPreferences() }
    }

    public func setPreferences(_ preferences: AccountPreferences, for accountId: String) {
        lock.withLock { values[accountId] = preferences }
    }

    public func removePreferences(for accountId: String) {
        lock.withLock {
            values[accountId] = nil

            if defaultAccount == accountId {
                defaultAccount = nil
            }
        }
    }

    public var defaultOutgoingAccountId: String? {
        get { lock.withLock { defaultAccount } }
        set { lock.withLock { defaultAccount = newValue } }
    }
}
