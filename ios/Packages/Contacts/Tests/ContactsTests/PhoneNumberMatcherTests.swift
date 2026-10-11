// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import FSContacts

final class PhoneNumberMatcherTests: XCTestCase {
    private func exact(_ number: String) -> String? {
        PhoneNumberMatcher.key(for: number)?.exact
    }

    func testAllWrittenFormsOfOneDutchMobileNumberAreTheSame() {
        let forms = ["0612345678", "06 12 34 56 78", "06-12345678", "+31612345678", "+31 6 12345678", "0031612345678", "0031 (0)6 12345678", "31612345678", "+31 (0)6 12345678", "sip:0612345678@voorbeeld.powervoip.nl", "tel:+31612345678", "+310612345678"]

        for form in forms {
            XCTAssertEqual(exact(form), "+31612345678", form)
        }
    }

    func testLandlineWithAreaCode() {
        XCTAssertEqual(exact("070 123 45 67"), "+31701234567")
        XCTAssertEqual(exact("+31 70 123 4567"), "+31701234567")
        XCTAssertEqual(exact("0031701234567"), "+31701234567")
    }

    func testForeignNumbersKeepTheirCountryCode() {
        XCTAssertEqual(exact("+32 471 23 45 67"), "+32471234567")
        XCTAssertEqual(exact("0032471234567"), "+32471234567")
        XCTAssertEqual(exact("+49 30 1234567"), "+49301234567")
    }

    func testShortNumbersAndCodesStayAsTyped() {
        XCTAssertEqual(exact("101"), "101")
        XCTAssertEqual(exact("112"), "112")
        XCTAssertEqual(exact("1234"), "1234")
        XCTAssertEqual(exact("*97"), "*97")
        XCTAssertEqual(exact("#31#"), "#31#")
        XCTAssertNil(PhoneNumberMatcher.key(for: "112")?.tail)
        XCTAssertNil(PhoneNumberMatcher.key(for: "101")?.tail)
    }

    func testNothingToMatch() {
        XCTAssertNil(PhoneNumberMatcher.key(for: ""))
        XCTAssertNil(PhoneNumberMatcher.key(for: "Anoniem"))
        XCTAssertNil(PhoneNumberMatcher.key(for: "+"))
    }

    func testTailIsTheLastNineDigits() {
        XCTAssertEqual(PhoneNumberMatcher.key(for: "0612345678")?.tail, "612345678")
        XCTAssertEqual(PhoneNumberMatcher.key(for: "+31612345678")?.tail, "612345678")
        XCTAssertEqual(PhoneNumberMatcher.key(for: "0612345678")?.isCountryAssumed, true)
        XCTAssertEqual(PhoneNumberMatcher.key(for: "+31612345678")?.isCountryAssumed, false)
    }

    func testE164ForTheServer() {
        XCTAssertEqual(PhoneNumberMatcher.e164(for: "06 12 34 56 78"), "+31612345678")
        XCTAssertEqual(PhoneNumberMatcher.e164(for: "+32 471 23 45 67"), "+32471234567")
        XCTAssertNil(PhoneNumberMatcher.e164(for: "101"), "an extension is sent as typed")
    }

    func testSame() {
        XCTAssertTrue(PhoneNumberMatcher.same("0612345678", "+31 6 12345678"))
        XCTAssertFalse(PhoneNumberMatcher.same("0612345678", "0612345679"))
        XCTAssertFalse(PhoneNumberMatcher.same("101", "0101"))
    }
}
