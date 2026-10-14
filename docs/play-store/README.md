# Google Play material for FSVoip 1.0

Everything here is prepared without a Google account. Nothing has been uploaded. Order of work for the person with the Play Console: see "Checklist for Sebas" in the release PR description / chat handover and `../../android/README.md`.

| File | Use |
| --- | --- |
| `listing.md` | short + full description (NL), graphics list |
| `release-notes.md` | what's new text for 1.0 |
| `data-safety.md` | answers for the Data safety form |
| `content-rating.md` | IARC questionnaire + target audience |
| `app-access.md` | instructions for the reviewers (pairing link by mail) |
| `permissions-declarations.md` | permission list, foreground service (phoneCall) and full-screen-intent declaration texts |
| `icon-512.png`, `feature-graphic.png`, `screenshots/` | graphics |

Re-create screenshots: build the debug APK against a local mock of the API (see `../../android/README.md` "Testing without production"), pair, set the app language to Dutch (`adb shell cmd locale set-app-locales nl.fullstackstudio.fsvoip --locales nl-NL`) and take `adb exec-out screencap -p`.
