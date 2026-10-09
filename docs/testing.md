# Testing FSVoip v2 on a real device

For build 6 and later. Use a Release or TestFlight build, the house PBX of FullStack Studio, and test extension **102** ("FSVoip test"), never 100.
Do **not** dial 112 on production. You need the customer portal, a second phone to call from, and a third number to call out to.

What is automated and what is not:

| Automated (`scripts/build.sh`) | Only by hand (below) |
|---|---|
| contract fixtures, models, parsers, status mapping, caller-choice header on the INVITE, `Privacy` only for anonymous, park rules, recorder state machine, Dynamic Type XXL fitting of the new rows, contrast of the text colours | real audio, push wake-up, CallKit, the PBX behaviour (caller choice, park, recording), Face ID, microphone permission |

Mark every step as pass/fail in the PR. **None of the steps below has been run on the house PBX yet.**

## 1. Role: pair as a user (no admin)

1. Portal → extension 102 → *FSVoip koppelen* with the role **Gebruiker**, scan it.
2. Settings shows Profiel, Oproepvoorkeuren, Koppeling, but **no** Centrale, Nummers, Geluiden, Gebruiker uitnodigen or Opnames.
3. **Do not disturb**: switch it on in Oproepvoorkeuren. Call 102: it does not ring and goes to voicemail. Switch it off; the call rings again.
4. **Team history**: Recents shows calls of colleagues too (name of who answered), without a recording play button.
5. **Voicemail**: only the own box.

## 2. Caller choice ("Bellen via")

Needs a PBX with two or more numbers and FssApi 1.9.0.

1. Dialer: a bar under the keypad shows the number in use. Tap it, pick the other number. The bar changes at once.
2. Call the second phone: it sees the **chosen** number. Hang up, call again with the first number: it sees that one.
3. Kill the app, reopen: the last choice is still there.
4. A PBX without the capability shows no bar and no header is sent (check the PBX log: no `X-FSS-From`).
5. 112 is never changed by the choice (simulation on the throw-away domain only, not on production).

## 3. Park and pick up

1. Call 102 from the second phone, answer. In the call screen tap **Parkeren**. The call goes silent for the caller; the app shows the slot.
2. Tab **On hold** lists it with the caller and the time. "Alle oproepen" shows every parked call of the PBX, "Mijn oproepen" only yours.
3. Pick it up from the app (tap the row): it dials the retrieve number and you are connected again.
4. Park again and do nothing: after the time-out (default 120 s) the call rings back at your extension.
5. A `user` can hang up only a call that is theirs; an `admin` any parked call.
6. Park while the PBX is slow: the app says it is unsure and refreshes the list; it never parks a second time.

## 4. Administrator: numbers, sounds, invite, recordings

Pair as **Beheerder** (portal, role switch). Face ID is asked for Centrale, Nummers, Geluiden, Voicemail and Opnames.

1. **Nummer instellen.** Nummers → a number. Change the name. Opening hours: change a day, add an exception; the portal shows the same. A second number with its own hours.
2. **Welkomstbericht inspreken.** Geluiden → record (microphone permission), stop, give it a name, add. Play it back. Number → Welkomstbericht → pick it. Call the number: the message plays.
3. **Doorschakeling.** Switch between a ring group and a menu with keys; every key goes to a destination. A number the chain cannot show (advanced) is read-only and offers "Bewerk in het portaal".
4. **Gespreksopname.** Switch on: the price (excl./incl. VAT) must be shown and accepted first. A beep announcement can be chosen. Make a call and play the recording under Opnames.
5. **Gebruiker uitnodigen.** Pick a colleague's extension, show the QR/link, scan it with a second phone: it pairs as a user. The own extension is refused.
6. **Concurrent change.** Change a step in the portal while the app has it open, then save in the app: the app says it changed and shows the new state.
7. **Take the role away** in the portal: the admin sections disappear after the refresh push.

## 5. Accessibility pass (VoiceOver and text size)

1. Settings → Accessibility → Larger Text: the largest size. Walk Dialer, Recents, On hold, Voicemail, Contacts, Settings, Nummers, one number, Geluiden: no row may be cut off with "…".
2. VoiceOver on: Dialer → pick a number → call → Parkeren → On hold → Settings → Nummers → one step → save. Every control has a spoken name; rows read as one item.
3. Reduce Motion on: sheets and player bars appear without sliding; the recorder dot does not pulse.
4. Light and dark: grey help text stays readable.

## Not yet verified on the house PBX

Caller choice on the callee's display, parking and ring-back, the recording announcement, the welcome message and the menu keys, opening hours on a second number, the invite flow end to end, and the push wake-up with the app closed after the server update. Anonymous calling is not part of this build.
