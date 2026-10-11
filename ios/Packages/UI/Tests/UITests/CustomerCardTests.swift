// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import XCTest
@testable import UI

private final class FakeCards: CustomerCardServicing, @unchecked Sendable {
    private let lock = NSLock()
    var pages: [Result<TimelinePage, Error>] = []
    private(set) var cursors: [String?] = []

    func lookup(number: String, for account: StoredAccount) async throws -> CallerLookup {
        CallerLookup(contact: nil)
    }

    func timeline(contactId: String, cursor: String?, limit: Int, for account: StoredAccount) async throws -> TimelinePage {
        let next: Result<TimelinePage, Error> = lock.withLock {
            cursors.append(cursor)

            return pages.isEmpty ? .failure(APIError.transport("none")) : pages.removeFirst()
        }

        return try next.get()
    }
}

final class CallerLineTests: XCTestCase {
    private func context(orders: Int, requests: Int) -> CallerContext {
        CallerContext(contactId: "c", name: "Jansen", openRequests: requests, openOrders: orders)
    }

    func testTheLineShowsOnlyWhatTheServerCounted() {
        XCTAssertEqual(CallerLine.text(context(orders: 2, requests: 1)), "Klant in website: 2 open bestellingen, 1 open verzoek")
        XCTAssertEqual(CallerLine.text(context(orders: 1, requests: 0)), "Klant in website: 1 open bestelling")
        XCTAssertEqual(CallerLine.text(context(orders: 0, requests: 3)), "Klant in website: 3 open verzoeken")
    }

    func testAKnownContactWithNothingOpenStillSaysSo() {
        XCTAssertEqual(CallerLine.text(context(orders: 0, requests: 0)), "Klant in website")
    }

    func testNoContextNoLine() {
        XCTAssertNil(CallerLine.text(nil))
    }

    func testEveryKeyExistsInDutchAndEnglish() throws {
        for key in ["callcard.prefix", "callcard.known", "callcard.orders.one", "callcard.orders.other", "callcard.requests.one", "callcard.requests.other", "timeline.title", "timeline.loading", "timeline.empty", "timeline.more", "timeline.error"] {
            for language in ["nl", "en"] {
                let path = try XCTUnwrap(L10n.bundle.path(forResource: language, ofType: "lproj"), language)
                let bundle = try XCTUnwrap(Bundle(path: path))
                let value = bundle.localizedString(forKey: key, value: "MISSING", table: nil)

                XCTAssertNotEqual(value, "MISSING", "\(key) in \(language)")
            }
        }
    }
}

@MainActor
final class TimelineModelTests: XCTestCase {
    private let account = StoredAccount(
        id: "a", label: "x", pbxName: "x", extensionName: "x", extensionNumber: "102", customerName: "x",
        deviceToken: Secret("fss_vapp_test"), installId: "i",
        sip: SIPCredentials(username: "102", password: Secret("pw"), domain: "d", proxy: "p", port: 5061, transport: .tls, srv: true),
        pairedAt: Date(timeIntervalSince1970: 1)
    )

    private func item(_ id: String) -> TimelineItem {
        TimelineItem(id: id, kind: "order", title: "Bestelling \(id)", occurredAt: "2026-10-09T14:02:11.000Z")
    }

    func testPagesAreAppendedWithoutDuplicatesAndTheCursorIsFollowed() async {
        let service = FakeCards()
        service.pages = [.success(TimelinePage(items: [item("1"), item("2")], nextCursor: "c1")), .success(TimelinePage(items: [item("2"), item("3")], nextCursor: nil))]
        let model = TimelineModel(service: service, account: account, contactId: "c")

        await model.loadFirstPage()
        XCTAssertEqual(model.items.map(\.id), ["1", "2"])
        XCTAssertTrue(model.canLoadMore)

        await model.loadMore()
        XCTAssertEqual(model.items.map(\.id), ["1", "2", "3"], "an overlapping line is shown once")
        XCTAssertFalse(model.canLoadMore)
        XCTAssertEqual(service.cursors, [nil, "c1"])
    }

    func testTheFirstPageLoadsOnlyOnce() async {
        let service = FakeCards()
        service.pages = [.success(TimelinePage(items: [item("1")], nextCursor: nil))]
        let model = TimelineModel(service: service, account: account, contactId: "c")

        await model.loadFirstPage()
        await model.loadFirstPage()

        XCTAssertEqual(service.cursors.count, 1)
    }

    func testAFailureKeepsWhatIsOnScreenAndCanBeRetried() async {
        let service = FakeCards()
        service.pages = [.success(TimelinePage(items: [item("1")], nextCursor: "c1")), .failure(APIError.unavailable(retryable: true, retryAfterSeconds: nil)), .success(TimelinePage(items: [item("2")], nextCursor: nil))]
        let model = TimelineModel(service: service, account: account, contactId: "c")

        await model.loadFirstPage()
        await model.loadMore()
        XCTAssertEqual(model.phase, .failed)
        XCTAssertEqual(model.items.map(\.id), ["1"])

        await model.loadMore()
        XCTAssertEqual(model.phase, .idle)
        XCTAssertEqual(model.items.map(\.id), ["1", "2"])
    }

    func testAFailedFirstLoadCanBeRetried() async {
        let service = FakeCards()
        service.pages = [.failure(APIError.transport("offline")), .success(TimelinePage(items: [item("1")], nextCursor: nil))]
        let model = TimelineModel(service: service, account: account, contactId: "c")

        await model.loadFirstPage()
        XCTAssertEqual(model.phase, .failed)
        XCTAssertFalse(model.hasLoaded)

        await model.loadFirstPage()
        XCTAssertEqual(model.items.map(\.id), ["1"])
        XCTAssertTrue(model.hasLoaded)
    }

    func testIconsPerKindAndASafeFallback() {
        XCTAssertEqual(ContactTimelineSection.symbol(for: "order"), "bag")
        XCTAssertEqual(ContactTimelineSection.symbol(for: "TICKET"), "bubble.left")
        XCTAssertEqual(ContactTimelineSection.symbol(for: "something_new"), "circle.fill")
    }

    func testTheSubtitleJoinsTheReferenceAndTheDate() {
        let withRef = TimelineItem(id: "1", kind: "order", title: "t", occurredAt: "not a date", ref: "W-1042")
        XCTAssertEqual(ContactTimelineSection.subtitle(withRef), "W-1042")

        let bare = TimelineItem(id: "2", kind: "order", title: "t", occurredAt: "not a date")
        XCTAssertEqual(ContactTimelineSection.subtitle(bare), "")
    }
}
