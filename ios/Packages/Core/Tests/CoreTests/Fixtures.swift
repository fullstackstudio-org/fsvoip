// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation

/// Loads `shared/fixtures/*.json` straight from the repository (the same files the Android app tests will use).
enum Fixtures {
    static let directory: URL = {
        // .../ios/Packages/Core/Tests/CoreTests/Fixtures.swift -> repo root is 6 levels up from this file.
        var url = URL(fileURLWithPath: #filePath)

        for _ in 0 ..< 6 {
            url.deleteLastPathComponent()
        }

        return url.appendingPathComponent("shared/fixtures", isDirectory: true)
    }()

    static func data(_ name: String) throws -> Data {
        try Data(contentsOf: directory.appendingPathComponent("\(name).json"))
    }

    static func names() throws -> [String] {
        try FileManager.default.contentsOfDirectory(atPath: directory.path)
            .filter { $0.hasSuffix(".json") }
            .map { String($0.dropLast(5)) }
            .sorted()
    }
}
