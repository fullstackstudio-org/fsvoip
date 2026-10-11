// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Swift mirror of `/contacts` and `/contact-lists` in `shared/openapi.yaml`: the customer's own address book (not the
// contacts of the phone), as the portal shows it.
//
// - `updatedAt` and `serverTime` are OPAQUE TIMESTAMP STRINGS. They go back to the server unchanged (`expectedUpdatedAt`, `since`);
//   parsing and re-formatting them could shift a millisecond and turn an edit into a false `stale` or a sync into a gap.
// - Sync: `serverTime` is the same on every page of one sync run; store it as the next `since` only after the LAST page
//   (`nextCursor == nil`). The server counts 120 s of overlap, so a contact can arrive twice: upsert on `id`.

import Foundation

public enum ContactPhoneLabel: String, WireEnum {
    case mobile
    case work
    case home
    case main
    case fax
    case other
    /// A label this app version does not know; shown as "other".
    case unknown

    public static var unknownValue: ContactPhoneLabel { .unknown }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

public struct ContactPhone: Codable, Equatable, Sendable {
    /// E.164 with a plus (`+31612345678`) as the server stores it.
    public var number: String
    public var label: ContactPhoneLabel
    public var isPrimary: Bool

    public init(number: String, label: ContactPhoneLabel = .other, isPrimary: Bool = false) {
        self.number = number
        self.label = label
        self.isPrimary = isPrimary
    }

    private enum CodingKeys: String, CodingKey {
        case number, label, isPrimary
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        number = try container.decode(String.self, forKey: .number)
        label = try container.decodeIfPresent(ContactPhoneLabel.self, forKey: .label) ?? .other
        isPrimary = try container.decodeIfPresent(Bool.self, forKey: .isPrimary) ?? false
    }
}

/// A contact as in the sync (no notes, no lists).
public struct Contact: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    /// The display name (`name`, not `displayName`).
    public var name: String
    public var firstName: String?
    public var lastName: String?
    public var company: String?
    public var email: String?
    public var phones: [ContactPhone]
    public var tags: [String]
    /// Opaque: send it back as `expectedUpdatedAt` when editing.
    public var updatedAt: String

    private enum CodingKeys: String, CodingKey {
        case id, name, firstName, lastName, company, email, phones, tags, updatedAt
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decodeIfPresent(String.self, forKey: .name) ?? ""
        firstName = try container.decodeIfPresent(String.self, forKey: .firstName)
        lastName = try container.decodeIfPresent(String.self, forKey: .lastName)
        company = try container.decodeIfPresent(String.self, forKey: .company)
        email = try container.decodeIfPresent(String.self, forKey: .email)
        phones = try container.decodeIfPresent([ContactPhone].self, forKey: .phones) ?? []
        tags = try container.decodeIfPresent([String].self, forKey: .tags) ?? []
        updatedAt = try container.decode(String.self, forKey: .updatedAt)
    }

    /// The updated-at as a date (for display only; never send this back).
    public var updatedAtDate: Date? {
        FSVoipJSON.parseTimestamp(updatedAt)
    }
}

/// One contact in full: `GET /contacts/{id}` and the answer of POST/PATCH.
public struct ContactDetail: Decodable, Equatable, Sendable, Identifiable {
    public var contact: Contact
    public var notes: String?
    /// The lists this contact is in.
    public var listIds: [String]

    public var id: String { contact.id }

    private enum CodingKeys: String, CodingKey {
        case notes, listIds
    }

    public init(from decoder: Decoder) throws {
        contact = try Contact(from: decoder)
        let container = try decoder.container(keyedBy: CodingKeys.self)
        notes = try container.decodeIfPresent(String.self, forKey: .notes)
        listIds = try container.decodeIfPresent([String].self, forKey: .listIds) ?? []
    }
}

/// `GET /contacts` (a page of the sync).
public struct ContactsPage: Decodable, Equatable, Sendable {
    public var contacts: [Contact]
    /// Ids deleted in the period (only with `since`).
    public var deleted: [String]
    /// `nil` = last page.
    public var nextCursor: String?
    /// Opaque instant of the server clock. Identical on all pages of one run; keep the one of the last page as the next `since`.
    public var serverTime: String

    private enum CodingKeys: String, CodingKey {
        case contacts, deleted, nextCursor, serverTime
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        contacts = try container.decodeIfPresent([Contact].self, forKey: .contacts) ?? []
        deleted = try container.decodeIfPresent([String].self, forKey: .deleted) ?? []
        nextCursor = try container.decodeIfPresent(String.self, forKey: .nextCursor)
        serverTime = try container.decode(String.self, forKey: .serverTime)
    }
}

/// The result of a whole sync run (all pages of `GET /contacts`).
public struct ContactsSyncResult: Equatable, Sendable {
    /// Added or changed contacts; a contact that came in twice appears once (the last version).
    public var contacts: [Contact]
    public var deleted: [String]
    /// Store this as the next `since`.
    public var serverTime: String
    /// `true` = there was no `since`: this is the complete set; drop locally what is not in `contacts`.
    public var isFull: Bool

    public init(contacts: [Contact], deleted: [String], serverTime: String, isFull: Bool) {
        self.contacts = contacts
        self.deleted = deleted
        self.serverTime = serverTime
        self.isFull = isFull
    }
}

public struct ContactSingleResponse: Decodable, Equatable, Sendable {
    public var contact: ContactDetail
}

/// The answer of `PATCH /contacts/{id}`. `changed == false`: nothing was different, nothing was written.
public struct ContactUpdateResponse: Decodable, Equatable, Sendable {
    public var contact: ContactDetail
    public var changed: Bool
}

public struct ContactDeleteResponse: Decodable, Equatable, Sendable {
    public var deleted: Int
}

// MARK: - Lists

public struct ContactListInfo: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    /// The version is the ETag of the snapshot.
    public var version: Int
    public var contactCount: Int
}

public struct ContactListsResponse: Decodable, Equatable, Sendable {
    public var lists: [ContactListInfo]
}

public struct ContactListHeader: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var version: Int
}

public struct SnapshotPhone: Decodable, Equatable, Sendable {
    public var number: String
    public var label: ContactPhoneLabel
}

/// A contact in a list snapshot: only what the dialler needs.
public struct SnapshotContact: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var company: String?
    public var phones: [SnapshotPhone]
    public var email: String?
}

public struct ContactListSnapshot: Decodable, Equatable, Sendable {
    public var list: ContactListHeader
    public var contacts: [SnapshotContact]
    /// `nil` = last page (lists above 500 contacts come in pages).
    public var nextCursor: String?
}

/// The answer to a conditional snapshot request.
public enum ContactListSnapshotOutcome: Equatable, Sendable {
    /// `304`: the version equals the one sent in `If-None-Match`; nothing to download.
    case notModified(etag: String?)
    case snapshot(ContactListSnapshot, etag: String?)
}

public enum ContactListETag {
    /// The ETag of a list version, as the server writes it (`"3"`).
    public static func make(version: Int) -> String {
        "\"\(version)\""
    }
}

// MARK: - Requests

/// `POST /contacts`. A weak key (`Change.keep`) is left out; `.clear` is not meaningful on create but allowed by the server.
public struct ContactCreate: Encodable, Equatable, Sendable {
    public var name: String?
    public var firstName: Change<String>
    public var lastName: Change<String>
    public var company: Change<String>
    public var email: Change<String>
    public var notes: Change<String>
    public var phones: [ContactPhone]?
    public var tags: [String]?
    public var listIds: [String]?

    public init(
        name: String? = nil,
        firstName: Change<String> = .keep,
        lastName: Change<String> = .keep,
        company: Change<String> = .keep,
        email: Change<String> = .keep,
        notes: Change<String> = .keep,
        phones: [ContactPhone]? = nil,
        tags: [String]? = nil,
        listIds: [String]? = nil
    ) {
        self.name = name
        self.firstName = firstName
        self.lastName = lastName
        self.company = company
        self.email = email
        self.notes = notes
        self.phones = phones
        self.tags = tags
        self.listIds = listIds
    }

    private enum CodingKeys: String, CodingKey {
        case name, firstName, lastName, company, email, notes, phones, tags, listIds
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(name, forKey: .name)
        try container.encodeChange(firstName, forKey: .firstName)
        try container.encodeChange(lastName, forKey: .lastName)
        try container.encodeChange(company, forKey: .company)
        try container.encodeChange(email, forKey: .email)
        try container.encodeChange(notes, forKey: .notes)
        try container.encodeIfPresent(phones, forKey: .phones)
        try container.encodeIfPresent(tags, forKey: .tags)
        try container.encodeIfPresent(listIds, forKey: .listIds)
    }
}

/// `PATCH /contacts/{id}`. `expectedUpdatedAt` is REQUIRED (the `updatedAt` you showed): without it the server answers `400`.
/// A weakly changed field stays out; `.clear` sends `null` (wipes the text). Lists, phones and tags REPLACE the whole set.
public struct ContactUpdate: Encodable, Equatable, Sendable {
    public var expectedUpdatedAt: String
    public var name: String?
    public var firstName: Change<String>
    public var lastName: Change<String>
    public var company: Change<String>
    public var email: Change<String>
    public var notes: Change<String>
    public var phones: [ContactPhone]?
    public var tags: [String]?
    public var listIds: [String]?

    public init(
        expectedUpdatedAt: String,
        name: String? = nil,
        firstName: Change<String> = .keep,
        lastName: Change<String> = .keep,
        company: Change<String> = .keep,
        email: Change<String> = .keep,
        notes: Change<String> = .keep,
        phones: [ContactPhone]? = nil,
        tags: [String]? = nil,
        listIds: [String]? = nil
    ) {
        self.expectedUpdatedAt = expectedUpdatedAt
        self.name = name
        self.firstName = firstName
        self.lastName = lastName
        self.company = company
        self.email = email
        self.notes = notes
        self.phones = phones
        self.tags = tags
        self.listIds = listIds
    }

    private enum CodingKeys: String, CodingKey {
        case expectedUpdatedAt, name, firstName, lastName, company, email, notes, phones, tags, listIds
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(expectedUpdatedAt, forKey: .expectedUpdatedAt)
        try container.encodeIfPresent(name, forKey: .name)
        try container.encodeChange(firstName, forKey: .firstName)
        try container.encodeChange(lastName, forKey: .lastName)
        try container.encodeChange(company, forKey: .company)
        try container.encodeChange(email, forKey: .email)
        try container.encodeChange(notes, forKey: .notes)
        try container.encodeIfPresent(phones, forKey: .phones)
        try container.encodeIfPresent(tags, forKey: .tags)
        try container.encodeIfPresent(listIds, forKey: .listIds)
    }
}
