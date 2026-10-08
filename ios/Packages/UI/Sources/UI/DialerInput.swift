// SPDX-License-Identifier: AGPL-3.0-or-later
import CallController
import Foundation

/// The number being typed on the keypad.
public struct DialerInput: Equatable, Sendable {
    public private(set) var number = ""

    public init(number: String = "") {
        self.number = DialNumber.sanitize(number)
    }

    public var isEmpty: Bool {
        number.isEmpty
    }

    public var canCall: Bool {
        DialNumber.isDialable(number)
    }

    /// A key press: digits, `*`, `#`; `+` only as the first character.
    public mutating func press(_ key: Character) {
        guard number.count < DialNumber.maxLength else {
            return
        }

        if key == "+" {
            if number.isEmpty {
                number = "+"
            }

            return
        }

        guard key.isASCII, key.isNumber || key == "*" || key == "#" else {
            return
        }

        number.append(key)
    }

    /// Long press on 0: `+` at the start, otherwise a 0.
    public mutating func longPressZero() {
        if number.isEmpty {
            number = "+"
        } else {
            press("0")
        }
    }

    public mutating func deleteLast() {
        if !number.isEmpty {
            number.removeLast()
        }
    }

    public mutating func clear() {
        number = ""
    }

    /// Paste replaces what was typed with the cleaned text.
    public mutating func paste(_ text: String) {
        number = DialNumber.sanitize(text)
    }
}

/// Letters under the keypad digits.
enum KeypadLetters {
    static func letters(for key: Character) -> String {
        switch key {
        case "2": return "ABC"
        case "3": return "DEF"
        case "4": return "GHI"
        case "5": return "JKL"
        case "6": return "MNO"
        case "7": return "PQRS"
        case "8": return "TUV"
        case "9": return "WXYZ"
        case "0": return "+"
        default: return ""
        }
    }
}
