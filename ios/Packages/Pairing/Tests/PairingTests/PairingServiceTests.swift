// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import XCTest
@testable import Pairing

private final class RecordingTransport: HTTPTransport, @unchecked Sendable {
    let reply: (Int, Data)
    private let lock = NSLock()
    private(set) var bodies: [Data] = []

    init(status: Int, body: Data) {
        reply = (status, body)
    }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        lock.withLock { bodies.append(request.httpBody ?? Data()) }

        return (reply.1, HTTPURLResponse(url: request.url!, statusCode: reply.0, httpVersion: nil, headerFields: nil)!)
    }
}

final class PairingServiceTests: XCTestCase {
    private func fixture(_ name: String) throws -> Data {
        // .../ios/Packages/Pairing/Tests/PairingTests/<this file> -> repo root is 6 levels up.
        var url = URL(fileURLWithPath: #filePath)

        for _ in 0 ..< 6 {
            url.deleteLastPathComponent()
        }

        return try Data(contentsOf: url.appendingPathComponent("shared/fixtures/\(name).json"))
    }

    private let token = "fss_vpair_" + String(repeating: "aB3-_", count: 8) + "xyz"

    func testPairStoresTheAccountAndSendsTheDevice() async throws {
        let transport = RecordingTransport(status: 201, body: try fixture("pair-response"))
        let store = AccountStore(secrets: InMemorySecretStore())
        let service = PairingService(api: FSVoipAPIClient(transport: transport), accounts: store)

        let device = DeviceDescriptor(model: "iPhone17,1", osVersion: "26.5", appVersion: "1.0 (1)", installId: "a1b2c3d4e5f60718")
        let account = try await service.pair(try PairingLink(token: token), device: device, now: Date(timeIntervalSince1970: 1_791_462_000))

        XCTAssertEqual(account.sip.username, "102")
        XCTAssertEqual(try store.accounts(), [account])

        let sent = try JSONSerialization.jsonObject(with: try XCTUnwrap(transport.bodies.first)) as? [String: Any]
        XCTAssertEqual(sent?["token"] as? String, token)
        let sentDevice = sent?["device"] as? [String: Any]
        XCTAssertEqual(sentDevice?["platform"] as? String, "ios")
        XCTAssertEqual(sentDevice?["installId"] as? String, "a1b2c3d4e5f60718")
        XCTAssertNil(sentDevice?["pushToken"], "no push token yet: it is registered later with PUT /push-token")
    }

    func testFailedPairingStoresNothing() async throws {
        let transport = RecordingTransport(status: 404, body: try fixture("error-not-found"))
        let store = AccountStore(secrets: InMemorySecretStore())
        let service = PairingService(api: FSVoipAPIClient(transport: transport), accounts: store)

        do {
            _ = try await service.pair(try PairingLink(token: token), device: DeviceDescriptor(model: nil, osVersion: nil, appVersion: nil, installId: "a1b2c3d4e5f60718"))
            XCTFail("expected notFound")
        } catch {
            XCTAssertEqual(error as? APIError, .notFound)
        }

        XCTAssertTrue(try store.accounts().isEmpty)
    }
}
