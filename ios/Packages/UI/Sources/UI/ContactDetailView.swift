// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import FSContacts
import SwiftUI

struct ContactDetailView: View {
    @ObservedObject var model: FSVoipAppModel
    @ObservedObject private var hub: ContactsHub
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
        .navigationBarTitleDisplayMode(.inline)
    }

    private func content(_ entry: ContactEntry) -> some View {
        List {
            Section {
                VStack(spacing: 10) {
                    ContactAvatar(name: entry.displayName, size: 76)
                    Text(entry.displayName)
                        .font(.title2.bold())
                        .multilineTextAlignment(.center)

                    if let company = entry.company, !company.isEmpty, company != entry.displayName {
                        Text(company)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .accessibilityElement(children: .combine)
            }

            Section {
                if entry.phones.isEmpty {
                    L10n.text("contacts.detail.noNumbers")
                        .foregroundStyle(.secondary)
                }

                ForEach(entry.phones, id: \.self) { phone in
                    numberRow(phone, entry: entry)
                }
            } header: {
                L10n.text("contacts.detail.numbers")
            }

            if let email = entry.email, !email.isEmpty {
                Section {
                    if let url = URL(string: "mailto:\(email)") {
                        Link(email, destination: url)
                    } else {
                        Text(email)
                    }
                } header: {
                    L10n.text("contacts.detail.email")
                }
            }

            if let notes = detail?.notes, !notes.isEmpty {
                Section {
                    Text(notes)
                } header: {
                    L10n.text("contacts.detail.notes")
                }
            }

            if let names = listNames(entry), !names.isEmpty {
                Section {
                    Text(names.joined(separator: ", "))
                } header: {
                    L10n.text("contacts.detail.lists")
                }
            }

            switch entry.source {
            case .device:
                Section { L10n.text("contacts.detail.deviceNote").font(.footnote).foregroundStyle(.secondary) }
            case .internalExtensions:
                Section { L10n.text("contacts.detail.colleagueNote").font(.footnote).foregroundStyle(.secondary) }
            case .customer:
                EmptyView()
            }

            if let errorText {
                Section {
                    Text(errorText)
                        .font(.footnote)
                        .foregroundStyle(Brand.hangUp)
                }
            }

            if hub.canDelete(entry) {
                Section {
                    Button(role: .destructive) {
                        confirmsDelete = true
                    } label: {
                        HStack {
                            L10n.text("contacts.detail.delete")
                            if isDeleting {
                                Spacer()
                                ProgressView()
                            }
                        }
                    }
                    .disabled(isDeleting)
                    .accessibilityIdentifier("contact-delete")
                }
            }
        }
        .toolbar {
            if hub.canWrite(entry) {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(L10n.string("contacts.detail.edit")) { showsEdit = true }
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

    // MARK: Numbers

    @ViewBuilder
    private func numberRow(_ phone: ContactPhoneEntry, entry: ContactEntry) -> some View {
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
            .accessibilityIdentifier("contact-number")
        } else {
            Button {
                model.call(phone.number, from: nil)
            } label: {
                numberLabel(phone)
            }
            .accessibilityIdentifier("contact-number")
        }
    }

    private func numberLabel(_ phone: ContactPhoneEntry) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(phone.label.title)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text(phone.number)
                    .font(.body)
                    .foregroundStyle(.primary)
            }

            Spacer()

            Image(systemName: "phone.fill")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Brand.ink)
                .frame(width: 34, height: 34)
                .background(Circle().fill(Brand.lime))
                .accessibilityHidden(true)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(String(format: L10n.string("contacts.call.number"), phone.label.title, phone.number))
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
