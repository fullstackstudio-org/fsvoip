// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation

/// One finished call in the local history. Stays on the phone; never uploaded.
public struct RecentCall: Codable, Equatable, Identifiable, Sendable {
    public enum Direction: String, Codable, Sendable {
        case incoming
        case outgoing
    }

    public enum Outcome: String, Codable, Sendable {
        /// The call was connected.
        case answered
        /// Incoming and not answered here.
        case missed
        /// Incoming and declined here.
        case declined
        /// Outgoing and not answered, busy, or ended before it was answered.
        case notAnswered
        case failed
    }

    public var id: String
    public var number: String
    public var name: String?
    public var accountId: String
    public var accountLabel: String
    public var direction: Direction
    public var outcome: Outcome
    public var startedAt: Date
    /// Seconds connected (0 when the call was never answered).
    public var duration: TimeInterval

    public init(
        id: String = UUID().uuidString,
        number: String,
        name: String?,
        accountId: String,
        accountLabel: String,
        direction: Direction,
        outcome: Outcome,
        startedAt: Date,
        duration: TimeInterval
    ) {
        self.id = id
        self.number = number
        self.name = name
        self.accountId = accountId
        self.accountLabel = accountLabel
        self.direction = direction
        self.outcome = outcome
        self.startedAt = startedAt
        self.duration = duration
    }
}

/// Local call history, newest first, at most `limit` entries.
public final class RecentCallsStore: @unchecked Sendable {
    public static let defaultLimit = 200

    private let defaults: UserDefaults
    private let key: String
    private let limit: Int
    private let lock = NSLock()

    public init(defaults: UserDefaults = .standard, key: String = "fsvoip.recent-calls", limit: Int = RecentCallsStore.defaultLimit) {
        self.defaults = defaults
        self.key = key
        self.limit = limit
    }

    public func all() -> [RecentCall] {
        lock.withLock { load() }
    }

    public func add(_ call: RecentCall) {
        lock.withLock {
            var calls = load()
            calls.insert(call, at: 0)
            save(Array(calls.prefix(limit)))
        }
    }

    public func remove(accountId: String) {
        lock.withLock { save(load().filter { $0.accountId != accountId }) }
    }

    public func clear() {
        lock.withLock { save([]) }
    }

    private func load() -> [RecentCall] {
        guard let data = defaults.data(forKey: key), let calls = try? FSVoipJSON.decoder().decode([RecentCall].self, from: data) else {
            return []
        }

        return calls
    }

    private func save(_ calls: [RecentCall]) {
        defaults.set(try? FSVoipJSON.encoder().encode(calls), forKey: key)
    }
}
