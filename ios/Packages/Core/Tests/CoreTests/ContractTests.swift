// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import Core

/// Decodes (and where it is a request, re-encodes) every fixture in `shared/fixtures`. The same fixtures are
/// validated against `shared/openapi.yaml` by `scripts/validate-contract.ts`, so a green run of both means the
/// Swift models, the OpenAPI document and the real server agree.
final class ContractTests: XCTestCase {
    private let decoder = FSVoipJSON.decoder()

    private func decode<T: Decodable>(_ type: T.Type, _ fixture: String) throws -> T {
        try decoder.decode(type, from: Fixtures.data(fixture))
    }

    /// JSON object equality without caring about key order.
    private func assertJSONEqual(_ lhs: Data, _ rhs: Data, file: StaticString = #filePath, line: UInt = #line) throws {
        let a = try JSONSerialization.jsonObject(with: lhs) as? NSDictionary
        let b = try JSONSerialization.jsonObject(with: rhs) as? NSDictionary
        XCTAssertEqual(a, b, file: file, line: line)
    }

    private func dumped<T>(_ value: T) -> String {
        var output = ""
        dump(value, to: &output)

        return output
    }

    // MARK: Every fixture has a test

    func testEveryFixtureIsCoveredByAContractTest() throws {
        let covered: Set<String> = Set([
            "pair-request", "pair-request-minimal", "pair-response", "pair-response-tls",
            "me-response", "me-response-no-sip", "me-patch-request", "me-patch-request-clear", "me-patch-response",
            "push-token-request", "push-token-request-clear", "ok-response",
            "error-not-found", "error-unauthorized", "error-rate-limited", "error-invalid-request", "error-unavailable-retryable",
            "push-ring", "push-ring-anonymous", "push-revoked", "push-refresh", "apns-voip-body", "apns-alert-body", "fcm-message",
        ]).union(BeheerContractTests.coveredFixtures)

        XCTAssertEqual(Set(try Fixtures.names()), covered, "A fixture was added or removed without a contract test")
    }

    // MARK: Pairing

    func testPairRequestRoundTrips() throws {
        let data = try Fixtures.data("pair-request")
        let request = try decoder.decode(PairRequest.self, from: data)

        XCTAssertEqual(request.token.count, 53)
        XCTAssertTrue(request.token.hasPrefix("fss_vpair_"))
        XCTAssertEqual(request.device.platform, .ios)
        XCTAssertEqual(request.device.pushKind, .apnsVoip)
        XCTAssertEqual(request.device.pushEnv, .sandbox)
        XCTAssertEqual(request.device.installId, "a1b2c3d4e5f60718")
        XCTAssertNotNil(request.device.alertPushToken)

        try assertJSONEqual(FSVoipJSON.encoder().encode(request), data)
    }

    func testMinimalPairRequestOmitsOptionalFields() throws {
        let data = try Fixtures.data("pair-request-minimal")
        let request = try decoder.decode(PairRequest.self, from: data)

        XCTAssertNil(request.device.pushToken)
        try assertJSONEqual(FSVoipJSON.encoder().encode(request), data)
    }

    func testPairResponseDecodes() throws {
        let response = try decode(PairResponse.self, "pair-response")

        XCTAssertTrue(response.deviceToken.reveal().hasPrefix("fss_vapp_"))
        XCTAssertEqual(response.device.id, response.account.id)
        XCTAssertEqual(response.account.label, "Voorbeeld Bouw · Jan de Vries")
        XCTAssertEqual(response.account.extensionNumber, "102")
        XCTAssertEqual(response.sip.username, "102")
        XCTAssertEqual(response.sip.domain, "voorbeeld-bouw.powervoip.nl")
        XCTAssertEqual(response.sip.proxy, "sip.powervoip.nl")
        XCTAssertEqual(response.sip.port, 5060)
        XCTAssertEqual(response.sip.transport, .tcp)
        XCTAssertTrue(response.sip.srv)
        XCTAssertTrue(response.contacts.hasInternal)
        XCTAssertEqual(response.contacts.listsAvailable, 2)
    }

    func testPairResponseWithTLSAndNullExtensionNumber() throws {
        let response = try decode(PairResponse.self, "pair-response-tls")

        XCTAssertEqual(response.sip.transport, .tls)
        XCTAssertEqual(response.sip.port, 5061)
        XCTAssertNil(response.account.extensionNumber)
    }

    func testPairResponseNeverPrintsSecrets() throws {
        let response = try decode(PairResponse.self, "pair-response")
        let stored = StoredAccount(pairing: response)

        for text in [String(describing: response), String(reflecting: response), "\(response)", String(describing: stored), String(reflecting: stored), dumped(response)] {
            XCTAssertFalse(text.contains("fixture-not-a-real-password"), "SIP password leaked: \(text)")
            XCTAssertFalse(text.contains("fss_vapp_FIXTURE"), "device token leaked: \(text)")
        }
    }

    func testResponsesIgnoreUnknownFields() throws {
        // Additive API changes must never break an installed app.
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: Fixtures.data("pair-response")) as? [String: Any])
        object["futureField"] = ["a": 1]
        var sip = try XCTUnwrap(object["sip"] as? [String: Any])
        sip["futureSipField"] = true
        object["sip"] = sip

        let data = try JSONSerialization.data(withJSONObject: object)
        XCTAssertNoThrow(try decoder.decode(PairResponse.self, from: data))
    }

    // MARK: /me

    func testMeResponseDecodes() throws {
        let me = try decode(MeResponse.self, "me-response")

        XCTAssertEqual(me.account.label, "Jan (balie)")
        XCTAssertEqual(me.account.labelOverride, "Jan (balie)")
        XCTAssertEqual(me.sip?.domain, "voorbeeld-bouw.powervoip.nl")
        XCTAssertEqual(me.internalContacts.map(\.number), ["100", "101", "103"])
        XCTAssertEqual(me.internalContacts.first?.name, "Receptie")
        XCTAssertTrue(me.push.registered)
        XCTAssertEqual(me.push.kind, .apnsVoip)
        XCTAssertEqual(me.push.env, .sandbox)
        XCTAssertFalse(me.push.invalid)

        // "2026-10-08T12:34:56.789Z" - milliseconds survive.
        let expected = Date(timeIntervalSince1970: 1791462896.789)
        XCTAssertEqual(me.serverTime.timeIntervalSince1970, expected.timeIntervalSince1970, accuracy: 0.001)
    }

    func testMeResponseWithoutSIPAndPush() throws {
        let me = try decode(MeResponse.self, "me-response-no-sip")

        XCTAssertNil(me.sip)
        XCTAssertNil(me.account.labelOverride)
        XCTAssertTrue(me.internalContacts.isEmpty)
        XCTAssertFalse(me.push.registered)
        XCTAssertNil(me.push.kind)
        XCTAssertNil(me.push.env)
    }

    func testUnknownPushKindDoesNotBreakMe() throws {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: Fixtures.data("me-response")) as? [String: Any])
        var push = try XCTUnwrap(object["push"] as? [String: Any])
        push["kind"] = "carrier_pigeon"
        object["push"] = push

        let me = try decoder.decode(MeResponse.self, from: JSONSerialization.data(withJSONObject: object))
        XCTAssertNil(me.push.kind)
        XCTAssertTrue(me.push.registered)
    }

    func testMePatchEncodesExplicitNullToClear() throws {
        try assertJSONEqual(FSVoipJSON.encoder().encode(MePatchRequest(labelOverride: "Jan (balie)")), Fixtures.data("me-patch-request"))
        try assertJSONEqual(FSVoipJSON.encoder().encode(MePatchRequest(labelOverride: nil)), Fixtures.data("me-patch-request-clear"))

        let response = try decode(MePatchResponse.self, "me-patch-response")
        XCTAssertEqual(response.label, "Jan (balie)")
        XCTAssertEqual(response.labelOverride, "Jan (balie)")
    }

    // MARK: Push token

    func testPushTokenUpdateEncoding() throws {
        let token = String(repeating: "ab", count: 32)
        let alert = String(repeating: "cd", count: 32)
        let update = PushTokenUpdate(pushKind: .apnsVoip, pushToken: token, pushEnv: .sandbox, alertPushToken: alert)

        try assertJSONEqual(FSVoipJSON.encoder().encode(update), Fixtures.data("push-token-request"))
        try assertJSONEqual(FSVoipJSON.encoder().encode(PushTokenUpdate.clear), Fixtures.data("push-token-request-clear"))
    }

    func testOkResponse() throws {
        XCTAssertTrue(try decode(OkResponse.self, "ok-response").ok)
    }

    // MARK: Errors

    func testErrorBodies() throws {
        XCTAssertEqual(try decode(APIErrorBody.self, "error-not-found").error, "not_found")
        XCTAssertEqual(try decode(APIErrorBody.self, "error-unauthorized").error, "unauthorized")
        XCTAssertEqual(try decode(APIErrorBody.self, "error-rate-limited").error, "rate_limited")
        XCTAssertEqual(try decode(APIErrorBody.self, "error-invalid-request").message, "device.platform moet ios of android zijn.")

        let retryable = try decode(APIErrorBody.self, "error-unavailable-retryable")
        XCTAssertEqual(retryable.error, "not_ready")
        XCTAssertEqual(retryable.retryable, true)
    }

    // MARK: Push payloads

    func testRingPush() throws {
        let message = try decode(PushMessage.self, "push-ring")

        guard case let .ring(ring) = message else {
            return XCTFail("expected ring, got \(message)")
        }

        XCTAssertEqual(ring.callRef, "9d3f5c52-7b1e-4a0c-8e6d-2f4a6b8c0d1e")
        XCTAssertEqual(ring.from.number, "+31701234567")
        XCTAssertEqual(ring.from.name, "Bakkerij Smit")
        XCTAssertEqual(ring.accountLabel, "Voorbeeld Bouw · Jan de Vries")
        XCTAssertEqual(ring.accountId, "3f0c2b1e-8a4d-4d6f-9b7a-1c2d3e4f5a6b")
        XCTAssertEqual(FSVoipJSON.formatTimestamp(ring.expiresAt), "2026-10-08T12:35:08.000Z")
    }

    func testAnonymousRingPush() throws {
        guard case let .ring(ring) = try decode(PushMessage.self, "push-ring-anonymous") else {
            return XCTFail("expected ring")
        }

        XCTAssertNil(ring.from.number)
        XCTAssertNil(ring.from.name)
    }

    func testRevokedAndRefreshPush() throws {
        XCTAssertEqual(
            try decode(PushMessage.self, "push-revoked"),
            .revoked(RevokedPush(accountId: "3f0c2b1e-8a4d-4d6f-9b7a-1c2d3e4f5a6b", accountLabel: "Voorbeeld Bouw · Jan de Vries"))
        )
        XCTAssertEqual(
            try decode(PushMessage.self, "push-refresh"),
            .refresh(RefreshPush(accountId: "3f0c2b1e-8a4d-4d6f-9b7a-1c2d3e4f5a6b"))
        )
    }

    func testPushEnvelopes() throws {
        let ring = try decode(PushMessage.self, "push-ring")
        let revoked = try decode(PushMessage.self, "push-revoked")

        XCTAssertEqual(try PushMessage.decode(apnsPayload: Fixtures.data("apns-voip-body")), ring)
        XCTAssertEqual(try PushMessage.decode(apnsPayload: Fixtures.data("apns-alert-body")), revoked)

        let dictionary = try XCTUnwrap(JSONSerialization.jsonObject(with: Fixtures.data("apns-voip-body")) as? [AnyHashable: Any])
        XCTAssertEqual(try PushMessage.decode(apnsDictionary: dictionary), ring)

        let fcm = try XCTUnwrap(JSONSerialization.jsonObject(with: Fixtures.data("fcm-message")) as? [String: Any])
        let data = try XCTUnwrap((fcm["message"] as? [String: Any])?["data"] as? [String: String])
        XCTAssertEqual(try PushMessage.decode(fcmData: data), ring)
    }

    func testUnsupportedPushVersionAndTypeAreRejected() throws {
        let future = Data(#"{"v":2,"type":"ring"}"#.utf8)
        XCTAssertThrowsError(try decoder.decode(PushMessage.self, from: future)) { error in
            XCTAssertEqual(error as? PushMessageError, .unsupportedVersion(2))
        }

        let unknown = Data(#"{"v":1,"type":"voicemail"}"#.utf8)
        XCTAssertThrowsError(try decoder.decode(PushMessage.self, from: unknown)) { error in
            XCTAssertEqual(error as? PushMessageError, .unknownType("voicemail"))
        }

        XCTAssertThrowsError(try PushMessage.decode(apnsPayload: Data(#"{"aps":{}}"#.utf8))) { error in
            XCTAssertEqual(error as? PushMessageError, .missingPayload)
        }
    }

    func testPushFixturesNeverCarrySecrets() throws {
        for name in ["push-ring", "push-ring-anonymous", "push-revoked", "push-refresh", "apns-voip-body", "apns-alert-body", "fcm-message"] {
            let text = try String(decoding: Fixtures.data(name), as: UTF8.self).lowercased()

            for forbidden in ["password", "devicetoken", "fss_vapp_", "pushtoken", "sip\""] {
                XCTAssertFalse(text.contains(forbidden), "\(name) contains \(forbidden)")
            }
        }
    }
}
