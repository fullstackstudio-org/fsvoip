# Security policy

## Reporting a vulnerability

Please report security problems **privately** by e-mail to **info@fullstackstudio.nl** with the
subject "FSVoip security". Do not open a public issue for a vulnerability.

Include, where possible: the affected version or commit, a description of the problem, steps to
reproduce, and the impact you expect. We aim to acknowledge a report within 3 working days and to
tell you what we will do within 10 working days.

## Scope

In scope: the code in this repository (the iOS app, later the Android app, the shared API
contract). Out of scope: the FullStack Studio server platform and PBX (closed source) - but
please report problems you notice there to the same address.

## What the app protects

- The SIP password lives only in the iOS Keychain (`AfterFirstUnlockThisDeviceOnly`) and is never
  written to logs, crash reports or the file system. The logger redacts tokens and passwords.
- Pairing tokens are single use and expire after 10 minutes; the server stores only their hash.
- This repository must never contain secrets, signing certificates, provisioning profiles or
  push keys. A `gitleaks` scan runs in CI.
