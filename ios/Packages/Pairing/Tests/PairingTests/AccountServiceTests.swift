// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import XCTest
@testable import Pairing

/// Answers per path, records requests.
private final class ScriptedTransport: HTTPTransport, @unchecked Sendable {
    var replies: [String: (Int, Data)] = [:]
    var failure: Error?
    private let lock = NSLock()
    private(set) var requests: [URLRequest] = []

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        lock.withLock { requests.append(request) }

        if let failure {
            throw failure
        }

        let key = "\(request.httpMethod ?? "GET") \(request.url!.lastPathComponent)"
        let reply = replies[key] ?? (500, Data())

        return (reply.1, HTTPURLResponse(url: request.url!, statusCode: reply.0, httpVersion: nil, headerFields: nil)!)
    }
}

final class AccountServiceTests: XCTestCase {
    private func fixture(_ name: String) throws -> Data {
        var url = URL(fileURLWithPath: #filePath)

        for _ in 0 ..< 6 {
            url.deleteLastPathComponent()
        }

        return try Data(contentsOf: url.appendingPathComponent("shared/fixtures/\(name).json"))
    }

    private func setUp(_ transport: ScriptedTransport) throws -> (AccountService, AccountStore, StoredAccount) {
        let store = AccountStore(secrets: InMemorySecretStore())
        let pair = try FSVoipJSON.decoder().decode(PairResponse.self, from: try fixture("pair-response"))
        let account = StoredAccount(pairing: pair, pairedAt: Date(timeIntervalSince1970: 1))
        try store.save(account)

        return (AccountService(api: FSVoipAPIClient(transport: transport), accounts: store), store, account)
    }

    func testRefreshUpdatesTheStoredAccountWithTheDeviceToken() async throws {
        let transport = ScriptedTransport()
        transport.replies["GET me"] = (200, try fixture("me-response"))
        let (service, store, account) = try setUp(transport)

        let result = try await service.refresh(account)

        guard case let .updated(updated, contacts) = result else {
            return XCTFail("expected updated")
        }

        XCTAssertEqual(updated.displayLabel, "Jan (balie)")
        XCTAssertEqual(contacts.map(\.number), ["100", "101", "103"])
        XCTAssertEqual(try store.account(id: account.id), updated)
        XCTAssertEqual(transport.requests.first?.value(forHTTPHeaderField: "Authorization"), "Bearer \(account.deviceToken.reveal())")
    }

    func testRefreshRemovesARevokedAccount() async throws {
        let transport = ScriptedTransport()
        transport.replies["GET me"] = (401, try fixture("error-unauthorized"))
        let (service, store, account) = try setUp(transport)

        let result = try await service.refresh(account)

        XCTAssertEqual(result, .revoked)
        XCTAssertNil(try store.account(id: account.id))
    }

    func testRefreshOfflineKeepsTheAccount() async throws {
        let transport = ScriptedTransport()
        transport.failure = URLError(.notConnectedToInternet)
        let (service, store, account) = try setUp(transport)

        do {
            _ = try await service.refresh(account)
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(PairingFailure(error), .network)
        }

        XCTAssertNotNil(try store.account(id: account.id))
    }

    func testRenameSendsTheCleanedAliasAndStoresTheAnswer() async throws {
        let transport = ScriptedTransport()
        transport.replies["PATCH me"] = (200, try fixture("me-patch-response"))
        let (service, store, account) = try setUp(transport)

        let updated = try await service.rename(account, alias: "  Jan (balie)\n ")

        let body = try JSONSerialization.jsonObject(with: try XCTUnwrap(transport.requests.first?.httpBody)) as? [String: Any]
        XCTAssertEqual(body?["labelOverride"] as? String, "Jan (balie)")
        XCTAssertEqual(try store.account(id: account.id), updated)
    }

    func testClearingTheAliasSendsAnExplicitNull() async throws {
        let transport = ScriptedTransport()
        transport.replies["PATCH me"] = (200, try fixture("me-patch-response"))
        let (service, _, account) = try setUp(transport)

        _ = try await service.rename(account, alias: "   ")

        let text = String(decoding: try XCTUnwrap(transport.requests.first?.httpBody), as: UTF8.self)
        XCTAssertEqual(text, #"{"labelOverride":null}"#)
    }

    func testUnpairRemovesTheAccount() async throws {
        let transport = ScriptedTransport()
        transport.replies["POST unpair"] = (200, try fixture("ok-response"))
        let (service, store, account) = try setUp(transport)

        try await service.unpair(account)

        XCTAssertNil(try store.account(id: account.id))
    }

    func testUnpairOfAnAlreadyRevokedAccountCountsAsDone() async throws {
        let transport = ScriptedTransport()
        transport.replies["POST unpair"] = (401, try fixture("error-unauthorized"))
        let (service, store, account) = try setUp(transport)

        try await service.unpair(account)

        XCTAssertNil(try store.account(id: account.id))
    }

    func testUnpairOfflineKeepsTheAccountUntilForget() async throws {
        let transport = ScriptedTransport()
        transport.failure = URLError(.timedOut)
        let (service, store, account) = try setUp(transport)

        do {
            try await service.unpair(account)
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(PairingFailure(error), .network)
        }

        XCTAssertNotNil(try store.account(id: account.id))

        try service.forget(account)
        XCTAssertNil(try store.account(id: account.id))
    }

    func testAliasCleaning() {
        XCTAssertNil(AccountService.cleanAlias(nil))
        XCTAssertNil(AccountService.cleanAlias(" \n "))
        XCTAssertEqual(AccountService.cleanAlias("a\nb"), "a b")
        XCTAssertEqual(AccountService.cleanAlias(String(repeating: "x", count: 80))?.count, 60)
    }
}

final class PairingFailureTests: XCTestCase {
    func testMapping() {
        XCTAssertEqual(PairingFailure(APIError.notFound), .codeExpiredOrUsed)
        XCTAssertEqual(PairingFailure(APIError.unauthorized), .revoked)
        XCTAssertEqual(PairingFailure(APIError.rateLimited(retryAfterSeconds: 30)), .tooManyAttempts(retryAfterSeconds: 30))
        XCTAssertEqual(PairingFailure(APIError.unavailable(retryable: true, retryAfterSeconds: nil)), .temporarilyUnavailable)
        XCTAssertEqual(PairingFailure(APIError.transport("offline")), .network)
        XCTAssertEqual(PairingFailure(APIError.decoding("x")), .other)
        XCTAssertEqual(PairingFailure(SecretStoreError.keychain(-25308)), .storage)
        XCTAssertEqual(PairingFailure(URLError(.notConnectedToInternet)), .network)
    }

    func testOnlyAnExpiredOrUsedCodeOrARevocationIsFinal() {
        XCTAssertFalse(PairingFailure.codeExpiredOrUsed.isRetryable)
        XCTAssertFalse(PairingFailure.revoked.isRetryable)
        XCTAssertTrue(PairingFailure.temporarilyUnavailable.isRetryable, "the server did not consume the code")
        XCTAssertTrue(PairingFailure.network.isRetryable)
    }
}
