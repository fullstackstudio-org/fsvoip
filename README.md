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

> Status: iOS, Task 6. Pairing (QR camera, universal link, `fsvoip://`), several accounts, registration over TLS,
> outgoing and incoming calls, in-call controls and per-account settings work, and incoming calls also ring **with the
> app in the background or closed** (PushKit + CallKit + the PBX push gate). Contacts follow in Task 7.

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

For calls with the app in the background or closed, see the device test protocol below.

## Incoming calls with the app closed

```
caller ─► PBX push gate (FssApi ≥ 1.8.0) ─► FSS /push/ring ─► APNs VoIP push ─► FSVoip (PushKit callback)
            │ waits ≤ 6 s for a FRESH registration                               1. CallKit call reported at once
            │ whose contact holds ;fss-dev=<installId>                           2. REGISTER (Expires 120, fss-dev)
            └──────────── INVITE with X-FSS-Call: <callRef> ◄──────────────────  3. INVITE joins the reported call
```

- **Push payload** (`shared/push-payload.schema.json`): `fsvoip: { v, type: "ring", callRef, from, accountId,
  accountLabel, expiresAt }`, `apns-push-type: voip`, topic `nl.fullstackstudio.fsvoip.voip`.
- **Every VoIP push is reported to CallKit inside the PushKit callback** (Apple's rule; iOS stops delivering VoIP pushes
  to an app that does not). A push the app cannot use is still reported and ended at once: unreadable, expired
  (`expiresAt` + 5 s clock tolerance), for an account that is no longer on the phone, or while another call is going on
  (its INVITE then gets 486).
- **CallKit UUID = the callRef** (the FreeSWITCH call UUID), so the push and the INVITE meet on the same call whichever
  arrives first. An INVITE without `X-FSS-Call` joins a waiting call of the same account and caller.
- **Call screen text** (plan D10): `<caller>`, or `<caller> → <account>` when "Toon welk toestel gebeld wordt" is on (on by
  default with several accounts). The caller name comes from the phone's own contacts first, then the push.
- **Waiting for the INVITE**: up to 10 s (never long past `expiresAt`). Nothing came (the caller hung up first, the gate
  gave up): the call ends as missed. Answered on the lock screen before the INVITE arrived: CallKit's answer is accepted
  straight away and the INVITE is answered the moment it comes; the audio only starts in `provider(_:didActivate:)`.
  Declined before the INVITE arrived: the late INVITE gets 603.
- **Background registration**: in the background without a call the app un-registers (no battery drain, no dead contact
  on the PBX) and relies on push. A push wakes only the pushed account, on a new connection (the old one died while iOS
  suspended the app). After the call it goes quiet again; in the foreground all accounts register.
- **Push tokens**: the PushKit token and the regular APNs token go to FSS with `PUT /push-token` for every account, at
  every launch and whenever a token changes (`pushEnv` = `sandbox` for Debug builds, `production` for Release /
  TestFlight / App Store, set by `FSVOIP_PUSH_ENV` in `ios/Config/*.xcconfig`). Before PushKit answered nothing is sent.
- **"Unpaired" notice**: a regular alert push (never a VoIP push) to the APNs token; the app removes the account at once
  (also in the background, via `remote-notification`). Notification permission is asked after the first pairing; the
  account is removed without it too (and otherwise at the next `GET /me` with 401).

Simulator: PushKit delivers nothing there. The push logic is unit tested with the fixtures (`CallControllerTests`), the
demo mode shows the flow (`-FSVoipDemo YES -FSVoipDemoScreen push`), and the "unpaired" notice can be sent to a simulator
that runs a real (non-demo) build with a paired account:

```sh
xcrun simctl push booted nl.fullstackstudio.fsvoip shared/fixtures/apns-alert-body.json
```

## Device test protocol: incoming calls with the app closed (Task 6)

Before you start: FSS has the APNs key in the vault (`voip-app-push`), the PBX runs FssApi ≥ 1.8.0 with the push gate on
both nodes, and the extension's push gate is set. Install with Xcode (Debug = sandbox push; a TestFlight build =
production push). Pair the extension with the app and open it once: the device's push status in the admin (App-koppelingen)
or `GET /me` (`push.registered`) must show a token (the app reports it at launch).

Use a second phone (any mobile) as the caller. For every step note the time from dialling to the call screen (target
≤ 8 s) and what the PBX sees (`sofia status profile internal reg` shows `fss-dev=<installId>` while the call rings).

1. **App in the background, phone unlocked**: open FSVoip, go to the home screen, wait 30 s. Call the extension. The
   CallKit screen shows `<caller>` (or `<caller> → <account>` with the setting on). Answer: audio both ways. Hang up
   from either side.
2. **Locked screen**: lock the phone, wait 2 minutes (iOS suspends the app, the registration expires). Call. The full
   screen call UI appears; answer with the slider before the screen is fully up: the call connects and audio starts.
3. **App killed**: swipe FSVoip away in the app switcher. Call. The call screen must still appear (iOS launches the app
   in the background for the push). Answer: audio both ways.
4. **Decline**: call, press decline on the call screen. The caller hears "busy/declined" right away (603), not voicemail
   after a timeout. The app's recents show the call as declined.
5. **Caller hangs up first**: call and hang up after 3 s, before answering. The call screen disappears within 12 s and
   the recents show a missed call.
6. **Ring group**: put the app extension and a second device (a desk phone or Groundwire) in a ring group "allemaal
   tegelijk". Call the group's number with the app closed: both ring; the other device starts at most ~6 s later. Answer
   on the app. Repeat with the group "Iedereen" (the default entrance) and with a queue that has the app as agent.
7. **Second call within 120 s**: right after test 1 call again: it rings and connects.
8. **Two accounts**: pair a second extension. Call each one with the app closed: the call screen shows which account is
   called (`→ <label>`); answering connects the right line.
9. **Unpaired**: remove the pairing in the portal. With notifications allowed a notice appears and the account is gone
   from the app (also when the app was in the background). Calling the extension no longer rings the app.
10. **Logs** (Console.app, subsystem `nl.fullstackstudio.fsvoip`, categories `phone` and `push`): per call you see "Push
    for call <callRef> … ringing" and "INVITE joined to call <callRef>"; "No INVITE … in time" means the gate did not
    see a fresh registration (check the contact marker and the network).

If a step fails: check the device's push status in the portal (an invalid token after reinstalling: open the app once),
that the build's push environment matches its signing (`FSVOIP_PUSH_ENV`), and the PBX log for the gate's decision.

## Contract and tests

`shared/fixtures/*.json` are decoded by the Swift contract tests (`ios/Packages/Core/Tests`) and validated against
the schemas by `scripts/validate-contract.ts`. Details in [shared/README.md](shared/README.md).

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md).
