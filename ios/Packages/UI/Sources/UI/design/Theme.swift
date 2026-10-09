// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI
import UIKit

/// The design tokens of FSVoip (plan `fsvoip-app-v2`, D12). Dark is the default, light is kept tidy. Lime `#c7ff4a` is the ONLY
/// accent: the call button, the primary action, the active tab, the selected segment, the "connected" dot. Everything else is
/// neutral. Danger is `hangUp` red, busy is amber. There is no blue anywhere.
///
/// The UIColor twins exist so a test can resolve a token for a given interface style.
enum Theme {
    // MARK: Fixed colours (the same in dark and light)

    /// `#c7ff4a`
    static let uiAccent = UIColor(hex: 0xC7FF4A)
    /// `#101317`: text on lime, and the base of the dark theme.
    static let uiInk = UIColor(hex: 0x101317)
    /// `#1d232b`
    static let uiInkRaised = UIColor(hex: 0x1D232B)
    static let uiBusy = UIColor(hex: 0xE8A23A)
    static let uiDanger = UIColor(hex: 0xEB4034)

    static let accent = Color(uiAccent)
    static let onAccent = Color(uiInk)
    static let ink = Color(uiInk)
    static let inkRaised = Color(uiInkRaised)
    static let busy = Color(uiBusy)
    static let danger = Color(uiDanger)

    // MARK: Adaptive colours

    /// The screen behind everything. Dark: ink `#101317`. Light: the system background.
    static let uiBackground = UIColor.adaptive(dark: UIColor(hex: 0x101317), light: .systemBackground)
    /// The background of a sheet. Dark: `#161b21`.
    static let uiSheet = UIColor.adaptive(dark: UIColor(hex: 0x161B21), light: .systemBackground)
    /// Cards, rows, the selected segment's neighbours. Dark: `#1d232b`.
    static let uiRaised = UIColor.adaptive(dark: UIColor(hex: 0x1D232B), light: .secondarySystemBackground)
    /// Hairlines: white 8 % on dark, black 8 % on light.
    static let uiSeparator = UIColor.adaptive(dark: UIColor.white.withAlphaComponent(0.08), light: UIColor.black.withAlphaComponent(0.08))
    /// Text: white, 70 % and 55 % on dark; ink, 70 % and 62 % on light (both readable at 4.5:1 on every surface).
    static let uiTextPrimary = UIColor.adaptive(dark: .white, light: UIColor(hex: 0x101317))
    static let uiTextSecondary = UIColor.adaptive(dark: UIColor.white.withAlphaComponent(0.70), light: UIColor(hex: 0x101317).withAlphaComponent(0.70))
    static let uiTextTertiary = UIColor.adaptive(dark: UIColor.white.withAlphaComponent(0.55), light: UIColor(hex: 0x101317).withAlphaComponent(0.62))
    /// Lime as TEXT or ICON: lime on dark, ink on light (lime on white does not read).
    static let uiAccentText = UIColor.adaptive(dark: UIColor(hex: 0xC7FF4A), light: UIColor(hex: 0x101317))
    /// The selected segment: lime text on a raised surface (dark) / on ink (light).
    static let uiSegmentSelected = UIColor.adaptive(dark: UIColor(hex: 0x1D232B), light: UIColor(hex: 0x101317))
    /// The track behind the segments.
    static let uiSegmentTrack = UIColor.adaptive(dark: UIColor(hex: 0x161B21), light: .secondarySystemBackground)

    static let background = Color(uiBackground)
    static let sheet = Color(uiSheet)
    static let raised = Color(uiRaised)
    static let separator = Color(uiSeparator)
    static let textPrimary = Color(uiTextPrimary)
    static let textSecondary = Color(uiTextSecondary)
    static let textTertiary = Color(uiTextTertiary)
    static let accentText = Color(uiAccentText)
    static let segmentSelected = Color(uiSegmentSelected)
    /// Text on `segmentSelected`: lime on both themes (the selected fill is ink on light, so `accentText` would vanish).
    static let uiOnSegmentSelected = uiAccent
    static let onSegmentSelected = Color(uiOnSegmentSelected)
    static let segmentTrack = Color(uiSegmentTrack)

    // MARK: Spacing and radius

    enum Spacing {
        static let xs: CGFloat = 4
        static let s: CGFloat = 8
        static let m: CGFloat = 12
        static let l: CGFloat = 16
        static let xl: CGFloat = 24
    }

    enum Radius {
        static let s: CGFloat = 12
        static let m: CGFloat = 16
        static let l: CGFloat = 22
    }

    /// The minimum height of anything the thumb must hit.
    static let minimumTarget: CGFloat = 44

    /// Digits on the keypad and the dialled number: rounded and light, like a phone's own keypad.
    static func digits(_ size: CGFloat, weight: Font.Weight = .light) -> Font {
        .system(size: size, weight: weight, design: .rounded)
    }

    /// The cards of a settings page: a raised surface with a continuous corner.
    static func card(_ radius: CGFloat = Radius.m) -> RoundedRectangle {
        RoundedRectangle(cornerRadius: radius, style: .continuous)
    }
}

// MARK: - Appearance

/// "Weergave": dark is the default (plan D12).
enum AppearancePreference: String, CaseIterable, Identifiable {
    case dark
    case light
    case system

    static let storageKey = "fsvoip.appearance"
    static let `default` = AppearancePreference.dark

    var id: String { rawValue }

    /// `nil` = follow the system.
    var colorScheme: ColorScheme? {
        switch self {
        case .dark: return .dark
        case .light: return .light
        case .system: return nil
        }
    }

    var titleKey: String {
        "appearance.\(rawValue)"
    }
}

// MARK: - UIColor helpers

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1
        )
    }

    static func adaptive(dark: UIColor, light: UIColor) -> UIColor {
        UIColor { traits in
            traits.userInterfaceStyle == .dark ? dark : light
        }
    }
}
