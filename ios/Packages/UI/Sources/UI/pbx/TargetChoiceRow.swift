// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// A row "label ... current choice >" on the design system that opens `TargetChoicePage`.
struct TargetChoiceRow: View {
    let title: String
    @Binding var target: PbxTarget?
    let options: [PbxTargetOption]
    /// What nil reads as ("Niet ingesteld").
    var noneLabel = L10n.string("pbx.target.none")
    var allowsNone = true
    /// "Verbinding verbreken" as a choice. Not for a `user`'s own forwarding: the server only knows colleagues, groups, voicemail and
    /// external numbers there.
    var allowsHangup = true
    var footer: String?
    var symbol: String?

    var body: some View {
        NavigationLink {
            TargetChoicePage(title: title, selection: $target, options: options, noneLabel: noneLabel, allowsNone: allowsNone, allowsHangup: allowsHangup, footer: footer)
        } label: {
            SettingsRow(symbol: symbol, title: title, value: target.map { PbxVocabulary.describe($0, options: options) } ?? noneLabel)
        }
        .buttonStyle(RowButtonStyle())
        .accessibilityIdentifier("target-row")
    }
}

/// One list for every kind of destination, in sheet style: a check on the chosen one, an external number typed in a field.
struct TargetChoicePage: View {
    let title: String
    @Binding var selection: PbxTarget?
    let options: [PbxTargetOption]
    var noneLabel: String
    var allowsNone: Bool
    var allowsHangup = true
    var footer: String?

    @Environment(\.dismiss) private var dismiss
    @Environment(\.pbxClose) private var close
    @State private var externalNumber = ""

    private var hasExternal: Bool {
        options.contains { $0.type == .external }
    }

    private var cleanNumber: String {
        externalNumber.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        SheetShell(title: title, back: { dismiss() }, onClose: { (close ?? { dismiss() })() }) {
            VStack(alignment: .leading, spacing: 0) {
                if allowsNone {
                    SettingsGroup(footer: footer) {
                        ChoiceRow(title: noneLabel, isSelected: selection == nil) { choose(nil) }
                    }
                }

                ForEach(PbxVocabulary.pickerOrder, id: \.self) { type in
                    let group = options.filter { $0.type == type }

                    if !group.isEmpty {
                        SettingsGroup(title: PbxVocabulary.groupName(type)) {
                            ForEach(group, id: \.value) { option in
                                ChoiceRow(title: PbxVocabulary.name(of: option), isSelected: TargetChoice.option(for: selection, in: options)?.value == option.value) {
                                    choose(TargetChoice.target(for: option))
                                }
                            }
                        }
                    }
                }

                if allowsHangup {
                    SettingsGroup {
                        ChoiceRow(title: L10n.string("pbx.type.hangup"), isSelected: selection?.type == .hangup) { choose(.hangup) }
                    }
                }

                if hasExternal {
                    SettingsGroup(title: L10n.string("pbx.external.title"), footer: L10n.string("pbx.external.footer")) {
                        TextField(L10n.string("pbx.external.placeholder"), text: $externalNumber)
                            .keyboardType(.phonePad)
                            .textContentType(.telephoneNumber)
                            .foregroundStyle(Theme.textPrimary)
                            .settingsRowChrome()
                            .accessibilityIdentifier("pbx-external-number")

                        Button {
                            if !cleanNumber.isEmpty { choose(.external(cleanNumber)) }
                        } label: {
                            SettingsRow(symbol: selection?.type == .external ? "checkmark" : nil, title: L10n.string("pbx.external.use"), showsChevron: false)
                        }
                        .buttonStyle(RowButtonStyle())
                        .disabled(cleanNumber.isEmpty)
                        .opacity(cleanNumber.isEmpty ? 0.5 : 1)
                    }
                }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            if selection?.type == .external { externalNumber = selection?.number ?? "" }
        }
    }

    private func choose(_ target: PbxTarget?) {
        selection = target
        dismiss()
    }
}
