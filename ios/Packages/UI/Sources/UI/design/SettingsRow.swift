// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI

private struct SettingsRowSeparatorKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    /// Set by `SettingsGroup`: rows inside a group draw a hairline above themselves.
    var drawsRowSeparator: Bool {
        get { self[SettingsRowSeparatorKey.self] }
        set { self[SettingsRowSeparatorKey.self] = newValue }
    }
}

extension View {
    /// The shared frame of a settings-like row: at least 44 pt (and taller as the text grows), the hairline above, one
    /// VoiceOver element.
    func settingsRowChrome() -> some View {
        modifier(SettingsRowChrome())
    }
}

private struct SettingsRowChrome: ViewModifier {
    @Environment(\.drawsRowSeparator) private var drawsSeparator
    @Environment(\.displayScale) private var scale

    func body(content: Content) -> some View {
        content
            .padding(.horizontal, Theme.Spacing.l)
            .padding(.vertical, Theme.Spacing.s)
            .frame(maxWidth: .infinity, minHeight: Theme.minimumTarget, alignment: .leading)
            .contentShape(Rectangle())
            .overlay(alignment: .top) {
                if drawsSeparator {
                    Rectangle().fill(Theme.separator).frame(height: 1 / scale)
                }
            }
    }
}

/// A group of rows on one raised card, hairlines between the rows.
struct SettingsGroup<Content: View>: View {
    var title: String?
    var footer: String?
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.s) {
            if let title {
                Text(title)
                    .font(.footnote.weight(.semibold))
                    .textCase(.uppercase)
                    .foregroundStyle(Theme.textTertiary)
                    .padding(.horizontal, Theme.Spacing.l)
                    .accessibilityAddTraits(.isHeader)
            }

            VStack(spacing: 0) {
                content()
            }
            .environment(\.drawsRowSeparator, true)
            // The first row's hairline sits above the card: shift it out of the clip.
            .offset(y: -1)
            .frame(maxWidth: .infinity)
            .background(Theme.raised)
            .clipShape(Theme.card())

            if let footer {
                Text(footer)
                    .font(.footnote)
                    .foregroundStyle(Theme.textTertiary)
                    .padding(.horizontal, Theme.Spacing.l)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.bottom, Theme.Spacing.l)
    }
}

/// A row of a settings list: optional symbol, title, optional subtitle, optional value and a chevron. It is the LABEL; wrap it in a
/// `Button` or `NavigationLink` (plain style). Long values move under the title instead of being cut off.
struct SettingsRow: View {
    var symbol: String?
    let title: String
    var subtitle: String?
    var value: String?
    var showsChevron = true
    var isDestructive = false

    @ScaledMetric(relativeTo: .body) private var symbolWidth: CGFloat = 26

    var body: some View {
        HStack(spacing: Theme.Spacing.m) {
            if let symbol {
                Image(systemName: symbol)
                    .font(.body)
                    .foregroundStyle(isDestructive ? Theme.danger : Theme.textSecondary)
                    .frame(width: symbolWidth)
                    .accessibilityHidden(true)
            }

            ViewThatFits(in: .horizontal) {
                HStack(alignment: .firstTextBaseline, spacing: Theme.Spacing.m) {
                    titleBlock
                    Spacer(minLength: Theme.Spacing.s)
                    valueText
                }
                VStack(alignment: .leading, spacing: Theme.Spacing.xs) {
                    titleBlock
                    valueText
                }
            }

            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Theme.textTertiary)
                    .accessibilityHidden(true)
            }
        }
        .settingsRowChrome()
        .accessibilityElement(children: .combine)
    }

    private var titleBlock: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.body)
                .foregroundStyle(isDestructive ? Theme.danger : Theme.textPrimary)
                .fixedSize(horizontal: false, vertical: true)

            if let subtitle {
                Text(subtitle)
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    @ViewBuilder
    private var valueText: some View {
        if let value {
            Text(value)
                .font(.body)
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// Buttons inside a group should not get the system's blue tint or a grey press flash.
struct RowButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Theme.separator : Color.clear)
    }
}
