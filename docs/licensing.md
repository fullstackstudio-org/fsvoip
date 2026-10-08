# Licensing of FSVoip

FSVoip is licensed under the **AGPL-3.0-or-later** because it links the Linphone SDK (linphone-sdk), which is
AGPL-3.0 (see `NOTICE`). The server side of the FullStack Studio platform is closed source and only talks to the app
through the documented API in `shared/openapi.yaml`.

## App Store distribution

The FSF considers distribution of GPL/AGPL software through the Apple App Store problematic, because the App Store
terms add restrictions the GPL does not allow. Belledonne publishes its own Linphone app there as the copyright
holder, which a third party cannot do. FullStack Studio accepts this risk knowingly.

**Way out:** the app talks to the SIP stack only through the `SipEngine` protocol (`ios/Packages/SipEngine`);
`import linphonesw` exists only in `ios/Packages/LinphoneEngine` (enforced by `scripts/check-imports.sh`). A
BSD-licensed engine (baresip) can implement the same protocol without touching the UI, pairing or contacts code.

## Optional: ask Belledonne (not blocking, not yet sent)

Draft for FullStack Studio to send from info@fullstackstudio.nl to sales@belledonne-communications.com:

> Subject: App Store distribution of an AGPL-licensed app built on linphone-sdk
>
> Dear Belledonne team,
>
> FullStack Studio (The Hague, NL) is building "FSVoip", an open-source iOS softphone for our own hosted PBX
> customers. The app itself will be published under the AGPL-3.0-or-later, with full source on GitHub
> (fullstackstudio-org/fullstackstudio-voip). It links linphone-sdk via your Swift Package (5.5.x), with no
> modifications to the SDK, and uses no Flexisip services.
>
> We intend to distribute the binary through the Apple App Store. We are aware of the FSF's position on App Store
> terms and GPL software. Could you confirm whether Belledonne, as copyright holder of linphone-sdk, considers App
> Store distribution of such an (A)GPL-licensed third-party app acceptable, or whether you would require a
> commercial SDK licence for that? If a commercial licence is required, we would appreciate an indicative quote for
> a single iOS (later also Android) app.
>
> Kind regards,
> Sebastiaan Eekhof, FullStack Studio

Record the answer here (without personal data) when it arrives.
