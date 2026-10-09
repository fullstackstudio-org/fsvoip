// SPDX-License-Identifier: AGPL-3.0-or-later
//
// What the "Centrale" screens need from the API, per paired account. One implementation talks to the server, tests and the
// demo mode bring their own. Every method throws `APIError` (`forbidden`, `stale`, `readOnly`, ...); the screens decide what
// to say.

import Foundation

public protocol PbxServicing: Sendable {
    /// `GET /me`: the role and capabilities of this pairing (does the section exist?).
    func me(for account: StoredAccount) async throws -> MeResponse
    func overview(for account: StoredAccount) async throws -> PbxOverview
    func devices(for account: StoredAccount) async throws -> PbxDevicesResponse
    func updateDevice(for account: StoredAccount, id: String, patch: PbxDevicePatch) async throws
    func ringGroups(for account: StoredAccount) async throws -> PbxRingGroupsResponse
    func createRingGroup(for account: StoredAccount, _ group: PbxRingGroupCreate) async throws -> PbxRingGroupCreated
    func updateRingGroup(for account: StoredAccount, id: String, patch: PbxRingGroupPatch) async throws
    func hours(for account: StoredAccount) async throws -> PbxHoursResponse
    func updateHours(for account: StoredAccount, id: String, patch: PbxHoursPatch) async throws
    func setNumberRouting(for account: StoredAccount, numberId: String, patch: PbxRoutingPatch) async throws
}

public struct LivePbxService: PbxServicing {
    private let api: FSVoipAPIClient

    public init(api: FSVoipAPIClient = FSVoipAPIClient()) {
        self.api = api
    }

    private func client(_ account: StoredAccount) -> FSVoipAPIClient {
        api.authenticated(with: account.deviceToken)
    }

    public func me(for account: StoredAccount) async throws -> MeResponse {
        try await client(account).me()
    }

    public func overview(for account: StoredAccount) async throws -> PbxOverview {
        try await client(account).pbxOverview()
    }

    public func devices(for account: StoredAccount) async throws -> PbxDevicesResponse {
        try await client(account).pbxDevices()
    }

    public func updateDevice(for account: StoredAccount, id: String, patch: PbxDevicePatch) async throws {
        try await client(account).updatePbxDevice(id: id, patch: patch)
    }

    public func ringGroups(for account: StoredAccount) async throws -> PbxRingGroupsResponse {
        try await client(account).pbxRingGroups()
    }

    public func createRingGroup(for account: StoredAccount, _ group: PbxRingGroupCreate) async throws -> PbxRingGroupCreated {
        try await client(account).createPbxRingGroup(group)
    }

    public func updateRingGroup(for account: StoredAccount, id: String, patch: PbxRingGroupPatch) async throws {
        try await client(account).updatePbxRingGroup(id: id, patch: patch)
    }

    public func hours(for account: StoredAccount) async throws -> PbxHoursResponse {
        try await client(account).pbxHours()
    }

    public func updateHours(for account: StoredAccount, id: String, patch: PbxHoursPatch) async throws {
        try await client(account).updatePbxHours(id: id, patch: patch)
    }

    public func setNumberRouting(for account: StoredAccount, numberId: String, patch: PbxRoutingPatch) async throws {
        try await client(account).setPbxNumberRouting(numberId: numberId, patch: patch)
    }
}
