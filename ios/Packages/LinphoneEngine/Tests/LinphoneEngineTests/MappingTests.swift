// SPDX-License-Identifier: AGPL-3.0-or-later
import SipEngine
import XCTest
@testable import LinphoneEngine

final class MappingTests: XCTestCase {
    func testDialableNumbers() {
        for ok in ["100", "+31701234567", "0701234567", "*72", "#1", "112"] {
            XCTAssertTrue(LinphoneSipEngine.isDialable(ok), ok)
        }

        // Anything that could smuggle SIP syntax into the request URI is refused.
        for bad in ["", "abc", "100@evil.example", "100;transport=udp", "100 200", "100\r\nVia: x", String(repeating: "1", count: 33)] {
            XCTAssertFalse(LinphoneSipEngine.isDialable(bad), bad)
        }
    }

    func testAudioHooksAreHarmlessBeforeStart() {
        let engine = LinphoneSipEngine()

        engine.audio.configure()
        engine.audio.activate(true)
        engine.audio.activate(false)
        XCTAssertThrowsError(try engine.call(number: "100", from: "x"))
        XCTAssertTrue(engine.calls().isEmpty)
    }
}
