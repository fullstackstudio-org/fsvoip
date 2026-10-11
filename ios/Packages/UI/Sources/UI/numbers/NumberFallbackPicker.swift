// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// A row "Gesloten ... Voicemail >" that opens the choice of what happens: voicemail (shared, or of a phone), play a message,
/// forward to a number, hang up, or a phone. Something the portal set up (`other`) is shown read-only.
struct NumberFallbackRow: View {
    let title: String
    var symbol: String?
    @Binding var fallback: Fallback
    let options: ChainOptions

    var body: some View {
        if fallback.isSelectable {
            NavigationLink {
                NumberFallbackPicker(title: title, selection: Binding(get: { fallback }, set: { fallback = $0 ?? fallback }), options: options)
            } label: {
                SettingsRow(symbol: symbol, title: title, value: ChainWords.fallback(fallback, options))
            }
            .buttonStyle(RowButtonStyle())
            .accessibilityIdentifier("fallback-row")
        } else {
            SettingsRow(symbol: symbol, title: title, subtitle: L10n.string("numbers.portalOnly.hint"), value: ChainWords.fallback(fallback, options), showsChevron: false)
                .accessibilityIdentifier("fallback-row-readonly")
        }
    }
}

/// Like `NumberFallbackRow`, with "the same as when closed" as first choice (`nil`), for holidays.
struct NumberOptionalFallbackRow: View {
    let title: String
    var symbol: String?
    @Binding var fallback: Fallback?
    let noneTitle: String
    let options: ChainOptions

    var body: some View {
        if fallback?.isSelectable ?? true {
            NavigationLink {
                NumberFallbackPicker(title: title, selection: $fallback, options: options, noneTitle: noneTitle)
            } label: {
                SettingsRow(symbol: symbol, title: title, value: fallback.map { ChainWords.fallback($0, options) } ?? noneTitle)
            }
            .buttonStyle(RowButtonStyle())
        } else {
            SettingsRow(symbol: symbol, title: title, subtitle: L10n.string("numbers.portalOnly.hint"), value: L10n.string("numbers.portalOnly"), showsChevron: false)
        }
    }
}

struct NumberFallbackPicker: View {
    let title: String
    @Binding var selection: Fallback?
    let options: ChainOptions
    /// When set, the first choice is "nothing of its own" (`nil`).
    var noneTitle: String?

    @Environment(\.dismiss) private var dismiss
    @State private var number = ""
    @FocusState private var numberFocused: Bool

    private var cleanedNumber: String {
        number.filter { $0.isNumber || $0 == "+" }
    }

    var body: some View {
        ChainSubPage(title: title) {
            VStack(alignment: .leading, spacing: 0) {
                if let noneTitle {
                    SettingsGroup {
                        ChoiceRow(title: noneTitle, isSelected: selection == nil) { choose(nil) }
                    }
                }

                SettingsGroup(title: L10n.string("numbers.fallback.group.voicemail")) {
                    ChoiceRow(title: L10n.string("numbers.fallback.voicemail.shared"), isSelected: isSharedVoicemail) {
                        // The shared box the chain already points at stays the same box; a fresh choice lets the server pick it.
                        if case let .voicemail(_, ofDevice)? = selection, !ofDevice {
                            choose(selection)
                        } else {
                            choose(.voicemail(boxId: nil, ofDevice: false))
                        }
                    }
                    .accessibilityIdentifier("fallback-voicemail-shared")

                    ForEach(options.devices) { device in
                        ChoiceRow(title: String(format: L10n.string("numbers.fallback.voicemailOf"), device.name), isSelected: selection == .voicemail(boxId: device.id, ofDevice: true)) {
                            choose(.voicemail(boxId: device.id, ofDevice: true))
                        }
                    }
                }

                SettingsGroup(title: L10n.string("numbers.fallback.group.message"), footer: options.sounds.isEmpty ? L10n.string("numbers.sound.empty.message") : nil) {
                    ForEach(options.sounds) { sound in
                        ChoiceRow(title: sound.name, isSelected: selection == .message(soundId: sound.id)) {
                            choose(.message(soundId: sound.id))
                        }
                    }

                    if options.sounds.isEmpty {
                        Text(L10n.string("numbers.sound.empty.title"))
                            .foregroundStyle(Theme.textSecondary)
                            .settingsRowChrome()
                    }
                }

                SettingsGroup(title: L10n.string("numbers.fallback.group.device")) {
                    ForEach(options.devices) { device in
                        ChoiceRow(title: device.name, subtitle: device.extensionNumber.map { String(format: L10n.string("account.extension"), $0) }, isSelected: selection == .device(deviceId: device.id)) {
                            choose(.device(deviceId: device.id))
                        }
                    }
                }

                SettingsGroup(title: L10n.string("numbers.fallback.group.forward"), footer: L10n.string("numbers.fallback.forward.footer")) {
                    HStack(spacing: Theme.Spacing.s) {
                        TextField(L10n.string("numbers.fallback.forward.placeholder"), text: $number)
                            .keyboardType(.phonePad)
                            .textContentType(.telephoneNumber)
                            .focused($numberFocused)
                            .foregroundStyle(Theme.textPrimary)
                            .accessibilityIdentifier("fallback-forward-field")

                        Button(L10n.string("numbers.fallback.forward.use")) {
                            choose(.forward(number: cleanedNumber))
                        }
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Theme.accentText)
                        .disabled(cleanedNumber.count < 3)
                    }
                    .settingsRowChrome()
                }

                SettingsGroup {
                    ChoiceRow(title: L10n.string("numbers.fallback.hangup"), isSelected: selection == .hangup) { choose(.hangup) }
                }
            }
        }
        .onAppear {
            if case let .forward(value)? = selection {
                number = value
            }
        }
    }

    private var isSharedVoicemail: Bool {
        if case let .voicemail(_, ofDevice)? = selection {
            return !ofDevice
        }

        return false
    }

    private func choose(_ value: Fallback?) {
        selection = value
        dismiss()
    }
}
