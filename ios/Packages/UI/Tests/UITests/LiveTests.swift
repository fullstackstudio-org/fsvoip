// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation
import SwiftUI
import XCTest
@testable import UI

private final class FakeLiveService: LiveServicing, @unchecked Sendable {
    private let lock = NSLock()
    private(set) var log: [String] = []
    private(set) var pickupRefs: [String] = []
    private(set) var transfers: [(ref: String, target: String)] = []
    var page = LiveCallsPage(calls: [])
    var targets = LiveTargetsPage(targets: [])
    var listError: Error?
    var pickupError: Error?
    var transferError: Error?

    func count(_ name: String) -> Int { lock.withLock { log.filter { $0 == name }.count } }

    func calls(for account: StoredAccount) async throws -> LiveCallsPage {
        lock.withLock { log.append("calls") }

        if let listError { throw listError }

        return lock.withLock { page }
    }

    func targets(for account: StoredAccount) async throws -> LiveTargetsPage {
        lock.withLock { log.append("targets") }

        return lock.withLock { targets }
    }

    func pickup(ref: String, for account: StoredAccount) async throws -> LivePickupResponse {
        lock.withLock {
            log.append("pickup")
            pickupRefs.append(ref)
        }

        if let pickupError { throw pickupError }

        return LivePickupResponse(pickedBy: LiveParty(number: "102", name: "Jan"))
    }

    func transfer(ref: String, to target: LiveTarget, for account: StoredAccount) async throws -> LiveTransferResponse {
        lock.withLock {
            log.append("transfer")
            transfers.append((ref, target.id))
        }

        if let transferError { throw transferError }

        return LiveTransferResponse(destinationType: target.type == .voicemail ? "voicemail" : "internal")
    }
}

private func liveAccount(_ id: String = "a") -> StoredAccount {
    StoredAccount(id: id, label: "Jan", pbxName: "Voorbeeld Bouw", extensionName: "Jan de Vries", extensionNumber: "102", customerName: "Voorbeeld", deviceToken: Secret("fss_vapp_x"), installId: "a1b2c3d4e5f60718", sip: SIPCredentials(username: "102", password: Secret("pw"), domain: "x.powervoip.nl", proxy: "sip.powervoip.nl", port: 5061, transport: .tls, srv: true), pairedAt: Date(timeIntervalSince1970: 1_790_000_000))
}

private func liveCall(_ ref: String, state: LiveState, started: TimeInterval = -30, caller: String = "0701234567", name: String? = "Bakkerij Smit", pickup: Bool = false, transfer: Bool = false, ringing: [LiveParty] = [], by: LiveParty? = nil) -> LiveCall {
    LiveCall(ref: ref, state: state, callerNumber: caller, callerName: name, calleeNumber: "0850000001", ringing: ringing, answeredBy: by, startedAt: Date(timeIntervalSinceNow: started), answeredAt: by == nil ? nil : Date(timeIntervalSinceNow: started + 5), canPickup: pickup, canTransfer: transfer)
}

private let target101 = LiveTarget(type: .extension, id: "t1", name: "Receptie", extension: "101")
private let targetVoicemail = LiveTarget(type: .voicemail, id: "t9", name: "Voicemail Algemeen", extension: "900")

@MainActor
final class LiveTests: XCTestCase {
    private var service: FakeLiveService!
    private var model: LiveModel!
    private let account = liveAccount()

    override func setUp() async throws {
        service = FakeLiveService()
        service.targets = LiveTargetsPage(targets: [target101, targetVoicemail])
        model = LiveModel(service: service, sleep: { _ in await Task.yield() })
    }

    // MARK: Reading

    func testRefreshFillsTheListOldestFirstAndLoadsTargetsOnce() async {
        service.page = LiveCallsPage(calls: [
            liveCall("r2", state: .active, started: -10, caller: "0201111111", name: nil, transfer: true, by: LiveParty(number: "102", name: "Jan")),
            liveCall("r1", state: .ringing, started: -50, pickup: true, ringing: [LiveParty(number: "101", name: "Receptie")]),
            liveCall("r3", state: .parked, started: -90),
        ])

        let first = await model.refresh(account)
        let second = await model.refresh(account)
        XCTAssertTrue(first && second)

        XCTAssertEqual(model.calls.map(\.ref), ["r3", "r1", "r2"])
        XCTAssertEqual(model.ringing.map(\.ref), ["r1"])
        XCTAssertEqual(model.running.map(\.ref), ["r2"])
        XCTAssertEqual(model.parked.map(\.ref), ["r3"])
        XCTAssertEqual(model.enabled, true)
        XCTAssertEqual(service.count("targets"), 1, "targets are read once per account")
        XCTAssertEqual(model.targets.count, 2)
    }

    func testDisabledPbxHidesTheListAndSkipsTargets() async {
        service.page = LiveCallsPage(enabled: false, fresh: false, calls: [])

        await model.refresh(account)

        XCTAssertEqual(model.enabled, false)
        XCTAssertEqual(service.count("targets"), 0)
    }

    func testAServerThatCannotDoLiveCallsIsTheSameAsOff() async {
        service.listError = APIError.conflict(code: "live_unavailable")

        let refreshed = await model.refresh(account)
        XCTAssertFalse(refreshed)

        XCTAssertEqual(model.enabled, false)
        XCTAssertNil(model.failure)
    }

    func testAFailedPollKeepsTheOldListAndRemembersTheFailure() async {
        service.page = LiveCallsPage(calls: [liveCall("r1", state: .ringing, pickup: true)])
        await model.refresh(account)

        service.listError = APIError.transport("offline")

        let failed = await model.refresh(account)
        XCTAssertFalse(failed)
        XCTAssertEqual(model.calls.count, 1)
        XCTAssertEqual(model.failure, .offline)

        service.listError = nil
        await model.refresh(account)
        XCTAssertNil(model.failure)
    }

    func testARevokedPairingIsReported() async {
        var revoked = false
        model.onRevoked = { revoked = true }
        service.listError = APIError.unauthorized

        await model.refresh(account)

        XCTAssertTrue(revoked)
        XCTAssertEqual(model.failure, .revoked)
    }

    func testSwitchingAccountStartsFromAnEmptyList() async {
        service.page = LiveCallsPage(calls: [liveCall("r1", state: .ringing)])
        await model.refresh(account)

        service.page = LiveCallsPage(calls: [])
        await model.refresh(liveAccount("b"))

        XCTAssertTrue(model.calls.isEmpty)
        XCTAssertEqual(service.count("targets"), 2, "targets are per account")
    }

    // MARK: Polling

    func testPollingStopsWhenTheScreenIsLeft() async throws {
        service.page = LiveCallsPage(calls: [])
        model.startPolling(account, interval: 0)
        XCTAssertTrue(model.isPolling)

        try await Task.sleep(nanoseconds: 100_000_000)
        model.stopPolling()
        XCTAssertFalse(model.isPolling)

        let polled = service.count("calls")
        XCTAssertGreaterThan(polled, 1)

        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertEqual(service.count("calls"), polled, "no polling after the screen is left")
    }

    func testStartingTwiceForTheSameAccountDoesNothing() async {
        model.startPolling(account, interval: 3600)
        model.startPolling(account, interval: 3600)

        XCTAssertTrue(model.isPolling)
        model.stopPolling()
    }

    func testPollsOnlyWhenTheOnHoldTabIsVisibleAndTheAppActive() {
        XCTAssertTrue(LiveCallsContent.shouldPoll(phase: .active, selectedTab: .onHold))
        XCTAssertFalse(LiveCallsContent.shouldPoll(phase: .background, selectedTab: .onHold))
        XCTAssertFalse(LiveCallsContent.shouldPoll(phase: .inactive, selectedTab: .onHold))
        XCTAssertFalse(LiveCallsContent.shouldPoll(phase: .active, selectedTab: .recents))
    }

    // MARK: Picking up

    func testPickupSendsTheRefOfTheRowAndRefreshes() async {
        service.page = LiveCallsPage(calls: [liveCall("ref-ringing", state: .ringing, pickup: true)])
        await model.refresh(account)

        let result = await model.pickup(model.calls[0], account: account)

        XCTAssertEqual(result, .pickedUp)
        XCTAssertEqual(service.pickupRefs, ["ref-ringing"])
        XCTAssertEqual(service.count("calls"), 2, "the list is read again after the pick-up")
    }

    func testPickupOfACallSomebodyElseAnsweredRefreshesAndExplains() async {
        service.page = LiveCallsPage(calls: [liveCall("r1", state: .ringing, pickup: true)])
        await model.refresh(account)
        service.pickupError = APIError.conflict(code: "call_not_ringing")
        service.page = LiveCallsPage(calls: [liveCall("r1", state: .active, by: LiveParty(number: "101", name: "Receptie"))])

        let result = await model.pickup(model.calls[0], account: account)

        XCTAssertEqual(result, .failed(.notRinging))
        XCTAssertEqual(model.ringing.count, 0)
        XCTAssertEqual(model.running.count, 1, "the truth is on screen")
        XCTAssertEqual(LiveFailure.notRinging.message, L10n.string("live.error.notRinging"))
    }

    // MARK: Transferring

    func testTransferSendsRefAndTargetIdAndReportsTheKindOfDestination() async {
        service.page = LiveCallsPage(calls: [liveCall("ref-active", state: .active, transfer: true, by: LiveParty(number: "102", name: "Jan"))])
        await model.refresh(account)

        let toExtension = await model.transfer(model.calls[0], to: target101, account: account)
        let toVoicemail = await model.transfer(model.calls[0], to: targetVoicemail, account: account)

        XCTAssertEqual(toExtension, .transferred(destination: "internal"))
        XCTAssertEqual(toVoicemail, .transferred(destination: "voicemail"))
        XCTAssertEqual(service.transfers.map(\.ref), ["ref-active", "ref-active"])
        XCTAssertEqual(service.transfers.map(\.target), ["t1", "t9"])
    }

    func testTransferFailuresAreClassified() async {
        service.page = LiveCallsPage(calls: [liveCall("r", state: .active, transfer: true, by: LiveParty(number: "102", name: "Jan"))])
        await model.refresh(account)

        let cases: [(Error, LiveFailure)] = [
            (APIError.forbidden, .forbidden),
            (APIError.conflict(code: "call_not_transferable"), .notTransferable),
            (APIError.callNotFound, .callNotFound),
            (APIError.readOnly, .readOnly),
            (APIError.rateLimited(retryAfterSeconds: 30), .rateLimited(retryAfterSeconds: 30)),
            (APIError.invalidRequest(message: nil), .invalidTarget),
        ]

        for (error, expected) in cases {
            service.transferError = error

            let result = await model.transfer(model.calls[0], to: target101, account: account)
            XCTAssertEqual(result, .failed(expected))
        }
    }

    func testAnInvalidTargetReloadsTheTargets() async {
        service.page = LiveCallsPage(calls: [liveCall("r", state: .active, transfer: true, by: LiveParty(number: "102", name: "Jan"))])
        await model.refresh(account)
        service.transferError = APIError.invalid(code: "invalid_target", field: nil)

        _ = await model.transfer(model.calls[0], to: target101, account: account)

        XCTAssertEqual(service.count("targets"), 2)
    }

    // MARK: Text

    func testRowTitlesAndDetailLines() {
        let ringing = liveCall("r", state: .ringing, ringing: [LiveParty(number: "101", name: "Receptie"), LiveParty(number: "102", name: nil)])
        let active = liveCall("a", state: .active, name: nil, by: LiveParty(number: "102", name: "Jan"))
        let parked = LiveCall(ref: "p", state: .parked, callerNumber: nil, startedAt: Date(), parked: LiveParked(slot: 3, retrieveNumber: "*5903"))

        XCTAssertEqual(LiveFormat.title(for: ringing), "Bakkerij Smit")
        XCTAssertEqual(LiveFormat.title(for: active), "0701234567")
        XCTAssertEqual(LiveFormat.title(for: parked), L10n.string("call.anonymous"))
        XCTAssertEqual(LiveFormat.detail(for: ringing), String(format: L10n.string("live.ringingAt"), "Receptie (101), 102"))
        XCTAssertEqual(LiveFormat.detail(for: active), String(format: L10n.string("live.onTheLine"), "Jan (102)"))
        XCTAssertEqual(LiveFormat.detail(for: parked), String(format: L10n.string("live.parkedSlot"), "03"))
        XCTAssertEqual(LiveFormat.elapsed(from: Date(timeIntervalSinceNow: -187), now: Date()), "3:07")
    }

    func testTargetsAreGroupedInAFixedOrderAndSearchable() {
        let all = [targetVoicemail, target101, LiveTarget(type: .queue, id: "q", name: "Offertes", extension: "300")]

        XCTAssertEqual(LiveFormat.grouped(all, search: "").map(\.type), [.extension, .queue, .voicemail])
        XCTAssertEqual(LiveFormat.grouped(all, search: "off").map(\.type), [.queue])
        XCTAssertEqual(LiveFormat.grouped(all, search: "900").first?.items.first?.name, "Voicemail Algemeen")
        XCTAssertTrue(LiveFormat.grouped(all, search: "zzz").isEmpty)
    }

    func testRowIdentityDoesNotDependOnTheRef() {
        let started = Date()
        let a = LiveCall(ref: "one", state: .ringing, callerNumber: "0701234567", calleeNumber: "0850000001", startedAt: started)
        let b = LiveCall(ref: "two", state: .active, callerNumber: "0701234567", calleeNumber: "0850000001", startedAt: started)

        XCTAssertEqual(a.id, b.id)
    }

    // MARK: Localization

    private static func strings(_ language: String) -> [String: String] {
        let path = Bundle.module.path(forResource: "Localizable", ofType: "strings", inDirectory: nil, forLocalization: language)!
        let dictionary = NSDictionary(contentsOfFile: path) as! [String: String]

        return dictionary.filter { $0.key.hasPrefix("live.") || $0.key.hasPrefix("onHold.section") }
    }

    func testBothLanguagesHaveTheSameLiveKeysAndPlaceholders() {
        let nl = Self.strings("nl")
        let en = Self.strings("en")
        let regex = try! NSRegularExpression(pattern: "%(?:\\d+\\$)?(?:ld|@|d|f)")
        func placeholders(_ text: String) -> [String] {
            regex.matches(in: text, range: NSRange(text.startIndex..., in: text)).map { String(text[Range($0.range, in: text)!]) }.sorted()
        }

        XCTAssertGreaterThan(nl.count, 40)
        XCTAssertEqual(Set(nl.keys), Set(en.keys))

        for (key, text) in nl {
            XCTAssertEqual(placeholders(text), placeholders(en[key] ?? ""), key)
            XCTAssertFalse(text.isEmpty, key)
        }
    }

    func testEveryKeyTheLiveScreenUsesResolves() {
        let keys = ["live.title", "live.loading", "live.disabled.title", "live.empty.title", "live.section.ringing", "live.section.running", "live.section.parked", "live.pickUp", "live.transfer", "live.transfer.title",
                    "live.target.extension", "live.target.ring_group", "live.target.queue", "live.target.ivr", "live.target.business_hours", "live.target.voicemail", "live.direction.inbound", "live.direction.unknown",
                    "live.error.callNotFound", "live.error.notRinging", "live.error.notTransferable", "live.error.unavailable", "live.error.forbidden", "live.error.invalidTarget", "onHold.section.live"]

        for key in keys {
            XCTAssertNotEqual(L10n.string(key), key)
        }
    }
}
