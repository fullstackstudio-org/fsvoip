// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import FSContacts
import SwiftUI

/// New contact / edit a contact of the customer's address book, as a sheet on the design system's `SheetShell`.
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
    /// The country picked for a number typed in national form.
    @State private var countries: [UUID: PhoneCountry] = [:]
    @State private var countryTouched = false
    /// The `updatedAt` the screen was filled with; goes back as `expectedUpdatedAt`.
    @State private var expectedUpdatedAt: String?
    @State private var contactId: String?
    @State private var accountId: String?
    @State private var isLoading = false
    @State private var isSaving = false
    @State private var errorText: String?
    @State private var showsStale = false
    @State private var didStart = false

    init(model: FSVoipAppModel, mode: Mode) {
        self.model = model
        hub = model.contacts
        self.mode = mode
    }

    private var isDirty: Bool {
        draft != original || countryTouched
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
        SheetShell(
            title: title,
            onClose: { dismiss() },
            footer: SheetFooter(canSave: ContactEditRules.canSave(draft) && !isLoading, onSave: save),
            isSaving: isSaving,
            isDirty: isDirty
        ) {
            if isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Theme.Spacing.xl)
            } else {
                fields
            }
        }
        .presentationDetents([.large])
        .alert(L10n.string("contacts.edit.stale.title"), isPresented: $showsStale) {
            Button(L10n.string("contacts.edit.stale.ok"), role: .cancel) {}
        } message: {
            L10n.text("contacts.edit.stale.message")
        }
        .task { await start() }
    }

    // MARK: Form

    @ViewBuilder
    private var fields: some View {
        if mode == .new, hub.writableAccountIds.count > 1 {
            SettingsGroup(title: L10n.string("contacts.edit.account"), footer: L10n.string("contacts.edit.account.footer")) {
                ForEach(hub.writableAccountIds, id: \.self) { id in
                    ChoiceRow(title: model.account(id: id)?.displayLabel ?? id, isSelected: accountId == id) {
                        accountId = id
                        draft.listIds = []
                    }
                }
            }
        }

        SettingsGroup(title: L10n.string("contacts.edit.name")) {
            field(L10n.string("contacts.edit.firstName"), text: $draft.firstName, content: .givenName, id: "contact-first-name")
            field(L10n.string("contacts.edit.lastName"), text: $draft.lastName, content: .familyName, id: "contact-last-name")
            field(L10n.string("contacts.edit.company"), text: $draft.company, content: .organizationName, id: "contact-company")
        }

        SettingsGroup(title: L10n.string("contacts.edit.numbers")) {
            ForEach($draft.phones) { $phone in
                phoneRow($phone)
            }

            Button {
                draft.phones.append(.init(label: draft.phones.isEmpty ? .mobile : .work))
            } label: {
                HStack(spacing: Theme.Spacing.m) {
                    Image(systemName: "plus.circle.fill")
                        .foregroundStyle(Theme.accentText)
                        .accessibilityHidden(true)
                    Text(L10n.string("contacts.edit.addNumber"))
                        .foregroundStyle(Theme.textPrimary)
                    Spacer(minLength: 0)
                }
                .settingsRowChrome()
            }
            .buttonStyle(RowButtonStyle())
            .accessibilityIdentifier("contact-add-number")
        }

        SettingsGroup(title: L10n.string("contacts.edit.email")) {
            TextField(L10n.string("contacts.edit.email"), text: $draft.email)
                .keyboardType(.emailAddress)
                .textContentType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .foregroundStyle(Theme.textPrimary)
                .settingsRowChrome()
                .accessibilityIdentifier("contact-email")
        }

        SettingsGroup(title: L10n.string("contacts.edit.notes")) {
            TextField(L10n.string("contacts.edit.notes"), text: $draft.notes, axis: .vertical)
                .lineLimit(3 ... 8)
                .foregroundStyle(Theme.textPrimary)
                .settingsRowChrome()
        }

        if !lists.isEmpty {
            SettingsGroup(title: L10n.string("contacts.edit.lists")) {
                ForEach(lists) { list in
                    ToggleRow(title: list.name, isOn: Binding(
                        get: { draft.listIds.contains(list.id) },
                        set: { isOn in
                            if isOn { draft.listIds.insert(list.id) } else { draft.listIds.remove(list.id) }
                        }
                    ))
                }
            }
        }

        if !ContactEditRules.canSave(draft) {
            Text(L10n.string("contacts.edit.needs"))
                .font(.footnote)
                .foregroundStyle(Theme.textTertiary)
                .padding(.horizontal, Theme.Spacing.l)
        }

        if let errorText {
            Text(errorText)
                .font(.footnote)
                .foregroundStyle(Theme.danger)
                .padding(.horizontal, Theme.Spacing.l)
                .padding(.top, Theme.Spacing.s)
                .accessibilityIdentifier("contact-error")
        }
    }

    private func field(_ placeholder: String, text: Binding<String>, content: UITextContentType, id: String) -> some View {
        TextField(placeholder, text: text)
            .textContentType(content)
            .textInputAutocapitalization(.words)
            .foregroundStyle(Theme.textPrimary)
            .settingsRowChrome()
            .accessibilityIdentifier(id)
    }

    /// Country code, label and number of one phone number. The number is on its own line so it never gets squeezed.
    private func phoneRow(_ phone: Binding<ContactDraft.Phone>) -> some View {
        let id = phone.wrappedValue.id
        let country = effectiveCountry(of: phone.wrappedValue)

        return VStack(alignment: .leading, spacing: Theme.Spacing.xs) {
            HStack(spacing: Theme.Spacing.s) {
                Menu {
                    Picker(L10n.string("contacts.edit.country"), selection: Binding(
                        get: { country },
                        set: { picked in
                            countries[id] = picked
                            countryTouched = true
                        }
                    )) {
                        ForEach(PhoneCountry.all) { option in
                            Text("\(option.name) (\(option.dialText))").tag(option)
                        }
                    }
                } label: {
                    pill("\(country.region) \(country.dialText)")
                }
                .accessibilityLabel(L10n.string("contacts.edit.country"))
                .accessibilityValue("\(country.name) \(country.dialText)")
                .accessibilityIdentifier("contact-country")

                Menu {
                    Picker(L10n.string("contacts.edit.number"), selection: phone.label) {
                        ForEach(ContactPhoneLabel.choices, id: \.self) { Text($0.title).tag($0) }
                    }
                } label: {
                    pill(phone.wrappedValue.label.title)
                }
                .accessibilityLabel(L10n.string("contacts.edit.labelPicker"))
                .accessibilityValue(phone.wrappedValue.label.title)

                Spacer(minLength: 0)

                if draft.phones.count > 1 {
                    Button {
                        draft.phones.removeAll { $0.id == id }
                    } label: {
                        Image(systemName: "minus.circle.fill")
                            .foregroundStyle(Theme.textTertiary)
                            .frame(minWidth: Theme.minimumTarget, minHeight: Theme.minimumTarget)
                    }
                    .accessibilityLabel(L10n.string("contacts.edit.removeNumber"))
                }
            }

            TextField(L10n.string("contacts.edit.number"), text: phone.number)
                .keyboardType(.phonePad)
                .textContentType(.telephoneNumber)
                .foregroundStyle(Theme.textPrimary)
                .accessibilityIdentifier("contact-number-field")
        }
        .settingsRowChrome()
    }

    private func pill(_ text: String) -> some View {
        HStack(spacing: Theme.Spacing.xs) {
            Text(text)
                .font(.subheadline.weight(.medium))
                .fixedSize(horizontal: false, vertical: true)
            Image(systemName: "chevron.down")
                .font(.caption2.weight(.semibold))
                .accessibilityHidden(true)
        }
        .foregroundStyle(Theme.textPrimary)
        .padding(.horizontal, Theme.Spacing.m)
        .frame(minHeight: 36)
        .background(Theme.sheet, in: Capsule())
        .overlay(Capsule().strokeBorder(Theme.separator, lineWidth: 1))
        .frame(minHeight: Theme.minimumTarget)
    }

    /// A number that carries its own code shows that country; otherwise the one picked (default the Netherlands).
    private func effectiveCountry(of phone: ContactDraft.Phone) -> PhoneCountry {
        PhoneCountry.detect(from: phone.number) ?? countries[phone.id] ?? .netherlands
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
            original = draft
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
            countries = [:]
            countryTouched = false
            expectedUpdatedAt = detail.contact.updatedAt
        } catch {
            if !silently { errorText = ContactFailure.message(for: error) }
        }
    }

    private func save() {
        guard let accountId, !isSaving, ContactEditRules.canSave(draft) else {
            return
        }

        isSaving = true
        errorText = nil

        let prepared = ContactEditRules.prepared(draft, countries: countries)

        Task {
            do {
                switch mode {
                case .new:
                    try await hub.create(prepared, accountId: accountId)
                case .edit:
                    guard let contactId, let expectedUpdatedAt else {
                        throw APIError.notFound
                    }

                    try await hub.update(prepared, contactId: contactId, expectedUpdatedAt: expectedUpdatedAt, accountId: accountId)
                }

                Haptics.success()
                original = draft
                countryTouched = false
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
