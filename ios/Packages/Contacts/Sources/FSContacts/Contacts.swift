// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The contacts of the app: four sources, one list.
//   1. `customer`: the customer's own address book from the portal (synced to this phone, editable),
//   2. `device`: the phone's own contacts (CNContactStore, read only, never uploaded and never written to disk by FSVoip),
//   3. `internalExtensions`: the other extensions of the PBX (from `GET /me`),
// plus number matching for caller names (`PhoneNumberMatcher`, `ContactIndex`).

import Core
import Foundation

public enum ContactSource: Hashable, Sendable {
    case customer
    case device
    case internalExtensions
}

public struct ContactPhoneEntry: Equatable, Hashable, Sendable {
    public var number: String
    public var label: ContactPhoneLabel
    public var isPrimary: Bool

    public init(number: String, label: ContactPhoneLabel = .other, isPrimary: Bool = false) {
        self.number = number
        self.label = label
        self.isPrimary = isPrimary
    }
}

public struct ContactEntry: Identifiable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var company: String?
    public var email: String?
    public var phones: [ContactPhoneEntry]
    public var source: ContactSource
    /// Customer contacts: the accounts whose address book holds this contact (the same customer can have several extensions).
    public var accountIds: [String]
    /// Customer contacts: the server's id and `updatedAt` (needed to edit).
    public var contactId: String?
    public var updatedAt: String?

    public init(
        id: String,
        name: String,
        company: String? = nil,
        email: String? = nil,
        phones: [ContactPhoneEntry],
        source: ContactSource,
        accountIds: [String] = [],
        contactId: String? = nil,
        updatedAt: String? = nil
    ) {
        self.id = id
        self.name = name
        self.company = company
        self.email = email
        self.phones = phones
        self.source = source
        self.accountIds = accountIds
        self.contactId = contactId
        self.updatedAt = updatedAt
    }

    public init(id: String, name: String, company: String? = nil, numbers: [String], source: ContactSource) {
        self.init(id: id, name: name, company: company, phones: numbers.map { ContactPhoneEntry(number: $0) }, source: source)
    }

    public var numbers: [String] {
        phones.map(\.number)
    }

    /// What a list shows for this contact: the name, otherwise the company, otherwise the first number.
    public var displayName: String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)

        if !trimmed.isEmpty { return trimmed }
        if let company, !company.trimmingCharacters(in: .whitespaces).isEmpty { return company }

        return phones.first?.number ?? ""
    }

    /// The letter a list files this contact under (`#` for digits and symbols).
    public var sectionLetter: Character {
        guard let first = displayName.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil).uppercased().first, first.isLetter else {
            return "#"
        }

        return first
    }

    /// Search: name, company, e-mail and numbers (digits compared without spaces or punctuation).
    public func matches(query: String) -> Bool {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !needle.isEmpty else {
            return true
        }

        let options: String.CompareOptions = [.caseInsensitive, .diacriticInsensitive]

        if displayName.range(of: needle, options: options) != nil { return true }
        if let company, company.range(of: needle, options: options) != nil { return true }
        if let email, email.range(of: needle, options: options) != nil { return true }

        let digits = needle.filter { $0.isNumber }

        if digits.count >= 3 {
            return phones.contains { phone in
                let own = phone.number.filter { $0.isNumber }
                // Typed the way people write it: `06 12…` finds `+31 6 12…`.
                let national = own.hasPrefix("31") && phone.number.hasPrefix("+") ? "0" + own.dropFirst(2) : own

                return own.contains(digits) || national.contains(digits)
            }
        }

        return false
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
