// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import Core

final class BeheerAPIClientTests: XCTestCase {
    private let deviceToken = Secret("fss_vapp_" + String(repeating: "x", count: 43))
    private let base = "https://fullstackstudio.nl/api/voip-app/v1"

    private func client(_ replies: [MockTransport.Reply]) -> (FSVoipAPIClient, MockTransport) {
        let transport = MockTransport(replies)
        let api = FSVoipAPIClient(deviceToken: deviceToken, transport: transport, userAgent: "FSVoip/1.0 (test)", logger: FSLogger(category: "test", sink: MemoryLogSink()))

        return (api, transport)
    }

    private func reply(_ status: Int, _ fixture: String, headers: [String: String] = [:]) throws -> MockTransport.Reply {
        .init(status: status, body: try Fixtures.data(fixture), headers: headers)
    }

    private func bodyText(_ request: URLRequest) throws -> String {
        String(decoding: try XCTUnwrap(request.httpBody), as: UTF8.self)
    }

    // MARK: Routes and verbs

    func testPbxReadRoutes() async throws {
        let (api, transport) = client([
            try reply(200, "pbx-overview"), try reply(200, "pbx-devices"), try reply(200, "pbx-ring-groups"), try reply(200, "pbx-hours"),
        ])

        _ = try await api.pbxOverview()
        _ = try await api.pbxDevices()
        _ = try await api.pbxRingGroups()
        _ = try await api.pbxHours()

        XCTAssertEqual(transport.requests.map { $0.url?.absoluteString }, ["\(base)/pbx/overview", "\(base)/pbx/devices", "\(base)/pbx/ring-groups", "\(base)/pbx/hours"])
        XCTAssertTrue(transport.requests.allSatisfy { $0.httpMethod == "GET" && $0.httpBody == nil })
        XCTAssertTrue(transport.requests.allSatisfy { $0.value(forHTTPHeaderField: "Authorization") == "Bearer \(deviceToken.reveal())" })
    }

    func testPbxWriteRoutes() async throws {
        let id = "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57"
        let (api, transport) = client([
            try reply(200, "ok-response"), try reply(201, "pbx-ring-group-created"), try reply(200, "ok-response"), try reply(200, "ok-response"), try reply(200, "ok-response"),
        ])

        try await api.updatePbxDevice(id: id, patch: PbxDevicePatch(version: 4, dnd: true))
        let created = try await api.createPbxRingGroup(PbxRingGroupCreate(name: "Support"))
        try await api.updatePbxRingGroup(id: id, patch: PbxRingGroupPatch(version: 2, name: "Nieuw"))
        try await api.updatePbxHours(id: id, patch: PbxHoursPatch(version: 6, closedTarget: .clear))
        try await api.setPbxNumberRouting(numberId: id, patch: PbxRoutingPatch(target: nil, version: 3))

        XCTAssertEqual(created.ringGroupId, "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")
        XCTAssertEqual(transport.requests.map(\.httpMethod), ["PATCH", "POST", "PATCH", "PATCH", "PATCH"])
        XCTAssertEqual(
            transport.requests.map { $0.url?.absoluteString },
            ["\(base)/pbx/devices/\(id)", "\(base)/pbx/ring-groups", "\(base)/pbx/ring-groups/\(id)", "\(base)/pbx/hours/\(id)", "\(base)/pbx/numbers/\(id)/routing"]
        )
        XCTAssertEqual(try bodyText(transport.requests[0]), #"{"dnd":true,"version":4}"#)
        XCTAssertEqual(try bodyText(transport.requests[3]), #"{"closedTarget":null,"version":6}"#)
        XCTAssertEqual(try bodyText(transport.requests[4]), #"{"target":null,"version":3}"#)
    }

    func testCallsAndVoicemailQueries() async throws {
        let (api, transport) = client([try reply(200, "calls-page"), try reply(200, "calls-page"), try reply(200, "voicemail-page"), try reply(200, "ok-response")])

        _ = try await api.calls()
        _ = try await api.calls(month: "2026-09", locale: "en")
        _ = try await api.voicemail(box: "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57", locale: "nl")
        try await api.deleteVoicemail(boxId: "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57", ref: "v1.Zm9vYmFy")

        XCTAssertEqual(transport.requests[0].url?.absoluteString, "\(base)/calls")
        XCTAssertEqual(transport.requests[1].url?.absoluteString, "\(base)/calls?month=2026-09&locale=en")
        XCTAssertEqual(transport.requests[2].url?.absoluteString, "\(base)/voicemail?box=7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57&locale=nl")
        XCTAssertEqual(transport.requests[3].httpMethod, "DELETE")
        XCTAssertEqual(transport.requests[3].url?.absoluteString, "\(base)/voicemail/7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57/v1.Zm9vYmFy")
    }

    // MARK: Media

    func testMediaRequestsCarryTheBearerHeaderAndNeverALoggableToken() throws {
        let (api, transport) = client([])
        let recording = try api.recordingMedia(callId: "11111111-aaaa-4bbb-8ccc-000000000001")
        let voicemail = try api.voicemailMedia(boxId: "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57", ref: "v1.Zm9vYmFy")

        XCTAssertEqual(recording.url.absoluteString, "\(base)/calls/11111111-aaaa-4bbb-8ccc-000000000001/recording")
        XCTAssertEqual(voicemail.url.absoluteString, "\(base)/voicemail/7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57/v1.Zm9vYmFy/audio")
        XCTAssertEqual(recording.headers()["Authorization"], "Bearer \(deviceToken.reveal())")
        XCTAssertEqual(voicemail.urlRequest().value(forHTTPHeaderField: "Authorization"), "Bearer \(deviceToken.reveal())")
        XCTAssertEqual(voicemail.urlRequest(method: "HEAD").httpMethod, "HEAD")
        XCTAssertFalse(recording.url.absoluteString.contains("fss_vapp_"), "the token is never in the URL")

        for text in ["\(recording)", String(reflecting: recording), String(describing: voicemail)] {
            XCTAssertFalse(text.contains("fss_vapp_"), text)
        }

        XCTAssertTrue(transport.requests.isEmpty, "building a media request does no network call")
    }

    func testMediaRequestNeedsAToken() {
        let api = FSVoipAPIClient(transport: MockTransport([]))

        XCTAssertThrowsError(try api.recordingMedia(callId: "x")) { XCTAssertEqual($0 as? APIError, .missingDeviceToken) }
    }

    // MARK: Contacts

    func testContactRoutes() async throws {
        let id = "6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c"
        let (api, transport) = client([
            try reply(200, "contacts-page"), try reply(200, "contact-detail"), try reply(201, "contact-detail"), try reply(200, "contact-update-response"),
            try reply(200, "contact-delete-response"), try reply(200, "contact-lists"),
        ])

        _ = try await api.contactsPage(since: "2026-10-09T08:15:30.123Z", cursor: "abc", limit: 100)
        let one = try await api.contact(id: id)
        _ = try await api.createContact(ContactCreate(name: "Bakkerij Smit"))
        let updated = try await api.updateContact(id: id, ContactUpdate(expectedUpdatedAt: one.contact.updatedAt, company: .clear))
        let deleted = try await api.deleteContact(id: id)
        let lists = try await api.contactLists()

        XCTAssertTrue(updated.changed)
        XCTAssertEqual(deleted, 1)
        XCTAssertEqual(lists.count, 2)
        XCTAssertEqual(transport.requests.map(\.httpMethod), ["GET", "GET", "POST", "PATCH", "DELETE", "GET"])

        let url = try XCTUnwrap(transport.requests[0].url)
        let items = Dictionary(uniqueKeysWithValues: (URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(items, ["since": "2026-10-09T08:15:30.123Z", "cursor": "abc", "limit": "100"])
        XCTAssertEqual(try bodyText(transport.requests[3]), #"{"company":null,"expectedUpdatedAt":"2026-10-07T09:30:12.345Z"}"#)
        XCTAssertEqual(transport.requests[4].url?.absoluteString, "\(base)/contacts/\(id)")
    }

    func testSyncContactsFollowsTheCursorAndKeepsTheFirstServerTime() async throws {
        let page1 = Data(#"{"contacts":[{"id":"a","name":"A","phones":[],"tags":[],"updatedAt":"2026-10-09T08:00:00.000Z"},{"id":"b","name":"B","phones":[],"tags":[],"updatedAt":"2026-10-09T08:00:01.000Z"}],"deleted":["x"],"nextCursor":"c2","serverTime":"2026-10-09T08:15:30.123Z"}"#.utf8)
        // The overlap sends "b" again with a newer version; "x" comes in again and "b"'s id is also listed as deleted earlier (stale).
        let page2 = Data(#"{"contacts":[{"id":"b","name":"B2","phones":[],"tags":[],"updatedAt":"2026-10-09T08:10:00.000Z"}],"deleted":["x","b"],"nextCursor":null,"serverTime":"2026-10-09T08:15:30.123Z"}"#.utf8)
        let (api, transport) = client([.init(status: 200, body: page1), .init(status: 200, body: page2)])

        let result = try await api.syncContacts(since: "2026-10-09T07:00:00.000Z", pageSize: 2)

        XCTAssertEqual(result.contacts.map(\.id), ["a", "b"])
        XCTAssertEqual(result.contacts.map(\.name), ["A", "B2"], "the last version wins")
        XCTAssertEqual(result.deleted, ["x"], "an id that is also a live contact is not a deletion")
        XCTAssertEqual(result.serverTime, "2026-10-09T08:15:30.123Z")
        XCTAssertFalse(result.isFull)
        XCTAssertEqual(transport.requests.count, 2)
        XCTAssertTrue(try XCTUnwrap(transport.requests[1].url?.query).contains("cursor=c2"))
        XCTAssertTrue(try XCTUnwrap(transport.requests[1].url?.query).contains("since="))
    }

    func testFullSyncHasNoSinceAndIsMarkedFull() async throws {
        let (api, transport) = client([try reply(200, "contacts-page")])

        let result = try await api.syncContacts()

        XCTAssertTrue(result.isFull)
        XCTAssertEqual(result.contacts.count, 2)
        XCTAssertNil(transport.requests[0].url?.query)
    }

    func testResyncIsAnError() async throws {
        let (api, _) = client([try reply(400, "error-resync")])

        do {
            _ = try await api.syncContacts(since: "2025-01-01T00:00:00.000Z")
            XCTFail("expected resync")
        } catch {
            XCTAssertEqual(error as? APIError, .resync)
        }
    }

    func testSnapshotSendsIfNoneMatchAndHandles304() async throws {
        let (api, transport) = client([
            try reply(200, "contact-list-snapshot", headers: ["ETag": "\"3\""]),
            .init(status: 304, body: Data(), headers: ["ETag": "\"3\""]),
        ])
        let list = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

        guard case let .snapshot(snapshot, etag) = try await api.contactListSnapshot(listId: list) else {
            return XCTFail("expected a snapshot")
        }

        XCTAssertEqual(snapshot.contacts.count, 2)
        XCTAssertEqual(etag, "\"3\"")
        XCTAssertNil(transport.requests[0].value(forHTTPHeaderField: "If-None-Match"))

        let second = try await api.contactListSnapshot(listId: list, etag: etag)

        XCTAssertEqual(second, .notModified(etag: "\"3\""))
        XCTAssertEqual(transport.requests[1].value(forHTTPHeaderField: "If-None-Match"), "\"3\"")
        XCTAssertEqual(transport.requests[1].url?.absoluteString, "\(base)/contact-lists/\(list)/contacts")
    }

    func testSnapshotCursorPageDoesNotSendIfNoneMatch() async throws {
        let (api, transport) = client([try reply(200, "contact-list-snapshot", headers: ["ETag": "\"3\""])])

        _ = try await api.contactListSnapshot(listId: "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", cursor: "next", etag: "\"3\"")

        XCTAssertNil(transport.requests[0].value(forHTTPHeaderField: "If-None-Match"))
        XCTAssertTrue(try XCTUnwrap(transport.requests[0].url?.query).contains("cursor=next"))
    }

    func testA304IsAnErrorWhereNoConditionalGetWasMade() async throws {
        let (api, _) = client([.init(status: 304, body: Data())])

        do {
            _ = try await api.contactLists()
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? APIError, .unexpectedStatus(304))
        }
    }

    // MARK: Error mapping

    func testStatusCodesOfTheBeheerRoutesMapToErrors() async throws {
        let cases: [(Int, String, [String: String], APIError)] = [
            (403, "error-forbidden", [:], .forbidden),
            (409, "error-read-only", [:], .readOnly),
            (409, "error-stale", [:], .stale(version: 5)),
            (409, "error-stale-contact", [:], .stale(version: nil)),
            (409, "error-in-use", [:], .inUse(places: [APIErrorPlace(kind: "object", name: "Kantoortijden")])),
            (409, "error-conflict-limit", [:], .conflict(code: "limit_reached")),
            (410, "error-gone", [:], .gone),
            (422, "error-blocked-destination", [:], .blockedDestination(["+449000000000"])),
            (400, "error-resync", [:], .resync),
            (400, "error-invalid-field", [:], .invalid(code: "invalid_phone", field: "phones")),
            (429, "error-rate-limited", ["Retry-After": "60"], .rateLimited(retryAfterSeconds: 60)),
        ]

        for (status, fixture, headers, expected) in cases {
            let (api, _) = client([.init(status: status, body: try Fixtures.data(fixture), headers: headers)])

            do {
                _ = try await api.pbxOverview()
                XCTFail("expected \(expected)")
            } catch {
                XCTAssertEqual(error as? APIError, expected, fixture)
            }
        }
    }

    func testOtherConflictsKeepTheirCode() async throws {
        let (api, _) = client([.init(status: 409, body: Data(#"{"error":"not_ready"}"#.utf8))])

        do {
            _ = try await api.pbxDevices()
            XCTFail("expected a conflict")
        } catch {
            XCTAssertEqual(error as? APIError, .conflict(code: "not_ready"))
        }
    }

    func testAnErrorWithoutAJSONBodyStillMaps() async throws {
        for (status, expected) in [(403, APIError.forbidden), (410, .gone), (409, .conflict(code: "conflict"))] {
            let (api, _) = client([.init(status: status, body: Data("<html>".utf8))])

            do {
                _ = try await api.pbxOverview()
                XCTFail("expected \(expected)")
            } catch {
                XCTAssertEqual(error as? APIError, expected)
            }
        }
    }

    func testBeheerClientNeverLogsTokensOrQueries() async throws {
        let sink = MemoryLogSink()
        let transport = MockTransport([.init(status: 200, body: try Fixtures.data("contacts-page")), .init(status: 200, body: try Fixtures.data("ok-response"))])
        let api = FSVoipAPIClient(deviceToken: deviceToken, transport: transport, logger: FSLogger(category: "api", sink: sink))

        _ = try await api.contactsPage(since: "2026-10-09T08:15:30.123Z", cursor: "secretcursor")
        try await api.deleteVoicemail(boxId: "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57", ref: "v1.sealedref")

        let log = sink.messages.joined(separator: "\n")
        XCTAssertFalse(log.isEmpty)
        XCTAssertFalse(log.contains("fss_vapp_"))
        XCTAssertFalse(log.contains("secretcursor"))
        XCTAssertFalse(log.contains("sealedref"))
        XCTAssertFalse(log.contains("2026-10-09T08"))
    }
}
