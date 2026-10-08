// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import Core

final class MockTransport: HTTPTransport, @unchecked Sendable {
    struct Reply {
        var status: Int
        var body: Data
        var headers: [String: String] = [:]
    }

    private let lock = NSLock()
    private var replies: [Reply]
    private(set) var requests: [URLRequest] = []

    init(_ replies: [Reply]) {
        self.replies = replies
    }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let reply: Reply = lock.withLock {
            requests.append(request)

            return replies.isEmpty ? Reply(status: 500, body: Data()) : replies.removeFirst()
        }

        let url = request.url ?? URL(string: "https://example.invalid")!
        let response = HTTPURLResponse(url: url, statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: reply.headers)!

        return (reply.body, response)
    }
}

final class APIClientTests: XCTestCase {
    private let deviceToken = Secret("fss_vapp_" + String(repeating: "x", count: 43))

    private func client(_ replies: [MockTransport.Reply], token: Secret? = nil) -> (FSVoipAPIClient, MockTransport) {
        let transport = MockTransport(replies)
        let sink = MemoryLogSink()
        let api = FSVoipAPIClient(deviceToken: token, transport: transport, userAgent: "FSVoip/1.0 (test)", logger: FSLogger(category: "test", sink: sink))

        return (api, transport)
    }

    func testPairPostsTheRequestWithoutAuthorization() async throws {
        let (api, transport) = client([.init(status: 201, body: try Fixtures.data("pair-response"))])
        let request = try FSVoipJSON.decoder().decode(PairRequest.self, from: Fixtures.data("pair-request"))

        let response = try await api.pair(request)

        XCTAssertEqual(response.sip.username, "102")

        let sent = try XCTUnwrap(transport.requests.first)
        XCTAssertEqual(sent.httpMethod, "POST")
        XCTAssertEqual(sent.url?.absoluteString, "https://fullstackstudio.nl/api/voip-app/v1/pair")
        XCTAssertNil(sent.value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual(sent.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertEqual(sent.value(forHTTPHeaderField: "User-Agent"), "FSVoip/1.0 (test)")
    }

    func testMeSendsTheBearerToken() async throws {
        let (api, transport) = client([.init(status: 200, body: try Fixtures.data("me-response"))], token: deviceToken)

        _ = try await api.me()

        let sent = try XCTUnwrap(transport.requests.first)
        XCTAssertEqual(sent.httpMethod, "GET")
        XCTAssertEqual(sent.value(forHTTPHeaderField: "Authorization"), "Bearer \(deviceToken.reveal())")
        XCTAssertNil(sent.httpBody)
        XCTAssertFalse(sent.url?.absoluteString.contains("fss_vapp_") ?? true, "the token must never be in the URL")
    }

    func testAuthenticatedRoutesNeedAToken() async throws {
        let (api, transport) = client([])

        do {
            _ = try await api.me()
            XCTFail("expected missingDeviceToken")
        } catch {
            XCTAssertEqual(error as? APIError, .missingDeviceToken)
        }

        XCTAssertTrue(transport.requests.isEmpty)
    }

    func testPatchAndPushTokenAndUnpair() async throws {
        let (api, transport) = client(
            [
                .init(status: 200, body: try Fixtures.data("me-patch-response")),
                .init(status: 200, body: try Fixtures.data("ok-response")),
                .init(status: 200, body: try Fixtures.data("ok-response")),
            ],
            token: deviceToken
        )

        let patched = try await api.setLabelOverride("Jan (balie)")
        XCTAssertEqual(patched.label, "Jan (balie)")
        try await api.updatePushToken(.clear)
        try await api.unpair()

        XCTAssertEqual(transport.requests.map(\.httpMethod), ["PATCH", "PUT", "POST"])
        XCTAssertEqual(transport.requests.map { $0.url?.lastPathComponent }, ["me", "push-token", "unpair"])
        XCTAssertEqual(String(decoding: try XCTUnwrap(transport.requests[0].httpBody), as: UTF8.self), #"{"labelOverride":"Jan (balie)"}"#)
        XCTAssertEqual(String(decoding: try XCTUnwrap(transport.requests[1].httpBody), as: UTF8.self), #"{"pushToken":null}"#)
    }

    func testStatusCodesMapToErrors() async throws {
        let cases: [(Int, String, [String: String], APIError)] = [
            (404, "error-not-found", [:], .notFound),
            (401, "error-unauthorized", ["WWW-Authenticate": "Bearer"], .unauthorized),
            (429, "error-rate-limited", ["Retry-After": "60"], .rateLimited(retryAfterSeconds: 60)),
            (400, "error-invalid-request", [:], .invalidRequest(message: "device.platform moet ios of android zijn.")),
            (503, "error-unavailable-retryable", ["Retry-After": "5"], .unavailable(retryable: true, retryAfterSeconds: 5)),
        ]

        for (status, fixture, headers, expected) in cases {
            let (api, _) = client([.init(status: status, body: try Fixtures.data(fixture), headers: headers)], token: deviceToken)

            do {
                _ = try await api.me()
                XCTFail("expected \(expected)")
            } catch {
                XCTAssertEqual(error as? APIError, expected)
            }
        }
    }

    func testUnexpectedStatusAndGarbage() async throws {
        let (api, _) = client([.init(status: 502, body: Data("<html>Bad gateway</html>".utf8))], token: deviceToken)

        do {
            _ = try await api.me()
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? APIError, .unexpectedStatus(502))
        }

        let (garbage, _) = client([.init(status: 200, body: Data("not json".utf8))], token: deviceToken)

        do {
            _ = try await garbage.me()
            XCTFail("expected a decoding error")
        } catch let error as APIError {
            guard case .decoding = error else {
                return XCTFail("expected decoding, got \(error)")
            }
        }
    }

    func testClientNeverLogsSecrets() async throws {
        let sink = MemoryLogSink()
        let transport = MockTransport([.init(status: 201, body: try Fixtures.data("pair-response"))])
        let api = FSVoipAPIClient(transport: transport, logger: FSLogger(category: "api", sink: sink))
        let request = try FSVoipJSON.decoder().decode(PairRequest.self, from: Fixtures.data("pair-request"))

        _ = try await api.pair(request)

        let log = sink.messages.joined(separator: "\n")
        XCTAssertFalse(log.isEmpty)
        XCTAssertFalse(log.contains("fixture-not-a-real-password"))
        XCTAssertFalse(log.contains("fss_vpair_"))
        XCTAssertFalse(log.contains("fss_vapp_"))
    }
}
