// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import SipEngine

final class TypesTests: XCTestCase {
    private func account() -> SipAccountConfig {
        SipAccountConfig(
            id: "3f0c2b1e-8a4d-4d6f-9b7a-1c2d3e4f5a6b",
            username: "102",
            password: SipSecret("hunter2"),
            domain: "voorbeeld-bouw.powervoip.nl",
            proxy: "sip.powervoip.nl",
            port: 5060,
            transport: .tcp,
            installId: "a1b2c3d4e5f60718"
        )
    }

    func testIdentityAndRouteAreBuiltFromTheServerFields() {
        let account = account()

        // domain = the SIP identity's domain; proxy = the outbound route. Mixing them up is the known customer trap.
        XCTAssertEqual(account.identity, "sip:102@voorbeeld-bouw.powervoip.nl")
        XCTAssertEqual(account.route, "sip:sip.powervoip.nl;transport=tcp", "with SRV the port comes from DNS")

        var fixedPort = account
        fixedPort.useSRV = false
        XCTAssertEqual(fixedPort.route, "sip:sip.powervoip.nl:5060;transport=tcp")
    }

    func testDefaultsFollowThePlan() {
        let account = account()

        XCTAssertEqual(account.expiresSeconds, 120)
        XCTAssertEqual(account.codecs, [.g722, .pcma, .pcmu])
        XCTAssertEqual(account.srtp, .optional)
    }

    func testPasswordNeverPrints() {
        let account = account()
        var dumped = ""
        dump(account, to: &dumped)

        XCTAssertFalse(String(describing: account).contains("hunter2"))
        XCTAssertFalse(String(reflecting: account).contains("hunter2"))
        XCTAssertFalse(dumped.contains("hunter2"))
        XCTAssertEqual(account.password.reveal(), "hunter2")
    }

    func testDTMFDigitValidation() {
        for character in "0123456789*#ABCD" {
            XCTAssertNotNil(DTMFDigit(character))
        }

        XCTAssertNil(DTMFDigit("x"))
        XCTAssertNil(DTMFDigit(" "))
    }

    func testCallStateEnded() {
        XCTAssertTrue(CallState.ended(.remoteHangup).isEnded)
        XCTAssertFalse(CallState.active.isEnded)
        XCTAssertFalse(CallState.incomingRinging.isEnded)
    }

    func testNullEngineDoesNothingAndRefusesCalls() throws {
        let engine = NullSipEngine()

        try engine.start()
        try engine.register(account())
        XCTAssertEqual(engine.registrationState(of: "x"), .unregistered)
        XCTAssertTrue(engine.calls().isEmpty)
        XCTAssertThrowsError(try engine.call(number: "100", from: "x"))
        engine.audio.configure()
        engine.audio.activate(true)
    }
}
