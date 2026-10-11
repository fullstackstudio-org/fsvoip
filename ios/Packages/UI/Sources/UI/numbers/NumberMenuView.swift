// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// "Keuzemenu": the instruction callers hear, how often it repeats, what counts when nothing is pressed, and the keys 1-9 and 0.
struct NumberMenuView: View {
    @Binding var draft: NumberForwardingDraft
    let options: ChainOptions

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SettingsGroup(title: L10n.string("numbers.menu.instruction"), footer: L10n.string("numbers.menu.instruction.footer")) {
                ChainSoundRow(title: L10n.string("numbers.menu.instruction"), soundId: $draft.greetingSoundId, options: options)
            }

            SettingsGroup(title: L10n.string("numbers.menu.repeats"), footer: L10n.string("numbers.menu.repeats.footer")) {
                Stepper(value: $draft.repeats, in: NumberForwardingDraft.repeatsRange) {
                    Text(String(format: L10n.string("numbers.menu.repeats.value"), draft.repeats))
                        .foregroundStyle(Theme.textPrimary)
                }
                .settingsRowChrome()
                .accessibilityIdentifier("menu-repeats")
            }

            SettingsGroup(title: L10n.string("numbers.menu.noChoiceGroup")) {
                NavigationLink {
                    DefaultKeyPage(draft: $draft, options: options)
                } label: {
                    SettingsRow(symbol: "hand.tap", title: L10n.string("numbers.menu.defaultKey"), value: draft.defaultKey ?? L10n.string("numbers.menu.defaultKey.none"))
                }
                .buttonStyle(RowButtonStyle())
                .accessibilityIdentifier("menu-default-key")

                if draft.defaultKey == nil {
                    NumberFallbackRow(title: L10n.string("numbers.menu.noChoice"), symbol: "clock", fallback: $draft.noChoice, options: options)
                }
            }

            SettingsGroup(title: L10n.string("numbers.menu.keys"), footer: draft.keys.isEmpty ? L10n.string("numbers.menu.keys.none") : nil) {
                ForEach(NumberForwardingDraft.menuDigits, id: \.self) { digit in
                    NavigationLink {
                        NumberMenuKeyView(digit: digit, draft: $draft, options: options)
                    } label: {
                        keyRow(digit)
                    }
                    .buttonStyle(RowButtonStyle())
                    .accessibilityIdentifier("menu-key-\(digit)")
                }
            }
        }
    }

    private func keyRow(_ digit: String) -> some View {
        let key = draft.key(digit)

        return HStack(spacing: Theme.Spacing.m) {
            Text(digit)
                .font(Theme.digits(17, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
                .frame(width: 32, height: 32)
                .background(Theme.separator, in: Circle())
                .accessibilityHidden(true)

            Text(key.map { ChainWords.target($0.target, options) } ?? L10n.string("numbers.menu.key.off"))
                .foregroundStyle(key == nil ? Theme.textTertiary : Theme.textSecondary)
                .frame(maxWidth: .infinity, alignment: .trailing)
                .fixedSize(horizontal: false, vertical: true)

            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Theme.textTertiary)
                .accessibilityHidden(true)
        }
        .settingsRowChrome()
        .accessibilityElement(children: .combine)
        .accessibilityLabel(String(format: L10n.string("numbers.menu.key.label"), digit))
    }
}

/// "Standaardtoets": the key that counts when the caller presses nothing.
private struct DefaultKeyPage: View {
    @Binding var draft: NumberForwardingDraft
    let options: ChainOptions

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ChainSubPage(title: L10n.string("numbers.menu.defaultKey")) {
            SettingsGroup(footer: L10n.string("numbers.menu.defaultKey.footer")) {
                ChoiceRow(title: L10n.string("numbers.menu.defaultKey.none"), isSelected: draft.defaultKey == nil) {
                    draft.defaultKey = nil
                    dismiss()
                }

                ForEach(draft.keys, id: \.digit) { key in
                    ChoiceRow(title: String(format: L10n.string("numbers.menu.key.label"), key.digit), subtitle: ChainWords.target(key.target, options), isSelected: draft.defaultKey == key.digit) {
                        draft.defaultKey = key.digit
                        dismiss()
                    }
                }
            }
        }
    }
}
