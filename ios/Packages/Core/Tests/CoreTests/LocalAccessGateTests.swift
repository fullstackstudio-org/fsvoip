// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation
import XCTest
@testable import Core

private final class ScriptedAuth: LocalAuthenticating, @unchecked Sendable {
    var availabilityValue = LocalAuthAvailability.available
    var results: [LocalAuthResult] = []
    private(set) var evaluations = 0

    func availability() -> LocalAuthAvailability { availabilityValue }

    func evaluate(reason: String) async -> LocalAuthResult {
        evaluations += 1

        return results.isEmpty ? .success : results.removeFirst()
    }
}

private final class Clock: @unchecked Sendable {
    var date = Date(timeIntervalSince1970: 1_800_000_000)
    func now() -> Date { date }
}

@MainActor
final class LocalAccessGateTests: XCTestCase {
    func testAsksOnceAndStaysOpenForFiveMinutesOfActivity() async {
        let auth = ScriptedAuth()
        let clock = Clock()
        let gate = LocalAccessGate(authenticator: auth, now: { clock.now() })

        XCTAssertFalse(gate.isUnlocked)
        let first = await gate.ensureUnlocked(reason: "test")
        XCTAssertEqual(first, .unlocked)
        XCTAssertEqual(auth.evaluations, 1)

        // Four minutes later, still working: no new question, and the window moves on.
        clock.date.addTimeInterval(4 * 60)
        let second = await gate.ensureUnlocked(reason: "test")
        XCTAssertEqual(second, .unlocked)
        XCTAssertEqual(auth.evaluations, 1)

        clock.date.addTimeInterval(4 * 60)
        gate.touch()
        XCTAssertTrue(gate.isUnlocked)

        // Idle for more than five minutes: locked again, the next use asks.
        clock.date.addTimeInterval(5 * 60 + 1)
        XCTAssertFalse(gate.isUnlocked)
        let third = await gate.ensureUnlocked(reason: "test")
        XCTAssertEqual(third, .unlocked)
        XCTAssertEqual(auth.evaluations, 2)
    }

    func testTouchDoesNotOpenALockedGate() async {
        let gate = LocalAccessGate(authenticator: ScriptedAuth())
        gate.touch()
        XCTAssertFalse(gate.isUnlocked)
    }

    func testCancelledAndFailedStayLocked() async {
        let auth = ScriptedAuth()
        auth.results = [.cancelled, .failed]
        let gate = LocalAccessGate(authenticator: auth)

        let cancelled = await gate.ensureUnlocked(reason: "test")
        XCTAssertEqual(cancelled, .cancelled)
        let failed = await gate.ensureUnlocked(reason: "test")
        XCTAssertEqual(failed, .failed)
        XCTAssertFalse(gate.isUnlocked)
    }

    func testNoPasscodeMeansUnavailableAndNothingIsAsked() async {
        let auth = ScriptedAuth()
        auth.availabilityValue = .noPasscode
        let gate = LocalAccessGate(authenticator: auth)

        let outcome = await gate.ensureUnlocked(reason: "test")
        XCTAssertEqual(outcome, .unavailable(.noPasscode))
        XCTAssertEqual(auth.evaluations, 0)
        XCTAssertFalse(gate.isUnlocked)
    }

    func testLockClosesImmediately() async {
        let gate = LocalAccessGate(authenticator: ScriptedAuth())
        _ = await gate.ensureUnlocked(reason: "test")
        XCTAssertTrue(gate.isUnlocked)
        gate.lock()
        XCTAssertFalse(gate.isUnlocked)
    }
}
