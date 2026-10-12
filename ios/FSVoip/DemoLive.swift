// SPDX-License-Identifier: AGPL-3.0-or-later
//
// DEBUG builds only: the live calls of the demo ("On hold" tab › Live, `-FSVoipDemoScreen live`; `live-transfer` opens the transfer sheet,
// `live-empty` shows nobody on the line, `live-off` the "switched off" screen). Four calls run: one rings, two are answered (one by this
// phone) and one is parked. Taking over and transferring change the list in memory. The `ref` changes with every answer, like on the server.

#if DEBUG
import Core
import Foundation

final class DemoLiveService: LiveServicing, @unchecked Sendable {
    private struct Entry {
        var key: String
        var direction: LiveDirection
        var state: LiveState
        var callerNumber: String?
        var callerName: String?
        var calleeNumber: String
        var ringing: [LiveParty]
        var answeredBy: LiveParty?
        var startedAt: Date
        var answeredAt: Date?
        var parkedSlot: Int?
    }

    private let lock = NSLock()
    private let screen: String?
    private var entries: [Entry] = []
    private var counter = 0
    private let own = LiveParty(number: "102", name: "Jan de Vries")
    private let targetsList: [LiveTarget] = [
        LiveTarget(type: .extension, id: "11111111-1111-4111-8111-111111111101", name: "Receptie", extension: "100"),
        LiveTarget(type: .extension, id: "11111111-1111-4111-8111-111111111103", name: "Werkplaats", extension: "103"),
        LiveTarget(type: .ringGroup, id: "11111111-1111-4111-8111-111111111200", name: "Iedereen", extension: "200"),
        LiveTarget(type: .queue, id: "11111111-1111-4111-8111-111111111300", name: "Offerteaanvragen", extension: "300"),
        LiveTarget(type: .businessHours, id: "11111111-1111-4111-8111-111111111500", name: "Kantoortijden", extension: "500"),
        LiveTarget(type: .voicemail, id: "11111111-1111-4111-8111-111111111900", name: "Voicemail Algemeen", extension: "900"),
    ]

    init(defaults: UserDefaults = .standard) {
        screen = defaults.string(forKey: "FSVoipDemoScreen")

        guard screen != "live-empty", screen != "live-off" else { return }

        let now = Date()
        entries = [
            Entry(key: "c1", direction: .inbound, state: .ringing, callerNumber: "0701234567", callerName: "Bakkerij Smit", calleeNumber: "0850000001", ringing: [LiveParty(number: "100", name: "Receptie"), own], answeredBy: nil, startedAt: now.addingTimeInterval(-9), answeredAt: nil, parkedSlot: nil),
            Entry(key: "c2", direction: .inbound, state: .active, callerNumber: "0201234567", callerName: nil, calleeNumber: "0850000001", ringing: [], answeredBy: own, startedAt: now.addingTimeInterval(-190), answeredAt: now.addingTimeInterval(-181), parkedSlot: nil),
            Entry(key: "c3", direction: .outbound, state: .active, callerNumber: "0850000001", callerName: nil, calleeNumber: "0301112233", ringing: [], answeredBy: LiveParty(number: "103", name: "Werkplaats"), startedAt: now.addingTimeInterval(-74), answeredAt: now.addingTimeInterval(-70), parkedSlot: nil),
            Entry(key: "c4", direction: .inbound, state: .parked, callerNumber: "0612345678", callerName: "Pieter Jansen", calleeNumber: "0850000001", ringing: [], answeredBy: nil, startedAt: now.addingTimeInterval(-320), answeredAt: nil, parkedSlot: 1),
        ]
    }

    private func isAdmin(_ account: StoredAccount) -> Bool { account.id == DemoMode.exampleAccounts[0].id }

    func calls(for account: StoredAccount) async throws -> LiveCallsPage {
        try await Task.sleep(nanoseconds: 150_000_000)

        if screen == "live-off" { return LiveCallsPage(enabled: false, fresh: false, calls: []) }

        return lock.withLock {
            counter += 1
            let admin = isAdmin(account)

            return LiveCallsPage(
                enabled: true,
                fresh: true,
                calls: entries.map { entry in
                    let mine = entry.answeredBy == own || entry.ringing.contains(own)

                    return LiveCall(
                        ref: "demo-ref-\(counter)-\(entry.key)-" + String(repeating: "x", count: 40),
                        direction: entry.direction,
                        state: entry.state,
                        callerNumber: entry.callerNumber,
                        callerName: entry.callerName,
                        calleeNumber: entry.calleeNumber,
                        ringing: entry.ringing,
                        answeredBy: entry.answeredBy,
                        startedAt: entry.startedAt,
                        answeredAt: entry.answeredAt,
                        parked: entry.parkedSlot.map { LiveParked(slot: $0, retrieveNumber: "*59" + String(format: "%02d", $0)) },
                        canPickup: entry.state == .ringing && !entry.ringing.contains(own),
                        canTransfer: entry.state == .active && (admin || entry.answeredBy == own),
                        mine: mine
                    )
                }
            )
        }
    }

    func targets(for account: StoredAccount) async throws -> LiveTargetsPage {
        try await Task.sleep(nanoseconds: 100_000_000)

        return LiveTargetsPage(readOnly: false, targets: targetsList)
    }

    private func index(of ref: String) -> Int? {
        let parts = ref.split(separator: "-")

        guard parts.count >= 4 else { return nil }

        return entries.firstIndex { $0.key == String(parts[3]) }
    }

    func pickup(ref: String, for account: StoredAccount) async throws -> LivePickupResponse {
        try await Task.sleep(nanoseconds: 250_000_000)

        return try lock.withLock {
            guard let i = index(of: ref) else { throw APIError.callNotFound }
            guard entries[i].state == .ringing else { throw APIError.conflict(code: "call_not_ringing") }

            entries[i].state = .active
            entries[i].ringing = []
            entries[i].answeredBy = own
            entries[i].answeredAt = Date()

            return LivePickupResponse(pickedBy: own)
        }
    }

    func transfer(ref: String, to target: LiveTarget, for account: StoredAccount) async throws -> LiveTransferResponse {
        try await Task.sleep(nanoseconds: 250_000_000)

        return try lock.withLock {
            guard let i = index(of: ref) else { throw APIError.callNotFound }
            guard entries[i].state == .active else { throw APIError.conflict(code: "call_not_transferable") }
            guard isAdmin(account) || entries[i].answeredBy == own else { throw APIError.forbidden }

            if target.type == .voicemail {
                entries.remove(at: i)

                return LiveTransferResponse(destinationType: "voicemail")
            }

            entries[i].state = .ringing
            entries[i].answeredBy = nil
            entries[i].answeredAt = nil
            entries[i].ringing = [LiveParty(number: target.extension, name: target.name)]
            entries[i].startedAt = Date()

            return LiveTransferResponse(destinationType: "internal")
        }
    }
}
#endif
