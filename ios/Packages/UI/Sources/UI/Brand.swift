// SPDX-License-Identifier: AGPL-3.0-or-later
import SipEngine
import SwiftUI
import UIKit

/// FullStack Studio house style for FSVoip. System fonts and colours carry the app; lime is the accent and is spent
/// on exactly three things: the call button, the "connected" light of a line and the primary action of the pairing
/// flow.
enum Brand {
    /// `#c7ff4a`
    static let lime = Color(red: 199 / 255, green: 255 / 255, blue: 74 / 255)
    /// `#101317`, text on lime and the in-call background.
    static let ink = Color(red: 16 / 255, green: 19 / 255, blue: 23 / 255)
    /// `#1d232b`, the lighter end of the in-call background.
    static let inkRaised = Color(red: 29 / 255, green: 35 / 255, blue: 43 / 255)
    /// "Connecting": a warm amber that reads on light and dark.
    static let amber = Color(red: 232 / 255, green: 162 / 255, blue: 58 / 255)
    /// Hang up / decline.
    static let hangUp = Color(red: 235 / 255, green: 64 / 255, blue: 52 / 255)

    /// Digits on the keypad and the dialled number: rounded, light, like a phone's own keypad.
    static func digits(_ size: CGFloat, weight: Font.Weight = .light) -> Font {
        .system(size: size, weight: weight, design: .rounded)
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(Brand.ink)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(Brand.lime.opacity(isEnabled ? (configuration.isPressed ? 0.75 : 1) : 0.4), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

struct SecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(.primary)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(Color(.secondarySystemFill).opacity(configuration.isPressed ? 0.6 : 1), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
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
        case .unregistered: return Color(.systemGray3)
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
                .foregroundStyle(notice.isError ? Brand.hangUp : Color.green)
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
