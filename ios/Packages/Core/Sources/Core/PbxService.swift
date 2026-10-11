// SPDX-License-Identifier: AGPL-3.0-or-later
//
// What the "Centrale" screens need from the API, per paired account. One implementation talks to the server, tests and the
// demo mode bring their own. Every method throws `APIError` (`forbidden`, `stale`, `readOnly`, ...); the screens decide what
// to say.

import Foundation

public protocol PbxServicing: Sendable {
    func overview(for account: StoredAccount) async throws -> PbxOverview
    func devices(for account: StoredAccount) async throws -> PbxDevicesResponse
    func updateDevice(for account: StoredAccount, id: String, patch: PbxDevicePatch) async throws
    func ringGroups(for account: StoredAccount) async throws -> PbxRingGroupsResponse
    func createRingGroup(for account: StoredAccount, _ group: PbxRingGroupCreate) async throws -> PbxRingGroupCreated
    func updateRingGroup(for account: StoredAccount, id: String, patch: PbxRingGroupPatch) async throws
    func hours(for account: StoredAccount) async throws -> PbxHoursResponse
    func updateHours(for account: StoredAccount, id: String, patch: PbxHoursPatch) async throws
    func setNumberRouting(for account: StoredAccount, numberId: String, patch: PbxRoutingPatch) async throws
    /// `GET /pbx/numbers`: every number with a one-line summary of its chain.
    func numbers(for account: StoredAccount) async throws -> PbxNumbersPage
    /// `GET /pbx/numbers/{id}/chain`.
    func numberChain(for account: StoredAccount, numberId: String) async throws -> NumberChain
    /// `PUT /pbx/numbers/{id}/chain/{step}`: answered with the fresh chain. `APIError.staleChain` carries the fresh chain too.
    func saveChainStep<Step: NumberChainStepRequest>(for account: StoredAccount, numberId: String, step: Step) async throws -> NumberChain
    /// `PATCH /pbx/numbers/{id}/recording`: answered with the fresh chain.
    func setNumberRecording(for account: StoredAccount, numberId: String, patch: NumberRecordingPatch) async throws -> NumberChain
}

public struct LivePbxService: PbxServicing {
    private let api: FSVoipAPIClient

    public init(api: FSVoipAPIClient = FSVoipAPIClient()) {
        self.api = api
    }

    private func client(_ account: StoredAccount) -> FSVoipAPIClient {
        api.authenticated(with: account.deviceToken)
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

    public func numbers(for account: StoredAccount) async throws -> PbxNumbersPage {
        try await client(account).pbxNumbers()
    }

    public func numberChain(for account: StoredAccount, numberId: String) async throws -> NumberChain {
        try await client(account).numberChain(numberId: numberId)
    }

    public func saveChainStep<Step: NumberChainStepRequest>(for account: StoredAccount, numberId: String, step: Step) async throws -> NumberChain {
        try await client(account).saveChainStep(numberId: numberId, step)
    }

    public func setNumberRecording(for account: StoredAccount, numberId: String, patch: NumberRecordingPatch) async throws -> NumberChain {
        try await client(account).setNumberRecording(numberId: numberId, patch)
    }
}
