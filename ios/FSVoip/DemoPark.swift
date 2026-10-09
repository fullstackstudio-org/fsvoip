// SPDX-License-Identifier: AGPL-3.0-or-later
//
// DEBUG builds only: the parked calls of the demo ("On hold" tab, `-FSVoipDemoScreen onhold`; `incall` shows the park button). Two calls
// are parked, one of them by this phone; parking and hanging up change the list in memory.

#if DEBUG
import Core
import Foundation

final class DemoParkService: ParkServicing, @unchecked Sendable {
    private let lock = NSLock()
    private var items: [ParkedCall]

    init() {
        let now = Date()
        items = [
            Self.call(id: "p1", slot: 1, number: "0701234567", name: "Bakkerij Smit", parkedAt: now.addingTimeInterval(-48), by: "Jan de Vries", mine: true),
            Self.call(id: "p2", slot: 2, number: "0201234567", name: nil, parkedAt: now.addingTimeInterval(-15), by: "Werkplaats", mine: false),
        ]
    }

    func park(callId: String, for account: StoredAccount) async throws -> ParkedCall {
        try await Task.sleep(nanoseconds: 300_000_000)

        return try lock.withLock {
            let used = Set(items.map(\.slot))

            guard let slot = (1 ... 9).first(where: { !used.contains($0) }) else { throw APIError.noFreeSlot }

            let call = Self.call(id: UUID().uuidString.lowercased(), slot: slot, number: "0612345678", name: nil, parkedAt: Date(), by: "Jan de Vries", mine: true)
            items.append(call)

            return call
        }
    }

    func parked(for account: StoredAccount) async throws -> ParkedCallsPage {
        try await Task.sleep(nanoseconds: 120_000_000)

        return lock.withLock { ParkedCallsPage(calls: items) }
    }

    func hangup(id: String, for account: StoredAccount) async throws {
        try await Task.sleep(nanoseconds: 120_000_000)
        lock.withLock { items.removeAll { $0.id == id } }
    }

    private static func call(id: String, slot: Int, number: String, name: String?, parkedAt: Date, by: String, mine: Bool) -> ParkedCall {
        ParkedCall(
            id: id,
            slot: slot,
            retrieveNumber: "*59" + String(format: "%02d", slot),
            callerNumber: number,
            callerName: name,
            parkedAt: parkedAt,
            expiresAt: parkedAt.addingTimeInterval(120),
            parkedBy: ParkedBy(deviceId: nil, name: by),
            mine: mine
        )
    }
}
#endif
