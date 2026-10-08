# Contributing

Thanks for your interest in FSVoip.

- **Issues are welcome**: bug reports, questions, and ideas. Please describe the iOS/Android
  version, the app version and, where relevant, the steps to reproduce. Never paste tokens,
  passwords or full SIP traces in an issue.
- **Pull requests only after discussion.** Open an issue first so we can agree on the approach.
  The app has strong architectural rules (see below) and a small team; unsolicited large PRs are
  likely to be declined.
- **No CLA.** Contributions are accepted under the project licence (AGPL-3.0-or-later) and you
  keep your copyright. Add an SPDX header to new source files:
  `// SPDX-License-Identifier: AGPL-3.0-or-later`.
- **Never commit secrets** (signing files, `.p8` keys, `Local.xcconfig`, provisioning profiles).

## Architectural rules

1. `import linphonesw` is allowed **only** inside `ios/Packages/LinphoneEngine`. Everything else
   talks to the SIP stack through the `SipEngine` protocol and its own value types.
   `scripts/check-imports.sh` enforces this and runs in CI.
2. `shared/openapi.yaml` and `shared/push-payload.schema.json` are the platform-neutral contract.
   Change them together with `shared/fixtures/*` and the contract tests. Only additive changes
   (new optional fields); breaking changes need a new API version.
3. User-visible text is Dutch and English and speaks customer language ("toestel", "centrale",
   "koppelen"), not telecom jargon.
4. Logging goes through the redacting logger in `Core`. Never log a SIP password, a device token
   or a push token.

## Building

See `README.md`. In short: `scripts/build.sh`.
