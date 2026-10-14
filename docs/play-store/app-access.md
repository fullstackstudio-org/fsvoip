# App access (instructions for reviewers)

Play Console > App content > App access: choose "All or some functionality is restricted" and add this instruction set. Reviewers cannot use the app without a pairing link, so we give them one.

**Name:** Reviewer access (extension)

**Instructions (paste):**

```
FSVoip only works with a phone-system extension of FullStack Studio; there is no sign-up or username/password login in the app.
To review the app:
1. Mail info@fullstackstudio.nl (or call 085 060 7848, Mon-Fri 09:00-21:00) and say "Google Play review, FSVoip". We reply within one working day with a pairing link of a test extension (a link like https://fullstackstudio.nl/fsvoip/pair?t=... or a QR code).
2. Install the app, open the pairing link on the device (or tap "QR-code scannen" in the app and scan the QR code). Confirm with "Koppelen".
3. The dialler, call history, voicemail, contacts, "On hold" and (for the test extension, an administrator) the settings sections are then available.
The pairing link is valid for 10 minutes and works once. Ask for a new link if it expired. The test extension is on a separate test phone system, no real customer data.
```

**Action for Sebas before submitting:** create a test phone system (or a dedicated test extension with role `admin`) and keep a pairing code ready on the day of review (portal > Telefonie > toestel > FSVoip koppelen, or the MCP tool `create_voip_app_pairing`). Codes expire after 10 minutes, so create one when the review starts and answer the reviewer's mail quickly. If you prefer to avoid the mail round trip, Play also accepts a fixed login; that does not exist here by design (pairing codes are single use).
