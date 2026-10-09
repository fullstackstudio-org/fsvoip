// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import FSContacts
import SwiftUI
import UIKit
import XCTest
@testable import UI

/// The Contacts tab in its new form (plan `fsvoip-app-v2`, Task 8): segments, filter, A-Z index, favourites, the edit rules and how
/// the screens draw in dark, light and the largest type.
@MainActor
final class ContactsTabTests: XCTestCase {
    private func entry(_ id: String, _ name: String, source: ContactSource = .customer, numbers: [String] = ["0612345678"]) -> ContactEntry {
        ContactEntry(id: id, name: name, phones: numbers.map { ContactPhoneEntry(number: $0) }, source: source, contactId: source == .customer ? id : nil)
    }

    private var entries: [ContactEntry] {
        [
            entry("a", "Anna Bakker"),
            entry("b", "Bram Visser"),
            entry("m", "Mila de Jong"),
            entry("p", "Pieter de Groot"),
            entry("z", "Zoë Smit"),
            entry("i1", "Receptie", source: .internalExtensions, numbers: ["100"]),
            entry("d1", "Oma", source: .device, numbers: ["0201234567"]),
        ]
    }

    private func sections(scope: ContactScope = .centrale, filter: ContactListFilter = .all, query: String = "", favorites: Set<String> = []) -> [ContactSection] {
        ContactsBrowsing.sections(entries: entries, scope: scope, filter: filter, query: query, favorites: favorites) { _, _ in ["a", "b"] }
    }

    // MARK: Segments and filter

    func testTheSegmentsSeparateThePhoneSystemFromThePhone() {
        let centrale = sections(scope: .centrale).flatMap(\.entries).map(\.id)
        let phone = sections(scope: .phone).flatMap(\.entries).map(\.id)

        XCTAssertEqual(Set(centrale), ["a", "b", "m", "p", "z", "i1"])
        XCTAssertEqual(phone, ["d1"])
    }

    func testTheFiltersNarrowTheSegment() {
        XCTAssertEqual(sections(filter: .internalOnly).flatMap(\.entries).map(\.id), ["i1"])
        XCTAssertEqual(Set(sections(filter: .list(accountId: "x", listId: "l")).flatMap(\.entries).map(\.id)), ["a", "b"])
        XCTAssertEqual(sections(filter: .favorites, favorites: ["m", "d1"]).flatMap(\.entries).map(\.id), ["m"])
        XCTAssertEqual(sections(scope: .phone, filter: .favorites, favorites: ["m", "d1"]).flatMap(\.entries).map(\.id), ["d1"])
        XCTAssertTrue(sections(filter: .favorites).isEmpty)
    }

    func testSearchWorksOnNameAndNumber() {
        XCTAssertEqual(sections(query: "pieter").flatMap(\.entries).map(\.id), ["p"])
        XCTAssertEqual(Set(sections(query: "0612").flatMap(\.entries).map(\.id)), ["a", "b", "m", "p", "z"])
    }

    // MARK: A-Z index

    func testTheIndexJumpsToTheSectionOfTheLetter() {
        let all = sections()

        XCTAssertEqual(all.map(\.letter), ["A", "B", "M", "P", "R", "Z"])
        XCTAssertEqual(AlphabetIndex.target(for: "M", in: all), "M")
        XCTAssertEqual(AlphabetIndex.target(for: "Z", in: all), "Z")
    }

    func testALetterWithoutContactsJumpsToTheNextSection() {
        let all = sections()

        XCTAssertEqual(AlphabetIndex.target(for: "C", in: all), "M")
        XCTAssertEqual(AlphabetIndex.target(for: "N", in: all), "P")
        XCTAssertEqual(AlphabetIndex.target(for: "S", in: all), "Z")
        // Past the last section: the last one. `#` has none here either.
        XCTAssertEqual(AlphabetIndex.target(for: "#", in: all), "Z")
        XCTAssertNil(AlphabetIndex.target(for: "A", in: []))
    }

    func testAFingerOnTheStripPicksTheLetterUnderIt() {
        XCTAssertEqual(AlphabetIndex.letter(atFraction: 0), "A")
        XCTAssertEqual(AlphabetIndex.letter(atFraction: 1), "#")
        XCTAssertEqual(AlphabetIndex.letter(atFraction: -3), "A")
        XCTAssertEqual(AlphabetIndex.letter(atFraction: 0.5), AlphabetIndex.letters[AlphabetIndex.letters.count / 2])
        XCTAssertEqual(AlphabetIndex.letters.count, 27)
    }

    // MARK: Favourites

    func testFavouritesAreKeptOnThePhone() {
        let suite = "fsvoip.tests.favorites.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }

        let first = FavoriteContacts(defaults: defaults)
        first.toggle("a")
        first.toggle("b")
        first.toggle("a")

        XCTAssertEqual(first.ids, ["b"])
        XCTAssertEqual(FavoriteContacts(defaults: defaults).ids, ["b"], "survives a restart")
    }

    // MARK: Edit rules

    func testSaveNeedsANumberOrANameWithAnEmailAddress() {
        var draft = ContactDraft()
        XCTAssertFalse(ContactEditRules.canSave(draft), "empty")

        draft.firstName = "Anna"
        XCTAssertFalse(ContactEditRules.canSave(draft), "a name nobody can reach is refused by the server")

        draft.email = "anna@example.nl"
        XCTAssertTrue(ContactEditRules.canSave(draft))

        draft.email = ""
        draft.phones[0].number = "0612345678"
        XCTAssertTrue(ContactEditRules.canSave(draft))

        var numberOnly = ContactDraft()
        numberOnly.phones[0].number = "0612345678"
        XCTAssertTrue(ContactEditRules.canSave(numberOnly), "a number alone is enough")
        XCTAssertEqual(ContactEditRules.prepared(numberOnly, countries: [:]).displayName, "0612345678", "named after the number")
    }

    func testTheCountryCodeIsAddedToANationalNumber() {
        let belgium = PhoneCountry(region: "BE", dialCode: "32")

        XCTAssertEqual(belgium.apply(to: "0471 23 45 67"), "+32471 23 45 67")
        XCTAssertEqual(belgium.apply(to: "+31612345678"), "+31612345678", "a number with its own code stays")
        XCTAssertEqual(belgium.apply(to: "0032471234567"), "+32471234567")
        XCTAssertEqual(PhoneCountry.netherlands.apply(to: "0612345678"), "0612345678", "Dutch numbers stay as typed")
        XCTAssertEqual(belgium.apply(to: "  "), "")
    }

    func testTheNetherlandsComesFirstInTheCountryList() {
        XCTAssertEqual(PhoneCountry.all.first, .netherlands)
        XCTAssertEqual(Set(PhoneCountry.all.map(\.region)).count, PhoneCountry.all.count)
        XCTAssertEqual(PhoneCountry.detect(from: "+32471234567")?.region, "BE")
        XCTAssertEqual(PhoneCountry.detect(from: "0031612345678")?.region, "NL")
        XCTAssertEqual(PhoneCountry.detect(from: "+352621123456")?.region, "LU")
        XCTAssertNil(PhoneCountry.detect(from: "0612345678"))
    }

    func testPreparedAppliesThePickedCountryPerNumber() {
        var draft = ContactDraft()
        draft.firstName = "Jan"
        draft.phones = [.init(number: "0471234567", label: .mobile), .init(number: "0612345678", label: .work)]
        let countries = [draft.phones[0].id: PhoneCountry(region: "BE", dialCode: "32")]

        let prepared = ContactEditRules.prepared(draft, countries: countries)

        XCTAssertEqual(prepared.phones.map(\.number), ["+32471234567", "0612345678"])
        XCTAssertEqual(prepared.phones.map(\.id), draft.phones.map(\.id))
    }

    // MARK: Recent calls

    func testTheDetailShowsTheCallsWithThatNumberNewestFirst() {
        func call(_ id: String, _ number: String, _ ago: TimeInterval) -> RecentCall {
            RecentCall(id: id, number: number, name: nil, accountId: "acc", accountLabel: "Kantoor", direction: .outgoing, outcome: .answered, startedAt: Date(timeIntervalSinceNow: -ago), duration: 30)
        }

        let calls = [call("old", "+31612345678", 500), call("other", "0698765432", 100), call("new", "0612345678", 50), call("anon", "", 10)]
        let result = ContactRecentCalls.matching(calls, phones: ["0612345678"])

        XCTAssertEqual(result.map(\.id), ["new", "old"], "0612… and +31612… are the same number")
        XCTAssertEqual(ContactRecentCalls.matching(calls, phones: []).count, 0)
        XCTAssertEqual(ContactRecentCalls.matching(calls, phones: ["0612345678"], limit: 1).map(\.id), ["new"])
    }

    // MARK: Layout (dark, light, largest Dynamic Type)

    private func fittingSize<V: View>(_ view: V, style: UIUserInterfaceStyle, category: UIContentSizeCategory, width: CGFloat = 390) -> CGSize {
        let host = UIHostingController(rootView: view.environment(\.sizeCategory, ContentSizeCategory(category) ?? .large))
        host.overrideUserInterfaceStyle = style
        host.view.frame = CGRect(x: 0, y: 0, width: width, height: 2000)
        host.view.layoutIfNeeded()

        return host.sizeThatFits(in: CGSize(width: width, height: .greatestFiniteMagnitude))
    }

    func testARowDrawsAtLeast44PointsHighInEveryMode() {
        let row = ContactRow(entry: entry("a", "Anna Bakker"), isFavorite: true)

        for style in [UIUserInterfaceStyle.dark, .light] {
            for category in [UIContentSizeCategory.large, .accessibilityExtraExtraExtraLarge] {
                let size = fittingSize(row, style: style, category: category)

                XCTAssertGreaterThanOrEqual(size.height, 44, "\(style.rawValue) \(category.rawValue)")
                XCTAssertLessThanOrEqual(size.width, 390.5)
            }
        }
    }

    func testALongNameWrapsInsteadOfBeingCutOff() {
        let name = "Een heel lange bedrijfsnaam voor een contact die op het grootste lettertype over meerdere regels moet lopen"
        let normal = fittingSize(ContactRow(entry: entry("a", name)), style: .dark, category: .large)
        let huge = fittingSize(ContactRow(entry: entry("a", name)), style: .dark, category: .accessibilityExtraExtraExtraLarge)

        XCTAssertGreaterThan(huge.height, normal.height)
        XCTAssertLessThanOrEqual(huge.width, 390.5)
    }

    func testTheFilterSheetDrawsInEveryMode() {
        let options = [
            ContactFilterOption(filter: .all, title: "Alles"),
            ContactFilterOption(filter: .favorites, title: "Favorieten"),
            ContactFilterOption(filter: .internalOnly, title: "Intern"),
            ContactFilterOption(filter: .list(accountId: "a", listId: "l"), title: "Klanten", isList: true),
        ]

        for style in [UIUserInterfaceStyle.dark, .light] {
            for category in [UIContentSizeCategory.large, .accessibilityExtraExtraExtraLarge] {
                let sheet = ContactFilterSheet(options: options, selection: .constant(.all), showsSources: true, onSources: {})
                let size = fittingSize(sheet, style: style, category: category)

                XCTAssertGreaterThan(size.height, 100, "\(style.rawValue) \(category.rawValue)")
                XCTAssertLessThanOrEqual(size.width, 390.5)
            }
        }
    }

    func testTheSegmentsAndFilterPillWrapAtTheLargestType() {
        let header = VStack {
            SegmentedBar(options: [.init(value: ContactScope.centrale, title: "Centrale"), .init(value: .phone, title: "Telefoon")], selection: .constant(.centrale))
            FilterButton(title: "Favorieten", isActive: true) {}
        }
        .padding(16)

        for style in [UIUserInterfaceStyle.dark, .light] {
            let size = fittingSize(header, style: style, category: .accessibilityExtraExtraExtraLarge)

            XCTAssertLessThanOrEqual(size.width, 390.5)
            XCTAssertGreaterThanOrEqual(size.height, 80)
        }
    }

    // MARK: Sources

    private static func table(_ language: String) -> [String: String] {
        let path = Bundle.module.path(forResource: "Localizable", ofType: "strings", inDirectory: nil, forLocalization: language)!

        return NSDictionary(contentsOfFile: path) as! [String: String]
    }

    private static var sourceDirectory: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources/UI", isDirectory: true)
    }

    func testEveryContactsKeyIsTranslatedAndBlueIsNotUsed() throws {
        let nl = Self.table("nl")
        let en = Self.table("en")
        let regex = try NSRegularExpression(pattern: "L10n\\.(?:string|text)\\(\"([A-Za-z0-9_.]+)\"")
        let files = ["ContactsView.swift", "ContactDetailView.swift", "ContactEditView.swift", "ContactsBrowsing.swift"]

        for file in files {
            let text = try String(contentsOf: Self.sourceDirectory.appendingPathComponent(file))

            XCTAssertFalse(text.contains(".blue"), "\(file) uses blue")
            XCTAssertFalse(text.contains("Privé"), "\(file): there is no private contact")

            for match in regex.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
                let key = String(text[Range(match.range(at: 1), in: text)!])

                XCTAssertNotNil(nl[key], "no Dutch text for \(key)")
                XCTAssertNotNil(en[key], "no English text for \(key)")
            }
        }

        for key in nl.keys where key.hasPrefix("contacts.") {
            XCTAssertNotNil(en[key], "missing in English: \(key)")
        }
    }
}
