// swift-tools-version:5.9
// SPDX-License-Identifier: AGPL-3.0-or-later
import PackageDescription

let package = Package(
    name: "CallController",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "CallController", targets: ["CallController"]),
    ],
    dependencies: [
        .package(path: "../SipEngine"),
    ],
    // Depends on the SipEngine protocol package only. Must never depend on LinphoneEngine / the SIP stack.
    targets: [
        .target(name: "CallController", dependencies: ["SipEngine"]),
        .testTarget(name: "CallControllerTests", dependencies: ["CallController", "SipEngine"]),
    ]
)
