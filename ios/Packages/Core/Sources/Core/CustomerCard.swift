// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The "customer card": who is calling, with a little context from the customer's website, and the timeline of a contact.
//
//   GET /contacts/lookup?number=      → `CallerLookup` (the contact of THIS customer with that number, counters, the last 10 timeline lines)
//   GET /contacts/{id}/timeline       → `TimelinePage` (newest first, paged with an opaque cursor)
//
// The server only sends what the app may show: a title and an optional summary per line (no event data), a human reference
// (order / request number) and two counters. The app shows exactly that and nothing it derives itself.

import Foundation

/// One line of a contact's timeline.
public struct TimelineItem: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    /// The kind the server gave (`order`, `ticket`, `call`, ...). Only used to pick an icon; an unknown kind is fine.
    public var kind: String
    public var title: String
    public var summary: String?
    /// Opaque instant; see `occurredAtDate`.
    public var occurredAt: String
    /// Human number such as an order number, when there is one.
    public var ref: String?

    private enum CodingKeys: String, CodingKey {
        case id, kind, title, summary, occurredAt, ref
    }

    public init(id: String, kind: String, title: String, summary: String? = nil, occurredAt: String, ref: String? = nil) {
        self.id = id
        self.kind = kind
        self.title = title
        self.summary = summary
        self.occurredAt = occurredAt
        self.ref = ref
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        kind = try container.decodeIfPresent(String.self, forKey: .kind) ?? ""
        title = try container.decodeIfPresent(String.self, forKey: .title) ?? ""
        summary = try container.decodeIfPresent(String.self, forKey: .summary)
        occurredAt = try container.decode(String.self, forKey: .occurredAt)
        ref = try container.decodeIfPresent(String.self, forKey: .ref)
    }

    public var occurredAtDate: Date? {
        FSVoipJSON.parseTimestamp(occurredAt)
    }
}

/// `GET /contacts/{id}/timeline`.
public struct TimelinePage: Decodable, Equatable, Sendable {
    public var items: [TimelineItem]
    /// `nil` = last page.
    public var nextCursor: String?

    private enum CodingKeys: String, CodingKey {
        case items, nextCursor
    }

    public init(items: [TimelineItem], nextCursor: String?) {
        self.items = items
        self.nextCursor = nextCursor
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        items = try container.decodeIfPresent([TimelineItem].self, forKey: .items) ?? []
        nextCursor = try container.decodeIfPresent(String.self, forKey: .nextCursor)
    }
}

public struct CallerCounters: Decodable, Equatable, Sendable {
    public var openRequests: Int
    public var openOrders: Int

    private enum CodingKeys: String, CodingKey {
        case openRequests, openOrders
    }

    public init(openRequests: Int = 0, openOrders: Int = 0) {
        self.openRequests = openRequests
        self.openOrders = openOrders
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        // A negative or missing number is no information.
        openRequests = max(0, try container.decodeIfPresent(Int.self, forKey: .openRequests) ?? 0)
        openOrders = max(0, try container.decodeIfPresent(Int.self, forKey: .openOrders) ?? 0)
    }
}

/// `GET /contacts/lookup?number=`. `contact == nil` covers unknown, suppressed (privacy) and ambiguous (`ambiguous == true`: several
/// contacts share the number; the server does not guess and neither does the app).
public struct CallerLookup: Decodable, Equatable, Sendable {
    public var contact: Contact?
    public var ambiguous: Bool
    public var doNotContact: Bool
    public var counters: CallerCounters
    public var timeline: [TimelineItem]

    private enum CodingKeys: String, CodingKey {
        case contact, ambiguous, doNotContact, counters, timeline
    }

    public init(contact: Contact?, ambiguous: Bool = false, doNotContact: Bool = false, counters: CallerCounters = CallerCounters(), timeline: [TimelineItem] = []) {
        self.contact = contact
        self.ambiguous = ambiguous
        self.doNotContact = doNotContact
        self.counters = counters
        self.timeline = timeline
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        contact = try container.decodeIfPresent(Contact.self, forKey: .contact)
        ambiguous = try container.decodeIfPresent(Bool.self, forKey: .ambiguous) ?? false
        doNotContact = try container.decodeIfPresent(Bool.self, forKey: .doNotContact) ?? false
        counters = try container.decodeIfPresent(CallerCounters.self, forKey: .counters) ?? CallerCounters()
        timeline = try container.decodeIfPresent([TimelineItem].self, forKey: .timeline) ?? []
    }
}

/// What the call screen knows about the caller from the website of the customer.
public struct CallerContext: Equatable, Sendable {
    public var contactId: String
    public var name: String
    public var company: String?
    public var openRequests: Int
    public var openOrders: Int

    public init(contactId: String, name: String, company: String? = nil, openRequests: Int = 0, openOrders: Int = 0) {
        self.contactId = contactId
        self.name = name
        self.company = company
        self.openRequests = openRequests
        self.openOrders = openOrders
    }

    /// `nil` when the server did not identify one contact (unknown, ambiguous or suppressed): then the app shows nothing extra.
    public init?(_ lookup: CallerLookup) {
        guard let contact = lookup.contact, !lookup.ambiguous else {
            return nil
        }

        let name = contact.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let company = contact.company?.trimmingCharacters(in: .whitespacesAndNewlines)
        let fallback = company ?? ""

        self.init(
            contactId: contact.id,
            name: name.isEmpty ? fallback : name,
            company: company.flatMap { $0.isEmpty ? nil : $0 },
            openRequests: lookup.counters.openRequests,
            openOrders: lookup.counters.openOrders
        )
    }

    public var displayName: String? {
        name.isEmpty ? nil : name
    }
}

/// What the app needs from the server for the customer card. `LiveCustomerCardService` is the implementation; tests use their own.
public protocol CustomerCardServicing: Sendable {
    func lookup(number: String, for account: StoredAccount) async throws -> CallerLookup
    func timeline(contactId: String, cursor: String?, limit: Int, for account: StoredAccount) async throws -> TimelinePage
}

public struct LiveCustomerCardService: CustomerCardServicing {
    private let api: FSVoipAPIClient

    public init(api: FSVoipAPIClient = FSVoipAPIClient()) {
        self.api = api
    }

    public func lookup(number: String, for account: StoredAccount) async throws -> CallerLookup {
        try await api.authenticated(with: account.deviceToken).callerLookup(number: number)
    }

    public func timeline(contactId: String, cursor: String?, limit: Int, for account: StoredAccount) async throws -> TimelinePage {
        try await api.authenticated(with: account.deviceToken).contactTimeline(contactId: contactId, cursor: cursor, limit: limit)
    }
}

extension FSVoipAPIClient {
    /// `GET /contacts/lookup?number=`. The number goes as given (an E.164 or a national number); the server normalises it.
    public func callerLookup(number: String) async throws -> CallerLookup {
        try await get("contacts/lookup", query: [URLQueryItem(name: "number", value: number)])
    }

    /// `GET /contacts/{id}/timeline?cursor=&limit=` (1...100). The id comes from the server; it is still encoded as one path segment.
    public func contactTimeline(contactId: String, cursor: String? = nil, limit: Int = 30) async throws -> TimelinePage {
        var query = [URLQueryItem(name: "limit", value: String(min(100, max(1, limit))))]

        if let cursor { query.append(URLQueryItem(name: "cursor", value: cursor)) }

        let segment = contactId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? contactId

        return try await get("contacts/\(segment)/timeline", query: query)
    }
}

/// Runs an operation but gives up after `seconds`: the call must never wait for the website.
public enum CallerLookupTimeout {
    /// The longest an incoming call waits for the customer card.
    public static let incomingCall: TimeInterval = 1.0

    /// `nil` on a timeout or any error; the operation is cancelled in that case.
    public static func run<T: Sendable>(seconds: TimeInterval, _ operation: @escaping @Sendable () async throws -> T?) async -> T? {
        await withTaskGroup(of: T?.self) { group in
            group.addTask {
                try? await operation()
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(max(0, seconds) * 1_000_000_000))

                return nil
            }

            let first = await group.next() ?? nil
            group.cancelAll()

            return first
        }
    }
}
