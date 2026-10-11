// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation

/// An in-memory index from phone number to contact: one dictionary lookup, no scanning (a caller's name must be there within 50 ms
/// even for 25,000 contacts).
///
/// Entries are given in priority order; for one number the first entry wins.
public struct ContactIndex: Sendable {
    private struct TailCandidate: Sendable {
        let entryIndex: Int
        let isAssumed: Bool
    }

    private let entries: [ContactEntry]
    private let exact: [String: Int]
    private let tails: [String: [TailCandidate]]

    public static let empty = ContactIndex(entries: [])

    public init(entries: [ContactEntry]) {
        self.entries = entries

        var exact: [String: Int] = [:]
        var tails: [String: [TailCandidate]] = [:]

        for (index, entry) in entries.enumerated() {
            for phone in entry.phones {
                guard let key = PhoneNumberMatcher.key(for: phone.number) else {
                    continue
                }

                if exact[key.exact] == nil {
                    exact[key.exact] = index
                }

                if let tail = key.tail {
                    tails[tail, default: []].append(TailCandidate(entryIndex: index, isAssumed: key.isCountryAssumed))
                }
            }
        }

        self.exact = exact
        self.tails = tails
    }

    public var count: Int {
        entries.count
    }

    public func entry(forNumber number: String) -> ContactEntry? {
        guard let key = PhoneNumberMatcher.key(for: number) else {
            return nil
        }

        if let index = exact[key.exact] {
            return entries[index]
        }

        // Fallback on the last 9 digits. Only a subscriber number gets here, and only when every candidate is the same person:
        // two different people with the same last digits (another country, another operator) answer "unknown".
        guard let tail = key.tail, let candidates = tails[tail] else {
            return nil
        }

        // A number that STATES its country (`+32…`) may only fall back on stored numbers whose country was assumed too.
        let usable = candidates.filter { key.isCountryAssumed || $0.isAssumed }
        let names = Set(usable.map { entries[$0.entryIndex].displayName.lowercased() })

        guard names.count == 1, let first = usable.map(\.entryIndex).min() else {
            return nil
        }

        return entries[first]
    }

    public func name(forNumber number: String) -> String? {
        guard let entry = entry(forNumber: number) else {
            return nil
        }

        let name = entry.displayName

        return name.isEmpty ? nil : name
    }
}
