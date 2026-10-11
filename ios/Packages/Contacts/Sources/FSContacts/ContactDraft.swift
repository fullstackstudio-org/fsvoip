// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation

/// A contact as the edit screen holds it, and the request bodies built from it.
public struct ContactDraft: Equatable, Sendable {
    public struct Phone: Equatable, Identifiable, Sendable {
        public var id: UUID
        public var number: String
        public var label: ContactPhoneLabel

        public init(id: UUID = UUID(), number: String = "", label: ContactPhoneLabel = .mobile) {
            self.id = id
            self.number = number
            self.label = label
        }
    }

    public var firstName = ""
    public var lastName = ""
    public var company = ""
    public var email = ""
    public var notes = ""
    public var phones: [Phone] = [Phone()]
    public var listIds: Set<String> = []

    public init() {}

    public init(detail: ContactDetail) {
        let contact = detail.contact
        let hasParts = !(contact.firstName ?? "").isEmpty || !(contact.lastName ?? "").isEmpty

        firstName = contact.firstName ?? ""
        lastName = contact.lastName ?? ""
        company = contact.company ?? ""
        email = contact.email ?? ""
        notes = detail.notes ?? ""

        // A contact that only has a display name ("Bakkerij Smit"): keep it as it is on the first line, unless it is the company.
        if !hasParts, !contact.name.isEmpty, contact.name != contact.company {
            firstName = contact.name
        }

        phones = contact.phones.map { Phone(number: $0.number, label: $0.label.isKnown ? $0.label : .other) }

        if phones.isEmpty {
            phones = [Phone()]
        }

        listIds = Set(detail.listIds)
    }

    public init(entry: ContactEntry) {
        company = entry.company ?? ""
        email = entry.email ?? ""

        let parts = entry.name.split(separator: " ", maxSplits: 1).map(String.init)

        if entry.name != entry.company {
            firstName = parts.first ?? ""
            lastName = parts.count > 1 ? parts[1] : ""
        }

        phones = entry.phones.map { Phone(number: $0.number, label: $0.label.isKnown ? $0.label : .other) }

        if phones.isEmpty {
            phones = [Phone()]
        }
    }

    /// The display name sent as `name`: first and last name, otherwise the company.
    public var displayName: String {
        let person = [firstName, lastName].map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }.joined(separator: " ")

        return person.isEmpty ? company.trimmingCharacters(in: .whitespacesAndNewlines) : person
    }

    /// Rows the user left empty do not count.
    public var filledPhones: [Phone] {
        phones.filter { !$0.number.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }

    public var hasEmail: Bool {
        !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// A contact needs a name and a way to reach it (the server refuses a contact with neither number nor e-mail).
    public var canSave: Bool {
        !displayName.isEmpty && (!filledPhones.isEmpty || hasEmail)
    }

    /// Numbers go to the server in the `+31…` form when they can be written that way; short numbers stay as typed.
    func requestPhones() -> [ContactPhone] {
        let rows = filledPhones.map { row -> (String, ContactPhoneLabel) in
            let typed = row.number.trimmingCharacters(in: .whitespacesAndNewlines)

            return (PhoneNumberMatcher.e164(for: typed) ?? typed, row.label.isKnown ? row.label : .other)
        }

        return rows.enumerated().map { ContactPhone(number: $0.element.0, label: $0.element.1, isPrimary: $0.offset == 0) }
    }

    private func text(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)

        return trimmed.isEmpty ? nil : trimmed
    }

    public func createRequest() -> ContactCreate {
        ContactCreate(
            name: displayName,
            firstName: text(firstName).map(Change.set) ?? .keep,
            lastName: text(lastName).map(Change.set) ?? .keep,
            company: text(company).map(Change.set) ?? .keep,
            email: text(email).map(Change.set) ?? .keep,
            notes: text(notes).map(Change.set) ?? .keep,
            phones: requestPhones(),
            listIds: listIds.isEmpty ? nil : listIds.sorted()
        )
    }

    /// Every field is sent (an emptied field clears it); phones and lists replace the whole set. `expectedUpdatedAt` is the
    /// `updatedAt` the screen was opened with.
    public func updateRequest(expectedUpdatedAt: String) -> ContactUpdate {
        ContactUpdate(
            expectedUpdatedAt: expectedUpdatedAt,
            name: displayName,
            firstName: Change(text(firstName)),
            lastName: Change(text(lastName)),
            company: Change(text(company)),
            email: Change(text(email)),
            notes: Change(text(notes)),
            phones: requestPhones(),
            listIds: listIds.sorted()
        )
    }
}
