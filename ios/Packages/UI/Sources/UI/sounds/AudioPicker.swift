// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI
import UniformTypeIdentifiers

/// "Audiobestanden": choose the sound for a message, listen to each one, add a new one with the microphone or from Bestanden.
/// `Toepassen` sets the choice; `Annuleren` leaves it as it was. A sound that was just added is selected.
struct AudioPicker: View {
    let title: String
    @Binding var soundId: String?
    let options: ChainOptions
    var allowsNone = false

    @Environment(\.soundsModel) private var environmentModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        if let model = environmentModel {
            AudioPickerContent(title: title, soundId: $soundId, options: options, allowsNone: allowsNone, model: model)
        } else {
            // No right to manage sounds (or no model): a plain choice from what the chain knows, without listening or adding.
            PlainSoundChoice(title: title, soundId: $soundId, options: options, allowsNone: allowsNone)
        }
    }
}

private struct AudioPickerContent: View {
    let title: String
    @Binding var soundId: String?
    let options: ChainOptions
    let allowsNone: Bool
    @ObservedObject var model: SoundsModel

    @State private var selection: String?
    @State private var openId: String?
    @State private var isRecording = false
    @State private var isImporting = false
    @Environment(\.dismiss) private var dismiss

    init(title: String, soundId: Binding<String?>, options: ChainOptions, allowsNone: Bool, model: SoundsModel) {
        self.title = title
        _soundId = soundId
        self.options = options
        self.allowsNone = allowsNone
        self.model = model
        _selection = State(initialValue: soundId.wrappedValue)
    }

    private var rows: [PickerSound] {
        if model.hasLoaded {
            return model.sounds.map { PickerSound(id: $0.id, name: $0.name, duration: model.duration(of: $0), playable: $0.playable) }
        }

        return options.sounds.map { PickerSound(id: $0.id, name: $0.name, duration: nil, playable: false) }
    }

    private var canApply: Bool {
        SoundSelection.canApply(selection: selection, current: soundId, allowsNone: allowsNone, isUploading: model.isUploading)
    }

    var body: some View {
        ChainSubPage(title: L10n.string("sounds.picker.title")) {
            VStack(alignment: .leading, spacing: 0) {
                if let banner = model.banner {
                    SoundNotice(banner: banner)
                }

                if let upload = model.upload {
                    SoundUploadBar(upload: upload) { model.cancelUpload() }
                }

                if model.isLoading, !model.hasLoaded, rows.isEmpty {
                    ProgressView().frame(maxWidth: .infinity).padding(.vertical, Theme.Spacing.xl)
                } else if let failure = model.failure, !model.hasLoaded {
                    NoticeCard(symbol: "exclamationmark.circle.fill", tint: Theme.danger, title: failure.message)

                    if failure.isRetryable {
                        Button(L10n.string("action.retry")) { Task { await model.load() } }
                            .buttonStyle(SecondaryButtonStyle())
                    }
                } else if rows.isEmpty, !allowsNone {
                    EmptyState(symbol: "waveform", title: L10n.string("numbers.sound.empty.title"), message: L10n.string("sounds.picker.empty"))
                        .frame(minHeight: 200)
                        .accessibilityIdentifier("chain-sound-empty")
                } else {
                    SettingsGroup(title: title, footer: L10n.string("sounds.picker.footer")) {
                        if allowsNone {
                            ChoiceRow(title: L10n.string("numbers.sound.none"), isSelected: selection == nil) {
                                selection = nil
                                model.stopPlaying()
                            }
                        }

                        ForEach(rows) { row in
                            pickerRow(row)
                        }

                        Button { isImporting = true } label: {
                            SettingsRow(symbol: "doc.badge.plus", title: L10n.string("sounds.addFile"), showsChevron: false)
                        }
                        .buttonStyle(RowButtonStyle())
                        .disabled(model.isUploading)
                        .accessibilityIdentifier("sound-add-file")
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) { footer }
        .task { await model.load() }
        .onDisappear { model.stopPlaying() }
        .sheet(isPresented: $isRecording) {
            SoundRecorderSheet(model: model) { id in selection = id }
                .presentationDetents([.large])
        }
        .fileImporter(isPresented: $isImporting, allowedContentTypes: SoundFileTypes.accepted) { result in
            guard case let .success(url) = result else { return }

            Task {
                if let id = await model.addFile(url: url) { selection = id }
            }
        }
        .onChange(of: model.lastAddedId) { id in
            if let id { selection = id }
        }
    }

    private func pickerRow(_ row: PickerSound) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: Theme.Spacing.m) {
                Button {
                    selection = row.id
                } label: {
                    HStack(spacing: Theme.Spacing.m) {
                        Image(systemName: selection == row.id ? "checkmark.circle.fill" : "circle")
                            .font(.title3)
                            .foregroundStyle(selection == row.id ? Theme.accentText : Theme.textTertiary)
                            .accessibilityHidden(true)

                        Text(row.name)
                            .font(.body)
                            .foregroundStyle(Theme.textPrimary)
                            .adaptiveLineLimit(2)
                            .multilineTextAlignment(.leading)

                        SoundDurationChip(seconds: row.duration)

                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(selection == row.id ? .isSelected : [])
                .accessibilityIdentifier("sound-choice")

                if row.playable {
                    Button {
                        Motion.run(.easeOut(duration: 0.15)) { toggleOpen(row) }
                    } label: {
                        HStack(spacing: 4) {
                            Text(L10n.string("sounds.listen"))
                            Image(systemName: "chevron.down")
                                .font(.caption.weight(.semibold))
                                .rotationEffect(.degrees(openId == row.id ? 180 : 0))
                        }
                        .font(.subheadline)
                        .foregroundStyle(Theme.textSecondary)
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(String(format: L10n.string("sounds.listen.named"), row.name))
                    .accessibilityValue(L10n.string(openId == row.id ? "sounds.listen.open" : "sounds.listen.closed"))
                    .accessibilityIdentifier("sound-listen")
                }
            }
            .padding(.horizontal, Theme.Spacing.l)
            .frame(minHeight: 52)

            if openId == row.id, row.playable {
                SoundInlinePlayer(player: model.player, id: row.id) {
                    if let sound = model.sound(row.id) { model.togglePlay(sound) }
                }
                .transition(.opacity)
            }
        }
    }

    private func toggleOpen(_ row: PickerSound) {
        if openId == row.id {
            openId = nil
            model.stopPlaying()
        } else {
            openId = row.id

            if let sound = model.sound(row.id) { model.play(sound) }
        }
    }

    private var footer: some View {
        VStack(spacing: Theme.Spacing.s) {
            Button(L10n.string("sounds.apply")) {
                soundId = selection
                dismiss()
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(!canApply)
            .accessibilityIdentifier("sound-apply")

            Button {
                model.stopPlaying()
                isRecording = true
            } label: {
                Label(L10n.string("sounds.newRecording"), systemImage: "mic")
            }
            .buttonStyle(SecondaryButtonStyle())
            .disabled(model.isUploading)
            .accessibilityIdentifier("sound-new-recording")

            Button(L10n.string("action.cancel")) { dismiss() }
                .buttonStyle(SecondaryButtonStyle())
                .accessibilityIdentifier("sound-cancel")
        }
        .padding(.horizontal, Theme.Spacing.l)
        .padding(.top, Theme.Spacing.m)
        .padding(.bottom, Theme.Spacing.s)
        .background(Theme.sheet.ignoresSafeArea(edges: .bottom))
    }
}

private struct PickerSound: Identifiable {
    let id: String
    let name: String
    let duration: TimeInterval?
    let playable: Bool
}

/// The choice without sounds management: the names the chain knows, no listening and no adding.
private struct PlainSoundChoice: View {
    let title: String
    @Binding var soundId: String?
    let options: ChainOptions
    let allowsNone: Bool

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

enum SoundFileTypes {
    /// WAV, MP3 and M4A (the server reads the real type from the first bytes).
    static var accepted: [UTType] {
        [.wav, .mp3, .mpeg4Audio, .audio]
    }
}
