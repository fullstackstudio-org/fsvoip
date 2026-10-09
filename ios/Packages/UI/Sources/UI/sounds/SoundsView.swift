// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// "Geluiden" (Beheer, admins): every sound of the centrale with listening, renaming, deleting, a new recording and adding a file.
/// The content of the page; the sheet around it (title, back, lock) is made by the settings sheet.
struct SoundsView: View {
    @ObservedObject var model: SoundsModel

    @State private var openId: String?
    @State private var isRecording = false
    @State private var isImporting = false
    @State private var renaming: Sound?
    @State private var newName = ""
    @State private var deleting: Sound?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let banner = model.banner {
                SoundNotice(banner: banner)
            }

            if let upload = model.upload {
                SoundUploadBar(upload: upload) { model.cancelUpload() }
            }

            content

            VStack(spacing: Theme.Spacing.s) {
                Button {
                    model.stopPlaying()
                    isRecording = true
                } label: {
                    Label(L10n.string("sounds.newRecording"), systemImage: "mic")
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(model.isUploading)
                .accessibilityIdentifier("sounds-new-recording")

                Button {
                    isImporting = true
                } label: {
                    Label(L10n.string("sounds.addFile"), systemImage: "doc.badge.plus")
                }
                .buttonStyle(SecondaryButtonStyle())
                .disabled(model.isUploading)
                .accessibilityIdentifier("sounds-add-file")
            }
            .padding(.top, Theme.Spacing.s)
        }
        .task { await model.load() }
        .refreshable { await model.load() }
        .onDisappear { model.stopPlaying() }
        .sheet(isPresented: $isRecording) {
            SoundRecorderSheet(model: model) { _ in }
                .presentationDetents([.large])
        }
        .fileImporter(isPresented: $isImporting, allowedContentTypes: SoundFileTypes.accepted) { result in
            guard case let .success(url) = result else { return }

            Task { _ = await model.addFile(url: url) }
        }
        .alert(L10n.string("sounds.rename.title"), isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField(L10n.string("sounds.record.name.placeholder"), text: $newName)
            Button(L10n.string("action.save")) {
                if let sound = renaming {
                    Task { _ = await model.rename(sound, to: newName) }
                }

                renaming = nil
            }
            Button(L10n.string("action.cancel"), role: .cancel) { renaming = nil }
        }
        .confirmationDialog(
            String(format: L10n.string("sounds.delete.title"), deleting?.name ?? ""),
            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
            titleVisibility: .visible
        ) {
            Button(L10n.string("sounds.delete.confirm"), role: .destructive) {
                if let sound = deleting { Task { await model.delete(sound) } }

                deleting = nil
            }
            Button(L10n.string("action.cancel"), role: .cancel) { deleting = nil }
        } message: {
            Text(L10n.string("sounds.delete.message"))
        }
        .alert(item: $model.deleteBlock) { block in
            Alert(
                title: Text(String(format: L10n.string("sounds.inUse.title"), block.soundName)),
                message: Text(SoundPlaces.inUseMessage(block.places)),
                dismissButton: .default(Text(L10n.string("action.done")))
            )
        }
    }

    @ViewBuilder
    private var content: some View {
        if !model.hasLoaded {
            if model.isLoading {
                ProgressView().frame(maxWidth: .infinity).padding(.vertical, Theme.Spacing.xl)
            } else if let failure = model.failure {
                NoticeCard(symbol: "exclamationmark.circle.fill", tint: Theme.danger, title: failure.message)

                if failure.isRetryable {
                    Button(L10n.string("action.retry")) { Task { await model.load() } }
                        .buttonStyle(SecondaryButtonStyle())
                        .padding(.bottom, Theme.Spacing.l)
                }
            }
        } else if model.sounds.isEmpty {
            EmptyState(symbol: "waveform", title: L10n.string("numbers.sound.empty.title"), message: L10n.string("sounds.empty.message"))
                .frame(minHeight: 220)
                .accessibilityIdentifier("sounds-empty")
        } else {
            SettingsGroup(footer: L10n.string("sounds.footer")) {
                ForEach(model.sounds) { sound in
                    row(sound)
                }
            }
        }
    }

    private func row(_ sound: Sound) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: Theme.Spacing.s) {
                Image(systemName: "waveform")
                    .foregroundStyle(Theme.textSecondary)
                    .frame(width: 26)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: Theme.Spacing.s) {
                        Text(sound.name)
                            .font(.body)
                            .foregroundStyle(Theme.textPrimary)
                            .lineLimit(2)
                        SoundDurationChip(seconds: model.duration(of: sound))
                    }

                    Text(usage(sound))
                        .font(.footnote)
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)

                Spacer(minLength: 0)

                if sound.playable {
                    Button {
                        withAnimation(.easeOut(duration: 0.15)) { toggleOpen(sound) }
                    } label: {
                        HStack(spacing: 4) {
                            Text(L10n.string("sounds.listen"))
                            Image(systemName: "chevron.down")
                                .font(.caption.weight(.semibold))
                                .rotationEffect(.degrees(openId == sound.id ? 180 : 0))
                        }
                        .font(.subheadline)
                        .foregroundStyle(Theme.textSecondary)
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(String(format: L10n.string("sounds.listen.named"), sound.name))
                    .accessibilityValue(L10n.string(openId == sound.id ? "sounds.listen.open" : "sounds.listen.closed"))
                    .accessibilityIdentifier("sound-listen")
                }

                Menu {
                    Button {
                        newName = sound.name
                        renaming = sound
                    } label: {
                        Label(L10n.string("sounds.rename"), systemImage: "pencil")
                    }

                    Button(role: .destructive) {
                        deleting = sound
                    } label: {
                        Label(L10n.string("sounds.delete"), systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Theme.textSecondary)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel(String(format: L10n.string("sounds.actions.named"), sound.name))
                .accessibilityIdentifier("sound-menu")
            }
            .padding(.horizontal, Theme.Spacing.l)
            .frame(minHeight: 56)

            if openId == sound.id, sound.playable {
                SoundInlinePlayer(player: model.player, id: sound.id) { model.togglePlay(sound) }
            }
        }
    }

    private func usage(_ sound: Sound) -> String {
        if !sound.playable { return L10n.string("sounds.notPlayable") }

        guard !sound.uses.isEmpty else { return L10n.string("sounds.unused") }

        return String(format: L10n.string("sounds.usedIn"), sound.uses.map(SoundPlaces.label).joined(separator: ", "))
    }

    private func toggleOpen(_ sound: Sound) {
        if openId == sound.id {
            openId = nil
            model.stopPlaying()
        } else {
            openId = sound.id
            model.play(sound)
        }
    }
}
