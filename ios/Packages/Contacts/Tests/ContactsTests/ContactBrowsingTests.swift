// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import FSContacts

final class ContactBrowsingTests: XCTestCase {
    private func customer(_ contactId: String, _ name: String, _ numbers: [String] = ["+31611111111"], company: String? = nil) -> ContactEntry {
        ContactEntry(id: "customer:\(contactId)", name: name, company: company, phones: numbers.map { ContactPhoneEntry(number: $0) }, source: .customer, accountIds: ["acc"], contactId: contactId)
    }

    private let none: (String, String) -> Set<String> = { _, _ in [] }

    func testGroupsByLetterSortedWithSymbolsLast() {
        let entries = [customer("1", "Bea"), customer("2", "anna"), customer("3", "Ärend"), customer("4", "06-bakker"), customer("5", "Anja")]

        let sections = ContactBrowsing.sections(entries: entries, filter: .all, query: "", members: none)

        XCTAssertEqual(sections.map(\.letter), ["A", "B", "#"], "Ärend files under A: accents do not matter")
        XCTAssertEqual(sections[0].entries.map(\.displayName), ["anna", "Anja", "Ärend"].sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending })
    }

    func testSearchFindsNameCompanyEmailAndPartsOfANumber() {
        var withEmail = customer("3", "Cor")
        withEmail.email = "cor@voorbeeld.nl"
        let entries = [customer("1", "Pieter de Groot", ["+31612345678"]), customer("2", "Anna", company: "Bakkerij Smit"), withEmail]

        func found(_ query: String) -> [String] {
            ContactBrowsing.sections(entries: entries, filter: .all, query: query, members: none).flatMap(\.entries).map(\.displayName)
        }

        XCTAssertEqual(found("groot"), ["Pieter de Groot"])
        XCTAssertEqual(found("bakkerij"), ["Anna"])
        XCTAssertEqual(found("VOORBEELD"), ["Cor"])
        XCTAssertEqual(found("06 12 34"), ["Pieter de Groot"])
        XCTAssertEqual(found("123456"), ["Pieter de Groot"])
        XCTAssertEqual(found("zzz"), [])
        XCTAssertEqual(found("  "), ["Anna", "Cor", "Pieter de Groot"])
    }

    func testTwoCharactersOfDigitsDoNotMatchEverything() {
        let entries = [customer("1", "Pieter", ["+31612345678"])]

        XCTAssertTrue(ContactBrowsing.sections(entries: entries, filter: .all, query: "12", members: none).isEmpty)
    }

    func testFiltersBySource() {
        let entries = [customer("1", "Anna"), ContactEntry(id: "device:1", name: "Mama", numbers: ["0655555555"], source: .device), ContactEntry(id: "internal:1", name: "Receptie", numbers: ["100"], source: .internalExtensions)]

        func names(_ filter: ContactFilter) -> [String] {
            ContactBrowsing.sections(entries: entries, filter: filter, query: "", members: none).flatMap(\.entries).map(\.name)
        }

        XCTAssertEqual(names(.all).sorted(), ["Anna", "Mama", "Receptie"])
        XCTAssertEqual(names(.customer), ["Anna"])
        XCTAssertEqual(names(.device), ["Mama"])
        XCTAssertEqual(names(.colleagues), ["Receptie"])
    }

    func testAListFilterShowsOnlyItsMembers() {
        let entries = [customer("1", "Anna"), customer("2", "Bea"), ContactEntry(id: "device:1", name: "Mama", numbers: ["0655555555"], source: .device)]

        let sections = ContactBrowsing.sections(entries: entries, filter: .list(accountId: "acc", listId: "l1"), query: "", members: { account, list in account == "acc" && list == "l1" ? ["2"] : [] })

        XCTAssertEqual(sections.flatMap(\.entries).map(\.name), ["Bea"])
    }

    func testInitials() {
        XCTAssertEqual(ContactBrowsing.initials(for: "Pieter de Groot"), "PG")
        XCTAssertEqual(ContactBrowsing.initials(for: "Bakkerij Smit"), "BS")
        XCTAssertEqual(ContactBrowsing.initials(for: "anna"), "A")
        XCTAssertEqual(ContactBrowsing.initials(for: "06123"), "#")
        XCTAssertEqual(ContactBrowsing.initials(for: ""), "#")
    }
}
