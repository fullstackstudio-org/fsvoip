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
        .package(path: "../SipEngine"),
        .package(path: "../CallController"),
    ],
    // SwiftUI screens. Depends on Core, Pairing, Contacts, the SipEngine protocol and CallController only, never on
    // LinphoneEngine / the SIP stack.
    targets: [
        .target(
            name: "UI",
            dependencies: ["Core", "Pairing", "Contacts", "SipEngine", "CallController"],
            resources: [.process("Resources")]
        ),
        .testTarget(name: "UITests", dependencies: ["UI", "Core", "Pairing", "SipEngine", "CallController"]),
    ]
)
