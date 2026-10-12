# FSVoip for Android

Native Android app (Kotlin, Jetpack Compose), feature-equal to the iOS app: pairing by QR code or link, several
extensions in one app, registration over TLS, outgoing and incoming calls, and incoming calls also with the app closed
(FCM data message, self-managed Telecom call, full-screen notification). Licensed AGPL-3.0-or-later like the rest of
the repository (see `../LICENSE`, `../NOTICE`).

## Status (branch `feature/android-v2`)

Feature parity with iOS 1.0 (plan `fsvoip-app-v2`), built and unit tested; **not yet released**. Everything below works against the
mock of the API (see "Testing without production") on the emulator; calls, push and the manufacturer behaviour still need a real phone.

| Area | What there is |
| --- | --- |
| Pairing | QR scanner, App Link, `fsvoip://`, several accounts; role and capabilities from `GET /me` (loaded right after pairing) |
| Calls | dialler with the number to call out with (`X-FSS-From`), incoming (Telecom, full-screen), in-call controls, parking and the "On hold" tab |
| Live calls | Section "Live gesprekken" in the "On hold" tab (a switch when parking is offered too), only when `capabilities.live`: running calls polled every 3 s while the screen is visible (stops on background / leaving the tab), take a ringing call over to the own extension (`canPickup`), blind transfer to an internal target (`canTransfer`); who may is decided by the server per role, `live_unavailable` = hidden. No demo mode on Android. |
| History / voicemail | team history per month, voicemail with the player (ExoPlayer, Bearer header, `Range`); an administrator can play recordings |
| Contacts | sync with cursor / `serverTime` / tombstones, lists with ETag, name recognition; create, edit (`expectedUpdatedAt`), delete (administrators), favourites (this phone only), copy a contact of the phone into the address book, activity of a contact |
| Customer card | at an incoming call: name and "Klant in website: ..." from `GET /contacts/lookup`, at most one second, never in the way of the call |
| Settings | Profile (own extension, e-mail), call preferences; for administrators: Phone system (call flow), numbers (per-number chain: hours, welcome, forwarding, recording, name), devices, ring groups, opening hours with "temporarily closed", sounds (listen, record, upload, rename, delete), invite a colleague (QR + share link), recordings |
| Local check | every administrator screen, recordings and the invite link sit behind a biometric / screen-lock check (5 minutes), like Face ID on iOS; a phone without a screen lock does not offer them |
| Portal notices | a `notice` push becomes a notification; a tap opens the tab its link maps to (`NoticeRoute`). There is no API to fetch notices when the app opens, so nothing is polled |

What iOS has and Android does not: a country picker for numbers typed in national form in the contact form (numbers with `+` or `00`
work; a number without is read as Dutch), and "Alle voicemail" for administrators (the own box only).

## What is still needed before a release

1. **Firebase project and FCM** (see below): create the project, add the Android app, put `google-services.json` in `android/app/`
   (git-ignored), and store the **service account JSON** in the FSS vault (item tag `voip-app-push`, field `fcm_service_account_json`).
   Without it calls only ring while the app is open and no portal notice arrives.
2. **Play Console**: create the app `nl.fullstackstudio.fsvoip`, enrol in Play App Signing, upload an AAB (`bundleRelease`, release keystore
   outside the repository), internal testing track, privacy form (microphone, contacts optional, notifications), data safety section.
3. **`assetlinks.json`** on `https://fullstackstudio.nl/.well-known/assetlinks.json` with the **SHA-256 of the Play App Signing key** (and of the
   upload/debug key for testing), see "App Links". Until then the pairing link opens the browser.
4. **Test on a real phone** (list below) against the real PBX: FCM wake-up with the app closed, full-screen call on a locked screen,
   Doze, battery savers, Bluetooth/car, registration over TLS, the biometric prompt on a phone with a fingerprint, the audio recorder
   on real hardware, and a `notice` push end to end.
5. Raise `versionCode` / `versionName` (now 1 / 1.0) and sign.

## Build

Requirements: the Android SDK (platform 37) and a JDK 17 or newer. Android Studio's bundled JDK works:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # macOS, if JAVA_HOME is not set
cd android
./run-checks.sh                 # = ./gradlew assembleDebug testDebugUnitTest
./gradlew :app:installDebug     # install on a running emulator or a connected phone
```

Put `sdk.dir=/path/to/Android/sdk` in `android/local.properties` (git-ignored) when `ANDROID_HOME` is not set; Android
Studio writes it on first open. The first build downloads the Linphone SDK (about 120 MB) from
`https://download.linphone.org/maven_repository`.

CI (`../.github/workflows/android.yml`) runs the import check, `assembleDebug testDebugUnitTest` and checks that the APK
contains no AMR or OpenH264 library. It needs no secrets and signs nothing.

## Modules

```
app             composition root, Compose UI, AppModel, call service + notifications, FCM service
 ├─ pairing         pairing link parser, pair flow, account service (refresh / rename / unpair), push-token reporter
 ├─ contacts        name lookup: internal contacts of the PBX, optionally the phone's contacts
 ├─ callcontroller  PhoneController (registrations + calls, same rules as iOS), Telecom ConnectionService
 ├─ core            API models + client (OkHttp), Keystore secret store, preferences, recents, redacting logger
 ├─ sipengine       SipEngine interface + our own value types, no dependencies
 └─ linphoneengine  SipEngine on linphone-sdk (org.linphone.no-video:linphone-sdk-android:5.5.29)
```

`scripts/check-imports.sh` (repository root) enforces the SIP boundary: only `linphoneengine` imports `org.linphone`,
only the `app` module (composition root) depends on `linphoneengine`, and only `linphoneengine` declares the SDK.

The contract tests in `core` parse every file in `../shared/fixtures` (and fail when a fixture is not used).

## Firebase (push while the app is closed)

The app builds and runs **without** Firebase: it then registers only while it is open, and Settings says that push is
not set up. To build with push:

1. Create (or open) the Firebase project and add an Android app with package `nl.fullstackstudio.fsvoip`.
2. Download `google-services.json` and put it in `android/app/google-services.json`. It is git-ignored; never commit it.
3. Build again. The Google Services plugin is applied only when that file exists, and `BuildConfig.FIREBASE_CONFIGURED`
   becomes true. The app sends its FCM token with `PUT /push-token` (`pushKind: "fcm"`).

The FSS side needs the matching service account (vault item with `fcm_service_account_json`, tag `voip-app-push`).

## Release signing

Debug builds use the standard debug key. For a release build, create a keystore outside the repository and a
git-ignored `android/keystore.properties`:

```properties
storeFile=/absolute/path/to/fsvoip-release.jks
storePassword=...
keyAlias=fsvoip
keyPassword=...
```

Then `./gradlew :app:bundleRelease` (prefer the AAB: the APK with all ABIs is large because of the native SDK). Without
`keystore.properties` a release build is unsigned. Never commit keystores, `*.jks` or the properties file.

## App Links (`https://fullstackstudio.nl/fsvoip/pair?t=...`)

The manifest asks for auto-verification of that path. Android only opens the app directly when
`https://fullstackstudio.nl/.well-known/assetlinks.json` lists the package with the **SHA-256 fingerprint of the
signing certificate** (the Play App Signing key when the app is distributed through Play, plus the upload/debug key for
testing). Get it with `keytool -list -v -keystore <keystore>` or from the Play Console. Until then the link opens the
pair page in the browser, and `fsvoip://pair?t=...` and the in-app QR scanner work regardless.

## Testing without production

Never pair against production while developing. Point a debug build at a local mock of `/api/voip-app/v1`:

```sh
adb reverse tcp:8787 tcp:8787
./gradlew :app:installDebug -Pfsvoip.apiBaseUrl=http://127.0.0.1:8787/api/voip-app/v1
adb shell am start -a android.intent.action.VIEW -d "fsvoip://pair?t=fss_vpair_<43 characters>"
```

Use `127.0.0.1` through `adb reverse`, not the emulator alias `10.0.2.2`: on Android 17 (target SDK 37) an app cannot
reach that private host address (connections time out), while `adb shell` can. Debug builds allow plain HTTP only to
`127.0.0.1`, `localhost` and `10.0.2.2` (`app/src/debug/res/xml/network_security_config.xml`).

Debug builds also have a receiver that handles a push exactly like the FCM service does, so the incoming-call path
(Telecom, foreground service, call screen) can be tried without Firebase. Keep the app in the foreground (Android does
not allow a foreground service to start from a background broadcast) and use an `expiresAt` in the future:

```sh
adb shell "am broadcast -a nl.fullstackstudio.fsvoip.DEBUG_PUSH \
  -n nl.fullstackstudio.fsvoip/.debug.DebugPushReceiver --es fsvoip '<push JSON, see shared/fixtures/push-ring.json>'"
```

Without a SIP INVITE the call ends as missed after the waiting time, like on iOS.

For a quick look at the screens without a PBX, serve the files of `../shared/fixtures` from a tiny local server (one route per fixture,
e.g. `/me` -> `me-response-admin-v2.json`) on port 8787, `adb reverse` it and pair as above. Give that server an idle timeout
of a few minutes (Bun's default of 10 seconds closes the connection the app keeps open and the next request fails once), and put the
emulator in airplane mode: the paired account tries to register at `sip.powervoip.nl` with the fixture password, which is a request to
production with a made-up login. The system credential prompt is a secure window: screenshots of it are black, type the PIN blind.

## Only testable on a real phone

- FCM delivery while the app is closed or the phone is in Doze (high-priority data message, foreground service start).
- The full-screen incoming call on a locked screen, and the Android 14+ "full-screen notifications" permission.
- Manufacturer battery savers (Samsung, Xiaomi, Huawei, OnePlus) that stop the app; Settings links to the right screen.
- Telecom behaviour of the manufacturer (car kits, Bluetooth headsets, calls next to a GSM call) and audio routing.
- Registration and calls against the real PBX (TLS on `sip.powervoip.nl`, SRTP, codecs, the push gate).

## Privacy and secrets

The SIP password and device token are encrypted with an AES-GCM key in the Android Keystore; the ciphertext lives in
app-private storage, which is excluded from backups (`allowBackup=false`, data extraction rules exclude everything).
Logs never contain passwords, tokens, push tokens or pairing codes (`LogRedactor`). The app asks for no call log
permission; contacts are optional and stay on the phone.
