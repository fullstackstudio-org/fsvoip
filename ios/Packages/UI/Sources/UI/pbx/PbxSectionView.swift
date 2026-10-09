// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// The door of the "Centrale" section: Face ID / Touch ID / passcode first (five minutes valid), then the overview.
/// A phone without a passcode cannot open it. When the role disappears while it is open, the section closes.
struct PbxSectionView: View {
    @ObservedObject var hub: PbxHub
    let account: StoredAccount
    /// Which part opens behind the lock (the settings sheet has a row for each).
    var part: PbxPart = .overview
    /// Closes the settings sheet (the close button of the new-style pages).
    var close: (() -> Void)?

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
                switch part {
                case .overview: PbxOverviewView(model: hub.section(for: account))
                case .devices: DevicesView(model: hub.section(for: account))
                case .ringGroups: RingGroupsView(model: hub.section(for: account))
                case .hours: HoursView(model: hub.section(for: account))
                case .numbers: NumbersListView(model: hub.section(for: account))
                }
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
        .environment(\.pbxClose, close)
        .navigationTitle(L10n.string(Self.titleKey(part)))
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

    static func titleKey(_ part: PbxPart) -> String {
        switch part {
        case .overview: return "pbx.title"
        case .devices: return "pbx.devices.title"
        case .ringGroups: return "pbx.ringGroups.title"
        case .hours: return "pbx.hours.title"
        case .numbers: return "numbers.title"
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
