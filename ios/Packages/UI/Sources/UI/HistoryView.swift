// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import FSContacts
import SwiftUI
import UIKit

/// The shapes of time in the history.
enum HistoryFormat {
    /// `0:42`, `5:12`, `1:02:03`.
    static func duration(_ seconds: TimeInterval) -> String {
        let total = max(0, Int(seconds.rounded()))
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let rest = total % 60

        return hours > 0 ? String(format: "%d:%02d:%02d", hours, minutes, rest) : String(format: "%d:%02d", minutes, rest)
    }

    static func when(_ date: Date) -> String {
        let calendar = Calendar.current

        if calendar.isDateInToday(date) {
            return date.formatted(date: .omitted, time: .shortened)
        }

        if calendar.isDateInYesterday(date) {
            return L10n.string("recents.yesterday")
        }

        return date.formatted(.dateTime.day().month(.abbreviated))
    }
}

/// "Geschiedenis": the calls of the PBX, newest first, for everyone on the team. Missed calls are red. An admin can open the
/// recording of a call; a `user` never sees one.
struct HistoryView: View {
    @ObservedObject var model: FSVoipAppModel
    @ObservedObject var history: HistoryModel

    @State private var filter: HistoryEntry.Filter = .all
    @State private var kind: HistoryEntry.Kind?
    @State private var chosenAccountId: String?
    @State private var showsFilter = false
    @State private var selected: HistoryEntry?
    @State private var confirmsClear = false

    init(model: FSVoipAppModel) {
        self.model = model
        history = model.history
    }

    private var account: StoredAccount? {
        if let chosenAccountId, let chosen = model.account(id: chosenAccountId) {
            return chosen
        }

        return model.defaultOutgoingAccountId.flatMap { model.account(id: $0) } ?? model.accounts.first
    }

    private var entries: [HistoryEntry] {
        guard let account else { return [] }

        let all = history.entries(for: account.id, local: model.recents, extensionName: account.extensionName)

        return HistoryModel.filtered(all, filter: filter, kind: kind)
    }

    private var hasAnyEntry: Bool {
        guard let account else { return false }

        return !history.entries(for: account.id, local: model.recents, extensionName: account.extensionName).isEmpty
    }

    var body: some View {
        VStack(spacing: 0) {
            controls

            content
        }
        .background(Theme.background)
        .navigationTitle(L10n.string("tab.recents"))
        .toolbar {
            if !model.recents.isEmpty {
                ToolbarItem(placement: .topBarLeading) {
                    Button(L10n.string("recents.clear")) { confirmsClear = true }
                        .accessibilityIdentifier("history-clear")
                }
            }
        }
        .confirmationDialog(L10n.string("recents.clear.title"), isPresented: $confirmsClear, titleVisibility: .visible) {
            Button(L10n.string("recents.clear.confirm"), role: .destructive) { model.clearRecents() }
        }
        .sheet(isPresented: $showsFilter) {
            HistoryFilterSheet(model: model, kind: $kind, accountId: Binding(get: { account?.id }, set: { chosenAccountId = $0 }))
        }
        .sheet(item: $selected) { entry in
            HistoryDetailSheet(model: model, history: history, entry: entry)
        }
        .task(id: account?.id) {
            if let account { await history.load(account) }
        }
        .refreshable {
            if let account { await history.load(account) }
        }
    }

    // MARK: Parts

    private var controls: some View {
        HStack(spacing: Theme.Spacing.m) {
            SegmentedBar(
                options: [
                    .init(value: HistoryEntry.Filter.all, title: L10n.string("history.filter.all")),
                    .init(value: HistoryEntry.Filter.missed, title: L10n.string("history.filter.missed")),
                ],
                selection: $filter
            )
            .accessibilityIdentifier("history-segments")

            FilterButton(title: L10n.string("history.filter.button"), isActive: kind != nil || (model.accounts.count > 1 && chosenAccountId != nil)) {
                showsFilter = true
            }
            .accessibilityIdentifier("history-filter")
        }
        .padding(.horizontal, Theme.Spacing.l)
        .padding(.vertical, Theme.Spacing.s)
    }

    @ViewBuilder
    private var content: some View {
        if let account {
            let state = history.state(for: account.id)
            let rows = entries

            if rows.isEmpty {
                if state == .loading && !hasAnyEntry {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .accessibilityLabel(L10n.string("history.loading"))
                } else if hasAnyEntry {
                    EmptyState(
                        symbol: "line.3.horizontal.decrease",
                        title: L10n.string(filter == .missed ? "history.empty.missed.title" : "history.empty.filtered.title"),
                        message: L10n.string("history.empty.filtered.message")
                    )
                } else {
                    EmptyState(
                        symbol: "clock",
                        title: L10n.string("recents.empty.title"),
                        message: L10n.string(state == .unavailable ? "history.empty.unavailable" : "history.empty.message")
                    )
                }
            } else {
                list(rows, account: account, state: state)
            }
        } else {
            EmptyState(symbol: "clock", title: L10n.string("recents.empty.title"), message: L10n.string("history.empty.message"))
        }
    }

    private func list(_ rows: [HistoryEntry], account: StoredAccount, state: HistoryModel.LoadState) -> some View {
        List {
            if state == .unavailable {
                Text(L10n.string("history.unavailable.notice"))
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .accessibilityIdentifier("history-unavailable")
            }

            ForEach(rows) { entry in
                Button {
                    selected = entry
                } label: {
                    HistoryRow(entry: entry, title: history.title(for: entry))
                }
                .buttonStyle(.plain)
                .listRowBackground(Theme.background)
                .listRowSeparatorTint(Theme.separator)
                .accessibilityIdentifier("history-row")
            }

            if history.nextMonth(for: account.id) != nil {
                Button {
                    Task { await history.loadMore(account) }
                } label: {
                    HStack {
                        Spacer()
                        if history.isLoadingMore {
                            ProgressView()
                        } else {
                            L10n.text("history.more")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Theme.accentText)
                        }
                        Spacer()
                    }
                    .frame(minHeight: Theme.minimumTarget)
                }
                .listRowBackground(Theme.background)
                .listRowSeparator(.hidden)
                .accessibilityIdentifier("history-more")
            } else if history.isTruncated(account.id) {
                Text(L10n.string("history.truncated"))
                    .font(.footnote)
                    .foregroundStyle(Theme.textTertiary)
                    .listRowBackground(Theme.background)
                    .listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .accessibilityIdentifier("history-list")
    }
}

// MARK: - Row

struct HistoryRow: View {
    let entry: HistoryEntry
    let title: String

    private var symbol: String {
        switch entry.kind {
        case .incoming: return "arrow.down.left"
        case .outgoing: return "arrow.up.right"
        case .internal: return "arrow.left.arrow.right"
        }
    }

    private var kindWord: String {
        if entry.isMissed { return L10n.string("history.kind.missed") }

        switch entry.kind {
        case .incoming: return L10n.string("history.kind.incoming")
        case .outgoing: return L10n.string("history.kind.outgoing")
        case .internal: return L10n.string("history.kind.internal")
        }
    }

    private var subtitle: String {
        var parts = [kindWord]

        if let number = entry.number, number != title {
            parts.append(number)
        }

        if let ours = entry.ourNumber, !ours.isEmpty {
            parts.append(PbxVocabulary.formatNumber(ours))
        }

        if !entry.durationLabel.isEmpty {
            parts.append(entry.durationLabel)
        }

        return parts.joined(separator: " · ")
    }

    var body: some View {
        HStack(spacing: Theme.Spacing.m) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(entry.isMissed ? Theme.danger : Theme.textPrimary)
                    .adaptiveLineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)

                HStack(spacing: Theme.Spacing.xs) {
                    Image(systemName: symbol)
                        .font(.caption.weight(.bold))
                        .foregroundStyle(entry.isMissed ? Theme.danger : Theme.textTertiary)
                        .accessibilityHidden(true)

                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(entry.isMissed ? Theme.danger : Theme.textSecondary)
                        .adaptiveLineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            Spacer(minLength: Theme.Spacing.s)

            Text(entry.whenLabel)
                .font(.footnote)
                .foregroundStyle(entry.isMissed ? Theme.danger : Theme.textSecondary)
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)

            // Who took (or made) the call, like the avatar at the right of a row in a colleague list.
            InitialsAvatar(name: entry.extensionName, size: 38)
        }
        .padding(.vertical, Theme.Spacing.xs)
        .frame(minHeight: Theme.minimumTarget)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue([subtitle, entry.whenLabel, entry.extensionName.map { String(format: L10n.string("history.handledBy"), $0) }].compactMap { $0 }.joined(separator: ", "))
        .accessibilityHint(L10n.string("history.row.hint"))
    }
}

// MARK: - Filter sheet

private struct HistoryFilterSheet: View {
    @ObservedObject var model: FSVoipAppModel
    @Binding var kind: HistoryEntry.Kind?
    @Binding var accountId: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        SheetShell(title: L10n.string("history.filter.title"), onClose: { dismiss() }) {
            VStack(alignment: .leading, spacing: Theme.Spacing.l) {
                SettingsGroup(title: L10n.string("history.filter.kind")) {
                    option("history.kind.all", nil)
                    option("history.kind.incoming", .incoming)
                    option("history.kind.outgoing", .outgoing)
                    option("history.kind.internal", .internal)
                }

                if model.accounts.count > 1 {
                    SettingsGroup(title: L10n.string("history.filter.account")) {
                        ForEach(model.accounts) { account in
                            ChoiceRow(title: account.displayLabel, isSelected: account.id == accountId) {
                                accountId = account.id
                            }
                        }
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func option(_ key: String, _ value: HistoryEntry.Kind?) -> some View {
        ChoiceRow(title: L10n.string(key), isSelected: kind == value) {
            kind = value
        }
        .accessibilityIdentifier("history-kind-\(key)")
    }
}

// MARK: - Detail sheet

private struct HistoryDetailSheet: View {
    @ObservedObject var model: FSVoipAppModel
    @ObservedObject var history: HistoryModel
    let entry: HistoryEntry

    @Environment(\.dismiss) private var dismiss
    @State private var copied = false
    @State private var addsContact = false

    private var account: StoredAccount? {
        model.account(id: entry.accountId)
    }

    private var title: String {
        history.title(for: entry)
    }

    private var isUnnamed: Bool {
        entry.number != nil && title == entry.number
    }

    private var canAddContact: Bool {
        isUnnamed && !model.contacts.writableAccountIds.isEmpty
    }

    var body: some View {
        SheetShell(title: title, onClose: { dismiss() }) {
            VStack(alignment: .leading, spacing: Theme.Spacing.l) {
                header

                actions

                details

                recording
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if let nowPlaying = history.nowPlaying, let media = model.media, let account {
                AudioPlayerBar(
                    player: media.player,
                    nowPlaying: nowPlaying,
                    kind: .recording,
                    onClose: history.stopPlayer,
                    onRetry: { history.retryPlaying(account: account, entries: [entry]) }
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .motionAnimation(.easeOut(duration: 0.22), value: history.nowPlaying)
        .onDisappear { history.stopPlayer() }
        .presentationDetents([.large])
        .sheet(isPresented: $addsContact) {
            HistoryAddContactSheet(model: model, number: entry.number ?? "")
        }
    }

    private var header: some View {
        VStack(spacing: Theme.Spacing.s) {
            if isUnnamed {
                // An unknown number is the title of the screen, large.
                Text(entry.number ?? "")
                    .font(.title2.weight(.semibold).monospacedDigit())
                    .foregroundStyle(Theme.textPrimary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                InitialsAvatar(name: title, size: 64)

                if let number = entry.number {
                    Text(number)
                        .font(.body.monospacedDigit())
                        .foregroundStyle(Theme.textSecondary)
                }
            }

            if entry.isMissed {
                Text(L10n.string("history.kind.missed"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.danger)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }

    private var actions: some View {
        VStack(spacing: Theme.Spacing.m) {
            if canAddContact {
                Button {
                    addsContact = true
                } label: {
                    Text(L10n.string("history.action.addContact"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Theme.accentText)
                        .frame(minHeight: Theme.minimumTarget)
                }
                .accessibilityIdentifier("history-add-contact")
            }

            HStack(spacing: Theme.Spacing.l) {
                RoundActionButton(symbol: "phone.fill", label: L10n.string("history.action.call"), isPrimary: true) {
                    if let number = entry.number {
                        dismiss()
                        model.call(number, from: model.account(id: entry.accountId) != nil ? entry.accountId : nil)
                    }
                }
                .disabled(entry.number == nil)
                .accessibilityIdentifier("history-call")

                if let number = entry.number {
                    RoundActionButton(symbol: copied ? "checkmark" : "doc.on.doc", label: L10n.string(copied ? "history.action.copied" : "history.action.copy"), isPrimary: false) {
                        UIPasteboard.general.string = number
                        Haptics.success()
                        copied = true
                    }
                    .accessibilityIdentifier("history-copy")
                }
            }
        }
        .frame(maxWidth: .infinity)
    }

    private var details: some View {
        SettingsGroup {
            SettingsRow(title: L10n.string("history.detail.when"), value: entry.whenLabel, showsChevron: false)

            if !entry.durationLabel.isEmpty {
                SettingsRow(title: L10n.string("history.detail.duration"), value: entry.durationLabel, showsChevron: false)
            }

            SettingsRow(title: L10n.string("history.detail.kind"), value: kindWord, showsChevron: false)

            if let ourNumber = entry.ourNumber, !ourNumber.isEmpty {
                SettingsRow(title: L10n.string("history.detail.ourNumber"), value: ourNumber, showsChevron: false)
            }

            if let name = entry.extensionName, !name.isEmpty {
                SettingsRow(title: L10n.string(entry.kind == .outgoing ? "history.detail.madeBy" : "history.detail.handledBy"), value: name, showsChevron: false)
            }

            if let country = entry.countryName, !country.isEmpty {
                SettingsRow(title: L10n.string("history.detail.country"), value: country, showsChevron: false)
            }
        }
    }

    private var kindWord: String {
        switch entry.kind {
        case .incoming: return L10n.string("history.kind.incoming")
        case .outgoing: return L10n.string("history.kind.outgoing")
        case .internal: return L10n.string("history.kind.internal")
        }
    }

    /// Only an admin pairing is offered a recording; for a `user` nothing here exists, not even a disabled button.
    @ViewBuilder
    private var recording: some View {
        if history.canPlayRecording(entry) || (entry.recordingExpired && model.media?.hasRecordings(entry.accountId) == true) {
            VStack(alignment: .leading, spacing: Theme.Spacing.s) {
                if let banner = history.banner {
                    Text(banner.text)
                        .font(.footnote)
                        .foregroundStyle(banner.isError ? Theme.danger : Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                if history.isRecordingGone(entry) {
                    Text(L10n.string("history.recording.gone"))
                        .font(.subheadline)
                        .foregroundStyle(Theme.textSecondary)
                } else {
                    Button {
                        if let account { Task { await history.play(entry, account: account) } }
                    } label: {
                        Label(L10n.string("history.recording.play"), systemImage: "play.circle.fill")
                    }
                    .buttonStyle(SecondaryButtonStyle())
                    .accessibilityIdentifier("history-play-recording")
                }
            }
        }
    }
}

// MARK: - Add to contacts

/// A number from the history becomes a contact of the customer's address book: a name and the number, nothing else.
private struct HistoryAddContactSheet: View {
    @ObservedObject var model: FSVoipAppModel
    let number: String

    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var isSaving = false
    @State private var errorText: String?
    @FocusState private var focused: Bool

    private var draft: ContactDraft {
        var draft = ContactDraft()
        draft.firstName = name
        draft.phones = [.init(number: number, label: .mobile)]

        return draft
    }

    var body: some View {
        SheetShell(
            title: L10n.string("history.addContact.title"),
            onClose: { dismiss() },
            footer: SheetFooter(canSave: draft.canSave, onSave: save),
            isSaving: isSaving,
            isDirty: !name.isEmpty
        ) {
            VStack(alignment: .leading, spacing: Theme.Spacing.m) {
                TextField(L10n.string("history.addContact.name"), text: $name)
                    .textContentType(.name)
                    .focused($focused)
                    .padding(Theme.Spacing.m)
                    .frame(minHeight: Theme.minimumTarget)
                    .background(Theme.raised, in: Theme.card(Theme.Radius.s))
                    .accessibilityIdentifier("history-contact-name")

                Text(number)
                    .font(.body.monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
                    .padding(.horizontal, Theme.Spacing.xs)

                if let errorText {
                    Text(errorText)
                        .font(.footnote)
                        .foregroundStyle(Theme.danger)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .onAppear { focused = true }
    }

    private func save() {
        guard let accountId = model.contacts.writableAccountIds.first, !isSaving else { return }

        isSaving = true
        errorText = nil

        Task {
            do {
                _ = try await model.contacts.create(draft, accountId: accountId)
                Haptics.success()
                dismiss()
            } catch {
                errorText = L10n.string("error.generic")
                Haptics.warning()
            }

            isSaving = false
        }
    }
}

/// The round buttons under a number: call (lime) and copy (raised).
struct RoundActionButton: View {
    let symbol: String
    let label: String
    let isPrimary: Bool
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.title3.weight(.semibold))
                .foregroundStyle(isPrimary ? Theme.onAccent : Theme.textPrimary)
                .frame(width: 56, height: 56)
                .background(Circle().fill(isPrimary ? Theme.accent : Theme.raised))
                .opacity(isEnabled ? 1 : 0.4)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}
