// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation

/// Which contacts the Contacts tab shows.
public enum ContactFilter: Hashable, Sendable {
    case all
    /// Only the customer's address book.
    case customer
    /// The members of one list of one account.
    case list(accountId: String, listId: String)
    case device
    case colleagues
}

public struct ContactSection: Identifiable, Equatable, Sendable {
    public var letter: Character
    public var entries: [ContactEntry]

    public var id: Character { letter }
}

public enum ContactBrowsing {
    /// Filter, search and group by first letter (digits and symbols under `#`, last). Sorted case- and accent-insensitively.
    ///
    /// `members` answers "which customer-contact ids are in this list"; it is only called for a `.list` filter.
    public static func sections(entries: [ContactEntry], filter: ContactFilter, query: String, members: (String, String) -> Set<String>) -> [ContactSection] {
        let allowed: Set<String>?

        if case let .list(accountId, listId) = filter {
            allowed = members(accountId, listId)
        } else {
            allowed = nil
        }

        let selected = entries.filter { entry in
            switch filter {
            case .all: break
            case .customer: guard entry.source == .customer else { return false }
            case .device: guard entry.source == .device else { return false }
            case .colleagues: guard entry.source == .internalExtensions else { return false }
            case .list:
                guard entry.source == .customer, let id = entry.contactId, allowed?.contains(id) == true else { return false }
            }

            return entry.matches(query: query)
        }

        var grouped: [Character: [ContactEntry]] = [:]

        for entry in selected {
            grouped[entry.sectionLetter, default: []].append(entry)
        }

        return grouped
            .map { ContactSection(letter: $0.key, entries: $0.value.sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }) }
            .sorted { lhs, rhs in
                if lhs.letter == "#" { return false }
                if rhs.letter == "#" { return true }

                return lhs.letter < rhs.letter
            }
    }

    /// Up to two letters for the round avatar ("PG" for Pieter de Groot, "B" for Bakkerij Smit).
    public static func initials(for name: String) -> String {
        let words = name.split(whereSeparator: { $0.isWhitespace }).filter { $0.first?.isLetter == true }

        guard let first = words.first?.first else {
            return "#"
        }

        guard words.count > 1, let last = words.last?.first else {
            return String(first).uppercased()
        }

        return (String(first) + String(last)).uppercased()
    }
}
