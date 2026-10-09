// SPDX-License-Identifier: AGPL-3.0-or-later
import SipEngine
import SwiftUI
import UIKit

/// Alias of `Theme` (the design tokens live in `design/Theme.swift`). Kept so the screens that predate the design system keep
/// compiling; new code uses `Theme`.
enum Brand {
    /// `#c7ff4a`
    static let lime = Theme.accent
    /// `#101317`, text on lime and the in-call background.
    static let ink = Theme.ink
    /// `#1d232b`, the lighter end of the in-call background.
    static let inkRaised = Theme.inkRaised
    /// "Connecting": a warm amber that reads on light and dark.
    static let amber = Theme.busy
    /// Hang up / decline.
    static let hangUp = Theme.danger

    /// Digits on the keypad and the dialled number: rounded, light, like a phone's own keypad.
    static func digits(_ size: CGFloat, weight: Font.Weight = .light) -> Font {
        Theme.digits(size, weight: weight)
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(Theme.onAccent)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, minHeight: 52)
            .padding(.horizontal, Theme.Spacing.s)
            .background(Theme.accent.opacity(isEnabled ? (configuration.isPressed ? 0.75 : 1) : 0.4), in: Theme.card(Theme.Radius.s))
    }
}

struct SecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(Theme.textPrimary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, minHeight: 52)
            .padding(.horizontal, Theme.Spacing.s)
            .background(Theme.raised.opacity(configuration.isPressed ? 0.6 : 1), in: Theme.card(Theme.Radius.s))
    }
}

enum Haptics {
    static func tap() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    static func success() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    static func warning() {
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
    }
}

// MARK: - Line status

extension RegistrationState {
    var tint: Color {
        switch self {
        case .registered: return Brand.lime
        case .registering: return Brand.amber
        case .failed: return Brand.hangUp
        case .unregistered: return Theme.textTertiary
        }
    }

    var label: String {
        switch self {
        case .registered: return L10n.string("line.registered")
        case .registering: return L10n.string("line.registering")
        case .unregistered: return L10n.string("line.unregistered")
        case .failed(.authentication): return L10n.string("line.failed.authentication")
        case .failed(.network): return L10n.string("line.failed.network")
        case .failed(.other): return L10n.string("line.failed.other")
        }
    }
}

/// The light of a line: lime = connected, amber = connecting, red = problem, grey = off.
struct StatusLight: View {
    let state: RegistrationState
    var size: CGFloat = 9

    var body: some View {
        Circle()
            .fill(state.tint)
            .frame(width: size, height: size)
            // Lime alone disappears on white; a hairline of ink keeps it visible in light mode.
            .overlay(Circle().strokeBorder(Brand.ink.opacity(0.18), lineWidth: 0.75))
            .accessibilityHidden(true)
    }
}

struct NoticeBanner: View {
    let notice: FSVoipAppModel.Notice
    let dismiss: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: notice.isError ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
                .foregroundStyle(notice.isError ? Theme.danger : Theme.accentText)
                .accessibilityHidden(true)
            Text(notice.message)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            Button(action: dismiss) {
                Image(systemName: "xmark")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(.secondary)
                    .frame(width: 28, height: 28)
            }
            .accessibilityLabel(L10n.string("action.close"))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .shadow(color: .black.opacity(0.12), radius: 12, y: 4)
        .padding(.horizontal, 12)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("notice-banner")
    }
}

/// Brand mark: a handset in a lime tile.
struct BrandMark: View {
    var symbol = "phone.fill"
    var size: CGFloat = 64

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.42, weight: .semibold))
            .foregroundStyle(Brand.ink)
            .frame(width: size, height: size)
            .background(Brand.lime, in: RoundedRectangle(cornerRadius: size * 0.28, style: .continuous))
            .accessibilityHidden(true)
    }
}
