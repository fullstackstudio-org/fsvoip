// swift-tools-version:5.9
// SPDX-License-Identifier: AGPL-3.0-or-later
import PackageDescription

// The package lives in `Contacts/`, but product and module are called `FSContacts`: anything named `Contacts` (module or framework
// product) hides Apple's Contacts framework (`CNContactStore`) from the sources that need it.
let package = Package(
    name: "Contacts",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "FSContacts", targets: ["FSContacts"]),
    ],
    dependencies: [
        .package(path: "../Core"),
    ],
    // Depends on Core only. Must never depend on LinphoneEngine / the SIP stack.
    targets: [
        .target(name: "FSContacts", dependencies: ["Core"]),
        .testTarget(name: "ContactsTests", dependencies: ["FSContacts", "Core"]),
    ]
)
