# Shared contract

Platform-neutral files used by the iOS app and, later, the Android app.

| File | What |
|---|---|
| `openapi.yaml` | OpenAPI 3.1 description of the FSVoip app API v1 (`https://fullstackstudio.nl/api/voip-app/v1`): pairing, `/me` (with `role`, `capabilities`, `pbx`), push token, unpair, `/me/extension` (the own extension), `/pbx/*` (admin pairings: extensions, ring groups, opening hours, numbers as a chain, sounds, inviting a colleague), `/calls` (team history), `/calls/park` and `/parked`, `/voicemail` with recordings, `/contacts` and `/contact-lists`. It is also the public description of that API. |
| `push-payload.schema.json` | JSON Schema of the `fsvoip` object in every push (APNs payload key, FCM `data.fsvoip`), plus the APNs and FCM envelopes. |
| `fixtures/*.json` | Example requests, responses and pushes with obviously fake values. Both apps decode them in their contract tests. |

`POST /push/ring` (PBX to server, HMAC-signed) is internal and intentionally not in `openapi.yaml`.

## Rules

- Additive changes only: new optional response fields. Clients ignore unknown response fields.
- Every fixture is validated against the schemas by `scripts/validate-contract.ts` and decoded by the Swift tests
  (`ios/Packages/Core/Tests/CoreTests/ContractTests.swift` fails if a fixture has no test).
- Responses are decoded leniently (unknown enum values become `.unknown`); requests are strict (only known keys, `version` / `expectedUpdatedAt` always present). `scripts/validate-contract.ts` also checks that every `$ref` resolves.
- A pairing token, device token, SIP password or push token in a fixture is fake. Never put a real one here.

## Permissions per role

The pairing carries a role (`user` or `admin`, `GET /me` → `role` and `capabilities`). The server enforces it on every route; the
app only uses `capabilities` to decide what to show. A route that is not allowed answers `403 forbidden` with `required`
(the role that is needed) before anything is loaded. A role change in the portal sends a `refresh` push; the app reads `/me`
again and a section that is no longer allowed disappears.

| Route | `user` | `admin` |
|---|---|---|
| `GET /me`, `PATCH /me`, `PUT /push-token`, `POST /unpair` | yes | yes |
| `GET /me/extension`, `PATCH /me/extension` (do not disturb, forwarding, no-answer, voicemail, e-mail of the **own** extension) | yes; any other extension field is `403 forbidden` with `field` | yes (same) |
| `GET /pbx/overview`, `/pbx/devices`, `/pbx/ring-groups`, `/pbx/hours` | 403 | yes |
| `GET /pbx/numbers`, `GET /pbx/numbers/{id}/chain` | 403 | yes |
| `PATCH /pbx/devices/{id}`, `POST`/`PATCH /pbx/ring-groups`, `PATCH /pbx/hours/{id}`, `PATCH /pbx/numbers/{id}/routing` | 403 | yes (`409 read_only` while the PBX is frozen or being set up) |
| `PUT /pbx/numbers/{id}/chain/{step}`, `PATCH /pbx/numbers/{id}/recording` | 403 | yes (`409 read_only`, `409 stale` + `chain`, `409 advanced`, `422 blocked_destination`, `422 cost_not_accepted` + `cost`, `400` + `code`) |
| `GET /pbx/sounds`, `GET /pbx/sounds/{id}/audio` (media), `POST /pbx/sounds`, `PATCH`/`DELETE /pbx/sounds/{id}` | 403 | yes (`400 invalid_audio`, `413 too_large`, `409 too_many`, `409 in_use` + `places`) |
| `POST /pbx/devices/{id}/app-pairing` (invite a colleague; role is always `user`) | 403 | yes (not for the own extension) |
| `GET /calls` | all calls of the PBX (team history); `hasRecording` always `false` | all calls of the PBX, with recordings |
| `GET /calls/{id}/recording` (media) | 403 | yes |
| `POST /calls/park` (the **own** running call) | yes | yes |
| `GET /parked` | yes | yes |
| `DELETE /parked/{id}` | only a call that is `mine` (else 403) | every parked call of the PBX |
| `GET /voicemail` | own box only (box id = id of the own extension); another box is 403 | every box |
| `GET /voicemail/{boxId}/{ref}/audio` (media), `DELETE /voicemail/{boxId}/{ref}` | own box only | every box |
| `GET /contacts`, `GET /contact-lists`, `GET /contact-lists/{id}/contacts` | yes | yes |
| `POST /contacts`, `PATCH /contacts/{id}` | yes | yes |
| `DELETE /contacts/{id}` | 403 | yes |

`capabilities` in `GET /me` mirrors this table: `calls` is `all` (admin), `team` (user) or `own` (a server from before the team history);
`selfExtension`, `sounds` (`manage`/`none`), `invite`, `park` and `callerChoice` are booleans (strings for `sounds`). All of them are additive: an app
treats a missing or unknown value as the restrictive choice.

## Number chain (admin)

A number is shown as a chain: opening hours, welcome message, forwarding (one extension or a ring group, or a menu with keys). `GET /pbx/numbers/{id}/chain`
answers with the whole chain (`NumberChain`), including `version`s, the choices for the pickers (`options`) and the recording state. Every step is one
`PUT /pbx/numbers/{id}/chain/{step}` and answers with the fresh chain.

- Send back the `versions` (`{ <objectId>: version }`) and `numberVersion` the chain showed. `409 stale` carries the fresh chain in `chain`: keep what the user
  typed, show the new state.
- `mode: advanced` (or `409 advanced`): the flow behind the number is more than the chain can show. Show `advanced.summary` read-only; only `step name` still works.
- A `Fallback` (what happens when it is closed or nobody answers) with `mode: other` decodes but cannot be chosen and is never sent back; leave a left-out
  fallback field out of the body to keep it as it is.
- A welcome message needs a sound: `enabled: true` without one is `400 invalid_request` with `code: greeting_required`.
- Nothing is ever deleted by a step ("off" only unlinks).
- Recording costs money per number per month: `PATCH /pbx/numbers/{id}/recording` without `costAccepted: true` is `422 cost_not_accepted` with `cost` (`priceE4` =
  euro x 10 000, `vatIncluded`). Show the price, then repeat the request with `costAccepted: true`.

## Sounds (admin)

`POST /pbx/sounds` is `multipart/form-data` with exactly the parts `name` and `file` (up to 20 MB; the type is read from the first bytes: WAV, MP3 or M4A).
The duration is `null` in the answers: the app reads it from the audio. A sound can only be deleted while nothing uses it (`409 in_use` with `places`).
Playing (`GET /pbx/sounds/{id}/audio`) is media: Bearer header only, `Range`, `HEAD`. An invitation link (`POST /pbx/devices/{id}/app-pairing`) is a credential:
show it once, never log it.

## Caller choice header (`X-FSS-From`)

A PBX with two or more numbers can let the app choose, per call, which number the callee sees and which block list, recording and call routes apply.

- The app sends the SIP header `X-FSS-From: <national number>` (`0850607848`, ten digits, from the `numbers` of `GET /me/extension`) on the INVITE of an outgoing call.
- 🚨 **Only when `capabilities.callerChoice` is `true`.** A PBX without the dialplan would forward an unknown header to the provider. Otherwise never send it; the extension
  then calls out with its `defaultNumber`.
- The number must be one of `GET /me/extension` → `numbers`; the PBX checks the list per domain and falls back to the default number for anything else. 112 and
  internal numbers are never touched by it.
- The chosen number keeps its own block list and its own recording setting; nothing about fraud limits, destinations or recording changes because of the header.
- Choosing is not a setting of the extension (`PATCH /me/extension` has no outbound number). The app remembers the last choice itself.

## Park

`POST /calls/park` with `{ "callId": "<SIP Call-ID of the app's leg>" }` puts the own running call on hold in a numbered slot (`201`, a `ParkedCall`). The server finds
the call on the PBX by that Call-ID and only parks it when it belongs to the extension of this pairing; another Call-ID is `404 call_not_found`.

- `GET /parked` lists the parked calls (`calls`, `available`; at most 3 s old). `mine` = parked by this pairing. `available: false` or `capabilities.park: false` = hide the park UI.
- Picking a call up = **dial `retrieveNumber`** (`*5901`) as a normal call. There is no pickup route.
- If nobody picks it up the call rings back at the extension that parked it (`expiresAt`).
- `DELETE /parked/{id}` hangs up: a `user` only a call that is `mine`.
- 🚨 `503 park_uncertain`: the PBX failed while parking and the call **may be parked**. **Never park again** (that could park a different call or the same one twice):
  refresh `GET /parked` and look. Other answers: `409 no_free_slot`, `409 park_unavailable`, `409 park_busy`, `409 read_only`.
- 30 park/hang-up requests per 10 minutes per device.

## Sync rules (contacts)

- Full sync: `GET /contacts` without `since`, following `nextCursor` until it is `null`. Delta: `since` = the `serverTime` stored by
  the previous complete run. Every page of one run carries the **same** `serverTime`; take it from the **first** page and store it
  only after the last page succeeded (a run that fails halfway stores nothing).
- The server rewinds `since` by 120 s, so a contact can arrive twice: **upsert on `id`**. `deleted` lists ids that were removed
  in the period (tombstones, kept 90 days).
- `400 invalid_request` with `code: "resync"` (a `since` older than 90 days): do a full sync and drop every local contact that is
  not in it.
- Lists: `GET /contact-lists` gives the lists with a `version`. `GET /contact-lists/{id}/contacts` answers with that version as `ETag`;
  send it back as `If-None-Match` and a `304` means nothing changed. Lists above 500 contacts come in pages via `cursor`; a version
  change halfway is `409 stale`, start again.
- Writes: `PATCH /contacts/{id}` needs `expectedUpdatedAt` (the `updatedAt` the app showed, sent back unchanged); another value is
  `409 stale` and the app reloads the contact. `phones`, `tags` and `listIds` replace the whole set, `null` wipes a text field, a left-out
  key stays as it was. `PATCH /pbx/devices/{id}` and the other PBX writes need `version` in the same way.

## Media (recordings and voicemail)

- Audio is only served to `Authorization: Bearer <device token>`; a token in a query string is never accepted. On iOS use
  `AVURLAsset` with `AVURLAssetHTTPHeaderFieldsKey`, not a URL with a token.
- `Range` is supported (`206`; `416` when it does not fit) and `HEAD`, so a player can show the duration and scrub without
  downloading everything. `410 gone` means the audio is past its retention period (recordings 90 days, voicemail 30 days).
- Media requests have their own limit (60 per minute per device) and do not use the token limit.

## Limits

- Request bodies above 64 kB are `413 payload_too_large`.
- `GET /contacts`: `limit` 1-500 (maximum 500 per page). A contact has at most 20 numbers and 20 tags; a customer at most
  25,000 contacts (`409` with `code: "limit_reached"`).
- PBX writes: 60 per 10 minutes per device. API in general: rate limits per device token and per IP, answered with `429 rate_limited` and `Retry-After`. `POST /pair`: 20 per hour per IP.


## Validate

```
bun install --cwd scripts && bun scripts/validate-contract.ts
```
