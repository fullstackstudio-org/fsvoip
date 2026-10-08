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

> Status: iOS MVP (Task 5). Pairing (QR camera, universal link, `fsvoip://`), several accounts, registration over TLS,
> outgoing calls and incoming calls **while the app is open**, in-call controls and per-account settings work. Incoming
> calls with the app closed (PushKit + the PBX push gate) follow in Task 6, contacts in Task 7.

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
FSVoip (app target, composition root, demo mode in DEBUG)
 ├─ UI              SwiftUI screens, app model         -> Core, Pairing, Contacts, SipEngine, CallController
 ├─ Pairing         link parsing, pair flow, account service (refresh / rename / unpair)   -> Core
 ├─ Contacts        contact sources (skeleton)         -> Core
 ├─ CallController  PhoneController (registrations + calls), CallKit, audio route   -> Core, SipEngine
 ├─ Core            API models + client, Keychain, install identity, preferences, recents, redacting logger
 ├─ SipEngine       protocol + our own value types, no dependencies
 └─ LinphoneEngine  SipEngine on linphone-sdk         -> SipEngine, linphone-sdk (Swift Package)
```

The rule that matters: **only `LinphoneEngine` may `import linphonesw`**. Everything else uses the `SipEngine`
protocol, so the SIP stack can be swapped (a BSD-licensed engine is the documented fallback) without touching the UI.
`scripts/check-imports.sh` enforces it (run by `scripts/build.sh` and in CI) and proves with a self-test that it
catches violations.

CallKit and the audio session belong to the app (`CallController`), not to the SIP stack: linphone's push model is
switched off. Every call, also one started from our own dialler or in-call screen, goes through a CallKit transaction
(`CallSystem` → `PhoneController` → `SipEngine`), so the lock screen and the app never disagree. In liblinphone the flag
`callkitEnabled = true` means exactly that ("the app owns the audio session through CallKit": the SDK waits for
`activateAudioSession` from `provider(_:didActivate:)`); the SDK has no CallKit UI of its own.

### How an account is registered

| | |
|---|---|
| Identity | `sip:<extension>@<domain>`; domain = `<slug>.powervoip.nl` (or a legacy `<slug>.pbx.fullstackstudio.nl`) |
| Outbound proxy | `sip.powervoip.nl`, via DNS SRV when the API says `srv: true` (failover pbx01/pbx02) |
| Transport | TLS (5061, certificate verified against the root CAs in linphone.framework) when the API says `tls`; otherwise TCP. An API answer of `udp` is registered over TCP (mobile NAT drops idle UDP) |
| SRTP | offered (optional) over TLS, off otherwise |
| Contact marker | `;fss-dev=<installId>` (the PBX push gate looks for it), User-Agent `FSVoip/<version> (<installId>)` |
| Expires | 120 s |
| Codecs | G.722, PCMA, PCMU (+ DTMF events) |

The SIP password and the device token live in the Keychain only (`AfterFirstUnlockThisDeviceOnly`); liblinphone runs
without a configuration file, so it never writes them to disk. Logs go through a redacting logger.

## Pairing

1. The portal shows a QR code / link `https://fullstackstudio.nl/fsvoip/pair?t=<token>` (alternative
   `fsvoip://pair?t=<token>`); the token is single use and valid for 10 minutes.
2. The app exchanges it with `POST /pair` for a device token and the SIP credentials. The SIP password is returned
   once and is stored only in the Keychain.
3. The app registers with the PBX (domain, outbound proxy with SRV, TLS when the server says so).

The universal link needs the app's `applinks:fullstackstudio.nl` association and the
`apple-app-site-association` file on that domain (both are set up by FullStack Studio).

## Try it in the simulator (demo mode)

DEBUG builds have a demo mode that needs no server and no phone system:

```sh
xcrun simctl launch booted nl.fullstackstudio.fsvoip -FSVoipDemo YES            # two example extensions
xcrun simctl launch booted nl.fullstackstudio.fsvoip -FSVoipDemo onboarding     # nothing paired yet
#   add -FSVoipDemoScreen <recents|settings|scanner|pairing|failed|incall|incoming> to open a screen directly
```

The demo uses an in-memory store, a fake SIP engine and a loop-back instead of CallKit; it writes nothing to the
Keychain and talks to no server. It is compiled out of Release builds.

## How to test on a device

What you need: an iPhone (iOS 16+), a Mac with Xcode 26+, membership of the Apple team `FDGV4X8F27`, and an extension
on a FullStack Studio phone system that the portal can pair ("FSVoip koppelen", Task 3).

1. `cp ios/Local.xcconfig.example ios/Local.xcconfig` (it sets `DEVELOPMENT_TEAM = FDGV4X8F27` and automatic signing),
   then `cd ios && xcodegen generate` and open `ios/FSVoip.xcodeproj`.
2. In Xcode: target FSVoip → Signing & Capabilities: check that the team is FullStack Studio and "Automatically manage
   signing" is on. Xcode creates the development profile for `nl.fullstackstudio.fsvoip` (Push Notifications, Associated
   Domains, Background Modes voip + audio are already in the entitlements). Choose your iPhone as the run destination
   and press Run. The first time, trust the developer on the phone (Settings → General → VPN & Device Management) and
   switch on Developer Mode if iOS asks.
3. Pair: in the portal open Telefonie → your device → FSVoip koppelen. Scan the QR code with the app (or open the link on
   the phone: the universal link only opens the app once the `apple-app-site-association` of fullstackstudio.nl lists the
   team; `fsvoip://pair?t=…` always works). Confirm with "Koppelen".
4. Check in the app: Settings shows the extension with a green light ("Verbonden"). If it stays orange/red: the account
   screen shows the reason (wrong password = the pairing was replaced on another phone; unreachable = network/TLS).
   On the PBX the registration shows `fss-dev=<installId>` in its contact and `FSVoip/<version>` as agent.
5. Test protocol for this version (app open): call a mobile number; call the extension from a mobile (the phone shows
   the CallKit call, answer it, then the in-call screen); mute, speaker, hold, keypad (e.g. a voicemail menu); pair a
   second extension and check that both are green and that "Toon welk toestel gebeld wordt" changes the CallKit
   label; switch wifi → 4G during idle and check the light turns green again within 30 s; unpair from the app and from
   the portal (the app removes the account at the next refresh).
6. Logs: Console.app on the Mac, filter on subsystem `nl.fullstackstudio.fsvoip` (secrets are redacted).

Not covered yet (Task 6): calls while the app is in the background or closed.

## Contract and tests

`shared/fixtures/*.json` are decoded by the Swift contract tests (`ios/Packages/Core/Tests`) and validated against
the schemas by `scripts/validate-contract.ts`. Details in [shared/README.md](shared/README.md).

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md).
