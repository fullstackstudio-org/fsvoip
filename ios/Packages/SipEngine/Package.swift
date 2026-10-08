// swift-tools-version:5.9
// SPDX-License-Identifier: AGPL-3.0-or-later
import PackageDescription

let package = Package(
    name: "SipEngine",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "SipEngine", targets: ["SipEngine"]),
    ],
    // 🚨 No dependencies, on purpose: this package is the strict boundary between the app and whichever SIP stack
    // sits behind it (linphone-sdk now, baresip as the documented fallback). It holds the protocol and our own
    // value types only. `import linphonesw` is only allowed in the `LinphoneEngine` package.
    targets: [
        .target(name: "SipEngine"),
        .testTarget(name: "SipEngineTests", dependencies: ["SipEngine"]),
    ]
)
