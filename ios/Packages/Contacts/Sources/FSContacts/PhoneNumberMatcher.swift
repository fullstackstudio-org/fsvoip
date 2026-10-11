// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation

/// Makes phone numbers comparable: `06 12 34 56 78`, `+31612345678`, `0031612345678`, `31612345678` and `sip:0612345678@x` are the same
/// number. Short numbers (extensions such as `101`, `112`, `*97`) are never turned into something longer and only match exactly.
public enum PhoneNumberMatcher {
    /// The country code a number without one is assumed to belong to (the Netherlands).
    public static let defaultCountryCode = "31"

    public struct Key: Equatable, Hashable, Sendable {
        /// The canonical form: `+31612345678`, or the bare digits of a short number (`101`).
        public let exact: String
        /// The last 9 digits, for the fallback when two forms of one number do not normalise alike (nil for short numbers).
        public let tail: String?
        /// The country code was assumed (the number came in national form, `0612345678`), not stated (`+32…`).
        public let isCountryAssumed: Bool
    }

    /// `nil` when the text holds no digits.
    public static func key(for raw: String, countryCode: String = defaultCountryCode) -> Key? {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)

        // `sip:0612345678@domain`, `tel:+31612345678;ext=1`
        for prefix in ["sips:", "sip:", "tel:"] where text.lowercased().hasPrefix(prefix) {
            text = String(text.dropFirst(prefix.count))
        }

        if let at = text.firstIndex(of: "@") { text = String(text[..<at]) }
        if let semicolon = text.firstIndex(of: ";") { text = String(text[..<semicolon]) }

        text = text.replacingOccurrences(of: "(0)", with: "")

        let hasPlus = text.hasPrefix("+")
        let digits = String(text.filter { $0.isASCII && $0.isNumber })

        guard !digits.isEmpty else {
            return nil
        }

        // Dialling codes with * and # (`*97`, `#31#`): not a phone number, compare as typed.
        if text.contains(where: { $0 == "*" || $0 == "#" }) {
            return Key(exact: String(text.filter { ($0.isASCII && $0.isNumber) || $0 == "*" || $0 == "#" }), tail: nil, isCountryAssumed: false)
        }

        var international: String?
        var assumed = false

        if hasPlus {
            international = digits
        } else if digits.hasPrefix("00"), digits.count > 4 {
            international = String(digits.dropFirst(2))
        } else if digits.hasPrefix("0"), digits.count >= 9 {
            international = countryCode + digits.dropFirst()
            assumed = true
        } else if digits.hasPrefix(countryCode), digits.count >= 11 {
            international = digits
        }

        guard var number = international else {
            // A short number or an extension: exact digits only, and a tail only when it is long enough to be a subscriber number.
            return Key(exact: digits, tail: digits.count >= 9 ? String(digits.suffix(9)) : nil, isCountryAssumed: true)
        }

        // `+31 0 70 ...`: the trunk zero does not belong after the country code.
        if number.hasPrefix(countryCode + "0"), number.count >= countryCode.count + 10 {
            number = countryCode + number.dropFirst(countryCode.count + 1)
        }

        return Key(exact: "+" + number, tail: number.count >= 9 ? String(number.suffix(9)) : nil, isCountryAssumed: assumed)
    }

    /// The `+…` form of a number, or `nil` when it is not a subscriber number (short numbers, codes). Used before sending a number to the server.
    public static func e164(for raw: String, countryCode: String = defaultCountryCode) -> String? {
        guard let key = key(for: raw, countryCode: countryCode), key.exact.hasPrefix("+") else {
            return nil
        }

        return key.exact
    }

    /// Whether two numbers are the same number (exact match of the canonical form).
    public static func same(_ lhs: String, _ rhs: String) -> Bool {
        guard let left = key(for: lhs), let right = key(for: rhs) else {
            return false
        }

        return left.exact == right.exact
    }
}
