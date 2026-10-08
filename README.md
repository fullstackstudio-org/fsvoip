# FSVoip

FSVoip is the softphone of **FullStack Studio**'s hosted telephony: customers pair a phone with an extension of
their PBX by scanning a QR code (or tapping a link) in the customer portal, and then make and receive calls with
the app, also when it is closed. iOS first (Swift, SwiftUI, CallKit, PushKit); Android follows later.

- Native apps, no cross-platform framework.
- Several accounts (extensions) in one app; the name of the dialled account is shown on the call screen.
- Open source under the **AGPL-3.0-or-later** (see [LICENSE](LICENSE), [NOTICE](NOTICE), [docs/licensing.md](docs/licensing.md)).
  The name and logo are not part of that licence ([TRADEMARKS.md](TRADEMARKS.md)).
- The server side of the FullStack Studio platform is closed source. The app only talks to it through the public API in
  [`shared/openapi.yaml`](shared/openapi.yaml).

> Status: foundation (Task 4). The app builds and shows the pairing screen and handles pairing links; pairing itself,
> registering and calling follow in the next steps.

## Repository layout

```
shared/        platform-neutral contract: OpenAPI 3.1, push payload JSON Schema, fixtures
ios/           the iOS app (Xcode project generated from project.yml)
  FSVoip/        app target: composition root, entitlements, assets
  Packages/      local Swift packages (see Architecture)
android/       later
scripts/       build.sh, check-imports.sh, validate-contract.ts
docs/          licensing notes
```

## Build

Requirements: a Mac with Xcode 26 or newer, [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`),
and, for the contract check, [Bun](https://bun.sh). No signing is needed for the simulator.

```sh
scripts/build.sh            # checks + generate + build + all tests on the iPhone 17 simulator
scripts/build.sh checks     # only the fast checks
```

`scripts/build.sh` does, in order: the architecture gate, the API fixture validation, the package tests on the Mac,
`xcodegen generate`, `xcodebuild -scheme FSVoip -destination 'generic/platform=iOS Simulator' build`, and the tests of
all packages on a simulator. Logs go to `build/logs/`. The first build downloads the Linphone SDK binaries (about
300 MB) through Swift Package Manager.

Run on a device: copy `ios/Local.xcconfig.example` to `ios/Local.xcconfig` (git-ignored) and set your team, then
`xcodegen generate` in `ios/` and open `ios/FSVoip.xcodeproj`. A fork must use its own team and bundle identifier.
Never commit `Local.xcconfig`, provisioning profiles, certificates or push keys.

## Architecture

```
FSVoip (app target, composition root)
 ├─ UI              SwiftUI screens, app model         -> Core, Pairing, Contacts
 ├─ Pairing         link parsing, pair flow            -> Core
 ├─ Contacts        contact sources (skeleton)         -> Core
 ├─ CallController  CallKit provider, audio hooks      -> SipEngine
 ├─ Core            API models + client, Keychain, redacting logger, push payloads
 ├─ SipEngine       protocol + our own value types, no dependencies
 └─ LinphoneEngine  SipEngine on linphone-sdk         -> SipEngine, linphone-sdk (Swift Package)
```

The rule that matters: **only `LinphoneEngine` may `import linphonesw`**. Everything else uses the `SipEngine`
protocol, so the SIP stack can be swapped (a BSD-licensed engine is the documented fallback) without touching the UI.
`scripts/check-imports.sh` enforces it (run by `scripts/build.sh` and in CI) and proves with a self-test that it
catches violations.

CallKit and the audio session belong to the app (`CallController`), not to the SIP stack: linphone's own CallKit and
push model are switched off.

## Pairing

1. The portal shows a QR code / link `https://fullstackstudio.nl/fsvoip/pair?t=<token>` (alternative
   `fsvoip://pair?t=<token>`); the token is single use and valid for 10 minutes.
2. The app exchanges it with `POST /pair` for a device token and the SIP credentials. The SIP password is returned
   once and is stored only in the Keychain.
3. The app registers with the PBX (domain, outbound proxy with SRV, TLS when the server says so).

The universal link needs the app's `applinks:fullstackstudio.nl` association and the
`apple-app-site-association` file on that domain (both are set up by FullStack Studio).

## Contract and tests

`shared/fixtures/*.json` are decoded by the Swift contract tests (`ios/Packages/Core/Tests`) and validated against
the schemas by `scripts/validate-contract.ts`. Details in [shared/README.md](shared/README.md).

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md).
