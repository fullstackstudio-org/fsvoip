// swift-tools-version:5.9
// SPDX-License-Identifier: AGPL-3.0-or-later
import PackageDescription

let package = Package(
    name: "Core",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "Core", targets: ["Core"]),
    ],
    targets: [
        // Platform-neutral building blocks: API models and client, Keychain storage, redacting logger.
        // Must never depend on a SIP stack.
        .target(name: "Core"),
        .testTarget(name: "CoreTests", dependencies: ["Core"]),
    ]
)
