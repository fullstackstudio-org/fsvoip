// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation

/// The result of `GET /me` for one account.
public enum AccountRefreshResult: Equatable, Sendable {
    /// `me` is the whole answer: the app hands it to everything that reads from `GET /me` (contacts, the "Centrale" section), so one
    /// refresh is one request. `nil` only for a service that has no such answer (tests, the demo).
    case updated(StoredAccount, internalContacts: [InternalContact], me: MeResponse? = nil)
    /// 401: the pairing was revoked. The account has been removed from this phone.
    case revoked
}

/// Everything the app does with the FullStack Studio API for its accounts. One implementation talks to the server;
/// tests and the demo mode use their own.
public protocol AccountServicing: Sendable {
    /// `POST /pair` and keep the account (SIP password and device token in the Keychain only).
    func pair(_ link: PairingLink, device: DeviceDescriptor) async throws -> StoredAccount
    /// `GET /me`: refresh labels and the SIP server. Removes the account locally on a 401.
    func refresh(_ account: StoredAccount) async throws -> AccountRefreshResult
    /// `PATCH /me`: set the alias (`nil` or empty = back to the label from the portal).
    func rename(_ account: StoredAccount, alias: String?) async throws -> StoredAccount
    /// `POST /unpair`, then remove the account from this phone. A pairing the server no longer knows (401/404)
    /// counts as unpaired.
    func unpair(_ account: StoredAccount) async throws
    /// Remove the account from this phone only (when the server cannot be reached). The pairing stays active in the
    /// portal until it is removed there.
    func forget(_ account: StoredAccount) throws
}

public struct AccountService: AccountServicing {
    /// Maximum length of an alias (the server's limit).
    public static let aliasMaxLength = 60

    private let api: FSVoipAPIClient
    private let accounts: AccountStore
    private let logger: FSLogger

    public init(api: FSVoipAPIClient = FSVoipAPIClient(), accounts: AccountStore, logger: FSLogger = FSLogger(category: "accounts")) {
        self.api = api
        self.accounts = accounts
        self.logger = logger
    }

    public func pair(_ link: PairingLink, device: DeviceDescriptor) async throws -> StoredAccount {
        try await PairingService(api: api, accounts: accounts, logger: logger).pair(link, device: device)
    }

    public func refresh(_ account: StoredAccount) async throws -> AccountRefreshResult {
        do {
            let me = try await client(for: account).me()
            let updated = account.updated(with: me)

            if updated != account {
                try accounts.save(updated)
            }

            return .updated(updated, internalContacts: me.internalContacts, me: me)
        } catch APIError.unauthorized {
            logger.notice("Account \(account.id) was revoked by the server")
            try accounts.remove(id: account.id)

            return .revoked
        }
    }

    public func rename(_ account: StoredAccount, alias: String?) async throws -> StoredAccount {
        let cleaned = Self.cleanAlias(alias)
        let response = try await client(for: account).setLabelOverride(cleaned)
        var updated = account
        updated.label = response.label
        updated.labelOverride = response.labelOverride

        try accounts.save(updated)

        return updated
    }

    public func unpair(_ account: StoredAccount) async throws {
        do {
            try await client(for: account).unpair()
        } catch APIError.unauthorized {
            // Already revoked on the server: nothing left to undo there.
        } catch APIError.notFound {
            // Idem.
        }

        try accounts.remove(id: account.id)
        logger.notice("Account \(account.id) unpaired")
    }

    public func forget(_ account: StoredAccount) throws {
        try accounts.remove(id: account.id)
        logger.notice("Account \(account.id) removed from this phone only")
    }

    /// Trimmed, single line, at most 60 characters; empty = `nil`.
    public static func cleanAlias(_ alias: String?) -> String? {
        guard let alias else {
            return nil
        }

        let singleLine = alias.components(separatedBy: .newlines).joined(separator: " ")
        let trimmed = singleLine.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !trimmed.isEmpty else {
            return nil
        }

        return String(trimmed.prefix(aliasMaxLength))
    }

    private func client(for account: StoredAccount) -> FSVoipAPIClient {
        api.authenticated(with: account.deviceToken)
    }
}
