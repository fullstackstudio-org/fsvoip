// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import CallController

final class CallDisplayTests: XCTestCase {
    func testCallerOnlyByDefault() {
        XCTAssertEqual(CallDisplay.callerText(callerName: "Bakkerij Smit", callerNumber: "+31701234567", accountLabel: "Voorbeeld Bouw · Jan", showAccount: false), "Bakkerij Smit")
    }

    func testDialledAccountIsAppended() {
        XCTAssertEqual(CallDisplay.callerText(callerName: "Bakkerij Smit", callerNumber: "+31701234567", accountLabel: "Voorbeeld Bouw · Jan", showAccount: true), "Bakkerij Smit → Voorbeeld Bouw · Jan")
    }

    func testFallsBackToNumberThenAnonymous() {
        XCTAssertEqual(CallDisplay.callerText(callerName: nil, callerNumber: "+31701234567", accountLabel: "A", showAccount: false), "+31701234567")
        XCTAssertEqual(CallDisplay.callerText(callerName: "  ", callerNumber: " ", accountLabel: "A", showAccount: true, anonymous: "Anoniem"), "Anoniem → A")
        XCTAssertEqual(CallDisplay.callerText(callerName: nil, callerNumber: nil, accountLabel: "", showAccount: true), "Onbekend")
    }

    func testSeveralAccountsAlwaysShowTheAccount() {
        XCTAssertFalse(CallDisplay.shouldShowAccount(setting: false, accountCount: 1))
        XCTAssertTrue(CallDisplay.shouldShowAccount(setting: true, accountCount: 1))
        XCTAssertTrue(CallDisplay.shouldShowAccount(setting: false, accountCount: 2))
    }
}
