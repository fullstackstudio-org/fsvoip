// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI
import UIKit
import XCTest
@testable import UI

/// Tokens, sheet rules, strings and layout of the design system (plan `fsvoip-app-v2`, D12/D13, Task 6).
@MainActor
final class DesignSystemTests: XCTestCase {
    private func rgb(_ color: UIColor, style: UIUserInterfaceStyle) -> [Int] {
        let resolved = color.resolvedColor(with: UITraitCollection(userInterfaceStyle: style))
        var (r, g, b, a) = (CGFloat(0), CGFloat(0), CGFloat(0), CGFloat(0))
        resolved.getRed(&r, green: &g, blue: &b, alpha: &a)

        return [Int((r * 255).rounded()), Int((g * 255).rounded()), Int((b * 255).rounded())]
    }

    // MARK: Tokens

    func testDarkTokensAreTheAgreedInks() {
        XCTAssertEqual(rgb(Theme.uiBackground, style: .dark), [0x10, 0x13, 0x17])
        XCTAssertEqual(rgb(Theme.uiSheet, style: .dark), [0x16, 0x1B, 0x21])
        XCTAssertEqual(rgb(Theme.uiRaised, style: .dark), [0x1D, 0x23, 0x2B])
        XCTAssertEqual(rgb(Theme.uiAccent, style: .dark), [0xC7, 0xFF, 0x4A])
    }

    func testLimeIsTheAccentOnDarkAndInkTakesOverOnLight() {
        XCTAssertEqual(rgb(Theme.uiAccentText, style: .dark), [0xC7, 0xFF, 0x4A])
        XCTAssertEqual(rgb(Theme.uiAccentText, style: .light), [0x10, 0x13, 0x17], "lime text is unreadable on white")
        XCTAssertNotEqual(rgb(Theme.uiBackground, style: .dark), rgb(Theme.uiBackground, style: .light))
    }

    func testNoBlueAmongTheFixedColours() {
        for color in [Theme.uiAccent, Theme.uiInk, Theme.uiInkRaised, Theme.uiBusy, Theme.uiDanger] {
            let value = rgb(color, style: .dark)
            XCTAssertFalse(value[2] > value[0] && value[2] > value[1] && value[2] > 0x60, "a blue-ish token: \(value)")
        }
    }

    func testSpacingAndRadiusScale() {
        XCTAssertEqual([Theme.Spacing.xs, Theme.Spacing.s, Theme.Spacing.m, Theme.Spacing.l, Theme.Spacing.xl], [4, 8, 12, 16, 24])
        XCTAssertEqual([Theme.Radius.s, Theme.Radius.m, Theme.Radius.l], [12, 16, 22])
        XCTAssertGreaterThanOrEqual(Theme.minimumTarget, 44)
    }

    func testBrandIsAnAliasOfTheme() {
        XCTAssertEqual(Brand.lime, Theme.accent)
        XCTAssertEqual(Brand.hangUp, Theme.danger)
        XCTAssertEqual(Brand.amber, Theme.busy)
    }

    func testDarkIsTheDefaultAppearance() {
        XCTAssertEqual(AppearancePreference.default, .dark)
        XCTAssertEqual(AppearancePreference.dark.colorScheme, .dark)
        XCTAssertNil(AppearancePreference.system.colorScheme)
    }

    // MARK: Sheet

    func testAnUntouchedFormClosesAtOnceAndADirtyOneAsks() {
        XCTAssertEqual(SheetDismissal.decide(isDirty: false, isSaving: false), .proceed)
        XCTAssertEqual(SheetDismissal.decide(isDirty: true, isSaving: false), .confirmDiscard)
        XCTAssertEqual(SheetDismissal.decide(isDirty: true, isSaving: true), .proceed)
    }

    // MARK: Avatar and dot

    func testInitials() {
        XCTAssertEqual(Initials.make(from: "Jan de Vries"), "JV")
        XCTAssertEqual(Initials.make(from: "Receptie"), "R")
        XCTAssertEqual(Initials.make(from: "  "), "")
        XCTAssertEqual(Initials.make(from: nil), "")
    }

    func testTheDotFollowsDoNotDisturbBeforeTheLine() {
        XCTAssertEqual(AvailabilityDot.kind(registered: true, connecting: false, doNotDisturb: true), .doNotDisturb)
        XCTAssertEqual(AvailabilityDot.kind(registered: true, connecting: false, doNotDisturb: false), .available)
        XCTAssertEqual(AvailabilityDot.kind(registered: false, connecting: true, doNotDisturb: false), .connecting)
        XCTAssertEqual(AvailabilityDot.kind(registered: false, connecting: false, doNotDisturb: false), .offline)
    }

    // MARK: Tabs

    func testFiveTabsInTheAgreedOrder() {
        XCTAssertEqual(FSVoipAppModel.Tab.allCases, [.dialer, .onHold, .recents, .voicemail, .contacts])
    }

    // MARK: Layout (dark, light, largest Dynamic Type)

    private func fittingSize<V: View>(_ view: V, style: UIUserInterfaceStyle, category: UIContentSizeCategory, width: CGFloat = 390) -> CGSize {
        let host = UIHostingController(rootView: view.environment(\.sizeCategory, ContentSizeCategory(category) ?? .large))
        host.overrideUserInterfaceStyle = style
        host.view.frame = CGRect(x: 0, y: 0, width: width, height: 2000)
        host.view.layoutIfNeeded()

        return host.sizeThatFits(in: CGSize(width: width, height: .greatestFiniteMagnitude))
    }

    func testARowIsAtLeast44PointsInEveryMode() {
        for style in [UIUserInterfaceStyle.dark, .light] {
            for category in [UIContentSizeCategory.large, .accessibilityExtraExtraExtraLarge] {
                let size = fittingSize(SettingsRow(title: "Nummers"), style: style, category: category)

                XCTAssertGreaterThanOrEqual(size.height, 44, "\(style.rawValue) \(category.rawValue)")
            }
        }
    }

    func testALongRowGrowsInsteadOfBeingCutOff() {
        let title = "Een heel lange titel van een rij die op het grootste lettertype over meerdere regels moet lopen"
        let normal = fittingSize(SettingsRow(title: title, value: "Waarde"), style: .dark, category: .large)
        let huge = fittingSize(SettingsRow(title: title, value: "Waarde"), style: .dark, category: .accessibilityExtraExtraExtraLarge)

        XCTAssertGreaterThan(huge.height, normal.height * 1.5)
        XCTAssertLessThanOrEqual(huge.width, 390.5)
    }

    func testTheSheetShellDrawsAtTheLargestTypeInBothModes() {
        for style in [UIUserInterfaceStyle.dark, .light] {
            let shell = SheetShell(title: "Profiel", back: {}, onClose: {}, footer: SheetFooter(onSave: {})) {
                SettingsGroup(title: "Naam", footer: "Uitleg") {
                    ToggleRow(title: "Beschikbaar", explanation: "Je telefoon gaat over bij een inkomend gesprek.", isOn: .constant(true))
                }
            }
            let size = fittingSize(shell, style: style, category: .accessibilityExtraExtraExtraLarge)

            XCTAssertGreaterThan(size.height, 100)
            XCTAssertLessThanOrEqual(size.width, 390.5)
        }
    }

    // MARK: Strings

    private static func table(_ language: String) -> [String: String] {
        let path = Bundle.module.path(forResource: "Localizable", ofType: "strings", inDirectory: nil, forLocalization: language)!

        return NSDictionary(contentsOfFile: path) as! [String: String]
    }

    func testTheShellStringsExistInDutchAndEnglish() throws {
        let nl = Self.table("nl")
        let en = Self.table("en")
        let prefixes = ["settings.", "sheet.", "tab.", "onHold.", "voicemail.empty", "appearance.", "action."]

        let keys = nl.keys.filter { key in prefixes.contains { key.hasPrefix($0) } }
        XCTAssertGreaterThan(keys.count, 40)

        for key in keys {
            XCTAssertNotNil(en[key], "missing in English: \(key)")
            XCTAssertFalse(en[key]?.isEmpty ?? true, key)
        }

        for key in en.keys where prefixes.contains(where: { key.hasPrefix($0) }) {
            XCTAssertNotNil(nl[key], "missing in Dutch: \(key)")
        }
    }

    func testEveryKeyTheShellAsksForIsTranslated() throws {
        let sources = ["SettingsSheet.swift", "RootView.swift", "design"].map { Self.sourceDirectory.appendingPathComponent($0) }
        let nl = Self.table("nl")
        let en = Self.table("en")
        let regex = try NSRegularExpression(pattern: "L10n\\.(?:string|text)\\(\"([A-Za-z0-9_.]+)\"")

        for text in try sources.flatMap({ try Self.swiftSources(at: $0) }) {
            for match in regex.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
                let key = String(text[Range(match.range(at: 1), in: text)!])

                XCTAssertNotNil(nl[key], "no Dutch text for \(key)")
                XCTAssertNotNil(en[key], "no English text for \(key)")
            }
        }
    }

    /// Plan Task 6: the sheet has no "uitbellen via" and no "anoniem" row (the dialler owns them).
    func testTheSheetHasNoCallerChoiceOrAnonymousRow() throws {
        let banned = ["uitbellen", "anoniem", "anonymous", "outgoingvia", "callerchoice", "callerid"]

        for (language, table) in [("nl", Self.table("nl")), ("en", Self.table("en"))] {
            for (key, text) in table where key.hasPrefix("settings.") || key.hasPrefix("sheet.") {
                for word in banned {
                    XCTAssertFalse(key.lowercased().contains(word), "\(language) key \(key)")
                    XCTAssertFalse(text.lowercased().contains(word), "\(language) text \(key): \(text)")
                }
            }
        }

        let sheet = try String(contentsOf: Self.sourceDirectory.appendingPathComponent("SettingsSheet.swift"))

        for word in banned {
            XCTAssertFalse(sheet.lowercased().contains(word), "SettingsSheet.swift mentions \(word)")
        }
    }

    func testNoBlueAndNoRinkelInTheDesignSources() throws {
        let sources = try Self.swiftSources(at: Self.sourceDirectory.appendingPathComponent("design")) + [
            String(contentsOf: Self.sourceDirectory.appendingPathComponent("SettingsSheet.swift")),
        ]

        for text in sources {
            XCTAssertFalse(text.contains(".blue"), "a blue colour")
            XCTAssertFalse(text.lowercased().contains("rinkel"))
        }
    }

    private static var sourceDirectory: URL {
        // .../ios/Packages/UI/Tests/UITests/DesignSystemTests.swift -> .../ios/Packages/UI/Sources/UI
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources/UI", isDirectory: true)
    }

    private static func swiftSources(at url: URL) throws -> [String] {
        var isDirectory: ObjCBool = false
        FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory)

        if !isDirectory.boolValue {
            return [try String(contentsOf: url)]
        }

        return try FileManager.default.contentsOfDirectory(at: url, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "swift" }
            .map { try String(contentsOf: $0) }
    }
}
