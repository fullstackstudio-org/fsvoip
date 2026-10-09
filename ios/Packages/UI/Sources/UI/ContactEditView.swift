// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import FSContacts
import SwiftUI

/// New contact / edit a contact of the customer's address book.
struct ContactEditView: View {
    enum Mode: Equatable {
        case new
        case edit(entryId: String)
    }

    @ObservedObject var model: FSVoipAppModel
    @ObservedObject private var hub: ContactsHub
    let mode: Mode
    @Environment(\.dismiss) private var dismiss

    @State private var draft = ContactDraft()
    @State private var original = ContactDraft()
    /// The `updatedAt` the screen was filled with; goes back as `expectedUpdatedAt`.
    @State private var expectedUpdatedAt: String?
    @State private var contactId: String?
    @State private var accountId: String?
    @State private var isLoading = false
    @State private var isSaving = false
    @State private var errorText: String?
    @State private var showsStale = false
    @State private var confirmsDiscard = false
    @State private var didStart = false

    init(model: FSVoipAppModel, mode: Mode) {
        self.model = model
        hub = model.contacts
        self.mode = mode
    }

    private var isDirty: Bool {
        draft != original
    }

    private var title: String {
        mode == .new ? L10n.string("contacts.edit.titleNew") : L10n.string("contacts.edit.titleEdit")
    }

    private var lists: [StoredContactList] {
        guard let accountId else {
            return []
        }

        return hub.accountStates[accountId]?.lists.filter(\.isEnabled) ?? []
    }

    var body: some View {
        NavigationStack {
            Form {
                if isLoading {
                    Section { HStack { Spacer(); ProgressView(); Spacer() } }
                } else {
                    fields
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.string("action.cancel")) {
                        if isDirty { confirmsDiscard = true } else { dismiss() }
                    }
                }

                ToolbarItem(placement: .confirmationAction) {
                    if isSaving {
                        ProgressView()
                    } else {
                        Button(L10n.string("action.save")) { save() }
                            .disabled(!draft.canSave || isLoading)
                            .accessibilityIdentifier("contact-save")
                    }
                }
            }
            .confirmationDialog(L10n.string("contacts.edit.discard.title"), isPresented: $confirmsDiscard, titleVisibility: .visible) {
                Button(L10n.string("contacts.edit.discard.confirm"), role: .destructive) { dismiss() }
                Button(L10n.string("contacts.edit.discard.keep"), role: .cancel) {}
            }
            .alert(L10n.string("contacts.edit.stale.title"), isPresented: $showsStale) {
                Button(L10n.string("contacts.edit.stale.ok"), role: .cancel) {}
            } message: {
                L10n.text("contacts.edit.stale.message")
            }
        }
        .interactiveDismissDisabled(isDirty || isSaving)
        .task { await start() }
    }

    // MARK: Form

    @ViewBuilder
    private var fields: some View {
        if mode == .new, hub.writableAccountIds.count > 1 {
            Section {
                Picker(L10n.string("contacts.edit.account"), selection: Binding(get: { accountId ?? "" }, set: { accountId = $0; draft.listIds = [] })) {
                    ForEach(hub.writableAccountIds, id: \.self) { id in
                        Text(model.account(id: id)?.displayLabel ?? id).tag(id)
                    }
                }
            } footer: {
                L10n.text("contacts.edit.account.footer")
            }
        }

        Section {
            TextField(L10n.string("contacts.edit.firstName"), text: $draft.firstName)
                .textContentType(.givenName)
                .textInputAutocapitalization(.words)
                .accessibilityIdentifier("contact-first-name")
            TextField(L10n.string("contacts.edit.lastName"), text: $draft.lastName)
                .textContentType(.familyName)
                .textInputAutocapitalization(.words)
                .accessibilityIdentifier("contact-last-name")
            TextField(L10n.string("contacts.edit.company"), text: $draft.company)
                .textContentType(.organizationName)
                .textInputAutocapitalization(.words)
                .accessibilityIdentifier("contact-company")
        }

        Section {
            ForEach($draft.phones) { $phone in
                HStack(spacing: 10) {
                    Menu {
                        Picker(L10n.string("contacts.edit.number"), selection: $phone.label) {
                            ForEach(ContactPhoneLabel.choices, id: \.self) { Text($0.title).tag($0) }
                        }
                    } label: {
                        Text(phone.label.title)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .frame(minWidth: 72, alignment: .leading)
                    }

                    TextField(L10n.string("contacts.edit.number"), text: $phone.number)
                        .keyboardType(.phonePad)
                        .textContentType(.telephoneNumber)
                        .accessibilityIdentifier("contact-number-field")
                }
            }
            .onDelete { draft.phones.remove(atOffsets: $0) }

            Button {
                draft.phones.append(.init(label: draft.phones.isEmpty ? .mobile : .work))
            } label: {
                Label(L10n.string("contacts.edit.addNumber"), systemImage: "plus.circle.fill")
                    .foregroundStyle(.primary)
            }
        } header: {
            L10n.text("contacts.edit.numbers")
        }

        Section {
            TextField(L10n.string("contacts.edit.email"), text: $draft.email)
                .keyboardType(.emailAddress)
                .textContentType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .accessibilityIdentifier("contact-email")
        }

        Section {
            TextField("", text: $draft.notes, axis: .vertical)
                .lineLimit(3 ... 8)
                .accessibilityLabel(L10n.string("contacts.edit.notes"))
        } header: {
            L10n.text("contacts.edit.notes")
        }

        if !lists.isEmpty {
            Section {
                ForEach(lists) { list in
                    Toggle(list.name, isOn: Binding(
                        get: { draft.listIds.contains(list.id) },
                        set: { isOn in
                            if isOn { draft.listIds.insert(list.id) } else { draft.listIds.remove(list.id) }
                        }
                    ))
                    .tint(Brand.ink)
                }
            } header: {
                L10n.text("contacts.edit.lists")
            }
        }

        if !draft.canSave {
            Section {
                L10n.text("contacts.edit.needs")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }

        if let errorText {
            Section {
                Text(errorText)
                    .font(.footnote)
                    .foregroundStyle(Brand.hangUp)
                    .accessibilityIdentifier("contact-error")
            }
        }
    }

    // MARK: Actions

    private func start() async {
        guard !didStart else {
            return
        }

        didStart = true

        switch mode {
        case .new:
            accountId = hub.writableAccountIds.first
        case let .edit(entryId):
            guard let entry = hub.entries.first(where: { $0.id == entryId }), let id = entry.contactId, let account = hub.writeAccountId(for: entry) else {
                dismiss()
                return
            }

            contactId = id
            accountId = account
            // Show what we have at once; the notes and lists come from the server.
            draft = ContactDraft(entry: entry)
            original = draft
            expectedUpdatedAt = entry.updatedAt
            isLoading = true
            await reload(accountId: account, contactId: id, silently: true)
            isLoading = false
        }
    }

    /// Fill the form from the server's current version of the contact.
    private func reload(accountId: String, contactId: String, silently: Bool) async {
        do {
            let detail = try await hub.detail(accountId: accountId, contactId: contactId)
            draft = ContactDraft(detail: detail)
            original = draft
            expectedUpdatedAt = detail.contact.updatedAt
        } catch {
            if !silently { errorText = ContactFailure.message(for: error) }
        }
    }

    private func save() {
        guard let accountId, !isSaving, draft.canSave else {
            return
        }

        isSaving = true
        errorText = nil

        Task {
            do {
                switch mode {
                case .new:
                    try await hub.create(draft, accountId: accountId)
                case .edit:
                    guard let contactId, let expectedUpdatedAt else {
                        throw APIError.notFound
                    }

                    try await hub.update(draft, contactId: contactId, expectedUpdatedAt: expectedUpdatedAt, accountId: accountId)
                }

                Haptics.success()
                original = draft
                dismiss()
            } catch APIError.stale {
                // Someone else changed it first: take the server's version and tell the user.
                if let contactId {
                    await reload(accountId: accountId, contactId: contactId, silently: false)
                }

                showsStale = true
                Haptics.warning()
            } catch {
                errorText = ContactFailure.message(for: error)
                Haptics.warning()

                if case APIError.unauthorized = error {
                    await model.refreshAccounts()
                }
            }

            isSaving = false
        }
    }
}
