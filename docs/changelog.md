# Changelog

## 0.2.0 (build 9) — klantkaart en meldingen

### Nieuw (NL)

- **Klantkaart bij een inkomend gesprek.** Belt iemand die bij je klant als contact bekend is, dan toont het belscherm de naam en een regel als "Klant in website: 2 open bestellingen, 1 open verzoek". Het vergrendelscherm krijgt de naam binnen ongeveer een seconde. Een trage of mislukte opzoeking houdt het gesprek nooit op.
- **Activiteit bij een contact.** Het contactscherm toont de tijdlijn van het contact (bestellingen, verzoeken, gesprekken), met "Meer laden".
- **Meldingen uit het portaal** openen het juiste scherm: gemiste gesprekken en voicemail, contacten. Iets zonder eigen scherm in de app (verzoeken, bestellingen) opent het toetsenbord.

## 0.2.0 (build 7) — TestFlight-feedback

### Nieuw (NL)

- **Opnemen terwijl de app open is** zet het belscherm nu meteen op het lopende gesprek (bleef soms op "Verbinden…" staan).
- **Inkomend gesprek:** Weigeren en Opnemen staan onderaan het scherm.
- **Voicemail** heeft weer een icoon in de tabbalk.
- **Instellingen:** rijen en de nummerkiezer lopen over de volle breedte.

## 0.2.0 (build 6) — FSVoip v2

Release candidate with the whole of plan `fsvoip-app-v2`. Needs the matching server (FSS with the v2 routes, FssApi module 1.9.0 on the PBX).
An app without the new `capabilities` keeps the build 5 behaviour: the new parts hide themselves.

### Nieuw (NL)

- **Bellen via een ander nummer.** Heeft de centrale meer dan één nummer, dan kies je onder het toetsenbord welk nummer de gebelde ziet. De keuze geldt direct en wordt onthouden.
- **Parkeren en ophalen.** Zet het lopende gesprek in de wacht met "Parkeren" in het belscherm. Het nieuwe tabblad **On hold** toont de geparkeerde gesprekken (alle of alleen die van jou); tik om op te halen. Blijft het gesprek hangen, dan gaat het terug naar de parkeerder.
- **Teamgeschiedenis.** Als collega zie je de gesprekken van het hele bedrijf (zonder opnames). Beheerders kunnen opnames beluisteren.
- **Eigen toestel instellen.** Profiel en Oproepvoorkeuren: niet storen, doorschakelen, tijd tot voicemail, voicemail naar e-mail.
- **Beheerder: Nummers.** Per nummer de keten Openingstijden, Welkomstbericht, Doorschakeling (een toestel of belgroep, of een keuzemenu met toetsen) en Gespreksopname (met prijs en akkoord).
- **Beheerder: Geluiden.** Audiobestanden toevoegen, een welkomstbericht inspreken in de app, beluisteren, hernoemen, verwijderen en kiezen bij een nummer.
- **Beheerder: Gebruiker uitnodigen.** Maak een koppelcode (QR of link) voor een collega; die koppelt altijd als gebruiker.
- **Toestellen, belgroepen en openingstijden** in dezelfde stijl, met "Tijdelijk gesloten".
- **Toegankelijkheid.** VoiceOver-labels op alle nieuwe schermen, tekst loopt door tot de grootste tekstgrootte (geen afgekapte rijen), "Verminder beweging" zet alle overgangen uit, en grijze hulpteksten halen 4,5:1 contrast in licht en donker.

### Changed / fixed

- The grey secondary/tertiary text is a bit darker (light) or brighter (dark) to reach 4.5:1.
- Player bars, expanding rows and the scroll to a letter in Contacts no longer animate with Reduce Motion.
- The in-call name and the recorder timer follow the text size.

### Known open points

- **Anonymous calling** is not in this build: it waits for the Task 0 test on the house PBX (`docs/anonymous-calls.md`).
- **"Bewerk in het portaal"** on a number that is more than the chain can show opens `/portal/voip`; the server must send the `pbxId` for a direct link.
- The manual tests on the house PBX (extensions 100 and 102) are not done yet; see `docs/testing.md`.
