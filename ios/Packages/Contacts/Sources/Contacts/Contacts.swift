// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Skeleton (Task 7 fills it in): three contact sources with a per-source switch and number matching for caller names.
//   1. the phone's own contacts (CNContactStore, read only, never uploaded),
//   2. internal contacts (the other extensions of the PBX, from `GET /me`),
//   3. customer contact lists from the portal (a later API route), each list on or off.

import Core
import Foundation

public enum ContactSource: Hashable, Sendable {
    case device
    case internalExtensions
    /// A customer contact list from the portal.
    case list(id: String)
}

public struct ContactEntry: Identifiable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var company: String?
    public var numbers: [String]
    public var source: ContactSource

    public init(id: String, name: String, company: String? = nil, numbers: [String], source: ContactSource) {
        self.id = id
        self.name = name
        self.company = company
        self.numbers = numbers
        self.source = source
    }
}

public protocol ContactsProvider: Sendable {
    /// Contacts of the sources that are switched on.
    func contacts() async throws -> [ContactEntry]
    /// Name for a caller number, or `nil`.
    func name(forNumber number: String) async -> String?
}

/// Provides nothing. The app shell uses it until the sources exist.
public struct EmptyContactsProvider: ContactsProvider {
    public init() {}

    public func contacts() async throws -> [ContactEntry] {
        []
    }

    public func name(forNumber number: String) async -> String? {
        nil
    }
}

/// Internal contacts straight from `GET /me`.
public struct InternalContactsProvider: ContactsProvider {
    private let entries: [ContactEntry]

    public init(_ internalContacts: [InternalContact]) {
        entries = internalContacts.map {
            ContactEntry(id: "internal:\($0.number)", name: $0.name, numbers: [$0.number], source: .internalExtensions)
        }
    }

    public func contacts() async throws -> [ContactEntry] {
        entries
    }

    public func name(forNumber number: String) async -> String? {
        entries.first { $0.numbers.contains(number) }?.name
    }
}
