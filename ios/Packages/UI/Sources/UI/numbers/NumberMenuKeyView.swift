// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// One key of the menu: off, a phone, a ring group, a voicemail box, or an outside number. A key that leads somewhere the app
/// cannot show (set up in the portal) is shown, not changed.
struct NumberMenuKeyView: View {
    let digit: String
    @Binding var draft: NumberForwardingDraft
    let options: ChainOptions

    @Environment(\.dismiss) private var dismiss
    @State private var number = ""

    private var key: ChainKey? { draft.key(digit) }

    private var cleanedNumber: String {
        number.filter { $0.isNumber || $0 == "+" }
    }

    var body: some View {
        ChainSubPage(title: String(format: L10n.string("numbers.menu.key.label"), digit)) {
            if let key, !key.editable {
                VStack(alignment: .leading, spacing: 0) {
                    NoticeCard(symbol: "lock.fill", tint: Theme.textSecondary, title: L10n.string("numbers.portalOnly"), message: L10n.string("numbers.menu.key.portal"))
                        .accessibilityIdentifier("menu-key-readonly")
                    SettingsGroup {
                        SettingsRow(title: ChainWords.target(key.target, options), showsChevron: false)
                    }
                }
            } else {
                choices
            }
        }
        .onAppear {
            if let target = key?.target, target.type == .external {
                number = target.number ?? ""
            }
        }
    }

    private var choices: some View {
        VStack(alignment: .leading, spacing: 0) {
            SettingsGroup {
                ChoiceRow(title: L10n.string("numbers.menu.key.off"), isSelected: key == nil) { choose(nil) }
            }

            SettingsGroup(title: L10n.string("numbers.fallback.group.device")) {
                ForEach(options.devices) { device in
                    choice(device.name, .object(.device, id: device.id))
                }
            }

            if !options.groups.isEmpty {
                SettingsGroup(title: L10n.string("pbx.type.ringGroup")) {
                    ForEach(options.groups) { group in
                        choice(group.name, .object(.ringGroup, id: group.id))
                    }
                }
            }

            SettingsGroup(title: L10n.string("numbers.fallback.group.voicemail")) {
                ForEach(options.devices) { device in
                    choice(String(format: L10n.string("numbers.fallback.voicemailOf"), device.name), .object(.voicemail, id: device.id))
                }
            }

            SettingsGroup(title: L10n.string("numbers.fallback.group.forward"), footer: L10n.string("numbers.fallback.forward.footer")) {
                HStack(spacing: Theme.Spacing.s) {
                    TextField(L10n.string("numbers.fallback.forward.placeholder"), text: $number)
                        .keyboardType(.phonePad)
                        .foregroundStyle(Theme.textPrimary)

                    Button(L10n.string("numbers.fallback.forward.use")) {
                        choose(.external(cleanedNumber))
                    }
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Theme.accentText)
                    .disabled(cleanedNumber.count < 3)
                }
                .settingsRowChrome()
            }
        }
    }

    private func choice(_ title: String, _ target: PbxTarget) -> some View {
        ChoiceRow(title: title, isSelected: key?.target == target) { choose(target) }
    }

    private func choose(_ target: PbxTarget?) {
        draft.setKey(digit, target: target)
        dismiss()
    }
}
