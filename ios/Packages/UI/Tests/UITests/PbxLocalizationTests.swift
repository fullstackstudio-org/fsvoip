// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import UI

/// Dutch and English carry the same `pbx.*` keys with the same placeholders, and no screen asks for a key that is missing.
final class PbxLocalizationTests: XCTestCase {
    private static func strings(_ language: String) -> [String: String] {
        let path = Bundle.module.path(forResource: "Localizable", ofType: "strings", inDirectory: nil, forLocalization: language)!
        let dictionary = NSDictionary(contentsOfFile: path) as! [String: String]

        return dictionary.filter { $0.key.hasPrefix("pbx.") }
    }

    private func placeholders(_ text: String) -> [String] {
        let regex = try! NSRegularExpression(pattern: "%(?:\\d+\\$)?(?:ld|@|d|f)")
        let range = NSRange(text.startIndex..., in: text)

        return regex.matches(in: text, range: range).map { String(text[Range($0.range, in: text)!]) }.sorted()
    }

    func testBothLanguagesHaveTheSameKeysAndPlaceholders() {
        let nl = Self.strings("nl")
        let en = Self.strings("en")

        XCTAssertGreaterThan(nl.count, 150)
        XCTAssertEqual(Set(nl.keys), Set(en.keys))

        for (key, text) in nl {
            XCTAssertEqual(placeholders(text), placeholders(en[key] ?? ""), key)
            XCTAssertFalse(text.isEmpty, key)
            XCTAssertFalse((en[key] ?? "").isEmpty, key)
        }
    }

    func testNoTelecomJargonInTheDutchWords() {
        let banned = ["extensie", "ivr", "trunk", "gateway", "sip", "dialplan", "pbx"]

        for (key, text) in Self.strings("nl") {
            let lower = text.lowercased()

            for word in banned {
                XCTAssertFalse(lower.range(of: "\\b\(word)\\b", options: .regularExpression) != nil, "\(key): \(text)")
            }
        }
    }

    func testEveryVocabularyWordResolves() {
        // A key that is not translated would show as the key itself.
        for key in ["pbx.title", "pbx.flow.device", "pbx.when.open", "pbx.strategy.all", "pbx.error.stale", "pbx.closed.defaultName"] {
            XCTAssertNotEqual(L10n.string(key), key)
        }
    }
}
