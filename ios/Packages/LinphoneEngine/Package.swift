// swift-tools-version:5.9
// SPDX-License-Identifier: AGPL-3.0-or-later
import PackageDescription

let package = Package(
    name: "LinphoneEngine",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "LinphoneEngine", targets: ["LinphoneEngine"]),
    ],
    dependencies: [
        .package(path: "../SipEngine"),
        // Belledonne's official Swift Package of linphone-sdk, pinned to an exact version. The `-novideo` variant is
        // audio-only (FSVoip does not do video) and a lot smaller. linphone-sdk is AGPL-3.0 (see NOTICE).
        .package(url: "https://gitlab.linphone.org/BC/public/linphone-sdk-swift-ios.git", exact: "5.5.29-novideo"),
    ],
    targets: [
        // 🚨 The ONLY target allowed to `import linphonesw` (enforced by scripts/check-imports.sh).
        .target(
            name: "LinphoneEngine",
            dependencies: [
                "SipEngine",
                .product(name: "linphonesw", package: "linphone-sdk-swift-ios"),
            ]
        ),
        .testTarget(name: "LinphoneEngineTests", dependencies: ["LinphoneEngine", "SipEngine"]),
    ]
)
