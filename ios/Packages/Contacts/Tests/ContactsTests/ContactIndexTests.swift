// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import FSContacts

final class ContactIndexTests: XCTestCase {
    private func entry(_ id: String, _ name: String, _ numbers: [String], source: ContactSource = .customer) -> ContactEntry {
        ContactEntry(id: id, name: name, numbers: numbers, source: source)
    }

    func testFindsTheNameInEveryWrittenForm() {
        let index = ContactIndex(entries: [entry("1", "Pieter de Groot", ["+31612345678"])])

        for form in ["0612345678", "+31612345678", "0031612345678", "06 12 34 56 78", "31612345678"] {
            XCTAssertEqual(index.name(forNumber: form), "Pieter de Groot", form)
        }
    }

    func testAStoredNationalNumberMatchesAnInternationalCaller() {
        let index = ContactIndex(entries: [entry("1", "Henk", ["06-12345678"])])

        XCTAssertEqual(index.name(forNumber: "+31612345678"), "Henk")
    }

    func testExtensionsMatchOnlyExactly() {
        let index = ContactIndex(entries: [entry("1", "Receptie", ["100"], source: .internalExtensions), entry("2", "Pieter", ["+31612345678"])])

        XCTAssertEqual(index.name(forNumber: "100"), "Receptie")
        XCTAssertNil(index.name(forNumber: "10"))
        XCTAssertNil(index.name(forNumber: "1000"))
        XCTAssertNil(index.name(forNumber: "0100"))
    }

    func testShortNumbersGiveNoFalsePositives() {
        let index = ContactIndex(entries: [entry("1", "Pieter", ["+31612345678"]), entry("2", "Anja", ["+31701234567"])])

        for number in ["112", "101", "1234", "5678", "345678", "12345678", "*97"] {
            XCTAssertNil(index.name(forNumber: number), number)
        }
    }

    func testTheLastNineDigitsAreAFallbackOnlyWhenItIsUnambiguous() {
        // The number was stored without a usable country form; the caller comes in with a prefix the exact form misses.
        let single = ContactIndex(entries: [entry("1", "Jan", ["612345678"])])
        XCTAssertEqual(single.name(forNumber: "0612345678"), "Jan")
        XCTAssertEqual(single.name(forNumber: "+31612345678"), "Jan")

        let two = ContactIndex(entries: [entry("1", "Jan", ["612345678"]), entry("2", "Kees", ["+49612345678"])])
        XCTAssertNil(two.name(forNumber: "0612345678"), "two different people share these digits: unknown beats wrong")
    }

    func testACallerThatStatesItsCountryDoesNotFallBackOnAnotherCountry() {
        let index = ContactIndex(entries: [entry("1", "Pieter", ["+31471234567"])])

        XCTAssertNil(index.name(forNumber: "+32471234567"))
        XCTAssertEqual(index.name(forNumber: "0471234567"), "Pieter")
    }

    func testTheSamePersonInTwoSourcesDoesNotBlockTheFallback() {
        let index = ContactIndex(entries: [entry("1", "Pieter de Groot", ["612345678"]), entry("2", "pieter de groot", ["0612345670"], source: .device)])
        XCTAssertEqual(index.name(forNumber: "+31612345678"), "Pieter de Groot")
    }

    func testTheFirstEntryWinsForAnExactMatch() {
        let index = ContactIndex(entries: [entry("1", "Klantcontact", ["+31612345678"]), entry("2", "Telefooncontact", ["0612345678"], source: .device)])

        XCTAssertEqual(index.name(forNumber: "0612345678"), "Klantcontact")
    }

    func testLookupIsFastForAHugeAddressBook() {
        var entries: [ContactEntry] = []

        for number in 0 ..< 25_000 {
            entries.append(entry("c\(number)", "Contact \(number)", ["+316\(String(format: "%08d", number))"]))
        }

        let index = ContactIndex(entries: entries)
        let start = Date()

        for _ in 0 ..< 200 {
            XCTAssertEqual(index.name(forNumber: "0600012345"), "Contact 12345")
            XCTAssertNil(index.name(forNumber: "0700012345"))
        }

        let perLookup = Date().timeIntervalSince(start) / 400
        XCTAssertLessThan(perLookup, 0.005, "a name must be there far inside the 50 ms the call screen allows")
    }
}
