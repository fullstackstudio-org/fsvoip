// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import FSContacts
import SwiftUI

/// The round initials of a contact.
struct ContactAvatar: View {
    let name: String
    var size: CGFloat = 40

    var body: some View {
        Text(ContactBrowsing.initials(for: name))
            .font(.system(size: size * 0.38, weight: .semibold, design: .rounded))
            .foregroundStyle(.secondary)
            .frame(width: size, height: size)
            .background(Circle().fill(Color(.secondarySystemFill)))
            .accessibilityHidden(true)
    }
}

extension ContactPhoneLabel {
    var title: String {
        switch self {
        case .mobile: return L10n.string("contacts.label.mobile")
        case .work: return L10n.string("contacts.label.work")
        case .home: return L10n.string("contacts.label.home")
        case .main: return L10n.string("contacts.label.main")
        case .fax: return L10n.string("contacts.label.fax")
        case .other, .unknown: return L10n.string("contacts.label.other")
        }
    }

    /// The labels the edit screen offers.
    static let choices: [ContactPhoneLabel] = [.mobile, .work, .home, .main, .fax, .other]
}

extension ContactEntry {
    /// The grey line under the name in a list.
    var subtitle: String {
        switch source {
        case .internalExtensions:
            return numbers.first.map { String(format: L10n.string("account.extension"), $0) } ?? ""
        case .customer, .device:
            if let company, !company.isEmpty, company != name {
                return company
            }

            return numbers.first ?? ""
        }
    }

    /// A small tag for contacts that do not come from the address book.
    var sourceTag: String? {
        switch source {
        case .customer: return nil
        case .device: return L10n.string("contacts.source.device")
        case .internalExtensions: return L10n.string("contacts.source.colleague")
        }
    }
}

/// What to tell the user when a contact request fails. Never the server's own words.
enum ContactFailure {
    static func message(for error: Error) -> String {
        guard let error = error as? APIError else {
            return L10n.string("error.generic")
        }

        switch error {
        case let .invalid(code, _):
            switch code {
            case "invalid_phone": return L10n.string("contacts.error.phone")
            case "invalid_email": return L10n.string("contacts.error.email")
            default: return L10n.string("error.generic")
            }
        case .forbidden:
            return L10n.string("contacts.error.forbidden")
        case let .conflict(code):
            return code == "limit_reached" ? L10n.string("contacts.error.limit") : L10n.string("error.generic")
        case .rateLimited:
            return L10n.string("contacts.error.rate")
        case .unavailable, .transport:
            return L10n.string("contacts.error.network")
        case .notFound:
            return L10n.string("contacts.error.gone")
        default:
            return L10n.string("error.generic")
        }
    }

    static func countText(_ count: Int) -> String {
        count == 1 ? L10n.string("contacts.count.one") : String(format: L10n.string("contacts.count.many"), count)
    }
}
