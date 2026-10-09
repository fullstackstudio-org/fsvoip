// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

// MARK: - Closing the whole settings sheet from deep inside

private struct PbxCloseKey: EnvironmentKey {
    static let defaultValue: (() -> Void)? = nil
}

extension EnvironmentValues {
    /// Closes the settings sheet the "Centrale" screens live in (`nil` = only go back).
    var pbxClose: (() -> Void)? {
        get { self[PbxCloseKey.self] }
        set { self[PbxCloseKey.self] = newValue }
    }
}

// MARK: - Notices in the new style

/// One notice on a raised card (frozen, changed in the meantime, an error).
struct NoticeCard: View {
    let symbol: String
    let tint: Color
    let title: String
    var message: String?

    var body: some View {
        HStack(alignment: .top, spacing: Theme.Spacing.m) {
            Image(systemName: symbol)
                .foregroundStyle(tint)
                .frame(width: 22)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)

                if let message {
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            Spacer(minLength: 0)
        }
        .padding(Theme.Spacing.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.raised, in: Theme.card())
        .accessibilityElement(children: .combine)
        .padding(.bottom, Theme.Spacing.m)
    }
}

/// The section-wide notices (frozen, not current, still being applied) on cards.
struct SectionNoticeCards: View {
    @ObservedObject var model: PbxSectionModel

    var body: some View {
        if model.isReadOnly {
            NoticeCard(symbol: "lock.fill", tint: Theme.textSecondary, title: L10n.string("pbx.readOnly.title"), message: L10n.string("pbx.readOnly.message"))
                .accessibilityIdentifier("pbx-readonly-banner")
        }

        if model.isOutdated {
            NoticeCard(symbol: "wifi.slash", tint: Theme.busy, title: L10n.string("pbx.outdated.title"), message: L10n.string("pbx.outdated.message"))
        }

        if model.hasPendingSync {
            NoticeCard(symbol: "arrow.triangle.2.circlepath", tint: Theme.busy, title: L10n.string("pbx.pending.title"), message: L10n.string("pbx.pending.message"))
                .accessibilityIdentifier("pbx-pending-banner")
        } else if model.syncTimedOut {
            NoticeCard(symbol: "hourglass", tint: Theme.busy, title: L10n.string("pbx.pending.timeout.title"), message: L10n.string("pbx.pending.timeout.message"))
        }

        if let banner = model.banner {
            NoticeCard(symbol: banner.isError ? "exclamationmark.circle.fill" : "info.circle.fill", tint: banner.isError ? Theme.danger : Theme.textSecondary, title: banner.text)
        }

        if let failure = model.loadFailure {
            NoticeCard(symbol: "exclamationmark.circle.fill", tint: Theme.danger, title: failure.message)
        }
    }
}

// MARK: - The frame of one step of the chain

/// What a step form says above its fields after a save that did not go through.
enum ChainEditorMessage: Equatable {
    /// Someone changed the number in the meantime: the newest state is shown, the user's changes are still there.
    case stale
    case failure(PbxFailure)
}

/// A step of the chain in its own sheet: title and close, the notices, the fields (grey and locked when the centrale is
/// frozen), and Annuleren | Opslaan.
struct ChainEditorScaffold<Content: View>: View {
    @ObservedObject var model: PbxSectionModel
    let title: String
    let isDirty: Bool
    var canSave = true
    let message: ChainEditorMessage?
    let onSave: () -> Void
    @ViewBuilder let content: () -> Content

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        SheetShell(
            title: title,
            back: nil,
            onClose: { dismiss() },
            footer: SheetFooter(canSave: isDirty && canSave && !model.isReadOnly, onSave: onSave),
            isSaving: model.isSaving,
            isDirty: isDirty
        ) {
            VStack(alignment: .leading, spacing: 0) {
                if model.isReadOnly {
                    NoticeCard(symbol: "lock.fill", tint: Theme.textSecondary, title: L10n.string("pbx.readOnly.title"), message: L10n.string("pbx.readOnly.message"))
                        .accessibilityIdentifier("pbx-readonly-banner")
                }

                switch message {
                case .stale:
                    NoticeCard(symbol: "arrow.clockwise.circle.fill", tint: Theme.busy, title: L10n.string("numbers.stale.title"), message: L10n.string("numbers.stale.message"))
                        .accessibilityIdentifier("chain-stale")
                case let .failure(failure):
                    NoticeCard(symbol: "exclamationmark.circle.fill", tint: Theme.danger, title: failure.message)
                        .accessibilityIdentifier("chain-error")
                case .none:
                    EmptyView()
                }

                content()
                    .disabled(model.isReadOnly || model.isSaving)
                    .opacity(model.isReadOnly ? 0.5 : 1)
            }
        }
        .toolbar(.hidden, for: .navigationBar)
    }
}

/// A sub page inside a step sheet (a picker): back to the step, no footer.
struct ChainSubPage<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        SheetShell(title: title, back: { dismiss() }, onClose: { dismiss() }, content: content)
            .toolbar(.hidden, for: .navigationBar)
    }
}

/// "Ook voor …": a part of the chain that other numbers use as well (it is not copied: a change counts there too).
struct SharedNote: View {
    let names: [String]

    var body: some View {
        if !names.isEmpty {
            NoticeCard(symbol: "link", tint: Theme.textSecondary, title: String(format: L10n.string("numbers.shared.title"), names.joined(separator: ", ")), message: L10n.string("numbers.shared.message"))
        }
    }
}

// MARK: - Words for fallbacks and targets

enum ChainWords {
    static func soundName(_ id: String?, _ options: ChainOptions) -> String? {
        guard let id else { return nil }

        return options.sounds.first { $0.id == id }?.name ?? L10n.string("numbers.sound.unknown")
    }

    static func deviceName(_ id: String, _ options: ChainOptions) -> String {
        options.devices.first { $0.id == id }?.name ?? L10n.string("numbers.device.unknown")
    }

    /// "Voicemail", "Voicemail van Jan", "Bericht: Welkom", "Doorschakelen naar 06 …", "Verbinding verbreken".
    static func fallback(_ fallback: Fallback, _ options: ChainOptions) -> String {
        switch fallback {
        case let .voicemail(boxId, ofDevice):
            if ofDevice, let boxId {
                return String(format: L10n.string("numbers.fallback.voicemailOf"), deviceName(boxId, options))
            }

            return L10n.string("numbers.fallback.voicemail")
        case let .message(soundId):
            return String(format: L10n.string("numbers.fallback.messageNamed"), soundName(soundId, options) ?? "")
        case let .forward(number):
            return String(format: L10n.string("numbers.fallback.forwardTo"), number)
        case .hangup:
            return L10n.string("numbers.fallback.hangup")
        case let .device(deviceId):
            return deviceName(deviceId, options)
        case .other, .unknown:
            return L10n.string("numbers.portalOnly")
        }
    }

    /// Where a menu key goes.
    static func target(_ target: PbxTarget, _ options: ChainOptions) -> String {
        switch target.type {
        case .device:
            return target.id.map { deviceName($0, options) } ?? L10n.string("numbers.device.unknown")
        case .ringGroup:
            return options.groups.first { $0.id == target.id }?.name ?? L10n.string("pbx.type.ringGroup")
        case .voicemail:
            if let id = target.id, options.devices.contains(where: { $0.id == id }) {
                return String(format: L10n.string("numbers.fallback.voicemailOf"), deviceName(id, options))
            }

            return L10n.string("numbers.fallback.voicemail")
        case .external:
            return String(format: L10n.string("numbers.fallback.forwardTo"), target.number ?? "")
        case .recording:
            return String(format: L10n.string("numbers.fallback.messageNamed"), soundName(target.id, options) ?? "")
        case .hangup:
            return L10n.string("numbers.fallback.hangup")
        case .queue, .ivr, .businessHours, .unknown:
            return L10n.string("numbers.portalOnly")
        }
    }
}

// MARK: - Choosing a sound (stub until the audio picker of Task 10)

/// A row "label ... chosen sound >" that opens the list of sounds of the centrale. TASK 10 SEAM: the audio picker (listen,
/// record, upload) replaces `ChainSoundList`; the binding (`soundId`) and the row stay.
struct ChainSoundRow: View {
    let title: String
    @Binding var soundId: String?
    let options: ChainOptions
    var allowsNone = false

    var body: some View {
        NavigationLink {
            ChainSoundList(title: title, soundId: $soundId, options: options, allowsNone: allowsNone)
        } label: {
            SettingsRow(symbol: "waveform", title: title, value: ChainWords.soundName(soundId, options) ?? L10n.string("numbers.sound.none"))
        }
        .buttonStyle(RowButtonStyle())
        .accessibilityIdentifier("chain-sound-row")
    }
}

/// The sounds of the centrale to choose from. No listening and no recording yet: that is the audio picker of Task 10.
struct ChainSoundList: View {
    let title: String
    @Binding var soundId: String?
    let options: ChainOptions
    var allowsNone = false

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ChainSubPage(title: title) {
            if options.sounds.isEmpty {
                EmptyState(symbol: "waveform", title: L10n.string("numbers.sound.empty.title"), message: L10n.string("numbers.sound.empty.message"))
                    .accessibilityIdentifier("chain-sound-empty")
            } else {
                SettingsGroup(footer: L10n.string("numbers.sound.footer")) {
                    if allowsNone {
                        ChoiceRow(title: L10n.string("numbers.sound.none"), isSelected: soundId == nil) {
                            soundId = nil
                            dismiss()
                        }
                    }

                    ForEach(options.sounds) { sound in
                        ChoiceRow(title: sound.name, isSelected: soundId == sound.id) {
                            soundId = sound.id
                            dismiss()
                        }
                    }
                }
            }
        }
    }
}
