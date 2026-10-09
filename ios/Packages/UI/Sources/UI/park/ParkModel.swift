// SPDX-License-Identifier: AGPL-3.0-or-later
import Combine
import Core
import Foundation

/// Parking (plan `fsvoip-app-v2`, D7, Task 16): the park button in a call and the "On hold" tab.
///
/// - Parking is `POST /calls/park` with the SIP `Call-ID` of the running call. 🚨 A `park_uncertain` answer means the call MAY be parked:
///   this model never sends that `callId` again, it refreshes the list and says so.
/// - Picking a call up is NOT an API call: the list is refreshed first (the slot may be gone), then the call's `retrieveNumber` (`*5901`)
///   is dialled as a normal call. There is no time-out logic here: the PBX rings the extension that parked the call.
/// - The list is polled every few seconds, but only while the tab is visible (the view starts and stops it); there is no push for this.
@MainActor
public final class ParkModel: ObservableObject {
    public enum Scope: Hashable {
        case all
        case mine
    }

    public enum ParkResult: Equatable {
        case parked(slot: Int)
        case failed(ParkFailure)
        /// A park request is already running (or this call is already being parked).
        case ignored
    }

    public enum RetrieveResult: Equatable {
        /// The retrieve number is being dialled.
        case dialing
        /// The call is not parked any more ("Niet meer geparkeerd").
        case gone
        /// Dialling refused; the app model already told the user why (do not overwrite that with a generic message).
        case dialFailed
        case failed(ParkFailure)
    }

    public enum HangupResult: Equatable {
        case done
        case gone
        case failed(ParkFailure)
    }

    @Published public private(set) var calls: [ParkedCall] = []
    /// `nil` = not known yet. `false` = this PBX cannot park right now: show the explanation instead of an empty list.
    @Published public private(set) var available: Bool?
    @Published public private(set) var isLoading = false
    /// The last refresh failed (the old list stays).
    @Published public private(set) var failure: ParkFailure?
    @Published public private(set) var isParking = false
    @Published public private(set) var isPolling = false
    @Published public private(set) var busyIds: Set<String> = []

    /// Dials a number on an account as a normal call. Wired by the app model.
    public var dial: (String, String) -> Bool = { _, _ in false }
    /// Tells the user something (text, isError). Wired by the app model.
    public var notify: (String, Bool) -> Void = { _, _ in }
    /// May this pairing hang up calls of colleagues (an admin)? A `user` may only hang up their own.
    public var isAdmin: (String) -> Bool = { _ in false }
    /// A 401: the pairing is gone.
    public var onRevoked: (() -> Void)?

    let service: ParkServicing
    private let sleep: @Sendable (TimeInterval) async -> Void
    private(set) var refreshCount = 0
    private var currentAccountId: String?
    private var pollTask: Task<Void, Never>?
    /// Call-IDs whose parking ended `park_uncertain`: never parked again.
    private var uncertainCallIds: Set<String> = []
    private var pendingRetrieve: PendingRetrieve?

    private struct PendingRetrieve {
        let number: String
        let accountId: String
    }

    public init(service: ParkServicing, sleep: @escaping @Sendable (TimeInterval) async -> Void = { seconds in
        try? await Task.sleep(nanoseconds: UInt64(max(0, seconds) * 1_000_000_000))
    }) {
        self.service = service
        self.sleep = sleep
    }

    // MARK: Reading

    public func calls(in scope: Scope) -> [ParkedCall] {
        scope == .mine ? calls.filter(\.mine) : calls
    }

    public var mineCount: Int {
        calls.filter(\.mine).count
    }

    /// May the user hang this call up? A `user` only a call that is theirs.
    public func canHangUp(_ call: ParkedCall, accountId: String) -> Bool {
        call.mine || isAdmin(accountId)
    }

    public func forget(accountId: String) {
        guard currentAccountId == accountId else { return }

        stopPolling()
        calls = []
        available = nil
        failure = nil
        currentAccountId = nil
    }

    // MARK: Refreshing

    /// Reads the list of this account. Switching account starts from an empty list.
    @discardableResult
    public func refresh(_ account: StoredAccount) async -> Bool {
        if currentAccountId != account.id {
            currentAccountId = account.id
            calls = []
            available = nil
            failure = nil
        }

        isLoading = true
        refreshCount += 1
        defer { isLoading = false }

        do {
            let page = try await service.parked(for: account)

            // The user may have switched account while the request ran.
            guard currentAccountId == account.id else { return false }

            calls = page.calls.sorted { ($0.parkedAt ?? .distantPast) < ($1.parkedAt ?? .distantPast) }
            available = page.available
            failure = nil

            return true
        } catch {
            guard currentAccountId == account.id else { return false }

            let classified = ParkFailure.classify(error)
            failure = classified

            if classified == .revoked { onRevoked?() }

            return false
        }
    }

    /// Polls while the tab is visible. Calling it again for the same account does nothing; another account restarts it.
    public func startPolling(_ account: StoredAccount, interval: TimeInterval = 5) {
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

    // MARK: Parking

    public func park(callId: String, account: StoredAccount) async -> ParkResult {
        guard !isParking, !uncertainCallIds.contains(callId) else {
            return .ignored
        }

        isParking = true
        defer { isParking = false }

        do {
            let parked = try await service.park(callId: callId, for: account)
            await refreshIfShowing(account)

            return .parked(slot: parked.slot)
        } catch {
            var classified = ParkFailure.classify(error)

            // A time-out, a transport error or a gateway error after the request left: the PBX may have parked the call anyway.
            if classified != .uncertain, Self.outcomeIsUncertain(error) {
                classified = .uncertain
            }

            if classified == .uncertain {
                // Look, never repeat.
                uncertainCallIds.insert(callId)
                await refreshIfShowing(account)
            } else if classified == .revoked {
                onRevoked?()
            }

            return .failed(classified)
        }
    }

    /// Does this error of the park POST leave open whether the call was parked? (No answer, or a 5xx from a proxy in between.)
    static func outcomeIsUncertain(_ error: Error) -> Bool {
        guard let api = error as? APIError else { return false }

        switch api {
        case .transport, .unavailable: return true
        case let .unexpectedStatus(status): return status == 502 || status == 504 || status == 500
        default: return false
        }
    }

    /// Refreshes only when the list on screen belongs to `account`: parking a call of another account must not switch the tab's list.
    private func refreshIfShowing(_ account: StoredAccount) async {
        guard currentAccountId == nil || currentAccountId == account.id else { return }

        await refresh(account)
    }

    // MARK: Picking up

    /// Refreshes, then dials the retrieve number of the fresh entry. A call that is gone from the list is not dialled.
    public func retrieve(_ call: ParkedCall, account: StoredAccount) async -> RetrieveResult {
        guard !busyIds.contains(call.id) else { return .failed(.other) }

        busyIds.insert(call.id)
        defer { busyIds.remove(call.id) }

        guard await refresh(account) else {
            return .failed(failure ?? .other)
        }

        guard let fresh = calls.first(where: { $0.id == call.id }), let number = fresh.retrieveNumber, !number.isEmpty else {
            return .gone
        }

        pendingRetrieve = PendingRetrieve(number: number, accountId: account.id)

        guard dial(number, account.id) else {
            pendingRetrieve = nil

            return .dialFailed
        }

        return .dialing
    }

    /// A call ended. If it was our pick-up and it never connected, the slot was gone: say so and refresh.
    public func callFinished(_ call: RecentCall, account: StoredAccount?) {
        guard let pending = pendingRetrieve, pending.accountId == call.accountId, pending.number == call.number else {
            return
        }

        pendingRetrieve = nil

        guard call.outcome != .answered else { return }

        notify(L10n.string("onHold.notice.gone"), true)

        if let account {
            Task { await refresh(account) }
        }
    }

    // MARK: Hanging up

    public func hangUp(_ call: ParkedCall, account: StoredAccount) async -> HangupResult {
        guard canHangUp(call, accountId: account.id) else {
            return .failed(.forbidden)
        }

        guard !busyIds.contains(call.id) else { return .failed(.other) }

        busyIds.insert(call.id)
        defer { busyIds.remove(call.id) }

        do {
            try await service.hangup(id: call.id, for: account)
            calls.removeAll { $0.id == call.id }
            await refresh(account)

            return .done
        } catch {
            let classified = ParkFailure.classify(error)

            if classified == .notFound {
                await refresh(account)

                return .gone
            }

            if classified == .revoked { onRevoked?() }

            return .failed(classified)
        }
    }
}

extension ParkFailure {
    /// The sentence for the user. No server text, no telecom words.
    var message: String {
        switch self {
        case .callNotFound:
            return L10n.string("park.error.callNotFound")
        case .noFreeSlot:
            return L10n.string("park.error.noFreeSlot")
        case .unavailable:
            return L10n.string("onHold.unavailable.message")
        case .uncertain:
            return L10n.string("park.error.uncertain")
        case .forbidden:
            return L10n.string("park.error.forbidden")
        case .revoked:
            return L10n.string("media.error.revoked")
        case .readOnly:
            return L10n.string("media.error.readOnly")
        case .notFound:
            return L10n.string("onHold.notice.gone")
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

enum ParkFormat {
    /// `1:23` since the call was parked.
    static func elapsed(since date: Date?, now: Date) -> String? {
        guard let date else { return nil }

        return MediaFormat.clock(now.timeIntervalSince(date))
    }

    /// `0:37` until the call rings back; `nil` when unknown or passed.
    static func remaining(until date: Date?, now: Date) -> String? {
        guard let date, date > now else { return nil }

        return MediaFormat.clock(date.timeIntervalSince(now).rounded(.up))
    }

    /// `01` for slot 1 (the PBX shows `*5901`).
    static func slot(_ slot: Int) -> String {
        String(format: "%02d", slot)
    }
}
