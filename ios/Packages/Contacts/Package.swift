// swift-tools-version:5.9
// SPDX-License-Identifier: AGPL-3.0-or-later
import PackageDescription

let package = Package(
    name: "Contacts",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "Contacts", targets: ["Contacts"]),
    ],
    dependencies: [
        .package(path: "../Core"),
    ],
    // Depends on Core only. Must never depend on LinphoneEngine / the SIP stack.
    targets: [
        .target(name: "Contacts", dependencies: ["Core"]),
        .testTarget(name: "ContactsTests", dependencies: ["Contacts", "Core"]),
    ]
)
