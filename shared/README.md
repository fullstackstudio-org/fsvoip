# Shared contract

Platform-neutral files used by the iOS app and, later, the Android app.

| File | What |
|---|---|
| `openapi.yaml` | OpenAPI 3.1 description of the FSVoip app API v1 (`https://fullstackstudio.nl/api/voip-app/v1`): pairing, `/me` (with `role`, `capabilities`, `pbx`), push token, unpair, `/pbx/*` (admin pairings), `/calls`, `/voicemail` with recordings, `/contacts` and `/contact-lists`. It is also the public description of that API. |
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
| `GET /pbx/overview`, `/pbx/devices`, `/pbx/ring-groups`, `/pbx/hours` | 403 | yes |
| `PATCH /pbx/devices/{id}`, `POST`/`PATCH /pbx/ring-groups`, `PATCH /pbx/hours/{id}`, `PATCH /pbx/numbers/{id}/routing` | 403 | yes (`409 read_only` while the PBX is frozen or being set up) |
| `GET /calls` | own extension only, `hasRecording` always `false` | all calls of the PBX |
| `GET /calls/{id}/recording` (media) | 403 | yes |
| `GET /voicemail` | own box only (box id = id of the own extension); another box is 403 | every box |
| `GET /voicemail/{boxId}/{ref}/audio` (media), `DELETE /voicemail/{boxId}/{ref}` | own box only | every box |
| `GET /contacts`, `GET /contact-lists`, `GET /contact-lists/{id}/contacts` | yes | yes |
| `POST /contacts`, `PATCH /contacts/{id}` | yes | yes |
| `DELETE /contacts/{id}` | 403 | yes |

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
