// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation

/// The JSON conventions of the FSVoip API: camelCase keys (no key strategy needed) and ISO-8601 timestamps
/// that the server writes with milliseconds (`2026-10-08T12:34:56.789Z`).
public enum FSVoipJSON {
    public static func decoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let text = try container.decode(String.self)

            if let date = parseTimestamp(text) {
                return date
            }

            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Invalid timestamp")
        }

        return decoder
    }

    public static func encoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(formatTimestamp(date))
        }
        // Stable output makes requests and stored JSON easy to compare in tests.
        encoder.outputFormatting = [.sortedKeys]

        return encoder
    }

    public static func parseTimestamp(_ text: String) -> Date? {
        let withFraction = ISO8601DateFormatter()
        withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]

        if let date = withFraction.date(from: text) {
            return date
        }

        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]

        return plain.date(from: text)
    }

    public static func formatTimestamp(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]

        return formatter.string(from: date)
    }
}
