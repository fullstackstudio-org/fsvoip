// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation

/// "Beschikbaar": do-not-disturb of the own extension (`GET`/`PATCH /me/extension`, every role).
public protocol AvailabilityServicing: Sendable {
    func load(for account: StoredAccount) async throws -> AvailabilityHub.State
    /// Returns the new state (the `version` moves on).
    func setDoNotDisturb(_ dnd: Bool, version: Int, for account: StoredAccount) async throws -> AvailabilityHub.State
}

public struct LiveAvailabilityService: AvailabilityServicing {
    private let api: FSVoipAPIClient

    public init(api: FSVoipAPIClient = FSVoipAPIClient()) {
        self.api = api
    }

    public func load(for account: StoredAccount) async throws -> AvailabilityHub.State {
        let state = try await api.authenticated(with: account.deviceToken).selfExtension()

        return AvailabilityHub.State(doNotDisturb: state.dnd, version: state.version)
    }

    public func setDoNotDisturb(_ dnd: Bool, version: Int, for account: StoredAccount) async throws -> AvailabilityHub.State {
        let client = api.authenticated(with: account.deviceToken)
        let response = try await client.updateSelfExtension(SelfExtensionPatch(version: version, dnd: dnd))

        if let fresh = response.extensionState {
            return AvailabilityHub.State(doNotDisturb: fresh.dnd, version: fresh.version)
        }

        // The server could not read the fresh state: ask once more.
        let fresh = try await client.selfExtension()

        return AvailabilityHub.State(doNotDisturb: fresh.dnd, version: fresh.version)
    }
}

/// The state of "Beschikbaar" per account, shown as the dot on the avatar and as the switch in the settings sheet.
@MainActor
public final class AvailabilityHub: ObservableObject {
    public struct State: Equatable, Sendable {
        public var doNotDisturb: Bool
        public var version: Int

        public init(doNotDisturb: Bool, version: Int) {
            self.doNotDisturb = doNotDisturb
            self.version = version
        }
    }

    @Published public private(set) var states: [String: State] = [:]
    @Published public private(set) var saving: Set<String> = []
    /// Set when a change did not work; the app shows it and clears it.
    @Published public var failedFor: String?

    private let service: AvailabilityServicing

    public init(service: AvailabilityServicing) {
        self.service = service
    }

    public func state(for accountId: String) -> State? {
        states[accountId]
    }

    public func isAvailable(_ accountId: String) -> Bool? {
        states[accountId].map { !$0.doNotDisturb }
    }

    public func load(_ account: StoredAccount) async {
        guard let state = try? await service.load(for: account) else {
            return
        }

        states[account.id] = state
    }

    /// Switch availability. Optimistic: the switch moves at once and goes back when the server says no.
    public func setAvailable(_ available: Bool, account: StoredAccount) async {
        guard let current = states[account.id], !saving.contains(account.id) else {
            return
        }

        states[account.id] = State(doNotDisturb: !available, version: current.version)
        saving.insert(account.id)
        defer { saving.remove(account.id) }

        do {
            states[account.id] = try await service.setDoNotDisturb(!available, version: current.version, for: account)
        } catch {
            // Stale or offline: show what the server has now.
            states[account.id] = current
            failedFor = account.id
            await load(account)
        }
    }

    public func forget(accountId: String) {
        states[accountId] = nil
    }
}

/// The colour of the dot on the avatar.
enum AvailabilityDot {
    enum Kind: Equatable {
        case available
        case doNotDisturb
        case connecting
        case offline
    }

    static func kind(registered: Bool, connecting: Bool, doNotDisturb: Bool) -> Kind {
        if doNotDisturb { return .doNotDisturb }
        if registered { return .available }
        if connecting { return .connecting }

        return .offline
    }
}
