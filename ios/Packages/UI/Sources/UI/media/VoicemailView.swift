// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// "Voicemail": the messages, newest first; tap one to listen. Everyone sees the own box; an admin can switch to every box
/// (after Face ID / the passcode).
struct VoicemailView: View {
    @ObservedObject var hub: MediaHub
    @StateObject private var model: VoicemailModel
    @State private var pendingDelete: VoicemailMessage?

    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    init(hub: MediaHub, account: StoredAccount) {
        self.hub = hub
        _model = StateObject(wrappedValue: hub.makeVoicemailModel(account: account))
    }

    var body: some View {
        List {
            noticesSection

            if model.canChooseBox {
                scopeSection
            }

            if model.needsUnlock {
                Section {
                    MediaLockCard(
                        symbol: model.noPasscode ? "lock.slash.fill" : "lock.fill",
                        title: model.noPasscode ? L10n.string("pbx.lock.noPasscode.title") : L10n.string("media.lock.title"),
                        message: model.noPasscode ? L10n.string("media.lock.noPasscode") : L10n.string("media.lock.message.voicemail"),
                        buttonTitle: model.noPasscode ? nil : L10n.string("pbx.lock.unlock"),
                        prominent: false
                    ) {
                        Task { await model.unlock() }
                    }
                    .listRowBackground(Color.clear)
                }
            } else {
                content
            }
        }
        .navigationTitle(L10n.string("media.voicemail.title"))
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if let nowPlaying = model.nowPlaying {
                AudioPlayerBar(player: hub.player, nowPlaying: nowPlaying, kind: .voicemail, onClose: model.closePlayer, onRetry: model.retryPlaying)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.22), value: model.nowPlaying)
        .refreshable { await model.reload() }
        .task {
            await model.load()
            startDemo()
        }
        .task {
            // The five minutes of the local check run out while the screen stays open.
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 10_000_000_000)
                model.refreshLock()
            }
        }
        .onDisappear { hub.player.stop() }
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.refreshLock() }
        }
        .onChange(of: hub.hasVoicemail(model.account.id)) { available in
            if !available { dismiss() }
        }
        .confirmationDialog(L10n.string("media.delete.title"), isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }), titleVisibility: .visible, presenting: pendingDelete) { message in
            Button(L10n.string("media.delete.confirm"), role: .destructive) {
                Task { await model.delete(message) }
            }
        } message: { _ in
            Text(L10n.string("media.delete.message"))
        }
        .accessibilityIdentifier("voicemail-list")
    }

    // MARK: Parts

    @ViewBuilder
    private var noticesSection: some View {
        if model.isReadOnly || model.banner != nil {
            Section {
                if model.isReadOnly {
                    MediaNotice(symbol: "lock.fill", tint: .secondary, text: L10n.string("media.readOnly.notice"))
                }

                if let banner = model.banner {
                    MediaNotice(symbol: banner.isError ? "exclamationmark.circle.fill" : "info.circle.fill", tint: banner.isError ? Brand.hangUp : .secondary, text: banner.text)
                        .accessibilityIdentifier("media-banner")
                }
            }
        }
    }

    private var scopeSection: some View {
        Section {
            Menu {
                Button {
                    Task { await model.select(.own) }
                } label: {
                    if model.scope == .own { Label(L10n.string("media.voicemail.scope.own"), systemImage: "checkmark") } else { Text(L10n.string("media.voicemail.scope.own")) }
                }
                .disabled(model.ownBoxId == nil)

                Button {
                    Task { await model.select(.all) }
                } label: {
                    if model.scope == .all { Label(L10n.string("media.voicemail.scope.all"), systemImage: "checkmark") } else { Text(L10n.string("media.voicemail.scope.all")) }
                }

                if !model.boxes.isEmpty {
                    Divider()

                    ForEach(model.boxes) { box in
                        Button {
                            Task { await model.select(.box(box.id)) }
                        } label: {
                            let title = model.boxTitle(box) + (box.shared ? " (\(L10n.string("media.voicemail.shared")))" : "")

                            if model.scope == .box(box.id) { Label(title, systemImage: "checkmark") } else { Text(title) }
                        }
                    }
                }
            } label: {
                HStack {
                    Label(model.scopeTitle, systemImage: model.scope == .own ? "person.fill" : "person.2.fill")
                        .foregroundStyle(.primary)
                    Spacer()
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                }
            }
            .accessibilityLabel(L10n.string("media.voicemail.scope.header"))
            .accessibilityValue(model.scopeTitle)
            .accessibilityIdentifier("voicemail-scope")
        } header: {
            Text(L10n.string("media.voicemail.scope.header"))
        } footer: {
            if model.scope != .own {
                Text(L10n.string("media.voicemail.scope.footer"))
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        if model.page == nil {
            if model.isLoading {
                Section {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                }
            } else if let failure = model.failure {
                Section {
                    MediaNotice(symbol: "exclamationmark.circle.fill", tint: Brand.hangUp, text: failure.message(for: .voicemail))

                    if failure.isRetryable {
                        Button(L10n.string("action.retry")) { Task { await model.load() } }
                    }
                }
            }
        } else if model.isUnavailable {
            Section {
                MediaEmpty(symbol: "icloud.slash", title: L10n.string("media.voicemail.unavailable.title"), message: L10n.string("media.voicemail.unavailable.message"))
                    .listRowBackground(Color.clear)
            }
        } else if model.messages.isEmpty {
            Section {
                MediaEmpty(symbol: "voicemail", title: L10n.string("media.voicemail.empty.title"), message: L10n.string(model.scope == .own ? "media.voicemail.empty.own" : "media.voicemail.empty.other"))
                    .listRowBackground(Color.clear)
            }
        } else {
            Section {
                ForEach(model.messages) { message in
                    MessageRow(
                        title: model.title(for: message),
                        subtitle: model.subtitle(for: message),
                        transcription: message.transcription,
                        daysLeft: MediaFormat.daysLeft(message.daysLeft),
                        isGone: model.isGone(message),
                        isPlaying: model.nowPlayingId == message.id,
                        isNew: message.isNew
                    ) {
                        model.play(message)
                    }
                    .listRowBackground(model.nowPlayingId == message.id ? Color(.secondarySystemFill) : nil)
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) {
                            pendingDelete = message
                        } label: {
                            Label(L10n.string("media.delete.action"), systemImage: "trash")
                        }
                        .disabled(model.isReadOnly)
                    }
                    .contextMenu {
                        Button(role: .destructive) {
                            pendingDelete = message
                        } label: {
                            Label(L10n.string("media.delete.action"), systemImage: "trash")
                        }
                        .disabled(model.isReadOnly)
                    }
                    .accessibilityAction(named: L10n.string("media.delete.action")) {
                        if !model.isReadOnly { pendingDelete = message }
                    }
                }
            } footer: {
                Text(L10n.string("media.voicemail.footer"))
            }
        }
    }

    private func startDemo() {
        #if DEBUG
        if MediaDemo.opensVoicemail, let first = model.messages.first {
            model.play(first)
        }
        #endif
    }
}

// MARK: - Pieces shared with the recordings

private struct MessageRow: View {
    let title: String
    let subtitle: String
    let transcription: String?
    let daysLeft: String?
    let isGone: Bool
    let isPlaying: Bool
    let isNew: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 14) {
                Image(systemName: isPlaying ? "waveform" : "play.circle")
                    .font(.title3)
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(isGone ? Color.secondary.opacity(0.5) : Color.primary)
                    .frame(width: 28, height: 28)
                    .padding(.top, 1)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(title)
                            .font(.body.weight(isNew ? .semibold : .medium))
                            .foregroundStyle(isGone ? Color.secondary : Color.primary)
                            .lineLimit(1)

                    }

                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)

                    if let transcription, !transcription.isEmpty {
                        Text(transcription)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                            .padding(.top, 1)
                    }

                    if isGone {
                        Text(L10n.string("media.unavailable.row"))
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                    } else if let daysLeft {
                        Text(daysLeft)
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                }

                Spacer(minLength: 0)
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isGone)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue([subtitle, transcription, isGone ? L10n.string("media.unavailable.row") : daysLeft].compactMap { $0 }.joined(separator: ", "))
        .accessibilityHint(isGone ? "" : L10n.string("media.row.hint"))
        .accessibilityAddTraits(isPlaying ? [.isSelected] : [])
        .accessibilityIdentifier("media-row")
    }
}

struct MediaNotice: View {
    let symbol: String
    let tint: Color
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol)
                .foregroundStyle(tint)
                .frame(width: 22)
                .accessibilityHidden(true)
            Text(text)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

struct MediaEmpty: View {
    let symbol: String
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: symbol)
                .font(.system(size: 34, weight: .regular))
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text(title)
                .font(.headline)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
        .accessibilityElement(children: .combine)
    }
}
