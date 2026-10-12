// SPDX-License-Identifier: AGPL-3.0-or-later
import Combine
import Core
import Foundation

/// Live calls (plan `fss-features-2026-10`, Task 4): who is calling the PBX right now, taking a ringing call over to the own extension
/// and transferring a call (blind, internal only).
///
/// - There is no push for this: the list is polled every few seconds, only while the screen is visible (the view starts and stops it).
/// - `ref` changes with every response; the row identity (`LiveCall.id`) does not. An action sends the `ref` the row has in the latest list.
/// - Picking up always lands on the own extension: the server takes it from the pairing, the request carries no extension.
/// - Attended transfer (talk to the colleague first) is an app-side SIP matter on the running call; this model only does the blind one.
@MainActor
public final class LiveModel: ObservableObject {
    public enum PickupResult: Equatable {
        case pickedUp
        case failed(LiveFailure)
        /// The same call is already being handled.
        case ignored
    }

    public enum TransferResult: Equatable {
        case transferred(destination: String?)
        case failed(LiveFailure)
        case ignored
    }

    @Published public private(set) var calls: [LiveCall] = []
    /// `nil` = not known yet. `false` = live calls are off for this PBX: show the explanation instead of a list.
    @Published public private(set) var enabled: Bool?
    /// `false` = the list may lag a few seconds.
    @Published public private(set) var fresh = true
    @Published public private(set) var targets: [LiveTarget] = []
    @Published public private(set) var readOnly = false
    @Published public private(set) var isLoading = false
    /// The last refresh failed (the old list stays).
    @Published public private(set) var failure: LiveFailure?
    @Published public private(set) var isPolling = false
    @Published public private(set) var busyIds: Set<String> = []

    /// Tells the user something (text, isError). Wired by the app model.
    public var notify: (String, Bool) -> Void = { _, _ in }
    /// A 401: the pairing is gone.
    public var onRevoked: (() -> Void)?

    let service: LiveServicing
    private let sleep: @Sendable (TimeInterval) async -> Void
    private(set) var refreshCount = 0
    private var currentAccountId: String?
    private var targetsAccountId: String?
    private var pollTask: Task<Void, Never>?

    public init(service: LiveServicing, sleep: @escaping @Sendable (TimeInterval) async -> Void = { seconds in
        try? await Task.sleep(nanoseconds: UInt64(max(0, seconds) * 1_000_000_000))
    }) {
        self.service = service
        self.sleep = sleep
    }

    // MARK: Reading

    public var ringing: [LiveCall] { calls.filter(\.isRinging) }
    public var running: [LiveCall] { calls.filter(\.isRunning) }
    public var parked: [LiveCall] { calls.filter(\.isParked) }

    public func forget(accountId: String) {
        guard currentAccountId == accountId else { return }

        stopPolling()
        calls = []
        enabled = nil
        failure = nil
        targets = []
        targetsAccountId = nil
        currentAccountId = nil
    }

    // MARK: Refreshing

    /// Reads the list of this account. Switching account starts from an empty list.
    @discardableResult
    public func refresh(_ account: StoredAccount) async -> Bool {
        if currentAccountId != account.id {
            currentAccountId = account.id
            calls = []
            enabled = nil
            failure = nil
        }

        isLoading = true
        refreshCount += 1
        defer { isLoading = false }

        do {
            let page = try await service.calls(for: account)

            guard currentAccountId == account.id else { return false }

            // Longest running first inside a state: a stable order between polls.
            calls = page.calls.sorted { $0.startedAt < $1.startedAt }
            enabled = page.enabled
            fresh = page.fresh
            failure = nil

            if page.enabled, targetsAccountId != account.id {
                await loadTargets(account)
            }

            return true
        } catch {
            guard currentAccountId == account.id, !APIError.isCancellation(error) else { return false }

            let classified = LiveFailure.classify(error)

            if classified == .unavailable {
                // The module cannot do it now: the same as "off".
                calls = []
                enabled = false
                failure = nil
            } else {
                failure = classified
            }

            if classified == .revoked { onRevoked?() }

            return false
        }
    }

    /// The transfer targets of this PBX, once per account (they rarely change; a transfer that fails with a stale target reloads them).
    public func loadTargets(_ account: StoredAccount) async {
        do {
            let page = try await service.targets(for: account)

            guard currentAccountId == nil || currentAccountId == account.id else { return }

            targets = page.targets
            readOnly = page.readOnly
            targetsAccountId = account.id
        } catch {
            if LiveFailure.classify(error) == .revoked { onRevoked?() }
            // Without targets the transfer button stays off; the next poll tries again.
        }
    }

    /// Polls while the screen is visible. Calling it again for the same account does nothing; another account restarts it.
    public func startPolling(_ account: StoredAccount, interval: TimeInterval = 3) {
        if isPolling, currentAccountId == account.id { return }

        stopPolling()
        currentAccountId = currentAccountId ?? account.id
        isPolling = true

        let sleep = sleep
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }

                await refresh(account)
                await sleep(interval)
            }
        }
    }

    public func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
        isPolling = false
    }

    // MARK: Picking up

    /// Takes the ringing call over to the own extension and says what happened. The call then rings on the own phone.
    public func pickup(_ call: LiveCall, account: StoredAccount) async -> PickupResult {
        guard !busyIds.contains(call.id) else { return .ignored }

        busyIds.insert(call.id)
        defer { busyIds.remove(call.id) }

        do {
            _ = try await service.pickup(ref: call.ref, for: account)
            _ = await refresh(account)

            return .pickedUp
        } catch {
            if APIError.isCancellation(error) { return .ignored }

            let classified = LiveFailure.classify(error)

            if classified == .revoked { onRevoked?() }

            // The call changed in the meantime: show the truth.
            if classified == .notRinging || classified == .callNotFound { _ = await refresh(account) }

            return .failed(classified)
        }
    }

    // MARK: Transferring

    public func transfer(_ call: LiveCall, to target: LiveTarget, account: StoredAccount) async -> TransferResult {
        guard !busyIds.contains(call.id) else { return .ignored }

        busyIds.insert(call.id)
        defer { busyIds.remove(call.id) }

        do {
            let response = try await service.transfer(ref: call.ref, to: target, for: account)
            _ = await refresh(account)

            return .transferred(destination: response.destinationType)
        } catch {
            if APIError.isCancellation(error) { return .ignored }

            let classified = LiveFailure.classify(error)

            if classified == .revoked { onRevoked?() }

            if classified == .notTransferable || classified == .callNotFound { _ = await refresh(account) }

            if classified == .invalidTarget {
                // The list of targets was stale: read it again.
                targetsAccountId = nil
                await loadTargets(account)
            }

            return .failed(classified)
        }
    }
}

extension LiveFailure {
    /// The sentence for the user. No server text, no telecom words.
    var message: String {
        switch self {
        case .callNotFound:
            return L10n.string("live.error.callNotFound")
        case .notRinging:
            return L10n.string("live.error.notRinging")
        case .notTransferable:
            return L10n.string("live.error.notTransferable")
        case .unavailable:
            return L10n.string("live.error.unavailable")
        case .forbidden:
            return L10n.string("live.error.forbidden")
        case .revoked:
            return L10n.string("media.error.revoked")
        case .readOnly:
            return L10n.string("media.error.readOnly")
        case .invalidTarget:
            return L10n.string("live.error.invalidTarget")
        case let .rateLimited(seconds):
            if let seconds, seconds > 0 {
                return String(format: L10n.string("media.error.rateLimited.seconds"), seconds)
            }

            return L10n.string("media.error.rateLimited")
        case .offline:
            return L10n.string("media.error.offline")
        case .other:
            return L10n.string("error.generic")
        }
    }
}

enum LiveFormat {
    /// `1:23` since the call started (or was answered).
    static func elapsed(from date: Date, now: Date) -> String {
        MediaFormat.clock(max(0, now.timeIntervalSince(date)))
    }

    /// The title of a row: the name, else the number, else "anonymous".
    static func title(for call: LiveCall) -> String {
        if let name = call.callerName, !name.isEmpty { return name }
        if let number = call.callerNumber, !number.isEmpty { return number }

        return L10n.string("call.anonymous")
    }

    /// The line under the title: where the call is now.
    static func detail(for call: LiveCall) -> String {
        if call.isParked {
            return String(format: L10n.string("live.parkedSlot"), ParkFormat.slot(call.parked?.slot ?? 0))
        }

        if call.isRinging {
            let names = call.ringing.map(\.label).joined(separator: ", ")

            return names.isEmpty ? L10n.string("live.ringing") : String(format: L10n.string("live.ringingAt"), names)
        }

        if let by = call.answeredBy {
            return String(format: L10n.string(call.state == .held ? "live.heldAt" : "live.onTheLine"), by.label)
        }

        return L10n.string("live.direction.\(call.direction.rawValue)")
    }

    /// Targets grouped by kind, in a fixed order; a search term filters on name and number.
    static func grouped(_ targets: [LiveTarget], search: String) -> [(type: LiveTargetType, items: [LiveTarget])] {
        let needle = search.trimmingCharacters(in: .whitespaces).lowercased()
        let visible = needle.isEmpty ? targets : targets.filter { $0.name.lowercased().contains(needle) || $0.extension.contains(needle) }
        let order: [LiveTargetType] = [.extension, .ringGroup, .queue, .ivr, .businessHours, .voicemail]

        return order.compactMap { type in
            let items = visible.filter { $0.type == type }

            return items.isEmpty ? nil : (type, items)
        }
    }
}
