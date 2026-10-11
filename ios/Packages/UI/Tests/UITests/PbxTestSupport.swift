// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation
@testable import UI

/// The fixtures of `shared/fixtures`, decoded with the app's own decoder.
enum PbxFixtures {
    static let directory: URL = {
        // .../ios/Packages/UI/Tests/UITests/PbxTestSupport.swift -> repo root is 6 levels up.
        var url = URL(fileURLWithPath: #filePath)

        for _ in 0 ..< 6 {
            url.deleteLastPathComponent()
        }

        return url.appendingPathComponent("shared/fixtures", isDirectory: true)
    }()

    static func data(_ name: String) -> Data {
        try! Data(contentsOf: directory.appendingPathComponent("\(name).json"))
    }

    static func decode<T: Decodable>(_ name: String, as type: T.Type = T.self) -> T {
        try! FSVoipJSON.decoder().decode(T.self, from: data(name))
    }

    static var me: MeResponse { decode("me-response-admin") }
    static var meFrozen: MeResponse { decode("me-response-admin-frozen") }
    static var meUser: MeResponse { decode("me-response-user") }
    static var simpleChain: NumberChain { decode("number-chain-simple") }
    static var advancedChain: NumberChain { decode("number-chain-advanced") }
    static var menuChain: NumberChain { decode("number-chain-menu") }

    /// A chain from JSON text with edits (for tests that need a variant of a fixture).
    static func chain(_ name: String, _ edit: (inout [String: Any]) -> Void) -> NumberChain {
        var json = try! JSONSerialization.jsonObject(with: data(name)) as! [String: Any]
        edit(&json)

        return try! FSVoipJSON.decoder().decode(NumberChain.self, from: JSONSerialization.data(withJSONObject: json))
    }
}

/// The JSON body a request sends, as a dictionary.
func jsonBody<Value: Encodable>(_ value: Value) throws -> [String: Any] {
    try JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(value)) as! [String: Any]
}

/// The body of a chain step the draft returned as an existential.
func jsonBody(_ step: any NumberChainStepRequest) throws -> [String: Any] {
    func open<Step: NumberChainStepRequest>(_ value: Step) throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(value)) as! [String: Any]
    }

    return try open(step)
}

/// Scripted `/pbx/*`: every call is recorded, errors can be queued per method, and the responses can change between reads.
final class FakePbxService: PbxServicing, @unchecked Sendable {
    private let lock = NSLock()
    private(set) var calls: [String] = []
    private(set) var devicePatches: [(id: String, patch: PbxDevicePatch)] = []
    private(set) var hoursPatches: [(id: String, patch: PbxHoursPatch)] = []
    private(set) var ringGroupPatches: [(id: String, patch: PbxRingGroupPatch)] = []
    private(set) var created: [PbxRingGroupCreate] = []
    private(set) var routingPatches: [(id: String, patch: PbxRoutingPatch)] = []
    /// Every chain step that was sent: the step name and the JSON body as the server would receive it.
    private(set) var chainSteps: [(numberId: String, step: ChainStep, body: [String: Any])] = []
    private(set) var recordingPatches: [(numberId: String, body: [String: Any])] = []

    /// Errors thrown by the next calls of a method, in order (`"updateDevice"`, `"overview"`, ...).
    var failures: [String: [Error]] = [:]
    var overviewResult: PbxOverview = PbxFixtures.decode("pbx-overview")
    var devicesResult: PbxDevicesResponse = PbxFixtures.decode("pbx-devices")
    var ringGroupsResult: PbxRingGroupsResponse = PbxFixtures.decode("pbx-ring-groups")
    var hoursResult: PbxHoursResponse = PbxFixtures.decode("pbx-hours")
    var numbersResult: PbxNumbersPage = PbxFixtures.decode("numbers-page")
    /// The chains by number id (the simple and the advanced fixture).
    var chainResults: [String: NumberChain] = [
        PbxFixtures.simpleChain.id: PbxFixtures.simpleChain,
        PbxFixtures.advancedChain.id: PbxFixtures.advancedChain,
    ]
    /// What a step or recording answers (default: the chain as it is).
    var chainAnswer: ((String) -> NumberChain)?
    /// Runs before a read returns (to change what the next read says, e.g. a sync that finishes).
    var onRead: ((String) -> Void)?

    private func enter(_ name: String) throws {
        lock.lock()
        defer { lock.unlock() }

        calls.append(name)

        if var queue = failures[name], !queue.isEmpty {
            let error = queue.removeFirst()
            failures[name] = queue

            throw error
        }
    }

    func count(_ name: String) -> Int {
        calls.filter { $0 == name }.count
    }

    func overview(for account: StoredAccount) async throws -> PbxOverview {
        try enter("overview")
        onRead?("overview")

        return overviewResult
    }

    func devices(for account: StoredAccount) async throws -> PbxDevicesResponse {
        try enter("devices")
        onRead?("devices")

        return devicesResult
    }

    func updateDevice(for account: StoredAccount, id: String, patch: PbxDevicePatch) async throws {
        try enter("updateDevice")
        devicePatches.append((id, patch))
    }

    func ringGroups(for account: StoredAccount) async throws -> PbxRingGroupsResponse {
        try enter("ringGroups")
        onRead?("ringGroups")

        return ringGroupsResult
    }

    func createRingGroup(for account: StoredAccount, _ group: PbxRingGroupCreate) async throws -> PbxRingGroupCreated {
        try enter("createRingGroup")
        created.append(group)

        return try FSVoipJSON.decoder().decode(PbxRingGroupCreated.self, from: Data(#"{"ringGroupId":"11111111-1111-4111-8111-111111111111"}"#.utf8))
    }

    func updateRingGroup(for account: StoredAccount, id: String, patch: PbxRingGroupPatch) async throws {
        try enter("updateRingGroup")
        ringGroupPatches.append((id, patch))
    }

    func hours(for account: StoredAccount) async throws -> PbxHoursResponse {
        try enter("hours")
        onRead?("hours")

        return hoursResult
    }

    func updateHours(for account: StoredAccount, id: String, patch: PbxHoursPatch) async throws {
        try enter("updateHours")
        hoursPatches.append((id, patch))
    }

    func setNumberRouting(for account: StoredAccount, numberId: String, patch: PbxRoutingPatch) async throws {
        try enter("setNumberRouting")
        routingPatches.append((numberId, patch))
    }

    func numbers(for account: StoredAccount) async throws -> PbxNumbersPage {
        try enter("numbers")
        onRead?("numbers")

        return numbersResult
    }

    func numberChain(for account: StoredAccount, numberId: String) async throws -> NumberChain {
        try enter("chain")
        onRead?("chain")

        guard let chain = chainResults[numberId] else {
            throw APIError.notFound
        }

        return chain
    }

    func saveChainStep<Step: NumberChainStepRequest>(for account: StoredAccount, numberId: String, step: Step) async throws -> NumberChain {
        let body = try jsonBody(step)
        lock.lock()
        chainSteps.append((numberId, step.step, body))
        lock.unlock()
        try enter("saveChainStep")

        return chainAnswer?(numberId) ?? chainResults[numberId]!
    }

    func setNumberRecording(for account: StoredAccount, numberId: String, patch: NumberRecordingPatch) async throws -> NumberChain {
        let body = try jsonBody(patch)
        lock.lock()
        recordingPatches.append((numberId, body))
        lock.unlock()
        try enter("setNumberRecording")

        return chainAnswer?(numberId) ?? chainResults[numberId]!
    }
}

/// A scripted Face ID.
final class FakeLocalAuth: LocalAuthenticating, @unchecked Sendable {
    var availabilityValue = LocalAuthAvailability.available
    var results: [LocalAuthResult] = []
    private(set) var evaluations = 0

    func availability() -> LocalAuthAvailability { availabilityValue }

    func evaluate(reason: String) async -> LocalAuthResult {
        evaluations += 1

        return results.isEmpty ? .success : results.removeFirst()
    }
}
