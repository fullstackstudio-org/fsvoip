// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI
import UIKit

/// The "Voicemail" tab: the messages of the own box, Alles | Nieuw; an admin can switch to other boxes (after Face ID / the passcode).
/// Tapping a message opens its sheet: listen, call back, delete.
struct VoicemailTabView: View {
    @ObservedObject var model: FSVoipAppModel
    @State private var chosenAccountId: String?

    /// The accounts with a voicemail box.
    private var accounts: [StoredAccount] {
        guard let hub = model.media else { return [] }

        return model.accounts.filter { hub.hasVoicemail($0.id) }
    }

    private var account: StoredAccount? {
        if let chosenAccountId, let chosen = accounts.first(where: { $0.id == chosenAccountId }) {
            return chosen
        }

        let preferred = model.defaultOutgoingAccountId.flatMap { model.account(id: $0) }

        if let preferred, accounts.contains(where: { $0.id == preferred.id }) { return preferred }

        return accounts.first
    }

    var body: some View {
        Group {
            if let hub = model.media, let account {
                VoicemailContent(model: model, hub: hub, account: account, accounts: accounts) { chosenAccountId = $0 }
                    // Another account is another box: start over with its own model.
                    .id(account.id)
            } else {
                EmptyState(
                    symbol: "recordingtape",
                    title: L10n.string("voicemail.empty.title"),
                    message: L10n.string("voicemail.empty.message")
                )
            }
        }
        .background(Theme.background)
        .navigationTitle(L10n.string("media.voicemail.title"))
    }
}

private enum VoicemailFilter: Hashable {
    case all
    case new
}

private struct VoicemailContent: View {
    @ObservedObject var app: FSVoipAppModel
    @ObservedObject var hub: MediaHub
    @StateObject private var model: VoicemailModel
    let accounts: [StoredAccount]
    let selectAccount: (String) -> Void

    @State private var filter: VoicemailFilter = .all
    @State private var selected: VoicemailMessage?
    @State private var showsBoxes = false

    @Environment(\.scenePhase) private var scenePhase

    init(model app: FSVoipAppModel, hub: MediaHub, account: StoredAccount, accounts: [StoredAccount], selectAccount: @escaping (String) -> Void) {
        self.app = app
        self.hub = hub
        self.accounts = accounts
        self.selectAccount = selectAccount
        _model = StateObject(wrappedValue: hub.makeVoicemailModel(account: account))
    }

    private var rows: [VoicemailMessage] {
        filter == .new ? model.messages.filter(\.isNew) : model.messages
    }

    var body: some View {
        VStack(spacing: 0) {
            controls

            if model.needsUnlock {
                ScrollView {
                    MediaLockCard(
                        symbol: model.noPasscode ? "lock.slash.fill" : "lock.fill",
                        title: model.noPasscode ? L10n.string("pbx.lock.noPasscode.title") : L10n.string("media.lock.title"),
                        message: model.noPasscode ? L10n.string("media.lock.noPasscode") : L10n.string("media.lock.message.voicemail"),
                        buttonTitle: model.noPasscode ? nil : L10n.string("pbx.lock.unlock"),
                        prominent: false
                    ) {
                        Task { await model.unlock() }
                    }
                    .padding(Theme.Spacing.l)
                }
            } else {
                content
            }
        }
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
        .sheet(item: $selected) { message in
            VoicemailDetailSheet(app: app, hub: hub, model: model, message: message)
        }
        .sheet(isPresented: $showsBoxes) {
            VoicemailBoxSheet(app: app, model: model, accounts: accounts, current: model.account.id, selectAccount: selectAccount)
        }
        .accessibilityIdentifier("voicemail-list")
    }

    // MARK: Parts

    private var controls: some View {
        VStack(spacing: Theme.Spacing.s) {
            HStack(spacing: Theme.Spacing.m) {
                SegmentedBar(
                    options: [
                        .init(value: VoicemailFilter.all, title: L10n.string("voicemail.filter.all")),
                        .init(value: VoicemailFilter.new, title: L10n.string("voicemail.filter.new")),
                    ],
                    selection: $filter
                )
                .accessibilityIdentifier("voicemail-segments")

                if model.canChooseBox || accounts.count > 1 {
                    FilterButton(title: model.canChooseBox ? model.scopeTitle : L10n.string("voicemail.account"), isActive: model.canChooseBox && model.scope != .own) {
                        showsBoxes = true
                    }
                    .accessibilityIdentifier("voicemail-scope")
                }
            }

            if model.isReadOnly {
                MediaNotice(symbol: "lock.fill", tint: Theme.textSecondary, text: L10n.string("media.readOnly.notice"))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            if let banner = model.banner {
                MediaNotice(symbol: banner.isError ? "exclamationmark.circle.fill" : "info.circle.fill", tint: banner.isError ? Theme.danger : Theme.textSecondary, text: banner.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("media-banner")
            }
        }
        .padding(.horizontal, Theme.Spacing.l)
        .padding(.vertical, Theme.Spacing.s)
    }

    @ViewBuilder
    private var content: some View {
        if model.page == nil {
            if model.isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let failure = model.failure {
                EmptyState(
                    symbol: "exclamationmark.circle",
                    title: L10n.string("voicemail.failure.title"),
                    message: failure.message(for: .voicemail),
                    actionTitle: failure.isRetryable ? L10n.string("action.retry") : nil,
                    action: { Task { await model.load() } }
                )
            } else {
                Spacer()
            }
        } else if model.isUnavailable {
            EmptyState(
                symbol: "icloud.slash",
                title: L10n.string("media.voicemail.unavailable.title"),
                message: L10n.string("media.voicemail.unavailable.message")
            )
        } else if rows.isEmpty {
            EmptyState(
                symbol: "recordingtape",
                title: L10n.string(filter == .new && !model.messages.isEmpty ? "voicemail.empty.new.title" : "media.voicemail.empty.title"),
                message: L10n.string(filter == .new && !model.messages.isEmpty ? "voicemail.empty.new.message" : (model.scope == .own ? "media.voicemail.empty.own" : "media.voicemail.empty.other"))
            )
        } else {
            List {
                ForEach(rows) { message in
                    Button {
                        selected = message
                    } label: {
                        VoicemailRow(
                            title: model.title(for: message),
                            subtitle: model.subtitle(for: message),
                            transcription: message.transcription,
                            isNew: message.isNew,
                            isGone: model.isGone(message)
                        )
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(Theme.background)
                    .listRowSeparatorTint(Theme.separator)
                    .accessibilityIdentifier("media-row")
                }

                Text(L10n.string("media.voicemail.footerShort"))
                    .font(.footnote)
                    .foregroundStyle(Theme.textTertiary)
                    .listRowBackground(Theme.background)
                    .listRowSeparator(.hidden)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }

    private func startDemo() {
        #if DEBUG
        if MediaDemo.opensVoicemail, let first = model.messages.first {
            selected = first
            model.play(first)
        }
        #endif
    }
}

// MARK: - Row

private struct VoicemailRow: View {
    let title: String
    let subtitle: String
    let transcription: String?
    let isNew: Bool
    let isGone: Bool

    var body: some View {
        HStack(alignment: .top, spacing: Theme.Spacing.m) {
            // New messages: a lime dot in front, the title heavier.
            Circle()
                .fill(isNew ? Theme.accent : Color.clear)
                .frame(width: 8, height: 8)
                .padding(.top, 8)
                .accessibilityHidden(true)

            InitialsAvatar(name: title.first?.isNumber == true ? nil : title, size: 40)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body.weight(isNew ? .semibold : .regular))
                    .foregroundStyle(isGone ? Theme.textTertiary : Theme.textPrimary)
                    .adaptiveLineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)

                Text(subtitle)
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                    .adaptiveLineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)

                if let transcription, !transcription.isEmpty {
                    Text(transcription)
                        .font(.footnote)
                        .foregroundStyle(Theme.textTertiary)
                        .adaptiveLineLimit(2)
                        .padding(.top, 1)
                }

                if isGone {
                    Text(L10n.string("media.unavailable.row"))
                        .font(.caption.weight(.medium))
                        .foregroundStyle(Theme.textTertiary)
                }
            }

            Spacer(minLength: 0)
        }
        .padding(.vertical, Theme.Spacing.xs)
        .frame(minHeight: Theme.minimumTarget)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel((isNew ? L10n.string("voicemail.new") + ", " : "") + title)
        .accessibilityValue([subtitle, transcription, isGone ? L10n.string("media.unavailable.row") : nil].compactMap { $0 }.joined(separator: ", "))
        .accessibilityHint(L10n.string("voicemail.row.hint"))
    }
}

// MARK: - Detail

private struct VoicemailDetailSheet: View {
    @ObservedObject var app: FSVoipAppModel
    @ObservedObject var hub: MediaHub
    @ObservedObject var model: VoicemailModel
    let message: VoicemailMessage

    @Environment(\.dismiss) private var dismiss
    @State private var confirmsDelete = false

    private var title: String {
        model.title(for: message)
    }

    private var isPlaying: Bool {
        model.nowPlayingId == message.id
    }

    var body: some View {
        SheetShell(title: title, onClose: { dismiss() }) {
            VStack(alignment: .leading, spacing: Theme.Spacing.l) {
                header

                actions

                details

                if let transcription = message.transcription, !transcription.isEmpty {
                    SettingsGroup(title: L10n.string("voicemail.detail.transcription")) {
                        Text(transcription)
                            .font(.body)
                            .foregroundStyle(Theme.textPrimary)
                            .fixedSize(horizontal: false, vertical: true)
                            .settingsRowChrome()
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if let nowPlaying = model.nowPlaying {
                AudioPlayerBar(player: hub.player, nowPlaying: nowPlaying, kind: .voicemail, onClose: model.closePlayer, onRetry: model.retryPlaying)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .motionAnimation(.easeOut(duration: 0.22), value: model.nowPlaying)
        .onDisappear { hub.player.stop() }
        .presentationDetents([.large])
        .confirmationDialog(L10n.string("media.delete.title"), isPresented: $confirmsDelete, titleVisibility: .visible) {
            Button(L10n.string("media.delete.confirm"), role: .destructive) {
                Task {
                    await model.delete(message)
                    dismiss()
                }
            }
        } message: {
            Text(L10n.string("media.delete.message"))
        }
    }

    private var header: some View {
        VStack(spacing: Theme.Spacing.s) {
            InitialsAvatar(name: title.first?.isNumber == true ? nil : title, size: 64)

            if let caller = message.caller, !caller.isEmpty, caller != title {
                Text(caller)
                    .font(.body.monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }

    private var actions: some View {
        VStack(spacing: Theme.Spacing.m) {
            HStack(spacing: Theme.Spacing.l) {
                if let caller = message.caller, !caller.isEmpty {
                    RoundActionButton(symbol: "phone.fill", label: L10n.string("voicemail.action.callBack"), isPrimary: true) {
                        dismiss()
                        app.call(caller, from: model.account.id)
                    }
                    .accessibilityIdentifier("voicemail-callback")

                    RoundActionButton(symbol: "doc.on.doc", label: L10n.string("history.action.copy"), isPrimary: false) {
                        UIPasteboard.general.string = caller
                        Haptics.success()
                    }
                    .accessibilityIdentifier("voicemail-copy")
                }
            }

            Button {
                model.play(message)
            } label: {
                Label(L10n.string(isPlaying ? "voicemail.action.playing" : "voicemail.action.play"), systemImage: isPlaying ? "waveform" : "play.fill")
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(model.isGone(message))
            .accessibilityIdentifier("voicemail-play")

            Button(role: .destructive) {
                confirmsDelete = true
            } label: {
                Label(L10n.string("media.delete.action"), systemImage: "trash")
                    .foregroundStyle(model.isReadOnly ? Theme.textTertiary : Theme.danger)
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .background(Theme.raised, in: Theme.card(Theme.Radius.s))
            }
            .buttonStyle(.plain)
            .disabled(model.isReadOnly)
            .accessibilityIdentifier("voicemail-delete")
        }
    }

    /// "We bewaren dit bericht tot 15 juni (nog 248 dagen)".
    private var keptSentence: String {
        guard let days = message.daysLeft, days >= 0, let until = Calendar.current.date(byAdding: .day, value: days, to: Date()) else {
            return L10n.string("voicemail.detail.retention")
        }

        return String(format: L10n.string("voicemail.detail.keptUntil"), until.formatted(.dateTime.day().month(.wide)), days)
    }

    private var details: some View {
        SettingsGroup(footer: keptSentence) {
            SettingsRow(title: L10n.string("voicemail.detail.received"), value: message.receivedLabel, showsChevron: false)

            if !message.durationLabel.isEmpty {
                SettingsRow(title: L10n.string("history.detail.duration"), value: message.durationLabel, showsChevron: false)
            }

            if model.canChooseBox, !message.boxName.isEmpty {
                SettingsRow(title: L10n.string("voicemail.detail.box"), value: message.boxName, showsChevron: false)
            }

            SettingsRow(
                title: L10n.string("voicemail.detail.kept"),
                value: MediaFormat.daysLeft(message.daysLeft) ?? L10n.string("voicemail.detail.keptUnknown"),
                showsChevron: false
            )
        }
    }
}

// MARK: - Box / account chooser

private struct VoicemailBoxSheet: View {
    @ObservedObject var app: FSVoipAppModel
    @ObservedObject var model: VoicemailModel
    let accounts: [StoredAccount]
    let current: String
    let selectAccount: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        SheetShell(title: L10n.string("media.voicemail.scope.header"), onClose: { dismiss() }) {
            VStack(alignment: .leading, spacing: Theme.Spacing.l) {
                if accounts.count > 1 {
                    SettingsGroup(title: L10n.string("outbound.sheet.account")) {
                        ForEach(accounts) { account in
                            ChoiceRow(title: account.displayLabel, isSelected: account.id == current) {
                                selectAccount(account.id)
                                dismiss()
                            }
                        }
                    }
                }

                if model.canChooseBox {
                    SettingsGroup(footer: model.scope != .own ? L10n.string("media.voicemail.scope.footer") : nil) {
                        ChoiceRow(title: L10n.string("media.voicemail.scope.own"), isSelected: model.scope == .own) {
                            Task {
                                await model.select(.own)
                                dismiss()
                            }
                        }
                        .disabled(model.ownBoxId == nil)

                        ChoiceRow(title: L10n.string("media.voicemail.scope.all"), isSelected: model.scope == .all) {
                            Task {
                                await model.select(.all)
                                dismiss()
                            }
                        }

                        ForEach(model.boxes) { box in
                            ChoiceRow(
                                title: model.boxTitle(box) + (box.shared ? " (\(L10n.string("media.voicemail.shared")))" : ""),
                                isSelected: model.scope == .box(box.id)
                            ) {
                                Task {
                                    await model.select(.box(box.id))
                                    dismiss()
                                }
                            }
                        }
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
