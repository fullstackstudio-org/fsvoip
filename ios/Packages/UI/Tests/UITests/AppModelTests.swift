// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Pairing
import XCTest
@testable import UI

@MainActor
final class AppModelTests: XCTestCase {
    private let token = "fss_vpair_" + String(repeating: "aB3-_", count: 8) + "xyz"

    private func model() -> FSVoipAppModel {
        FSVoipAppModel(accountStore: AccountStore(secrets: InMemorySecretStore()))
    }

    func testStartsOnOnboardingWithoutAccounts() {
        let model = model()

        XCTAssertEqual(model.screen, .onboarding)
        XCTAssertTrue(model.accounts.isEmpty)
        XCTAssertFalse(model.canPair, "the shell does not claim tokens yet")
    }

    func testUniversalLinkShowsTheReceivedScreen() throws {
        let model = model()

        model.handleIncoming(url: URL(string: "https://fullstackstudio.nl/fsvoip/pair?t=\(token)")!)

        XCTAssertEqual(model.screen, .pairingLink(try PairingLink(token: token)))
        XCTAssertNil(model.errorMessage)
    }

    func testCustomSchemeLink() throws {
        let model = model()

        model.handleIncoming(url: URL(string: "fsvoip://pair?t=\(token)")!)

        XCTAssertEqual(model.screen, .pairingLink(try PairingLink(token: token)))
    }

    func testForeignLinkShowsAnErrorAndStaysOnOnboarding() {
        let model = model()

        model.handleIncoming(url: URL(string: "https://evil.example/fsvoip/pair?t=\(token)")!)

        XCTAssertEqual(model.screen, .onboarding)
        XCTAssertNotNil(model.errorMessage)
    }

    func testScannedTextClosesTheScanner() throws {
        let model = model()
        model.isScannerPresented = true

        XCTAssertTrue(model.handleScanned("https://fullstackstudio.nl/fsvoip/pair?t=\(token)"))

        XCTAssertFalse(model.isScannerPresented)
        XCTAssertEqual(model.screen, .pairingLink(try PairingLink(token: token)))
    }

    func testBadScanKeepsTheScannerOpen() {
        let model = model()
        model.isScannerPresented = true

        XCTAssertFalse(model.handleScanned("hello"))

        XCTAssertTrue(model.isScannerPresented)
        XCTAssertNotNil(model.errorMessage)
    }

    func testDiscardReturnsToOnboarding() {
        let model = model()
        model.handleIncoming(url: URL(string: "fsvoip://pair?t=\(token)")!)

        model.discardLink()

        XCTAssertEqual(model.screen, .onboarding)
    }

    func testConfirmPairingUsesThePairActionWhenPresent() async throws {
        let model = model()
        model.handleIncoming(url: URL(string: "fsvoip://pair?t=\(token)")!)
        let called = expectation(description: "pairAction called")
        model.pairAction = { link in
            XCTAssertEqual(link.token, "fss_vpair_" + String(repeating: "aB3-_", count: 8) + "xyz")
            called.fulfill()
            throw APIError.notFound
        }

        XCTAssertTrue(model.canPair)
        await model.confirmPairing()
        await fulfillment(of: [called], timeout: 1)

        XCTAssertNotNil(model.errorMessage)
    }

    func testStringsExistInBothLanguages() throws {
        // Every key must be translated: a missing key shows the key itself.
        let keys = try String(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Sources/UI/Resources/nl.lproj/Localizable.strings"))
            .split(separator: "\n")
            .compactMap { line -> String? in
                guard line.hasPrefix("\"") else { return nil }
                return line.split(separator: "\"").first.map(String.init)
            }

        XCTAssertGreaterThan(keys.count, 10)

        for language in ["nl", "en"] {
            let path = try XCTUnwrap(L10n.bundle.path(forResource: "Localizable", ofType: "strings", inDirectory: nil, forLocalization: language), "no \(language) strings")
            let table = try XCTUnwrap(NSDictionary(contentsOfFile: path) as? [String: String])

            for key in keys {
                XCTAssertFalse((table[key] ?? "").isEmpty, "\(key) missing in \(language)")
            }

            XCTAssertEqual(Set(table.keys), Set(keys), "\(language) has different keys than nl")
        }
    }
}
