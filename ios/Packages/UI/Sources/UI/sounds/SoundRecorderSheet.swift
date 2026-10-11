// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// "Nieuwe opname": speak, listen back, name it and add it. Opens as a sheet from the picker and from "Geluiden".
struct SoundRecorderSheet: View {
    @ObservedObject var model: SoundsModel
    /// Called with the id of the new sound once it is added.
    let onAdded: (String) -> Void

    @ObservedObject private var recorder: AudioRecorderSession
    @State private var name = ""
    @FocusState private var nameFocused: Bool
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.openURL) private var openURL
    @ScaledMetric(relativeTo: .largeTitle) private var timerSize: CGFloat = 54

    init(model: SoundsModel, onAdded: @escaping (String) -> Void) {
        self.model = model
        self.onAdded = onAdded
        recorder = model.recorder
    }

    private var isBusy: Bool { model.isUploading }

    var body: some View {
        SheetShell(title: L10n.string("sounds.record.title"), onClose: close, isSaving: isBusy, isDirty: recorder.isRecording) {
            VStack(spacing: Theme.Spacing.l) {
                if let banner = model.banner {
                    SoundNotice(banner: banner)
                }

                switch recorder.state {
                case .idle, .requestingPermission:
                    startView
                case .recording:
                    recordingView
                case .recorded:
                    recordedView
                case let .failed(failure):
                    failedView(failure)
                }
            }
            .padding(.top, Theme.Spacing.m)
        }
        .onChange(of: recorder.state) { state in
            if case .recorded = state, name.isEmpty {
                name = SoundsModel.defaultRecordingName()
            }
        }
        .interactiveDismissDisabled(isBusy)
        .onDisappear { discardIfLeft() }
        .accessibilityIdentifier("sound-recorder")
    }

    // MARK: States

    private var startView: some View {
        VStack(spacing: Theme.Spacing.l) {
            Text(L10n.string("sounds.record.hint"))
                .font(.callout)
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)

            recordButton(isRecording: false) {
                Task { await recorder.start() }
            }
            .disabled(recorder.state == .requestingPermission)

            Text(String(format: L10n.string("sounds.record.max"), MediaFormat.clock(AudioRecorderSession.maxDuration)))
                .font(.footnote)
                .foregroundStyle(Theme.textTertiary)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, Theme.Spacing.xl)
    }

    private var recordingView: some View {
        VStack(spacing: Theme.Spacing.l) {
            HStack(spacing: Theme.Spacing.s) {
                Circle()
                    .fill(Theme.danger)
                    .frame(width: 10, height: 10)
                    .opacity(reduceMotion ? 1 : 0.9)
                    .accessibilityHidden(true)

                Text(L10n.string("sounds.record.recording"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.textPrimary)
            }

            Text(MediaFormat.clock(recorder.elapsed))
                .font(.system(size: timerSize, weight: .light, design: .rounded).monospacedDigit())
                .foregroundStyle(Theme.textPrimary)
                .minimumScaleFactor(0.5)
                .accessibilityLabel(String(format: L10n.string("sounds.record.elapsed"), MediaFormat.clock(recorder.elapsed)))
                .accessibilityIdentifier("sound-record-time")

            ProgressView(value: recorder.elapsed, total: AudioRecorderSession.maxDuration)
                .tint(Theme.danger)
                .accessibilityHidden(true)

            recordButton(isRecording: true) {
                recorder.stop()
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, Theme.Spacing.xl)
    }

    private var recordedView: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.l) {
            if let url = recorder.fileURL {
                SettingsGroup(title: L10n.string("sounds.record.listen")) {
                    VStack(alignment: .leading, spacing: 0) {
                        HStack {
                            Text(L10n.string("sounds.record.preview"))
                                .font(.body)
                                .foregroundStyle(Theme.textPrimary)
                            Spacer()
                            SoundDurationChip(seconds: recorder.elapsed)
                        }
                        .padding(Theme.Spacing.l)

                        SoundInlinePlayer(player: model.player, id: SoundsModel.localPreviewId) { model.playLocal(url) }
                    }
                }
            }

            SettingsGroup(title: L10n.string("sounds.record.name")) {
                TextField(L10n.string("sounds.record.name.placeholder"), text: $name)
                    .focused($nameFocused)
                    .textInputAutocapitalization(.sentences)
                    .submitLabel(.done)
                    .padding(Theme.Spacing.l)
                    .accessibilityIdentifier("sound-record-name")
            }

            if let upload = model.upload {
                SoundUploadBar(upload: upload) { model.cancelUpload() }
            }

            Button(L10n.string("sounds.record.save")) { save() }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(isBusy || SoundsModel.cleanName(name).isEmpty)
                .accessibilityIdentifier("sound-record-save")

            Button(L10n.string("sounds.record.again")) {
                model.stopPlaying()
                recorder.discard()
                name = ""
            }
            .buttonStyle(SecondaryButtonStyle())
            .disabled(isBusy)
            .accessibilityIdentifier("sound-record-again")
        }
    }

    private func failedView(_ failure: RecorderFailure) -> some View {
        VStack(spacing: Theme.Spacing.l) {
            NoticeCard(symbol: "mic.slash.fill", tint: Theme.danger, title: failure.message)

            if failure == .permissionDenied {
                Button(L10n.string("sounds.record.openSettings")) {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
                .buttonStyle(SecondaryButtonStyle())
                .accessibilityIdentifier("sound-record-settings")
            }

            if failure != .callInProgress, failure != .permissionDenied {
                Button(L10n.string("sounds.record.tryAgain")) {
                    Task { await recorder.start() }
                }
                .buttonStyle(PrimaryButtonStyle())
            }
        }
        .padding(.top, Theme.Spacing.l)
    }

    private func recordButton(isRecording: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .strokeBorder(Theme.textTertiary, lineWidth: 3)
                    .frame(width: 88, height: 88)

                if isRecording {
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(Theme.danger)
                        .frame(width: 32, height: 32)
                } else {
                    Circle()
                        .fill(Theme.danger)
                        .frame(width: 66, height: 66)
                }
            }
            .frame(minWidth: 96, minHeight: 96)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L10n.string(isRecording ? "sounds.record.stop" : "sounds.record.start"))
        .accessibilityIdentifier(isRecording ? "sound-record-stop" : "sound-record-start")
    }

    // MARK: Actions

    private func save() {
        nameFocused = false

        Task {
            if let id = await model.addRecording(name: name) {
                onAdded(id)
                dismiss()
            }
        }
    }

    private func close() {
        guard !isBusy else { return }

        discardIfLeft()
        dismiss()
    }

    /// Leaving the sheet throws away what was not added (the temporary file goes with it).
    private func discardIfLeft() {
        model.stopPlaying()
        recorder.cancel()
    }
}
