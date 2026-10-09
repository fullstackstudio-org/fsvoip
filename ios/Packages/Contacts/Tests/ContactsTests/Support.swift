// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation
@testable import FSContacts

/// Builds API models the way the server sends them (the models are decode-only).
enum Make {
    static func decode<T: Decodable>(_ type: T.Type, _ object: [String: Any]) -> T {
        // swiftlint:disable:next force_try
        try! FSVoipJSON.decoder().decode(type, from: JSONSerialization.data(withJSONObject: object))
    }

    static func contact(_ id: String, _ name: String, phones: [String] = [], updatedAt: String = "2026-10-09T10:00:00.000Z", company: String? = nil, email: String? = nil) -> Contact {
        var object: [String: Any] = ["id": id, "name": name, "phones": phones.enumerated().map { ["number": $0.element, "label": "mobile", "isPrimary": $0.offset == 0] }, "tags": [String](), "updatedAt": updatedAt]
        if let company { object["company"] = company }
        if let email { object["email"] = email }

        return decode(Contact.self, object)
    }

    static func detail(_ contact: Contact, notes: String? = nil, listIds: [String] = []) -> ContactDetail {
        var object: [String: Any] = [
            "id": contact.id, "name": contact.name, "phones": contact.phones.map { ["number": $0.number, "label": $0.label.rawValue, "isPrimary": $0.isPrimary] }, "tags": contact.tags,
            "updatedAt": contact.updatedAt, "listIds": listIds,
        ]
        if let notes { object["notes"] = notes }
        if let company = contact.company { object["company"] = company }
        if let email = contact.email { object["email"] = email }
        if let first = contact.firstName { object["firstName"] = first }
        if let last = contact.lastName { object["lastName"] = last }

        return decode(ContactDetail.self, object)
    }

    static func run(_ contacts: [Contact], deleted: [String] = [], serverTime: String, full: Bool) -> ContactsSyncResult {
        ContactsSyncResult(contacts: contacts, deleted: deleted, serverTime: serverTime, isFull: full)
    }

    static func list(_ id: String, _ name: String, version: Int, count: Int = 0) -> ContactListInfo {
        decode(ContactListInfo.self, ["id": id, "name": name, "version": version, "contactCount": count])
    }

    static func snapshot(_ id: String, version: Int, contactIds: [String], nextCursor: String? = nil) -> ContactListSnapshot {
        var object: [String: Any] = ["list": ["id": id, "name": "L", "version": version], "contacts": contactIds.map { ["id": $0, "name": "N", "phones": [[String: Any]]()] }]
        if let nextCursor { object["nextCursor"] = nextCursor }

        return decode(ContactListSnapshot.self, object)
    }

    static func capabilities(read: Bool = true, write: Bool = true, delete: Bool = false) -> ContactCapabilities {
        decode(ContactCapabilities.self, ["read": read, "write": write, "delete": delete])
    }
}

/// A scripted server.
final class FakeContactsAPI: ContactsAPI, @unchecked Sendable {
    private let lock = NSLock()

    var capabilities = Make.capabilities()
    /// Answers by call: the next `syncAddressBook` takes the first one off the queue.
    var syncResults: [Result<ContactsSyncResult, Error>] = []
    var meError: Error?
    var lists: [ContactListInfo] = []
    /// Per list id: the snapshot pages, in order (the first has no cursor).
    var snapshots: [String: [Result<ContactListSnapshotOutcome, Error>]] = [:]
    var createResult: Result<ContactDetail, Error>?
    var updateResult: Result<ContactUpdateResponse, Error>?
    var detailResult: Result<ContactDetail, Error>?
    var deleteResult: Result<Int, Error> = .success(1)

    private(set) var syncSinces: [String?] = []
    private(set) var snapshotCalls: [(listId: String, cursor: String?, etag: String?)] = []
    private(set) var updates: [(id: String, update: ContactUpdate)] = []
    private(set) var creates: [ContactCreate] = []
    private(set) var deletes: [String] = []

    func contactCapabilities() async throws -> ContactCapabilities {
        if let meError { throw meError }

        return capabilities
    }

    func syncAddressBook(since: String?) async throws -> ContactsSyncResult {
        let next: Result<ContactsSyncResult, Error> = lock.withLock {
            syncSinces.append(since)

            return syncResults.isEmpty ? .failure(APIError.transport("no scripted answer")) : syncResults.removeFirst()
        }

        return try next.get()
    }

    func contact(id: String) async throws -> ContactDetail {
        try (detailResult ?? .failure(APIError.notFound)).get()
    }

    func createContact(_ contact: ContactCreate) async throws -> ContactDetail {
        lock.withLock { creates.append(contact) }

        return try (createResult ?? .failure(APIError.notFound)).get()
    }

    func updateContact(id: String, _ update: ContactUpdate) async throws -> ContactUpdateResponse {
        lock.withLock { updates.append((id, update)) }

        return try (updateResult ?? .failure(APIError.notFound)).get()
    }

    func deleteContact(id: String) async throws -> Int {
        lock.withLock { deletes.append(id) }

        return try deleteResult.get()
    }

    func contactLists() async throws -> [ContactListInfo] {
        lists
    }

    func contactListSnapshot(listId: String, cursor: String?, etag: String?) async throws -> ContactListSnapshotOutcome {
        let next: Result<ContactListSnapshotOutcome, Error> = lock.withLock {
            snapshotCalls.append((listId, cursor, etag))
            var queue = snapshots[listId] ?? []

            guard !queue.isEmpty else {
                return .failure(APIError.notFound)
            }

            let first = queue.removeFirst()
            snapshots[listId] = queue

            return first
        }

        return try next.get()
    }
}
