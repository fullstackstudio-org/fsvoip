# FSVoip for Android

Native Android app (Kotlin, Jetpack Compose), feature-equal to the iOS app: pairing by QR code or link, several
extensions in one app, registration over TLS, outgoing and incoming calls, and incoming calls also with the app closed
(FCM data message, self-managed Telecom call, full-screen notification). Licensed AGPL-3.0-or-later like the rest of
the repository (see `../LICENSE`, `../NOTICE`).

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
