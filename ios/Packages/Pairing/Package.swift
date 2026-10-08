// swift-tools-version:5.9
// SPDX-License-Identifier: AGPL-3.0-or-later
import PackageDescription

let package = Package(
    name: "Pairing",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "Pairing", targets: ["Pairing"]),
    ],
    dependencies: [
        .package(path: "../Core"),
    ],
    // Depends on Core only. Must never depend on LinphoneEngine / the SIP stack.
    targets: [
        .target(name: "Pairing", dependencies: ["Core"]),
        .testTarget(name: "PairingTests", dependencies: ["Pairing", "Core"]),
    ]
)
