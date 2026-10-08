// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation

/// A string that must never end up in a log, a crash report or a debugger print-out.
///
/// `description`, `debugDescription` and the reflection mirror all hide the value; the only way to read it
/// is the explicit `reveal()`. It encodes to its real value because it has to be stored (Keychain JSON) and
/// sent (request bodies).
public struct Secret: Codable, Hashable, Sendable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    private let value: String

    public init(_ value: String) {
        self.value = value
    }

    public func reveal() -> String {
        value
    }

    public var isEmpty: Bool {
        value.isEmpty
    }

    public var description: String {
        "•••"
    }

    public var debugDescription: String {
        "Secret(•••)"
    }

    public var customMirror: Mirror {
        Mirror(self, children: [], displayStyle: .struct)
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        value = try container.decode(String.self)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(value)
    }
}
