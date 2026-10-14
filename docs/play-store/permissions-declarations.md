# Permissions and Play Console declarations

Texts for Play Console > App content (and Policy > App content > "Foreground service permissions", "Full-screen intent"). English, because the review is.

## Permission overview (manifest, release)

| Permission | Why | Declaration needed |
| --- | --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | SIP over TLS, API calls, network changes | no |
| `RECORD_AUDIO` | microphone during calls; recording a welcome message in the admin sound screen | runtime prompt (first call) |
| `CAMERA` | scan the pairing QR code (ZXing, no ML Kit); camera is optional (`required=false`), a pasted link works too | runtime prompt (only in the scanner) |
| `POST_NOTIFICATIONS` | incoming-call notification, missed calls, portal notices | runtime prompt (Android 13+) |
| `MANAGE_OWN_CALLS` | self-managed Telecom `ConnectionService` for VoIP calls | no (normal for VoIP apps) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_PHONE_CALL` | call service (`foregroundServiceType="phoneCall"`) | **yes**, see below |
| `USE_FULL_SCREEN_INTENT` | full-screen incoming call on a locked screen | **yes**, see below |
| `WAKE_LOCK`, `VIBRATE` | keep the CPU awake during a call, ring vibration | no |
| `READ_CONTACTS` | optional: recognise callers by the phone's own contacts, and copy a phone contact into the business address book; asked only when the user switches it on in Settings | not a restricted permission; mention in data safety |
| `USE_BIOMETRIC` (via AndroidX) | local check before admin screens | no |

Removed on purpose (manifest `tools:node="remove"`): `READ_CALL_LOG`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_DATA_SYNC`, `CHANGE_WIFI_MULTICAST_STATE`, `ACCESS_NOTIFICATION_POLICY`. The app never reads the phone's call log, SMS or location. `READ_PHONE_STATE`/`CALL_PHONE` are not used (self-managed calls).

## Foreground service declaration (type `phoneCall`)

Play Console > Policy > App content > Foreground service permissions > `FOREGROUND_SERVICE_PHONE_CALL`.

**Task description (paste):**

```
FSVoip is a VoIP calling app (business phone system). While a call is ringing or in progress, a foreground service of type phoneCall keeps the SIP connection and audio running and shows the ongoing-call notification, so the call continues when the user switches apps or locks the screen. The service uses the self-managed Telecom framework (ConnectionService, MANAGE_OWN_CALLS). It starts only when (1) the user places a call, (2) the user answers, or (3) a high-priority FCM message announces an incoming call (it must start within seconds, the call cannot wait for the user to open the app). It stops when the call ends. No other foreground service types are used.
```

**Video:** record on a phone: incoming call (debug broadcast or real call) with the app closed > ringing notification/full-screen > answer > ongoing-call notification > hang up. (To do on a real phone, Play wants a link, unlisted YouTube is fine.)

## Full-screen intent declaration

Play Console > App content > "Use of full-screen intents" (Android 14+: apps must be calling or alarm apps).

**Core functionality (paste):**

```
FSVoip is a calling app. Incoming business calls must ring like a normal phone call. When the phone is locked or the screen is off, the app shows its incoming-call screen (caller name or number, Answer and Decline) with a full-screen intent on a notification of the CATEGORY_CALL kind, started from the self-managed Telecom call. Without it the user would only see a heads-up notification on a locked phone and could miss a business call. Full-screen intents are used only for incoming calls, never for ads, promotions or reminders. If the user has revoked the permission (Android 14+) the app falls back to a high-priority call notification and Settings explains how to allow it again.
```

Category to select: "Calling" (VoIP). Android 14 grants `USE_FULL_SCREEN_INTENT` automatically to apps declared as calling apps; for others the user must allow it.

## Other declarations

- **Photos and videos / SMS / call log / location / accessibility**: none requested.
- **Ads**: no ads. **Advertising ID**: not used (declare "No").
- **Financial features, health, news, government**: none.
- **Content rating, target audience, data safety**: see their own files here.
- **Network security**: release builds allow only HTTPS; plain HTTP is allowed to localhost in debug builds only.
- **16 KB page size**: all native libraries (Linphone SDK, 4 ABIs) are aligned to 16 KB (checked with readelf, `0x4000`).
- **Target API 37**, minSdk 26.
- **Encryption / export**: uses standard TLS/SRTP; no proprietary crypto. (Play has no export form; US export law is covered by the standard exemption for mass-market software.)
