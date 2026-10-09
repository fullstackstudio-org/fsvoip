// SPDX-License-Identifier: AGPL-3.0-or-later
import Contacts
import Core
import Foundation

public enum DeviceContactsAccess: Equatable, Sendable {
    /// The user was not asked yet.
    case notDetermined
    /// Refused (or restricted by a parent or a profile). Only the Settings app can change that.
    case denied
    /// Allowed, fully or (iOS 18) for a selection of contacts.
    case authorized
}

/// The phone's own contacts. Read only; FSVoip never stores them and never sends them anywhere.
public protocol DeviceContactsReading: Sendable {
    var access: DeviceContactsAccess { get }
    /// Shows the system question the first time. Returns whether contacts may be read.
    func requestAccess() async -> Bool
    func fetch() async throws -> [ContactEntry]
}

/// Used where the phone's contacts do not exist (tests, previews).
public struct NoDeviceContacts: DeviceContactsReading {
    public init() {}
    public var access: DeviceContactsAccess { .denied }
    public func requestAccess() async -> Bool { false }
    public func fetch() async throws -> [ContactEntry] { [] }
}

public struct SystemDeviceContacts: DeviceContactsReading {
    public init() {}

    public var access: DeviceContactsAccess {
        switch CNContactStore.authorizationStatus(for: .contacts) {
        case .notDetermined: return .notDetermined
        case .authorized: return .authorized
        case .denied, .restricted: return .denied
        @unknown default:
            // iOS 18 "limited" (raw value 4) lets us read the chosen contacts.
            return CNContactStore.authorizationStatus(for: .contacts).rawValue == 4 ? .authorized : .denied
        }
    }

    public func requestAccess() async -> Bool {
        (try? await CNContactStore().requestAccess(for: .contacts)) ?? false
    }

    public func fetch() async throws -> [ContactEntry] {
        try await Task.detached(priority: .utility) { try Self.read() }.value
    }

    private static func read() throws -> [ContactEntry] {
        let keys: [CNKeyDescriptor] = [
            CNContactIdentifierKey as CNKeyDescriptor,
            CNContactGivenNameKey as CNKeyDescriptor,
            CNContactFamilyNameKey as CNKeyDescriptor,
            CNContactOrganizationNameKey as CNKeyDescriptor,
            CNContactPhoneNumbersKey as CNKeyDescriptor,
        ]
        let request = CNContactFetchRequest(keysToFetch: keys)
        request.unifyResults = true

        var entries: [ContactEntry] = []

        try CNContactStore().enumerateContacts(with: request) { contact, _ in
            let phones = contact.phoneNumbers.map { value in
                ContactPhoneEntry(number: value.value.stringValue, label: label(for: value.label))
            }

            guard !phones.isEmpty else {
                return
            }

            let name = [contact.givenName, contact.familyName].filter { !$0.isEmpty }.joined(separator: " ")
            let company = contact.organizationName.isEmpty ? nil : contact.organizationName

            entries.append(ContactEntry(id: "device:\(contact.identifier)", name: name.isEmpty ? (company ?? "") : name, company: name.isEmpty ? nil : company, phones: phones, source: .device))
        }

        return entries
    }

    private static func label(for label: String?) -> ContactPhoneLabel {
        switch label {
        case CNLabelPhoneNumberMobile, CNLabelPhoneNumberiPhone: return .mobile
        case CNLabelWork: return .work
        case CNLabelHome: return .home
        case CNLabelPhoneNumberMain: return .main
        case CNLabelPhoneNumberWorkFax, CNLabelPhoneNumberHomeFax, CNLabelPhoneNumberOtherFax: return .fax
        default: return .other
        }
    }
}
