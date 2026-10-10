// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import FSContacts
import SwiftUI
import UIKit

struct ContactDetailView: View {
    @ObservedObject var model: FSVoipAppModel
    @ObservedObject private var hub: ContactsHub
    @ObservedObject private var favorites = FavoriteContacts.shared
    let entryId: String
    @Environment(\.dismiss) private var dismiss

    @State private var detail: ContactDetail?
    @State private var showsEdit = false
    @State private var confirmsDelete = false
    @State private var isDeleting = false
    @State private var errorText: String?

    init(model: FSVoipAppModel, entryId: String) {
        self.model = model
        hub = model.contacts
        self.entryId = entryId
    }

    private var entry: ContactEntry? {
        hub.entries.first { $0.id == entryId }
    }

    var body: some View {
        Group {
            if let entry {
                content(entry)
            } else {
                // Deleted (here or on another device).
                Color.clear.onAppear { dismiss() }
            }
        }
        .background(Theme.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
    }

    private func content(_ entry: ContactEntry) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Spacing.s) {
                header(entry)
                actions(entry)

                numbers(entry)

                if let email = entry.email, !email.isEmpty {
                    SettingsGroup(title: L10n.string("contacts.detail.email")) {
                        if let url = URL(string: "mailto:\(email)") {
                            Link(destination: url) {
                                SettingsRow(symbol: "envelope", title: email, showsChevron: false)
                            }
                        } else {
                            SettingsRow(symbol: "envelope", title: email, showsChevron: false)
                        }
                    }
                }

                recentCalls(entry)

                timeline(entry)

                if let notes = detail?.notes, !notes.isEmpty {
                    SettingsGroup(title: L10n.string("contacts.detail.notes")) {
                        SettingsRow(title: notes, showsChevron: false)
                    }
                }

                if let names = listNames(entry), !names.isEmpty {
                    SettingsGroup(title: L10n.string("contacts.detail.lists")) {
                        SettingsRow(title: names.joined(separator: ", "), showsChevron: false)
                    }
                }

                sourceNote(entry)

                if let errorText {
                    Text(errorText)
                        .font(.footnote)
                        .foregroundStyle(Theme.danger)
                        .padding(.horizontal, Theme.Spacing.l)
                }

                if hub.canDelete(entry) {
                    SettingsGroup {
                        Button {
                            confirmsDelete = true
                        } label: {
                            SettingsRow(symbol: "trash", title: L10n.string("contacts.detail.delete"), showsChevron: false, isDestructive: true)
                        }
                        .buttonStyle(RowButtonStyle())
                        .disabled(isDeleting)
                        .accessibilityIdentifier("contact-delete")
                    }
                }
            }
            .padding(.horizontal, Theme.Spacing.l)
            .padding(.bottom, Theme.Spacing.xl)
        }
        .toolbar {
            if hub.canWrite(entry) {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(L10n.string("contacts.detail.edit")) { showsEdit = true }
                        .foregroundStyle(Theme.textPrimary)
                        .accessibilityIdentifier("contact-edit")
                }
            }
        }
        .sheet(isPresented: $showsEdit) {
            ContactEditView(model: model, mode: .edit(entryId: entry.id))
        }
        .confirmationDialog(String(format: L10n.string("contacts.detail.delete.title"), entry.displayName), isPresented: $confirmsDelete, titleVisibility: .visible) {
            Button(L10n.string("contacts.detail.delete.confirm"), role: .destructive) { delete(entry) }
        } message: {
            L10n.text("contacts.detail.delete.message")
        }
        .task(id: entry.updatedAt) { await loadDetail(entry) }
        #if DEBUG
        .onAppear {
            if UserDefaults.standard.string(forKey: "FSVoipDemoContactsScreen") == "edit" { showsEdit = true }
        }
        #endif
    }

    // MARK: Header and actions

    private func header(_ entry: ContactEntry) -> some View {
        VStack(spacing: Theme.Spacing.s) {
            InitialsAvatar(name: entry.displayName, size: 84)

            Text(entry.displayName)
                .font(.title2.bold())
                .foregroundStyle(Theme.textPrimary)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)

            if let company = entry.company, !company.isEmpty, company != entry.displayName {
                Text(company)
                    .font(.subheadline)
                    .foregroundStyle(Theme.textSecondary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Theme.Spacing.m)
        .accessibilityElement(children: .combine)
    }

    private func actions(_ entry: ContactEntry) -> some View {
        let first = entry.phones.first?.number

        return HStack(spacing: Theme.Spacing.m) {
            actionButton(symbol: "phone.fill", title: L10n.string("contacts.call"), isPrimary: true, isEnabled: first != nil) {
                if let first { model.call(first, from: nil) }
            }
            .accessibilityIdentifier("contact-call")

            actionButton(symbol: "doc.on.doc", title: L10n.string("contacts.copy"), isEnabled: first != nil) {
                if let first {
                    UIPasteboard.general.string = first
                    Haptics.tap()
                }
            }

            actionButton(
                symbol: favorites.contains(entry.id) ? "star.fill" : "star",
                title: L10n.string(favorites.contains(entry.id) ? "contacts.favorite.remove" : "contacts.favorite.add")
            ) {
                favorites.toggle(entry.id)
            }
            .accessibilityIdentifier("contact-favorite")
        }
        .padding(.bottom, Theme.Spacing.l)
    }

    private func actionButton(symbol: String, title: String, isPrimary: Bool = false, isEnabled: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: Theme.Spacing.xs) {
                Image(systemName: symbol)
                    .font(.title3)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.footnote.weight(.medium))
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(isPrimary ? Theme.onAccent : Theme.textPrimary)
            .frame(maxWidth: .infinity, minHeight: 64)
            .padding(.vertical, Theme.Spacing.xs)
            .background(isPrimary ? Theme.accent : Theme.raised, in: Theme.card(Theme.Radius.s))
            .opacity(isEnabled ? 1 : 0.4)
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
    }

    // MARK: Numbers

    @ViewBuilder
    private func numbers(_ entry: ContactEntry) -> some View {
        SettingsGroup(title: L10n.string("contacts.detail.numbers")) {
            if entry.phones.isEmpty {
                SettingsRow(title: L10n.string("contacts.detail.noNumbers"), showsChevron: false)
            }

            ForEach(entry.phones, id: \.self) { phone in
                numberRow(phone)
            }
        }
    }

    @ViewBuilder
    private func numberRow(_ phone: ContactPhoneEntry) -> some View {
        Group {
            if model.accounts.count > 1 {
                Menu {
                    ForEach(model.accounts) { account in
                        Button {
                            model.call(phone.number, from: account.id)
                        } label: {
                            Label(String(format: L10n.string("contacts.call.with"), account.displayLabel), systemImage: "phone")
                        }
                    }
                } label: {
                    numberLabel(phone)
                } primaryAction: {
                    model.call(phone.number, from: nil)
                }
            } else {
                Button {
                    model.call(phone.number, from: nil)
                } label: {
                    numberLabel(phone)
                }
                .buttonStyle(RowButtonStyle())
            }
        }
        .contextMenu {
            Button {
                UIPasteboard.general.string = phone.number
            } label: {
                Label(L10n.string("contacts.copy"), systemImage: "doc.on.doc")
            }
        }
        .accessibilityIdentifier("contact-number")
    }

    private func numberLabel(_ phone: ContactPhoneEntry) -> some View {
        HStack(spacing: Theme.Spacing.m) {
            VStack(alignment: .leading, spacing: 2) {
                Text(phone.label.title)
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                Text(phone.number)
                    .font(.body)
                    .foregroundStyle(Theme.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: Theme.Spacing.s)

            Image(systemName: "phone.fill")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Theme.onAccent)
                .frame(width: 34, height: 34)
                .background(Circle().fill(Theme.accent))
                .accessibilityHidden(true)
        }
        .settingsRowChrome()
        .accessibilityElement(children: .combine)
        .accessibilityLabel(String(format: L10n.string("contacts.call.number"), phone.label.title, phone.number))
    }

    // MARK: Timeline

    /// The timeline of a contact of the customer's address book (not a phone or colleague entry), from the first account that has it.
    @ViewBuilder
    private func timeline(_ entry: ContactEntry) -> some View {
        if entry.source == .customer, let contactId = entry.contactId, let service = model.customerCards,
           let accountId = hub.writeAccountId(for: entry) ?? entry.accountIds.first, let account = model.account(id: accountId)
        {
            ContactTimelineSection(service: service, account: account, contactId: contactId)
                .id("\(accountId):\(contactId)")
        }
    }

    // MARK: Recent calls

    @ViewBuilder
    private func recentCalls(_ entry: ContactEntry) -> some View {
        let calls = ContactRecentCalls.matching(model.recents, phones: entry.phones.map(\.number))

        if !entry.phones.isEmpty {
            SettingsGroup(title: L10n.string("contacts.detail.recent")) {
                if calls.isEmpty {
                    SettingsRow(title: L10n.string("contacts.detail.recent.empty"), showsChevron: false)
                }

                ForEach(calls) { call in
                    recentRow(call)
                }
            }
        }
    }

    private func recentRow(_ call: RecentCall) -> some View {
        let isMissed = call.direction == .incoming && call.outcome == .missed

        return HStack(spacing: Theme.Spacing.m) {
            Image(systemName: call.direction == .outgoing ? "arrow.up.right" : "arrow.down.left")
                .font(.footnote.weight(.bold))
                .foregroundStyle(isMissed ? Theme.danger : Theme.textSecondary)
                .frame(width: 20)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(Self.outcome(call))
                    .font(.body)
                    .foregroundStyle(isMissed ? Theme.danger : Theme.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(call.startedAt.formatted(date: .abbreviated, time: .shortened))
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
            }

            Spacer(minLength: Theme.Spacing.s)
        }
        .settingsRowChrome()
        .accessibilityElement(children: .combine)
    }

    private static func outcome(_ call: RecentCall) -> String {
        switch call.outcome {
        case .answered:
            let total = max(0, Int(call.duration.rounded()))

            return String(format: "%d:%02d", total / 60, total % 60)
        case .missed: return L10n.string("recents.outcome.missed")
        case .declined: return L10n.string("recents.outcome.declined")
        case .notAnswered: return L10n.string("recents.outcome.notAnswered")
        case .failed: return L10n.string("recents.outcome.failed")
        }
    }

    @ViewBuilder
    private func sourceNote(_ entry: ContactEntry) -> some View {
        switch entry.source {
        case .device:
            note("contacts.detail.deviceNote")
        case .internalExtensions:
            note("contacts.detail.colleagueNote")
        case .customer:
            EmptyView()
        }
    }

    private func note(_ key: String) -> some View {
        Text(L10n.string(key))
            .font(.footnote)
            .foregroundStyle(Theme.textTertiary)
            .padding(.horizontal, Theme.Spacing.l)
            .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: Data

    private func listNames(_ entry: ContactEntry) -> [String]? {
        guard entry.source == .customer, let contactId = entry.contactId else {
            return nil
        }

        var names: [String] = []

        for accountId in entry.accountIds {
            for list in hub.accountStates[accountId]?.lists ?? [] where list.isEnabled && list.memberIds.contains(contactId) && !names.contains(list.name) {
                names.append(list.name)
            }
        }

        return names
    }

    private func loadDetail(_ entry: ContactEntry) async {
        guard entry.source == .customer, let contactId = entry.contactId, let accountId = hub.writeAccountId(for: entry) ?? entry.accountIds.first else {
            return
        }

        // Notes are not part of the sync; they come with the single contact. Offline: the screen simply has no notes.
        detail = try? await hub.detail(accountId: accountId, contactId: contactId)
    }

    private func delete(_ entry: ContactEntry) {
        guard let contactId = entry.contactId, let accountId = entry.accountIds.first(where: { hub.accountStates[$0]?.canDelete == true }) else {
            return
        }

        isDeleting = true
        errorText = nil

        Task {
            do {
                try await hub.delete(contactId: contactId, accountId: accountId)
                Haptics.success()
                dismiss()
            } catch {
                errorText = ContactFailure.message(for: error)
                Haptics.warning()
            }

            isDeleting = false
        }
    }
}
