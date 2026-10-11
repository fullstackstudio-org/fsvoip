// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation
import SwiftUI

enum PbxPart: Hashable, CaseIterable {
    case overview
    case devices
    case ringGroups
    case hours
    /// The numbers as chains (`GET /pbx/numbers`).
    case numbers
}

struct PbxBanner: Identifiable, Equatable {
    let id = UUID()
    let text: String
    let isError: Bool
}

/// The "Centrale" section of ONE paired account: what the server last said, the saves, and the rules around them
/// (the version goes with every change, "changed in the meantime" reloads, a frozen centrale blocks every change, a pending
/// sync is followed every five seconds for at most three minutes). The server enforces everything; this keeps the screens honest.
@MainActor
final class PbxSectionModel: ObservableObject {
    static let pollInterval: TimeInterval = 5
    /// Three minutes of polling.
    static let maxPollRounds = 36

    let account: StoredAccount

    @Published private(set) var overview: PbxOverview?
    @Published private(set) var devices: PbxDevicesResponse?
    @Published private(set) var ringGroups: PbxRingGroupsResponse?
    @Published private(set) var hours: PbxHoursResponse?
    @Published private(set) var numbers: PbxNumbersPage?
    /// The chains of the numbers that were opened, by number id.
    @Published private(set) var chains: [String: NumberChain] = [:]
    /// A chain that could not be loaded, by number id.
    @Published private(set) var chainFailures: [String: PbxFailure] = [:]
    /// The centrale does not accept changes now (frozen, being set up, error). Only a fresh answer can lift it.
    @Published private(set) var isReadOnly: Bool
    /// The last reload failed because there is no connection: what is shown is the last known state.
    @Published private(set) var isOutdated = false
    @Published private(set) var isLoading = false
    @Published private(set) var isSaving = false
    @Published private(set) var syncTimedOut = false
    @Published private(set) var loadFailure: PbxFailure?
    @Published var banner: PbxBanner?

    /// Called when the role is gone (403): the hub hides the section.
    var onAccessLost: (() -> Void)?
    /// Called on a 401: the pairing is gone, the app refreshes its accounts.
    var onRevoked: (() -> Void)?

    private let service: PbxServicing
    private let gate: LocalAccessGate
    private let sleep: @Sendable (TimeInterval) async -> Void
    private var pollTask: Task<Void, Never>?
    private let authReason: () -> String

    init(
        account: StoredAccount,
        readOnly: Bool,
        service: PbxServicing,
        gate: LocalAccessGate,
        authReason: @escaping () -> String = { L10n.string("pbx.lock.reason") },
        sleep: @escaping @Sendable (TimeInterval) async -> Void = { seconds in try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)) }
    ) {
        self.account = account
        isReadOnly = readOnly
        self.service = service
        self.gate = gate
        self.authReason = authReason
        self.sleep = sleep
    }

    deinit {
        pollTask?.cancel()
    }

    // MARK: Reading

    var pbxName: String {
        overview?.pbx.name ?? account.pbxName
    }

    /// Anything for `PbxNotices` to show.
    var hasNotices: Bool {
        isReadOnly || isOutdated || hasPendingSync || syncTimedOut || banner != nil || loadFailure != nil
    }

    /// Something is still being applied on the centrale ("Wordt bijgewerkt").
    var hasPendingSync: Bool {
        if overview?.numbers.contains(where: { $0.sync == .pending }) == true { return true }
        if devices?.devices.contains(where: { $0.sync == .pending }) == true { return true }
        if ringGroups?.ringGroups.contains(where: { $0.sync == .pending }) == true { return true }
        if hours?.hours.contains(where: { $0.sync == .pending }) == true { return true }
        if numbers?.numbers.contains(where: { $0.sync == .pending }) == true { return true }
        if chains.values.contains(where: { $0.sync == .pending }) { return true }

        return false
    }

    /// Everything a call can be sent to (from whichever list was loaded last).
    var targetOptions: [PbxTargetOption] {
        devices?.targets ?? ringGroups?.targets ?? hours?.targets ?? []
    }

    func load(_ part: PbxPart) async {
        do {
            switch part {
            case .overview:
                let value = try await service.overview(for: account)
                overview = value
                isReadOnly = value.pbx.readOnly
            case .devices:
                devices = try await service.devices(for: account)
            case .ringGroups:
                ringGroups = try await service.ringGroups(for: account)
            case .hours:
                hours = try await service.hours(for: account)
            case .numbers:
                numbers = try await service.numbers(for: account)
            }

            isOutdated = false
            loadFailure = nil
        } catch {
            handleLoadError(error)
        }
    }

    /// Loads a part that is not there yet (the first time a screen opens), and keeps the five-minute window alive.
    func loadIfNeeded(_ part: PbxPart) async {
        let missing: Bool

        switch part {
        case .overview: missing = overview == nil
        case .devices: missing = devices == nil
        case .ringGroups: missing = ringGroups == nil
        case .hours: missing = hours == nil
        case .numbers: missing = numbers == nil
        }

        if missing {
            isLoading = true
            await load(part)
            isLoading = false
        }

        gate.touch()
        startPollingIfNeeded()
    }

    /// Pull to refresh: this part again (and the overview, which shows all numbers).
    func refresh(_ part: PbxPart) async {
        await load(part)

        if part != .overview {
            await load(.overview)
        }

        gate.touch()
        startPollingIfNeeded()
    }

    /// Reload what was loaded before (and always the overview).
    func reloadLoaded() async {
        await load(.overview)

        if devices != nil { await load(.devices) }
        if ringGroups != nil { await load(.ringGroups) }
        if hours != nil { await load(.hours) }
        if numbers != nil { await load(.numbers) }

        for id in chains.keys.sorted() {
            await loadChain(id)
        }
    }

    // MARK: Number chains

    /// Reads the chain of one number (again).
    func loadChain(_ numberId: String) async {
        do {
            let chain = try await service.numberChain(for: account, numberId: numberId)
            chains[numberId] = chain
            chainFailures[numberId] = nil
            isOutdated = false
        } catch {
            let failure = PbxFailure.classify(error)

            switch failure {
            case .accessLost, .revoked, .offline, .readOnly:
                handleLoadError(error)
            default:
                chainFailures[numberId] = failure
            }
        }
    }

    /// The chain screen opens: load it when it is not there, and keep the five-minute window alive.
    func loadChainIfNeeded(_ numberId: String) async {
        if chains[numberId] == nil {
            isLoading = true
            await loadChain(numberId)
            isLoading = false
        }

        gate.touch()
        startPollingIfNeeded()
    }

    /// One step of a chain (`nil` = nothing changed, nothing is sent). The answer is the fresh chain.
    func saveChainStep(numberId: String, _ step: (any NumberChainStepRequest)?) async -> ChainSaveOutcome {
        guard let step else {
            return .unchanged
        }

        return await mutateChain(numberId: numberId) {
            try await self.send(step, numberId: numberId)
        }
    }

    private func send<Step: NumberChainStepRequest>(_ step: Step, numberId: String) async throws -> NumberChain {
        try await service.saveChainStep(for: account, numberId: numberId, step: step)
    }

    /// Call recording of a number (`nil` = nothing changed).
    func saveRecording(numberId: String, _ patch: NumberRecordingPatch?) async -> ChainSaveOutcome {
        guard let patch else {
            return .unchanged
        }

        return await mutateChain(numberId: numberId) {
            try await self.service.setNumberRecording(for: self.account, numberId: numberId, patch: patch)
        }
    }

    /// Like `mutate`, but the answer is the fresh chain and a "changed in the meantime" keeps the form open: the fresh chain is
    /// shown, what the user typed stays (the form rebases onto it).
    private func mutateChain(numberId: String, _ send: @escaping () async throws -> NumberChain) async -> ChainSaveOutcome {
        guard !isReadOnly else {
            return .failed(.readOnly)
        }

        // Taken before the Face ID prompt: a second tap while it is open must not send a second request.
        guard !isSaving else {
            return .unchanged
        }

        isSaving = true
        defer { isSaving = false }

        switch await gate.ensureUnlocked(reason: authReason()) {
        case .unlocked:
            break
        case .unavailable:
            return .failed(.notAvailable)
        case .cancelled, .failed:
            return .failed(.authentication)
        }

        let fresh: NumberChain

        do {
            fresh = try await send()
        } catch let APIError.staleChain(chain) {
            chains[numberId] = chain
            return .stale
        } catch let APIError.costNotAccepted(cost) {
            return .costRequired(cost)
        } catch {
            let failure = PbxFailure.classify(error)

            switch failure {
            case .stale:
                // A plain 409 without the chain: read it again.
                await loadChain(numberId)
                return .stale
            case .advanced:
                // The number turned out to be more than the chain can show: show it read-only.
                await loadChain(numberId)
                return .failed(.advanced)
            default:
                let outcome = await handleSaveError(error)
                if case let .failed(reason) = outcome { return .failed(reason) }
                return .failed(failure)
            }
        }

        chains[numberId] = fresh
        chainFailures[numberId] = nil
        banner = nil
        syncTimedOut = false

        if numbers != nil { await load(.numbers) }
        if overview != nil { await load(.overview) }

        gate.touch()
        startPollingIfNeeded()

        return .saved
    }

    private func handleLoadError(_ error: Error) {
        // Cancelled by the screen or by SwiftUI: not a failure, never "no connection".
        if APIError.isCancellation(error) { return }

        let failure = PbxFailure.classify(error)

        switch failure {
        case .accessLost:
            accessLost()
        case .revoked:
            onRevoked?()
        case .offline:
            // Keep the last known state and say it is not current.
            isOutdated = true
        case .readOnly:
            isReadOnly = true
        default:
            loadFailure = failure
        }
    }

    /// The centrale state from `GET /me`: it can lift a block (activated) or set one (frozen).
    func noteReadOnly(_ value: Bool) {
        isReadOnly = value
    }

    private func accessLost() {
        banner = PbxBanner(text: PbxFailure.accessLost.message, isError: true)
        onAccessLost?()
    }

    // MARK: Saving

    func saveDevice(_ draft: DeviceDraft, original: PbxDevice) async -> PbxSaveOutcome {
        guard let patch = draft.patch(from: original) else {
            return .unchanged
        }

        return await mutate {
            try await self.service.updateDevice(for: self.account, id: original.id, patch: patch)
        }
    }

    func createRingGroup(_ draft: RingGroupDraft) async -> PbxSaveOutcome {
        let body = draft.creation()

        return await mutate {
            _ = try await self.service.createRingGroup(for: self.account, body)
        }
    }

    func saveRingGroup(_ draft: RingGroupDraft, original: PbxRingGroup) async -> PbxSaveOutcome {
        guard let patch = draft.patch(from: original) else {
            return .unchanged
        }

        return await mutate {
            try await self.service.updateRingGroup(for: self.account, id: original.id, patch: patch)
        }
    }

    func saveHours(_ draft: HoursDraft, original: PbxHours) async -> PbxSaveOutcome {
        guard let patch = draft.patch(from: original) else {
            return .unchanged
        }

        return await applyHoursPatch(patch, id: original.id)
    }

    /// "Tijdelijk dicht": the holiday list replaces the old one (see `TemporaryClosure`).
    func saveHolidays(_ holidays: [PbxHolidayInput], original: PbxHours) async -> PbxSaveOutcome {
        await applyHoursPatch(PbxHoursPatch(version: original.version, holidays: holidays), id: original.id)
    }

    private func applyHoursPatch(_ patch: PbxHoursPatch, id: String) async -> PbxSaveOutcome {
        await mutate {
            try await self.service.updateHours(for: self.account, id: id, patch: patch)
        }
    }

    /// `target == nil` = back to the start of the call flow of the centrale.
    func saveRouting(of number: PbxNumber, to target: PbxTarget?) async -> PbxSaveOutcome {
        if target == number.routing {
            return .unchanged
        }

        let patch = PbxRoutingPatch(target: target, version: number.version)

        return await mutate {
            try await self.service.setNumberRouting(for: self.account, numberId: number.id, patch: patch)
        }
    }

    /// The common shape of every change: frozen? asked the phone? send, then read again, then follow a pending sync.
    private func mutate(_ send: @escaping () async throws -> Void) async -> PbxSaveOutcome {
        guard !isReadOnly else {
            return .failed(.readOnly)
        }

        // Taken before the Face ID prompt: a second tap while it is open must not send a second request.
        guard !isSaving else {
            return .unchanged
        }

        isSaving = true
        defer { isSaving = false }

        switch await gate.ensureUnlocked(reason: authReason()) {
        case .unlocked:
            break
        case .unavailable:
            return .failed(.notAvailable)
        case .cancelled, .failed:
            return .failed(.authentication)
        }

        do {
            try await send()
        } catch {
            return await handleSaveError(error)
        }

        banner = nil
        syncTimedOut = false
        await reloadLoaded()
        gate.touch()
        startPollingIfNeeded()

        return .saved
    }

    private func handleSaveError(_ error: Error) async -> PbxSaveOutcome {
        let failure = PbxFailure.classify(error)

        switch failure {
        case .accessLost:
            accessLost()
        case .revoked:
            onRevoked?()
        case .readOnly:
            isReadOnly = true
            await load(.overview)
        case .stale:
            // Someone (or this phone, an instant ago) changed it: load what is there now; the form closes.
            banner = PbxBanner(text: failure.message, isError: false)
            await reloadLoaded()

            return .stale
        default:
            break
        }

        return .failed(failure)
    }

    // MARK: Following a pending sync

    func startPollingIfNeeded() {
        guard pollTask == nil, hasPendingSync else {
            return
        }

        syncTimedOut = false
        pollTask = Task { [weak self] in
            await self?.poll()
        }
    }

    func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    private func poll() async {
        var rounds = 0

        while !Task.isCancelled, hasPendingSync {
            guard rounds < Self.maxPollRounds else {
                syncTimedOut = true
                break
            }

            rounds += 1
            await sleep(Self.pollInterval)

            guard !Task.isCancelled else {
                break
            }

            await reloadLoaded()
        }

        pollTask = nil
    }
}
