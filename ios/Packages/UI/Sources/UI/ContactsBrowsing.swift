// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import FSContacts
import SwiftUI

/// The two halves of the Contacts tab: what the phone system knows, and what is on this phone.
enum ContactScope: Hashable {
    case centrale
    case phone
}

/// The filter behind "Alles ⌄". `.favorites` is local to this phone.
enum ContactListFilter: Hashable {
    case all
    case favorites
    case internalOnly
    case list(accountId: String, listId: String)
}

/// Segment, filter, search and grouping in one pure function, on top of the unchanged `FSContacts` browsing.
enum ContactsBrowsing {
    static func sections(
        entries: [ContactEntry],
        scope: ContactScope,
        filter: ContactListFilter,
        query: String,
        favorites: Set<String>,
        members: (String, String) -> Set<String>
    ) -> [ContactSection] {
        var scoped = entries.filter { scope == .phone ? $0.source == .device : $0.source != .device }
        let base: ContactFilter

        switch filter {
        case .all:
            base = .all
        case .favorites:
            scoped = scoped.filter { favorites.contains($0.id) }
            base = .all
        case .internalOnly:
            base = .colleagues
        case let .list(accountId, listId):
            base = .list(accountId: accountId, listId: listId)
        }

        return ContactBrowsing.sections(entries: scoped, filter: base, query: query, members: members)
    }
}

/// The A-Z strip along the edge of the list.
enum AlphabetIndex {
    /// Every letter the strip shows, `#` last.
    static let letters: [Character] = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ") + ["#"]

    /// The section to scroll to for a letter: its own section, otherwise the next one that exists, otherwise the last.
    static func target(for letter: Character, in sections: [ContactSection]) -> Character? {
        let ids = sections.map(\.letter)

        if ids.contains(letter) {
            return letter
        }

        guard let position = letters.firstIndex(of: letter) else {
            return ids.last
        }

        for next in letters[position...] where ids.contains(next) {
            return next
        }

        return ids.last
    }

    /// The letter under a finger at `fraction` (0 = top, 1 = bottom) of the strip.
    static func letter(atFraction fraction: Double) -> Character {
        let clamped = min(max(fraction, 0), 0.999_999)

        return letters[Int(clamped * Double(letters.count))]
    }
}

/// The favourites of this phone: a set of contact ids kept in the user defaults. They never leave the phone.
@MainActor
final class FavoriteContacts: ObservableObject {
    static let shared = FavoriteContacts()
    static let storageKey = "fsvoip.contacts.favorites"

    @Published private(set) var ids: Set<String>
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        ids = Set(defaults.stringArray(forKey: Self.storageKey) ?? [])
    }

    func contains(_ id: String) -> Bool {
        ids.contains(id)
    }

    func toggle(_ id: String) {
        if ids.contains(id) { ids.remove(id) } else { ids.insert(id) }

        defaults.set(ids.sorted(), forKey: Self.storageKey)
    }
}

/// A country code for a number typed in national form. The Netherlands comes first.
struct PhoneCountry: Hashable, Identifiable {
    let region: String
    let dialCode: String

    var id: String { region }

    var name: String {
        Locale.current.localizedString(forRegionCode: region) ?? region
    }

    var dialText: String { "+" + dialCode }

    static let netherlands = PhoneCountry(region: "NL", dialCode: "31")

    private static let others: [PhoneCountry] = [
        ("BE", "32"), ("DE", "49"), ("FR", "33"), ("GB", "44"), ("LU", "352"), ("ES", "34"), ("IT", "39"), ("PT", "351"),
        ("AT", "43"), ("CH", "41"), ("DK", "45"), ("SE", "46"), ("NO", "47"), ("FI", "358"), ("PL", "48"), ("IE", "353"),
        ("TR", "90"), ("MA", "212"), ("SR", "597"), ("US", "1"),
    ].map { PhoneCountry(region: $0.0, dialCode: $0.1) }

    /// The Netherlands first, then the rest by name.
    static var all: [PhoneCountry] {
        [netherlands] + others.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    /// The country of a number that already carries its code (`+32…` or `0032…`); `nil` for a national number.
    static func detect(from number: String) -> PhoneCountry? {
        let trimmed = number.trimmingCharacters(in: .whitespaces)
        let digits: String

        if trimmed.hasPrefix("+") {
            digits = String(trimmed.dropFirst().filter(\.isNumber))
        } else if trimmed.hasPrefix("00") {
            digits = String(trimmed.dropFirst(2).filter(\.isNumber))
        } else {
            return nil
        }

        return ([netherlands] + others)
            .filter { digits.hasPrefix($0.dialCode) }
            .max { $0.dialCode.count < $1.dialCode.count }
    }

    /// The number as it goes to the server. A number that carries its own code, and any Dutch number, stays as typed (the
    /// server side already reads Dutch national numbers); another country turns `0612…` into `+32612…`.
    func apply(to number: String) -> String {
        let typed = number.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !typed.isEmpty, self != PhoneCountry.netherlands, !typed.hasPrefix("+") else {
            return typed
        }

        if typed.hasPrefix("00") {
            return "+" + typed.dropFirst(2)
        }

        let national = typed.hasPrefix("0") ? String(typed.dropFirst()) : typed

        return "+" + dialCode + national.filter { $0.isNumber || $0 == " " }
    }
}

/// When a contact may be saved, and what is sent.
enum ContactEditRules {
    /// A number is enough (the contact is then named after it); a name needs a number or an e-mail address, because the server
    /// refuses a contact nobody can reach.
    static func canSave(_ draft: ContactDraft) -> Bool {
        draft.canSave || !draft.filledPhones.isEmpty
    }

    /// The draft as it is saved: numbers with their country code, and the first number as the name when there is none.
    static func prepared(_ draft: ContactDraft, countries: [UUID: PhoneCountry]) -> ContactDraft {
        var result = draft

        for index in result.phones.indices {
            let phone = result.phones[index]
            result.phones[index].number = (countries[phone.id] ?? .netherlands).apply(to: phone.number)
        }

        if result.displayName.isEmpty, let first = result.filledPhones.first {
            result.firstName = first.number.trimmingCharacters(in: .whitespacesAndNewlines)
        }

        return result
    }
}

/// The calls the phone remembers with a contact's numbers, newest first.
enum ContactRecentCalls {
    static func matching(_ calls: [RecentCall], phones: [String], limit: Int = 5) -> [RecentCall] {
        calls
            .filter { call in !call.number.isEmpty && phones.contains { PhoneNumberMatcher.same(call.number, $0) } }
            .sorted { $0.startedAt > $1.startedAt }
            .prefix(limit)
            .map { $0 }
    }
}
