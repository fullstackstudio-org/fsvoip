// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation

/// The number a user typed or pasted, cleaned for dialling.
public enum DialNumber {
    public static let maxLength = 32

    /// Keeps digits, `*`, `#` and a leading `+`; drops spaces, dashes, dots, brackets and anything else
    /// (a pasted "+31 (0)70-123 45 67" becomes "+31701234567": the "(0)" trunk prefix is removed too).
    public static func sanitize(_ input: String) -> String {
        var text = input.replacingOccurrences(of: "(0)", with: "")
        // Map full-width and other Unicode digits to ASCII.
        text = text.applyingTransform(.fullwidthToHalfwidth, reverse: false) ?? text

        var result = ""

        for character in text {
            if character.isASCII, character.isNumber || character == "*" || character == "#" {
                result.append(character)
            } else if character == "+", result.isEmpty {
                result.append(character)
            }
        }

        return String(result.prefix(maxLength))
    }

    /// Digits, `+` (first only), `*`, `#`, 1 to 32 characters. Anything else could smuggle SIP syntax into the request URI.
    public static func isDialable(_ number: String) -> Bool {
        guard (1 ... maxLength).contains(number.count), number != "+" else {
            return false
        }

        for (index, character) in number.enumerated() {
            guard character.isASCII else {
                return false
            }

            if character == "+" {
                if index != 0 {
                    return false
                }
            } else if !(character.isNumber || character == "*" || character == "#") {
                return false
            }
        }

        return true
    }
}
