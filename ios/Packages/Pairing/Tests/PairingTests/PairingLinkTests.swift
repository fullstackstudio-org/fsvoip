// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import Pairing

final class PairingLinkTests: XCTestCase {
    private let token = "fss_vpair_" + String(repeating: "aB3-_", count: 8) + "xyz"

    func testTokenFixtureLengthIsRight() {
        XCTAssertEqual(token.count, 53)
    }

    func testUniversalLink() throws {
        let link = try PairingLinkParser.parse(URL(string: "https://fullstackstudio.nl/fsvoip/pair?t=\(token)")!)

        XCTAssertEqual(link.token, token)
    }

    func testUniversalLinkVariants() throws {
        for text in [
            "https://www.fullstackstudio.nl/fsvoip/pair?t=\(token)",
            "HTTPS://FullStackStudio.nl/fsvoip/pair/?t=\(token)&utm=x",
            "https://fullstackstudio.nl/fsvoip/pair?foo=1&t=\(token)",
        ] {
            XCTAssertEqual(try PairingLinkParser.parse(URL(string: text)!).token, token, text)
        }
    }

    func testCustomScheme() throws {
        XCTAssertEqual(try PairingLinkParser.parse(URL(string: "fsvoip://pair?t=\(token)")!).token, token)
        XCTAssertEqual(try PairingLinkParser.parse(URL(string: "fsvoip:///pair?t=\(token)")!).token, token)
    }

    func testScannedTextIsTrimmed() throws {
        XCTAssertEqual(try PairingLinkParser.parse(scanned: "  https://fullstackstudio.nl/fsvoip/pair?t=\(token)\n").token, token)
    }

    func testForeignLinksAreRejected() {
        let foreign = [
            "https://evil.example/fsvoip/pair?t=\(token)",
            "https://fullstackstudio.nl.evil.example/fsvoip/pair?t=\(token)",
            "https://fullstackstudio.nl/portal?t=\(token)",
            "https://fullstackstudio.nl/fsvoip/pair/extra?t=\(token)",
            "http://fullstackstudio.nl/fsvoip/pair?t=\(token)",
            "fsvoip://other?t=\(token)",
            "tel:+31701234567",
            "mailto:info@fullstackstudio.nl",
        ]

        for text in foreign {
            XCTAssertThrowsError(try PairingLinkParser.parse(URL(string: text)!), text) { error in
                XCTAssertEqual(error as? PairingLinkError, .notAPairingLink, text)
            }
        }

        XCTAssertThrowsError(try PairingLinkParser.parse(scanned: "just some words")) { error in
            XCTAssertEqual(error as? PairingLinkError, .notAPairingLink)
        }
    }

    func testMissingOrMalformedToken() {
        XCTAssertThrowsError(try PairingLinkParser.parse(URL(string: "https://fullstackstudio.nl/fsvoip/pair")!)) { error in
            XCTAssertEqual(error as? PairingLinkError, .missingToken)
        }
        XCTAssertThrowsError(try PairingLinkParser.parse(URL(string: "fsvoip://pair?t=")!)) { error in
            XCTAssertEqual(error as? PairingLinkError, .missingToken)
        }

        for bad in ["test", "fss_vpair_short", "fss_vapp_" + String(repeating: "a", count: 43), token + "X", String(token.dropLast()) + "!", "fss_vpair_" + String(repeating: "é", count: 43)] {
            XCTAssertThrowsError(try PairingLinkParser.parse(URL(string: "fsvoip://pair?t=\(bad.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? bad)")!), bad) { error in
                XCTAssertEqual(error as? PairingLinkError, .malformedToken, bad)
            }
        }
    }

    func testLinkNeverPrintsTheToken() throws {
        let link = try PairingLink(token: token)
        var dumped = ""
        dump(link, to: &dumped)

        XCTAssertFalse("\(link)".contains("aB3"))
        XCTAssertFalse(String(reflecting: link).contains("aB3"))
        XCTAssertFalse(dumped.contains("aB3"))
    }
}
