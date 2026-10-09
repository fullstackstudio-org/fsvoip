// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation

/// The pure parts of keeping the local address book equal to the server's.
public enum ContactsSync {
    /// Apply one sync run to the stored contacts.
    ///
    /// - A full run (`isFull`) replaces everything: what is not in it is gone on the server.
    /// - An incremental run upserts on `id` (a contact can arrive twice because of the server's overlap) and drops the deleted ids.
    public static func apply(_ result: ContactsSyncResult, to stored: [StoredContact]) -> [StoredContact] {
        let incoming = result.contacts.map(StoredContact.init)

        if result.isFull {
            return incoming
        }

        var contacts = stored
        var positions: [String: Int] = [:]

        for (index, contact) in contacts.enumerated() {
            positions[contact.id] = index
        }

        for contact in incoming {
            if let index = positions[contact.id] {
                contacts[index] = contact
            } else {
                positions[contact.id] = contacts.count
                contacts.append(contact)
            }
        }

        let deleted = Set(result.deleted)

        return deleted.isEmpty ? contacts : contacts.filter { !deleted.contains($0.id) }
    }

    /// The lists after a `GET /contact-lists`: lists that are gone on the server disappear, new ones start enabled, the local choice
    /// (`isEnabled`) and the downloaded members stay.
    public static func mergeLists(_ server: [ContactListInfo], into stored: [StoredContactList]) -> [StoredContactList] {
        let known = Dictionary(uniqueKeysWithValues: stored.map { ($0.id, $0) })

        return server.map { info in
            var list = known[info.id] ?? StoredContactList(id: info.id, name: info.name, version: info.version, etag: nil, isEnabled: true, memberIds: [], contactCount: info.contactCount)
            list.name = info.name
            list.contactCount = info.contactCount

            return list
        }
    }

    /// Whether the members of a list have to be fetched: never downloaded, or the server's version differs from ours.
    public static func needsSnapshot(_ list: StoredContactList, serverVersion: Int) -> Bool {
        list.isEnabled && (list.etag == nil || list.version != serverVersion)
    }

    public enum MembersOutcome: Equatable, Sendable {
        /// `304`: the stored members are still right.
        case notModified
        case members(ids: [String], version: Int, etag: String?)
    }

    /// Download the members of a list. Follows `cursor` pages; an unchanged list answers `304`.
    /// A version that changes halfway is `APIError.stale`: start again once, then give up (the next sync tries again).
    public static func fetchMembers(api: ContactsAPI, list: StoredContactList) async throws -> MembersOutcome {
        do {
            return try await fetchOnce(api: api, list: list)
        } catch APIError.stale {
            return try await fetchOnce(api: api, list: list)
        }
    }

    private static func fetchOnce(api: ContactsAPI, list: StoredContactList) async throws -> MembersOutcome {
        let first = try await api.contactListSnapshot(listId: list.id, cursor: nil, etag: list.etag)

        guard case let .snapshot(snapshot, etag) = first else {
            return .notModified
        }

        var ids = snapshot.contacts.map(\.id)
        var cursor = snapshot.nextCursor
        var pages = 1

        while let next = cursor, pages < 200 {
            guard case let .snapshot(page, _) = try await api.contactListSnapshot(listId: list.id, cursor: next, etag: nil) else {
                break
            }

            ids.append(contentsOf: page.contacts.map(\.id))
            cursor = page.nextCursor
            pages += 1
        }

        return .members(ids: ids, version: snapshot.list.version, etag: etag ?? ContactListETag.make(version: snapshot.list.version))
    }
}
