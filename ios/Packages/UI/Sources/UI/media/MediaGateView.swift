// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// The door of a protected screen (the recordings): Face ID / Touch ID / passcode first, valid for five minutes together with
/// the "Centrale" section (one `LocalAccessGate`). A phone without a passcode cannot open it. When the right goes away while the
/// screen is open, it closes.
struct MediaGateView<Content: View>: View {
    @ObservedObject var hub: MediaHub
    let account: StoredAccount
    let title: String
    let reason: String
    let message: String
    /// What the account needs to open this screen: the rights it was shown for (recordings, or managing sounds).
    var requirement = Requirement.recordings
    @ViewBuilder let content: () -> Content

    enum Requirement {
        case recordings
        case sounds
    }

    private func isAllowed() -> Bool {
        switch requirement {
        case .recordings: return hub.hasRecordings(account.id)
        case .sounds: return hub.canManageSounds(account.id)
        }
    }

    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    private enum Lock {
        case checking
        case locked
        case open
        case noPasscode
    }

    @State private var lock = Lock.checking

    var body: some View {
        Group {
            switch lock {
            case .open:
                content()
            case .checking:
                ProgressView(L10n.string("pbx.lock.checking"))
            case .locked:
                MediaLockCard(symbol: "lock.fill", title: L10n.string("media.lock.title"), message: message, buttonTitle: L10n.string("pbx.lock.unlock")) {
                    Task { await unlock() }
                }
            case .noPasscode:
                MediaLockCard(symbol: "lock.slash.fill", title: L10n.string("pbx.lock.noPasscode.title"), message: L10n.string("media.lock.noPasscode"), buttonTitle: nil, action: {})
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .task { await unlock() }
        .onChange(of: scenePhase) { phase in
            // Coming back after five idle minutes: ask again before showing anything, and stop what was playing.
            if phase == .active, lock == .open, !hub.gate.isUnlocked {
                hub.player.stop()
                lock = .checking
                Task { await unlock() }
            }
        }
        .onChange(of: isAllowed()) { available in
            if !available { dismiss() }
        }
    }

    private func unlock() async {
        guard isAllowed() else { return }

        switch await hub.gate.ensureUnlocked(reason: reason) {
        case .unlocked:
            lock = .open
        case .cancelled, .failed:
            lock = .locked
        case .unavailable:
            lock = .noPasscode
        }
    }
}

/// The same lock picture as the "Centrale" section, also used inside the voicemail list for a colleague's box.
struct MediaLockCard: View {
    let symbol: String
    let title: String
    let message: String
    let buttonTitle: String?
    var prominent = true
    let action: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: symbol)
                .font(.system(size: 40, weight: .semibold))
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text(title)
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            if let buttonTitle {
                Group {
                    if prominent {
                        Button(buttonTitle, action: action).buttonStyle(PrimaryButtonStyle())
                    } else {
                        Button(buttonTitle, action: action).buttonStyle(SecondaryButtonStyle())
                    }
                }
                .padding(.top, 8)
                .accessibilityIdentifier("media-unlock")
            }
        }
        .padding(24)
        .frame(maxWidth: 420)
        .frame(maxWidth: .infinity, maxHeight: prominent ? .infinity : nil)
    }
}
