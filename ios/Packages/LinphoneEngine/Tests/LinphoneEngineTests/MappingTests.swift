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

    // MARK: Caller choice (`X-FSS-From`)

    private final class Recorder: InviteHeaderSink {
        var headers: [(String, String)] = []

        func addInviteHeader(name: String, value: String) {
            headers.append((name, value))
        }
    }

    func testTheChosenNumberIsOneHeaderOnTheInvite() {
        let recorder = Recorder()

        LinphoneSipEngine.apply(CallOptions(fromNumber: "0850607848"), to: recorder)

        XCTAssertEqual(recorder.headers.count, 1, "exactly one header")
        XCTAssertEqual(recorder.headers.first?.0, "X-FSS-From")
        XCTAssertEqual(recorder.headers.first?.1, "0850607848")
    }

    func testNoChoiceMeansNoHeader() {
        let recorder = Recorder()

        LinphoneSipEngine.apply(.none, to: recorder)
        LinphoneSipEngine.apply(CallOptions(fromNumber: nil), to: recorder)

        XCTAssertTrue(recorder.headers.isEmpty)
    }

    func testAValueThatCouldCarryAnotherHeaderIsNeverSent() {
        for bad in ["", "085 060 7848", "0850607848\r\nX-Evil: 1", "+31850607848", "abc", String(repeating: "1", count: 33)] {
            let recorder = Recorder()

            LinphoneSipEngine.apply(CallOptions(fromNumber: bad), to: recorder)

            XCTAssertTrue(recorder.headers.isEmpty, bad)
        }
    }
}
