// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// The door of the "Centrale" section: Face ID / Touch ID / passcode first (five minutes valid), then the overview.
/// A phone without a passcode cannot open it. When the role disappears while it is open, the section closes.
struct PbxSectionView: View {
    @ObservedObject var hub: PbxHub
    let account: StoredAccount

    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    private enum Lock: Equatable {
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
                PbxOverviewView(model: hub.section(for: account))
            case .checking:
                ProgressView(L10n.string("pbx.lock.checking"))
            case .locked:
                PbxLockedView(symbol: "lock.fill", title: L10n.string("pbx.lock.title"), message: L10n.string("pbx.lock.message"), buttonTitle: L10n.string("pbx.lock.unlock")) {
                    Task { await unlock() }
                }
            case .noPasscode:
                PbxLockedView(symbol: "lock.slash.fill", title: L10n.string("pbx.lock.noPasscode.title"), message: L10n.string("pbx.lock.noPasscode"), buttonTitle: nil, action: {})
            }
        }
        .navigationTitle(L10n.string("pbx.title"))
        .navigationBarTitleDisplayMode(.inline)
        .task { await unlock() }
        .onChange(of: hub.isAvailable(account.id)) { available in
            if !available { dismiss() }
        }
        .onChange(of: scenePhase) { phase in
            // Coming back after five idle minutes: ask again before showing anything.
            if phase == .active, lock == .open, !hub.gate.isUnlocked {
                lock = .checking
                Task { await unlock() }
            }
        }
    }

    private func unlock() async {
        guard hub.isAvailable(account.id) else {
            return
        }

        switch await hub.gate.ensureUnlocked(reason: L10n.string("pbx.lock.reason")) {
        case .unlocked:
            lock = .open
        case .cancelled, .failed:
            lock = .locked
        case .unavailable:
            lock = .noPasscode
        }
    }
}

private struct PbxLockedView: View {
    let symbol: String
    let title: String
    let message: String
    let buttonTitle: String?
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
                Button(buttonTitle, action: action)
                    .buttonStyle(PrimaryButtonStyle())
                    .padding(.top, 8)
                    .accessibilityIdentifier("pbx-unlock")
            }
        }
        .padding(24)
        .frame(maxWidth: 420)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// The "Centrale" row of the account screen.
struct PbxAccountSection: View {
    @ObservedObject var hub: PbxHub
    let account: StoredAccount

    var body: some View {
        if hub.isAvailable(account.id) {
            Section {
                NavigationLink {
                    PbxSectionView(hub: hub, account: account)
                } label: {
                    Label(L10n.string("pbx.title"), systemImage: "switch.2")
                }
                .accessibilityIdentifier("pbx-section-link")
            } footer: {
                Text(L10n.string("pbx.section.footer"))
            }
        }
    }
}
