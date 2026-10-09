// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation

/// The pages the settings sheet can push (one NavigationStack, one enum).
enum SettingsPage: Hashable {
    case profile
    case callPreferences
    case link
    case centrale(PbxPart)
    case sounds
    case invite
    case recordings
    case appearance
    case notifications
    case about
    case licenses
}

/// Which parts of the settings sheet exist for one account. Pure, so a test can prove that a `user` never sees "Beheer".
struct SettingsOutline: Equatable {
    enum Admin: Equatable, CaseIterable {
        case numbers
        case overview
        case hours
        case devices
        case ringGroups
        case sounds
        case invite
        case recordings
    }

    var showsAccountSwitcher: Bool
    var showsAvailability: Bool
    var admin: [Admin]

    var showsAdmin: Bool { !admin.isEmpty }

    @MainActor
    init(model: FSVoipAppModel, accountId: String?) {
        showsAccountSwitcher = model.accounts.count > 1
        showsAvailability = model.availability != nil && accountId != nil

        guard let accountId, model.canManagePbx(accountId) else {
            admin = []

            return
        }

        var items: [Admin] = [.numbers, .devices, .ringGroups, .overview, .hours]

        if model.canManageSounds(accountId) { items.append(.sounds) }
        if model.canInvite(accountId) { items.append(.invite) }
        if model.media?.hasRecordings(accountId) == true { items.append(.recordings) }

        admin = items
    }

    /// The page a start request opens, if any.
    static func pages(for start: FSVoipAppModel.SettingsStart) -> [SettingsPage] {
        switch start {
        case .root: return []
        case .centrale: return [.centrale(.overview)]
        case .recordings: return [.recordings]
        case .sounds: return [.sounds]
        case .appearance: return [.appearance]
        case .profile: return [.profile]
        case .callPreferences: return [.callPreferences]
        case .invite: return [.invite]
        case .numbers: return [.centrale(.numbers)]
        case .devices: return [.centrale(.devices)]
        case .ringGroups: return [.centrale(.ringGroups)]
        case .hours: return [.centrale(.hours)]
        }
    }
}
