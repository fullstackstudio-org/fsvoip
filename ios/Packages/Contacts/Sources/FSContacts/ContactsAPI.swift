// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation

/// What the contacts need from the FullStack Studio API. `FSVoipAPIClient` is the implementation; tests use their own.
public protocol ContactsAPI: Sendable {
    /// What this pairing may do with contacts (`GET /me`). Also the way to find out the pairing was revoked (`APIError.unauthorized`).
    func contactCapabilities() async throws -> ContactCapabilities
    /// All pages of `GET /contacts`. `since == nil`: everything. Throws `APIError.resync` for a `since` that is too old.
    func syncAddressBook(since: String?) async throws -> ContactsSyncResult
    func contact(id: String) async throws -> ContactDetail
    func createContact(_ contact: ContactCreate) async throws -> ContactDetail
    func updateContact(id: String, _ update: ContactUpdate) async throws -> ContactUpdateResponse
    func deleteContact(id: String) async throws -> Int
    func contactLists() async throws -> [ContactListInfo]
    func contactListSnapshot(listId: String, cursor: String?, etag: String?) async throws -> ContactListSnapshotOutcome
}

extension FSVoipAPIClient: ContactsAPI {
    public func contactCapabilities() async throws -> ContactCapabilities {
        try await me().capabilities?.contacts ?? ContactCapabilities()
    }

    public func syncAddressBook(since: String?) async throws -> ContactsSyncResult {
        try await syncContacts(since: since, pageSize: nil, maxPages: 200)
    }
}
