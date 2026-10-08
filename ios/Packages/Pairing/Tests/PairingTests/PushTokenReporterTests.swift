// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import XCTest
@testable import Pairing

/// Records `PUT /push-token` per device token; answers 200, or the status scripted for a device token.
private final class PushTokenTransport: HTTPTransport, @unchecked Sendable {
    struct Sent: Equatable {
        let deviceToken: String
        let body: [String: AnyHashable]
    }

    private let lock = NSLock()
    private var _sent: [Sent] = []
    var statusFor: [String: Int] = [:]

    var sent: [Sent] {
        lock.withLock { _sent }
    }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let bearer = String(request.value(forHTTPHeaderField: "Authorization")?.dropFirst("Bearer ".count) ?? "")
        let body = (try? JSONSerialization.jsonObject(with: request.httpBody ?? Data())) as? [String: AnyHashable] ?? [:]

        XCTAssertEqual(request.httpMethod, "PUT")
        XCTAssertEqual(request.url?.lastPathComponent, "push-token")
        lock.withLock { _sent.append(Sent(deviceToken: bearer, body: body)) }

        let status = statusFor[bearer] ?? 200
        let data = status == 200 ? Data(#"{"ok":true}"#.utf8) : Data(#"{"error":"unauthorized"}"#.utf8)

        return (data, HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!)
    }
}

final class PushTokenReporterTests: XCTestCase {
    private let voip = Data(repeating: 0xab, count: 32)
    private let alert = Data(repeating: 0xcd, count: 32)

    private func account(_ id: String, token: String) -> StoredAccount {
        StoredAccount(id: id, label: id, pbxName: "P", extensionName: "E", extensionNumber: "100", customerName: "C", deviceToken: Secret(token), installId: "a1b2c3d4e5f60718", sip: SIPCredentials(username: "100", password: Secret("pw"), domain: "p.powervoip.nl", proxy: "sip.powervoip.nl", port: 5061, transport: .tls, srv: true), pairedAt: Date(timeIntervalSince1970: id == "a" ? 1 : 2))
    }

    private func make(_ transport: PushTokenTransport, ledger: PushTokenLedger = InMemoryPushTokenLedger(), env: PushEnvironment = .sandbox, accounts ids: [String] = ["a", "b"]) throws -> (PushTokenReporter, AccountStore) {
        let store = AccountStore(secrets: InMemorySecretStore())

        for id in ids {
            try store.save(account(id, token: "fss_vapp_\(id)"))
        }

        return (PushTokenReporter(api: FSVoipAPIClient(transport: transport), accounts: store, ledger: ledger, environment: env), store)
    }

    func testNothingIsSentBeforePushKitAnswered() async throws {
        let transport = PushTokenTransport()
        let (reporter, _) = try make(transport)

        // An early report (app start) must not clear the server's token.
        let report = await reporter.report()

        XCTAssertEqual(report, PushTokenReport())
        XCTAssertTrue(transport.sent.isEmpty)
    }

    func testBothTokensAndTheEnvironmentGoToEveryAccount() async throws {
        let transport = PushTokenTransport()
        let (reporter, _) = try make(transport)
        await reporter.setVoipToken(voip)
        await reporter.setAlertToken(alert)

        let report = await reporter.report()

        XCTAssertEqual(report.sent, ["a", "b"])
        XCTAssertEqual(transport.sent.map(\.deviceToken), ["fss_vapp_a", "fss_vapp_b"])
        // Exactly the contract fixture `push-token-request.json`.
        XCTAssertEqual(transport.sent[0].body, [
            "pushKind": "apns_voip",
            "pushToken": String(repeating: "ab", count: 32),
            "pushEnv": "sandbox",
            "alertPushToken": String(repeating: "cd", count: 32),
        ])
    }

    func testUnchangedTokensAreNotSentAgainInTheSameLaunchButAreInTheNext() async throws {
        let transport = PushTokenTransport()
        let ledger = InMemoryPushTokenLedger()
        let (reporter, store) = try make(transport, ledger: ledger, env: .production)
        await reporter.setVoipToken(voip)
        await reporter.report()

        await reporter.report()
        XCTAssertEqual(transport.sent.count, 2, "nothing new in this launch")

        // A new token: sent again.
        await reporter.setAlertToken(alert)
        await reporter.report()
        XCTAssertEqual(transport.sent.count, 4)
        XCTAssertEqual(transport.sent.last?.body["pushEnv"], "production")

        // Next launch (a new reporter, same ledger): sent once more, even though nothing changed.
        let next = PushTokenReporter(api: FSVoipAPIClient(transport: transport), accounts: store, ledger: ledger, environment: .production)
        await next.setVoipToken(voip)
        await next.setAlertToken(alert)
        await next.report()
        XCTAssertEqual(transport.sent.count, 6)
    }

    func testInvalidatedTokenClearsOnceThenStaysQuiet() async throws {
        let transport = PushTokenTransport()
        let (reporter, _) = try make(transport, accounts: ["a"])
        await reporter.setVoipToken(voip)
        await reporter.report()

        await reporter.setVoipToken(nil)
        await reporter.report()
        XCTAssertEqual(transport.sent.last?.body, ["pushToken": NSNull()])

        await reporter.report()
        XCTAssertEqual(transport.sent.count, 2, "nothing left to clear")
    }

    func testRevokedAndFailedAccountsAreReportedAndRetried() async throws {
        let transport = PushTokenTransport()
        transport.statusFor["fss_vapp_a"] = 401
        transport.statusFor["fss_vapp_b"] = 503
        let (reporter, _) = try make(transport)
        await reporter.setVoipToken(voip)

        let first = await reporter.report()
        XCTAssertEqual(first.revoked, ["a"])
        XCTAssertEqual(first.failed, ["b"])

        transport.statusFor["fss_vapp_b"] = nil
        let second = await reporter.report()
        XCTAssertEqual(second.sent, ["b"], "the failed one is tried again")
    }

    func testFingerprintsOfRemovedAccountsAreForgotten() async throws {
        let transport = PushTokenTransport()
        let ledger = InMemoryPushTokenLedger()
        let (reporter, store) = try make(transport, ledger: ledger)
        await reporter.setVoipToken(voip)
        await reporter.report()
        XCTAssertEqual(Set(ledger.fingerprints().keys), ["a", "b"])

        try store.remove(id: "b")
        await reporter.report()

        XCTAssertEqual(Set(ledger.fingerprints().keys), ["a"])
        XCTAssertFalse(ledger.fingerprints().values.contains { $0.contains("abab") }, "only hashes are kept, never the tokens")
    }
}
