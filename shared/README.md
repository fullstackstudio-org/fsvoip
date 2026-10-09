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

```
bun install --cwd scripts && bun scripts/validate-contract.ts
```
