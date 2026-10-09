// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// The "Voicemail" and "Opnames" rows of the account screen, next to "Centrale". A row exists only when the server's `GET /me`
/// gave this pairing the right to it.
struct MediaAccountSection: View {
    @ObservedObject var hub: MediaHub
    let account: StoredAccount

    /// Demo mode only (`-FSVoipDemoScreen voicemail|recordings`): open the screen without tapping, for screenshots.
    @State private var opensDemoVoicemail = MediaDemo.opensVoicemail
    @State private var opensDemoRecordings = MediaDemo.opensRecordings

    var body: some View {
        if hub.isAvailable(account.id) {
            Section {
                if hub.hasVoicemail(account.id) {
                    NavigationLink {
                        VoicemailView(hub: hub, account: account)
                    } label: {
                        Label(L10n.string("media.voicemail.title"), systemImage: "voicemail")
                    }
                    .accessibilityIdentifier("voicemail-link")
                }

                if hub.hasRecordings(account.id) {
                    NavigationLink {
                        recordings
                    } label: {
                        Label(L10n.string("media.recordings.title"), systemImage: "waveform")
                    }
                    .accessibilityIdentifier("recordings-link")
                }
            } footer: {
                Text(L10n.string(hub.hasRecordings(account.id) ? "media.section.footer.admin" : "media.section.footer"))
            }
            .navigationDestination(isPresented: $opensDemoVoicemail) {
                VoicemailView(hub: hub, account: account)
            }
            .navigationDestination(isPresented: $opensDemoRecordings) {
                recordings
            }
        }
    }

    private var recordings: some View {
        MediaGateView(
            hub: hub,
            account: account,
            title: L10n.string("media.recordings.title"),
            reason: L10n.string("media.lock.reason.recordings"),
            message: L10n.string("media.lock.message.recordings")
        ) {
            RecordingsView(hub: hub, account: account)
        }
    }
}
