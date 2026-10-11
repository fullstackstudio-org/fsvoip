// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import Core

/// A cancelled request is not "no connection", and a dropped kept-alive connection is retried once for a read (TestFlight: Opnames
/// said "Geen verbinding" while the internet worked).
final class TransportErrorTests: XCTestCase {
    private final class ThrowingTransport: HTTPTransport, @unchecked Sendable {
        let error: Error
        init(_ error: Error) { self.error = error }
        func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) { throw error }
    }

    private func client(_ error: Error) -> FSVoipAPIClient {
        FSVoipAPIClient(deviceToken: Secret("fss_vapp_" + String(repeating: "x", count: 43)), transport: ThrowingTransport(error), userAgent: "t", logger: FSLogger(category: "t", sink: MemoryLogSink()))
    }

    func testACancelledRequestIsACancellationNotATransportError() async {
        do {
            _ = try await client(URLError(.cancelled)).calls()
            XCTFail("expected an error")
        } catch {
            XCTAssertTrue(error is CancellationError, "got \(error)")
            XCTAssertTrue(APIError.isCancellation(error))
        }
    }

    func testARealNetworkErrorIsStillTransport() async {
        do {
            _ = try await client(URLError(.notConnectedToInternet)).calls()
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(MediaFailure.classify(error), .offline)
            XCTAssertFalse(APIError.isCancellation(error))
        }
    }

    func testOnlyReadsAreRetriedAndOnlyForALostConnection() {
        XCTAssertTrue(URLSessionTransport.retriesOnce(URLError(.networkConnectionLost), method: "GET"))
        XCTAssertTrue(URLSessionTransport.retriesOnce(URLError(.networkConnectionLost), method: nil))
        XCTAssertFalse(URLSessionTransport.retriesOnce(URLError(.networkConnectionLost), method: "POST"), "a write is never sent twice")
        XCTAssertFalse(URLSessionTransport.retriesOnce(URLError(.notConnectedToInternet), method: "GET"))
        XCTAssertFalse(URLSessionTransport.retriesOnce(URLError(.cancelled), method: "GET"))
    }

    func testALostConnectionOnAReadGoesOnceMore() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [FlakyProtocol.self]
        FlakyProtocol.reset(failures: 1)
        let transport = URLSessionTransport(session: URLSession(configuration: configuration))

        let (_, response) = try await transport.send(URLRequest(url: URL(string: "https://fullstackstudio.nl/api/voip-app/v1/calls")!))

        XCTAssertEqual(response.statusCode, 200)
        XCTAssertEqual(FlakyProtocol.attempts, 2)
    }
}

/// Fails the first `failures` requests with "connection lost", then answers 200.
final class FlakyProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) private static var remaining = 0
    nonisolated(unsafe) static var attempts = 0
    private static let lock = NSLock()

    static func reset(failures: Int) {
        lock.withLock {
            remaining = failures
            attempts = 0
        }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let fail: Bool = Self.lock.withLock {
            Self.attempts += 1
            guard Self.remaining > 0 else { return false }
            Self.remaining -= 1
            return true
        }

        if fail {
            client?.urlProtocol(self, didFailWithError: URLError(.networkConnectionLost))
            return
        }

        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: [:])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data("{}".utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
