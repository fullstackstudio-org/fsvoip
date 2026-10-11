// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Helpers for the "responses lenient, requests strict" rule of the FSVoip API:
//
// - A response enum decodes an unknown value to its `.unknown` case instead of failing the whole answer, so a later
//   server release that adds a value cannot break an installed app.
// - The same enum refuses to ENCODE `.unknown`: it is a decoding fallback, never something the app may send.
// - Optional request fields have three states (`Change`): leave out (unchanged), `null` (clear) or a value.

import Foundation

/// A string enum of the API with a fallback case for values this app version does not know.
public protocol WireEnum: RawRepresentable, Codable, Sendable, Equatable where RawValue == String {
    static var unknownValue: Self { get }
}

extension WireEnum {
    public static func decodeTolerant(from decoder: Decoder) throws -> Self {
        let raw = try decoder.singleValueContainer().decode(String.self)

        return Self(rawValue: raw) ?? Self.unknownValue
    }

    public func encodeStrict(to encoder: Encoder) throws {
        guard self != Self.unknownValue else {
            throw EncodingError.invalidValue(self, EncodingError.Context(codingPath: encoder.codingPath, debugDescription: "`unknown` is a decoding fallback and cannot be sent"))
        }

        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }

    /// Whether the server's value was one this app version knows.
    public var isKnown: Bool {
        self != Self.unknownValue
    }
}

/// A field of a PATCH body: `keep` leaves the key out, `clear` sends an explicit `null`, `set` sends the value.
public enum Change<Value: Equatable & Sendable>: Equatable, Sendable {
    case keep
    case clear
    case set(Value)

    /// `nil` clears, a value sets.
    public init(_ optional: Value?) {
        self = optional.map(Change.set) ?? .clear
    }

    public var isKept: Bool {
        self == .keep
    }
}

extension KeyedEncodingContainer {
    public mutating func encodeChange<Value: Encodable>(_ change: Change<Value>, forKey key: Key) throws {
        switch change {
        case .keep:
            break
        case .clear:
            try encodeNil(forKey: key)
        case let .set(value):
            try encode(value, forKey: key)
        }
    }
}
