# Data safety form (Play Console > App content > Data safety)

Consistent with the iOS App Privacy labels (device ID, audio, other user content; linked to the user; app functionality; no tracking). Answers are what the app actually does (source: `android/README.md` "Privacy and secrets", the API in `shared/openapi.yaml`). Re-read the "Check before submitting" list at the bottom.

## Overview questions

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (HTTPS to the API, SIP over TLS with SRTP) |
| Which account creation methods does your app support? | **None**: no accounts in the app, a device is paired with a pairing code from the portal |
| Do you provide a way for users to request that their data is deleted? | **Yes**: the user unpairs the device in the app (the device token is revoked and the SIP password renewed); data of the phone system is deleted by the company/administrator or on request to info@fullstackstudio.nl. Deletion URL to enter: `https://fullstackstudio.nl/privacybeleid` (mention the process there) |
| Data shared with third parties | **No** (see below: Google FCM only carries a push token and the call alert) |

## Data types

For every type: "Collected" = yes, "Shared" = no, processing is **not optional** unless noted, purpose is **App functionality** only, not used for advertising, analytics or personalisation. All of it is **linked** to the user (the extension).

| Play category | Type | Collected | Why / where it comes from |
| --- | --- | --- | --- |
| Personal info | **Name** | Yes | name of the extension and of contacts/callers in the business address book (shown in the app) |
| Personal info | **Phone number** | Yes | numbers of callers, called numbers and contact numbers (call history, address book) |
| Contacts | **Contacts** | Yes | the company address book stored on the phone-system server and synced to the app. The phone's own contacts are NOT uploaded; they are read on the device only when the user switches name recognition on, and only a contact the user explicitly copies is added to the business address book |
| Audio | **Voice or sound recordings** | Yes | voicemail messages and (administrators) call recordings, streamed from the server; sounds recorded in the admin screen (welcome message) are uploaded. The call audio itself is transported by the phone system and is not stored by the app |
| App activity | **Other user-generated content** | Yes | call history entries, notes, welcome-message audio and phone-system settings the user enters |
| Device or other IDs | **Device or other IDs** | Yes | per-install device token of the pairing, the push token (Firebase Cloud Messaging) and an install id; used to ring the phone for an incoming call |
| App info and performance | Crash logs, diagnostics | **No** | the app has no crash reporter or analytics (`firebase_analytics_collection_deactivated=true`, no Crashlytics) |
| Location, Financial, Health, Messages, Photos, Calendar, Web browsing | - | **No** | |

Notes for the optional-vs-required column:
- Contacts of the phone (READ_CONTACTS) are optional; everything else is required for the app to work as a phone.

## Security practices

- Data encrypted in transit: **Yes**.
- You can request data deletion: **Yes** (see above).
- Independent security review: **No** (do not claim one).
- Committed to the Play Families policy: **No** (not a children's app).

## Check before submitting

1. The privacy policy page (`https://fullstackstudio.nl/privacybeleid`) must mention FSVoip, the data above, FCM as a processor and the deletion route. Google rejects a policy that does not name the app.
2. When the Firebase project exists: Google processes the push token and the content of the high-priority message. The message holds no personal data except the caller number/name for an incoming call (see `shared/fixtures/fcm-message.json`). Keep "Shared: No" only if you consider Google as a service provider acting on our behalf (Play allows that for processors); otherwise declare "Device or other IDs" as shared.
3. If a later version adds crash reporting or analytics, update this form first.
