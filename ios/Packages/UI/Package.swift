// swift-tools-version:5.9
// SPDX-License-Identifier: AGPL-3.0-or-later
import PackageDescription

let package = Package(
    name: "UI",
    defaultLocalization: "nl",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "UI", targets: ["UI"]),
    ],
    dependencies: [
        .package(path: "../Core"),
        .package(path: "../Pairing"),
        .package(path: "../Contacts"),
    ],
    // SwiftUI screens. Depends on Core, Pairing and Contacts only, never on LinphoneEngine / the SIP stack.
    targets: [
        .target(
            name: "UI",
            dependencies: ["Core", "Pairing", "Contacts"],
            resources: [.process("Resources")]
        ),
        .testTarget(name: "UITests", dependencies: ["UI", "Core", "Pairing"]),
    ]
)
