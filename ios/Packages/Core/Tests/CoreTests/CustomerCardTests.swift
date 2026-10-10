// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import Core

/// The customer card (lookup + timeline) and the `notice` push, against the shared fixtures.
final class CustomerCardTests: XCTestCase {
    static let coveredFixtures: Set<String> = [
        "contact-lookup", "contact-lookup-unknown", "contact-timeline", "contact-timeline-last", "push-notice", "apns-notice-body",
    ]

    private let deviceToken = Secret("fss_vapp_" + String(repeating: "x", count: 43))
    private let decoder = FSVoipJSON.decoder()

    private func client(_ replies: [MockTransport.Reply]) -> (FSVoipAPIClient, MockTransport) {
        let transport = MockTransport(replies)
        let api = FSVoipAPIClient(deviceToken: deviceToken, transport: transport, userAgent: "FSVoip/2.0 (test)", logger: FSLogger(category: "test", sink: MemoryLogSink()))

        return (api, transport)
    }

    private func reply(_ status: Int, _ fixture: String) throws -> MockTransport.Reply {
        .init(status: status, body: try Fixtures.data(fixture))
    }

    // MARK: Lookup

    func testLookupDecodesAndMapsToTheCallerContext() throws {
        let lookup = try decoder.decode(CallerLookup.self, from: Fixtures.data("contact-lookup"))

        XCTAssertEqual(lookup.contact?.name, "Bakkerij Smit")
        XCTAssertEqual(lookup.counters, CallerCounters(openRequests: 1, openOrders: 2))
        XCTAssertEqual(lookup.timeline.count, 3)
        XCTAssertEqual(lookup.timeline[0].ref, "W-1042")
        XCTAssertNotNil(lookup.timeline[0].occurredAtDate)

        let context = try XCTUnwrap(CallerContext(lookup))
        XCTAssertEqual(context.contactId, "6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c")
        XCTAssertEqual(context.displayName, "Bakkerij Smit")
        XCTAssertEqual(context.openOrders, 2)
        XCTAssertEqual(context.openRequests, 1)
    }

    func testAnUnknownCallerHasNoContext() throws {
        let lookup = try decoder.decode(CallerLookup.self, from: Fixtures.data("contact-lookup-unknown"))

        XCTAssertNil(lookup.contact)
        XCTAssertNil(CallerContext(lookup))
    }

    func testAnAmbiguousNumberIsNeverGuessed() throws {
        var lookup = try decoder.decode(CallerLookup.self, from: Fixtures.data("contact-lookup"))
        lookup.ambiguous = true

        XCTAssertNil(CallerContext(lookup))
    }

    func testTheLookupToleratesMissingAndNegativeFields() throws {
        let json = #"{"contact":null,"counters":{"openRequests":-3},"extra":"ignored"}"#
        let lookup = try decoder.decode(CallerLookup.self, from: Data(json.utf8))

        XCTAssertEqual(lookup.counters, CallerCounters(openRequests: 0, openOrders: 0))
        XCTAssertEqual(lookup.timeline, [])
        XCTAssertFalse(lookup.ambiguous)
    }

    func testACompanyOnlyContactUsesTheCompanyAsTheName() throws {
        var lookup = try decoder.decode(CallerLookup.self, from: Fixtures.data("contact-lookup"))
        lookup.contact?.name = "  "

        XCTAssertEqual(CallerContext(lookup)?.displayName, "Bakkerij Smit")
    }

    func testLookupRouteSendsTheNumberAsAQueryItem() async throws {
        let (api, transport) = client([try reply(200, "contact-lookup")])
        let lookup = try await api.callerLookup(number: "+31 70 123 4567&x=1")

        XCTAssertNotNil(lookup.contact)

        let request = try XCTUnwrap(transport.requests.first)
        let components = try XCTUnwrap(URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false))
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(components.path, "/api/voip-app/v1/contacts/lookup")
        XCTAssertEqual(components.queryItems, [URLQueryItem(name: "number", value: "+31 70 123 4567&x=1")], "the number is one value, not extra parameters")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer \(deviceToken.reveal())")
    }

    // MARK: Timeline

    func testTimelinePagesFollowTheCursor() async throws {
        let (api, transport) = client([try reply(200, "contact-timeline"), try reply(200, "contact-timeline-last")])

        let first = try await api.contactTimeline(contactId: "6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c", limit: 2)
        XCTAssertEqual(first.items.map(\.id), ["e1", "e2"])
        let cursor = try XCTUnwrap(first.nextCursor)

        let second = try await api.contactTimeline(contactId: "6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c", cursor: cursor, limit: 2)
        XCTAssertEqual(second.items.map(\.id), ["e3"])
        XCTAssertNil(second.nextCursor)

        let one = try XCTUnwrap(URLComponents(url: try XCTUnwrap(transport.requests[0].url), resolvingAgainstBaseURL: false))
        let two = try XCTUnwrap(URLComponents(url: try XCTUnwrap(transport.requests[1].url), resolvingAgainstBaseURL: false))
        XCTAssertEqual(one.path, "/api/voip-app/v1/contacts/6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c/timeline")
        XCTAssertEqual(one.queryItems, [URLQueryItem(name: "limit", value: "2")])
        XCTAssertEqual(two.queryItems, [URLQueryItem(name: "limit", value: "2"), URLQueryItem(name: "cursor", value: cursor)])
    }

    func testTheLimitIsClampedAndTheIdStaysOneSegment() async throws {
        let (api, transport) = client([try reply(200, "contact-timeline-last")])
        _ = try await api.contactTimeline(contactId: "../admin/x", limit: 5000)

        let request = try XCTUnwrap(transport.requests.first)
        let components = try XCTUnwrap(URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false))
        XCTAssertEqual(components.queryItems?.first, URLQueryItem(name: "limit", value: "100"))
        XCTAssertFalse(components.percentEncodedPath.contains("/admin/"), components.percentEncodedPath)
        XCTAssertTrue(components.percentEncodedPath.hasSuffix("/timeline"))
    }

    func testAnErrorStatusIsAnAPIError() async throws {
        let (api, _) = client([.init(status: 404, body: try Fixtures.data("error-not-found"))])

        do {
            _ = try await api.contactTimeline(contactId: "6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c")
            XCTFail("expected an error")
        } catch let error as APIError {
            XCTAssertEqual(error, .notFound)
        }
    }

    // MARK: The one second

    func testTheTimeoutGivesUpOnASlowLookup() async {
        let started = Date()
        let result: Int? = await CallerLookupTimeout.run(seconds: 0.1) {
            try await Task.sleep(nanoseconds: 5_000_000_000)

            return 1
        }

        XCTAssertNil(result)
        XCTAssertLessThan(Date().timeIntervalSince(started), 2, "the slow operation is cancelled, not awaited")
    }

    func testTheTimeoutPassesAFastAnswerAndSwallowsErrors() async {
        let fast: Int? = await CallerLookupTimeout.run(seconds: 2) { 7 }
        XCTAssertEqual(fast, 7)

        struct Boom: Error {}
        let failed: Int? = await CallerLookupTimeout.run(seconds: 2) { throw Boom() }
        XCTAssertNil(failed)
    }

    func testTheIncomingCallWaitsAtMostOneSecond() {
        XCTAssertEqual(CallerLookupTimeout.incomingCall, 1)
    }

    // MARK: The notice push

    func testTheNoticeFixturesDecodeAsTheyArriveFromAPNs() throws {
        let message = try PushMessage.decode(apnsPayload: Fixtures.data("apns-notice-body"))

        guard case let .notice(notice) = message else {
            return XCTFail("expected a notice")
        }

        XCTAssertEqual(notice.title, "Nieuwe bestelling")
        XCTAssertEqual(notice.accountId, "3f0c2b1e-8a4d-4d6f-9b7a-1c2d3e4f5a6b")
        XCTAssertTrue(notice.href.contains("/orders/"))

        // The bare object (FCM carries it like this) is the same message.
        XCTAssertEqual(try decoder.decode(PushMessage.self, from: Fixtures.data("push-notice")), message)
    }

    func testANoticeWithoutAnHrefIsRejected() {
        let json = #"{"v":1,"type":"notice","accountId":"a","title":"t","body":"b"}"#

        XCTAssertThrowsError(try decoder.decode(PushMessage.self, from: Data(json.utf8)))
    }

    func testAnUnknownPushTypeStillThrowsSoItIsNotMistakenForANotice() {
        let json = #"{"v":1,"type":"surprise","accountId":"a"}"#

        XCTAssertThrowsError(try decoder.decode(PushMessage.self, from: Data(json.utf8))) { error in
            XCTAssertEqual(error as? PushMessageError, .unknownType("surprise"))
        }
    }
}

/// Where a tap on a notice goes.
final class NoticeRouteTests: XCTestCase {
    private let pbx = "99999999-8888-4777-8666-555555555555"
    private let project = "11111111-2222-4333-8444-555555555555"

    func testKnownPortalPathsMapToTheirScreens() {
        XCTAssertEqual(NoticeRoute.destination(forHref: "https://fullstackstudio.nl/portal/voip/\(pbx)/calls"), .recents)
        XCTAssertEqual(NoticeRoute.destination(forHref: "https://fullstackstudio.nl/portal/voip/\(pbx)/voicemail"), .voicemail)
        XCTAssertEqual(NoticeRoute.destination(forHref: "https://fullstackstudio.nl/portal/contacts"), .contacts)
        XCTAssertEqual(NoticeRoute.destination(forHref: "https://fullstackstudio.nl/portal/contacts/duplicates"), .contacts)
        XCTAssertEqual(NoticeRoute.destination(forHref: "/portal/voip/\(pbx)/calls?month=2026-10#top"), .recents, "a bare path, with query and fragment")
    }

    func testTicketsHaveNoScreenInTheAppAndOpenHome() {
        XCTAssertEqual(NoticeRoute.destination(forHref: "https://fullstackstudio.nl/portal/content/\(project)/tickets/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"), .home)
        XCTAssertEqual(NoticeRoute.destination(forHref: "https://fullstackstudio.nl/portal/content/\(project)/orders"), .home)
        XCTAssertEqual(NoticeRoute.destination(forHref: "https://fullstackstudio.nl/portal/billing/abc"), .home)
        XCTAssertEqual(NoticeRoute.destination(forHref: "https://fullstackstudio.nl/portal"), .home)
        XCTAssertEqual(NoticeRoute.destination(forHref: "https://fullstackstudio.nl/portal/voip"), .home)
        XCTAssertEqual(NoticeRoute.destination(forHref: "https://fullstackstudio.nl/portal/voip/\(pbx)"), .home)
        XCTAssertEqual(NoticeRoute.destination(forHref: "https://fullstackstudio.nl/portal/voip/\(pbx)/numbers"), .home)
    }

    func testAnythingUnreadableOrOutsideThePortalOpensHome() {
        for href in ["", "  ", "not a url", "ftp://x/portal/contacts", "mailto:a@b.nl", "https://fullstackstudio.nl/admin/contacts", "https://fullstackstudio.nl/", "javascript:alert(1)"] {
            XCTAssertEqual(NoticeRoute.destination(forHref: href), .home, href)
        }
    }

    func testTraversalAndEncodedSlashesNeverMatch() {
        XCTAssertEqual(NoticeRoute.destination(forHref: "https://fullstackstudio.nl/portal/../admin/contacts"), .home)
        XCTAssertEqual(NoticeRoute.destination(forHref: "https://fullstackstudio.nl/portal/voip/\(pbx)%2Fcalls"), .home)
        XCTAssertEqual(NoticeRoute.destination(forHref: "https://fullstackstudio.nl/portal/voip/\(pbx)/calls\u{0}"), .home)
        XCTAssertEqual(NoticeRoute.destination(forHref: String(repeating: "a", count: 600)), .home)
    }

    func testTheHostIsIgnoredSoALookalikeHostCannotRedirectAnything() {
        // The app never opens the link; it only maps the path. An odd host therefore changes nothing but the path is still read.
        XCTAssertEqual(NoticeRoute.destination(forHref: "https://evil.example/portal/contacts"), .contacts)
    }
}
