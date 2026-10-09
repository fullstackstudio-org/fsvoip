// SPDX-License-Identifier: AGPL-3.0-or-later
import CallController
import Core
import Foundation
import Pairing
import SipEngine
import XCTest
@testable import UI

private final class FakeParkService: ParkServicing, @unchecked Sendable {
    private let lock = NSLock()
    private(set) var log: [String] = []
    private(set) var parkRequests: [String] = []
    private(set) var hungUp: [String] = []
    var items: [ParkedCall] = []
    var available = true
    var parkResult: Result<ParkedCall, Error> = .success(ParkedCall(id: "new", slot: 1, retrieveNumber: "*5901", mine: true))
    var listError: Error?
    var hangupError: Error?

    func count(_ name: String) -> Int { lock.withLock { log.filter { $0 == name }.count } }

    func park(callId: String, for account: StoredAccount) async throws -> ParkedCall {
        lock.withLock {
            log.append("park")
            parkRequests.append(callId)
        }

        return try parkResult.get()
    }

    func parked(for account: StoredAccount) async throws -> ParkedCallsPage {
        lock.withLock { log.append("parked") }

        if let listError { throw listError }

        return lock.withLock { ParkedCallsPage(calls: items, available: available) }
    }

    func hangup(id: String, for account: StoredAccount) async throws {
        lock.withLock {
            log.append("hangup")
            hungUp.append(id)
        }

        if let hangupError { throw hangupError }

        lock.withLock { items.removeAll { $0.id == id } }
    }
}

private final class ParkMeAccounts: AccountServicing, @unchecked Sendable {
    var me: MeResponse?

    func pair(_ link: PairingLink, device: DeviceDescriptor) async throws -> StoredAccount { throw APIError.notFound }
    func refresh(_ account: StoredAccount) async throws -> AccountRefreshResult { .updated(account, internalContacts: [], me: me) }
    func rename(_ account: StoredAccount, alias: String?) async throws -> StoredAccount { account }
    func unpair(_ account: StoredAccount) async throws {}
    func forget(_ account: StoredAccount) throws {}
}

private func parkAccount(_ id: String = "a") -> StoredAccount {
    StoredAccount(id: id, label: "Jan", pbxName: "Voorbeeld Bouw", extensionName: "Jan de Vries", extensionNumber: "102", customerName: "Voorbeeld", deviceToken: Secret("fss_vapp_x"), installId: "a1b2c3d4e5f60718", sip: SIPCredentials(username: "102", password: Secret("pw"), domain: "x.powervoip.nl", proxy: "sip.powervoip.nl", port: 5061, transport: .tls, srv: true), pairedAt: Date(timeIntervalSince1970: 1_790_000_000))
}

private func parked(_ id: String, slot: Int, mine: Bool = false, name: String? = "Bakkerij Smit", by: String = "Jan de Vries", at seconds: TimeInterval = 100) -> ParkedCall {
    ParkedCall(id: id, slot: slot, retrieveNumber: "*59" + String(format: "%02d", slot), callerNumber: "0701234567", callerName: name, parkedAt: Date(timeIntervalSinceNow: -seconds), expiresAt: Date(timeIntervalSinceNow: 120 - seconds), parkedBy: ParkedBy(deviceId: nil, name: by), mine: mine)
}

private func meResponse(park: Bool, role: String) -> MeResponse {
    var object = try! JSONSerialization.jsonObject(with: PbxFixtures.data(role == "admin" ? "me-response-admin" : "me-response-user")) as! [String: Any]
    var caps = object["capabilities"] as! [String: Any]
    caps["park"] = park
    object["capabilities"] = caps

    return try! FSVoipJSON.decoder().decode(MeResponse.self, from: JSONSerialization.data(withJSONObject: object))
}

@MainActor
final class ParkTests: XCTestCase {
    private var service: FakeParkService!
    private var model: ParkModel!
    private var dialed: [(number: String, account: String)] = []
    private var notices: [(String, Bool)] = []

    override func setUp() async throws {
        service = FakeParkService()
        dialed = []
        notices = []
        model = ParkModel(service: service, sleep: { _ in try? await Task.sleep(nanoseconds: 5_000_000) })
        model.dial = { [unowned self] number, account in
            dialed.append((number, account))

            return true
        }
        model.notify = { [unowned self] message, isError in notices.append((message, isError)) }
    }

    // MARK: Parking

    func testParkingSendsTheCallIdAndAnswersWithTheSlot() async {
        service.parkResult = .success(parked("p", slot: 3, mine: true))
        service.items = [parked("p", slot: 3, mine: true)]

        let result = await model.park(callId: "sip-call-id-1", account: parkAccount())

        XCTAssertEqual(result, .parked(slot: 3))
        XCTAssertEqual(service.parkRequests, ["sip-call-id-1"])
        XCTAssertEqual(model.calls.map(\.slot), [3], "the list is refreshed after parking")
    }

    func testNoFreeSlotIsAMessageNotARetry() async {
        service.parkResult = .failure(APIError.noFreeSlot)

        let result = await model.park(callId: "c1", account: parkAccount())

        XCTAssertEqual(result, .failed(.noFreeSlot))
        XCTAssertEqual(ParkFailure.noFreeSlot.message, L10n.string("park.error.noFreeSlot"))
        XCTAssertEqual(service.parkRequests.count, 1)
    }

    func testTheOtherParkErrorsMap() {
        XCTAssertEqual(ParkFailure.classify(APIError.callNotFound), .callNotFound)
        XCTAssertEqual(ParkFailure.classify(APIError.parkUnavailable), .unavailable)
        XCTAssertEqual(ParkFailure.classify(APIError.conflict(code: "park_busy")), .noFreeSlot)
        XCTAssertEqual(ParkFailure.classify(APIError.readOnly), .readOnly)
        XCTAssertEqual(ParkFailure.classify(APIError.forbidden), .forbidden)
        XCTAssertEqual(ParkFailure.classify(APIError.notFound), .notFound)
    }

    func testParkUncertainRefreshesAndNeverParksTheSameCallAgain() async {
        service.parkResult = .failure(APIError.parkUncertain)
        service.items = [parked("p", slot: 1, mine: true)]

        let first = await model.park(callId: "c1", account: parkAccount())

        XCTAssertEqual(first, .failed(.uncertain))
        XCTAssertEqual(service.count("parked"), 1, "it looks at the list instead")
        XCTAssertEqual(model.calls.count, 1)

        let second = await model.park(callId: "c1", account: parkAccount())

        XCTAssertEqual(second, .ignored)
        XCTAssertEqual(service.parkRequests, ["c1"], "no second request, ever")
    }

    func testATimeOutOrAGatewayErrorIsUncertainAndNeverParksTheSameCallAgain() async {
        let errors: [Error] = [APIError.transport("timed out"), APIError.unexpectedStatus(502), APIError.unexpectedStatus(504)]

        for (index, error) in errors.enumerated() {
            service.parkResult = .failure(error)
            let callId = "call-\(index)"

            let first = await model.park(callId: callId, account: parkAccount())
            let second = await model.park(callId: callId, account: parkAccount())

            XCTAssertEqual(first, .failed(.uncertain))
            XCTAssertEqual(second, .ignored)
            XCTAssertEqual(service.parkRequests.filter { $0 == callId }.count, 1)
        }

        XCTAssertGreaterThanOrEqual(service.count("parked"), errors.count, "the list is refreshed each time")
    }

    func testAClearRefusalIsNotUncertain() async {
        service.parkResult = .failure(APIError.callNotFound)

        _ = await model.park(callId: "c9", account: parkAccount())
        service.parkResult = .failure(APIError.callNotFound)
        let again = await model.park(callId: "c9", account: parkAccount())

        XCTAssertEqual(again, .failed(.callNotFound), "a certain failure may be tried again")
    }

    func testParkingACallOfAnotherAccountDoesNotSwitchTheTabsList() async {
        service.items = [parked("p", slot: 1)]
        await model.refresh(parkAccount("a"))
        XCTAssertEqual(model.calls.count, 1)
        let listCalls = service.count("parked")

        service.parkResult = .success(parked("q", slot: 2))
        _ = await model.park(callId: "other", account: parkAccount("b"))

        XCTAssertEqual(service.count("parked"), listCalls, "no refresh for another account")
        XCTAssertEqual(model.calls.count, 1, "the list of account a stays")
    }

    func testPollingOnlyWhileOnHoldIsTheVisibleTabAndTheAppIsActive() {
        XCTAssertTrue(OnHoldContent.shouldPoll(phase: .active, selectedTab: .onHold))
        XCTAssertFalse(OnHoldContent.shouldPoll(phase: .active, selectedTab: .dialer))
        XCTAssertFalse(OnHoldContent.shouldPoll(phase: .background, selectedTab: .onHold))
    }

    // MARK: The list

    func testAllAndMineSegments() async {
        service.items = [parked("1", slot: 1, mine: true), parked("2", slot: 2), parked("3", slot: 3, mine: true)]

        await model.refresh(parkAccount())

        XCTAssertEqual(model.calls(in: .all).count, 3)
        XCTAssertEqual(model.calls(in: .mine).map(\.id), ["1", "3"])
        XCTAssertEqual(model.mineCount, 2)
        XCTAssertEqual(model.available, true)
    }

    func testAnUnavailablePbxIsExplainedNotListed() async {
        service.available = false

        await model.refresh(parkAccount())

        XCTAssertEqual(model.available, false)
        XCTAssertTrue(model.calls.isEmpty)
    }

    func testAFailedRefreshKeepsTheOldList() async {
        service.items = [parked("1", slot: 1)]
        await model.refresh(parkAccount())

        service.listError = APIError.transport("down")
        let ok = await model.refresh(parkAccount())

        XCTAssertFalse(ok)
        XCTAssertEqual(model.failure, .offline)
        XCTAssertEqual(model.calls.count, 1)
    }

    func testPollingRunsOnlyWhileStarted() async throws {
        let account = parkAccount()
        model.startPolling(account)
        XCTAssertTrue(model.isPolling)

        try await Task.sleep(nanoseconds: 120_000_000)
        XCTAssertGreaterThan(service.count("parked"), 2, "it keeps refreshing while the tab is visible")

        model.stopPolling()
        XCTAssertFalse(model.isPolling)
        try await Task.sleep(nanoseconds: 30_000_000)
        let settled = service.count("parked")
        try await Task.sleep(nanoseconds: 120_000_000)

        XCTAssertEqual(service.count("parked"), settled, "no more requests after the tab went away")
    }

    func testStartingTwiceDoesNotDoublePoll() async throws {
        let account = parkAccount()
        model.startPolling(account)
        model.startPolling(account)
        model.stopPolling()
        try await Task.sleep(nanoseconds: 60_000_000)

        XCTAssertLessThanOrEqual(service.count("parked"), 2)
    }

    // MARK: Picking up

    func testPickingUpRefreshesFirstThenDialsTheRetrieveNumber() async {
        service.items = [parked("1", slot: 1), parked("2", slot: 2)]
        await model.refresh(parkAccount())
        let before = service.count("parked")

        let result = await model.retrieve(model.calls[1], account: parkAccount())

        XCTAssertEqual(result, .dialing)
        XCTAssertEqual(service.count("parked"), before + 1, "the list is read again right before dialling")
        XCTAssertEqual(dialed.map(\.number), ["*5902"])
        XCTAssertEqual(dialed.map(\.account), ["a"])
    }

    func testAGoneSlotIsNotDialled() async {
        service.items = [parked("1", slot: 1)]
        await model.refresh(parkAccount())
        let stale = model.calls[0]
        service.items = []

        let result = await model.retrieve(stale, account: parkAccount())

        XCTAssertEqual(result, .gone)
        XCTAssertTrue(dialed.isEmpty)
        XCTAssertTrue(model.calls.isEmpty, "the list is refreshed")
    }

    func testAnotherCallInTheSameSlotIsNotDialled() async {
        service.items = [parked("1", slot: 1)]
        await model.refresh(parkAccount())
        let stale = model.calls[0]
        service.items = [parked("other", slot: 1)]

        let result = await model.retrieve(stale, account: parkAccount())

        XCTAssertEqual(result, .gone, "it matches on the call, not on the slot number")
        XCTAssertTrue(dialed.isEmpty)
    }

    func testAPickUpThatNeverConnectsSaysNoLongerParkedAndRefreshes() async {
        service.items = [parked("1", slot: 1)]
        await model.refresh(parkAccount())
        _ = await model.retrieve(model.calls[0], account: parkAccount())
        let before = service.count("parked")

        model.callFinished(
            RecentCall(number: "*5901", name: nil, accountId: "a", accountLabel: "Jan", direction: .outgoing, outcome: .failed, startedAt: Date(), duration: 0),
            account: parkAccount()
        )
        try? await Task.sleep(nanoseconds: 50_000_000)

        XCTAssertEqual(notices.first?.0, L10n.string("onHold.notice.gone"))
        XCTAssertEqual(notices.first?.1, true)
        XCTAssertGreaterThan(service.count("parked"), before)
    }

    func testAConnectedPickUpSaysNothing() async {
        service.items = [parked("1", slot: 1)]
        await model.refresh(parkAccount())
        _ = await model.retrieve(model.calls[0], account: parkAccount())

        model.callFinished(
            RecentCall(number: "*5901", name: nil, accountId: "a", accountLabel: "Jan", direction: .outgoing, outcome: .answered, startedAt: Date(), duration: 60),
            account: parkAccount()
        )

        XCTAssertTrue(notices.isEmpty)
    }

    func testAnUnrelatedCallEndingSaysNothing() async {
        service.items = [parked("1", slot: 1)]
        await model.refresh(parkAccount())
        _ = await model.retrieve(model.calls[0], account: parkAccount())

        model.callFinished(
            RecentCall(number: "0612345678", name: nil, accountId: "a", accountLabel: "Jan", direction: .outgoing, outcome: .notAnswered, startedAt: Date(), duration: 0),
            account: parkAccount()
        )

        XCTAssertTrue(notices.isEmpty)
    }

    // MARK: Hanging up

    func testAUserHangsUpOnlyTheirOwnCall() async {
        model.isAdmin = { _ in false }
        service.items = [parked("mine", slot: 1, mine: true), parked("theirs", slot: 2)]
        await model.refresh(parkAccount())
        let own = model.calls[0]
        let other = model.calls[1]

        XCTAssertTrue(model.canHangUp(own, accountId: "a"))
        XCTAssertFalse(model.canHangUp(other, accountId: "a"))

        let refused = await model.hangUp(other, account: parkAccount())
        XCTAssertEqual(refused, .failed(.forbidden))
        XCTAssertEqual(service.hungUp, [], "no request for a call that is not theirs")

        let done = await model.hangUp(own, account: parkAccount())
        XCTAssertEqual(done, .done)
        XCTAssertEqual(service.hungUp, ["mine"])
        XCTAssertEqual(model.calls.map(\.id), ["theirs"])
    }

    func testAnAdminHangsUpAnyCall() async {
        model.isAdmin = { _ in true }
        service.items = [parked("theirs", slot: 2)]
        await model.refresh(parkAccount())

        let result = await model.hangUp(model.calls[0], account: parkAccount())

        XCTAssertEqual(result, .done)
        XCTAssertEqual(service.hungUp, ["theirs"])
    }

    func testHangingUpACallThatIsGone() async {
        model.isAdmin = { _ in true }
        service.items = [parked("x", slot: 1)]
        await model.refresh(parkAccount())
        service.hangupError = APIError.notFound

        let result = await model.hangUp(model.calls[0], account: parkAccount())

        XCTAssertEqual(result, .gone)
    }

    // MARK: Capability

    private func appModel(me: MeResponse) async throws -> FSVoipAppModel {
        let store = AccountStore(secrets: InMemorySecretStore())
        try store.save(parkAccount())
        let accounts = ParkMeAccounts()
        accounts.me = me
        let preferences = InMemoryPreferencesStore()
        let model = FSVoipAppModel(
            phone: PhoneController(engine: NullSipEngine(), system: ImmediateCallSystem(), audioRouting: MemoryAudioRouting(), preferences: preferences, endedLinger: 0),
            accountStore: store,
            service: accounts,
            preferences: preferences,
            recentsStore: RecentCallsStore(defaults: UserDefaults(suiteName: "fsvoip.parktests.\(UUID().uuidString)")!),
            device: { DeviceDescriptor(model: "iPhone17,1", osVersion: "26.0", appVersion: "0.1.0 (1)", installId: "a1b2c3d4e5f60718") },
            requestMicrophone: { true },
            park: service
        )
        await model.refreshAccounts()

        return model
    }

    func testWithoutTheCapabilityThereIsNoParkButton() async throws {
        let model = try await appModel(me: meResponse(park: false, role: "admin"))

        XCTAssertFalse(model.canPark("a"))
        XCTAssertNil(model.parkAccount)

        await model.parkCall(CallSession(id: UUID(), engineCallID: CallID("x"), direction: .outgoing, accountId: "a", accountLabel: "Jan", remoteNumber: "0612345678", remoteName: nil, phase: .active, createdAt: Date()))

        XCTAssertTrue(service.parkRequests.isEmpty, "never a request without the capability")
    }

    func testWithTheCapabilityAUserParksAndSeesTheSlot() async throws {
        let model = try await appModel(me: meResponse(park: true, role: "user"))
        service.parkResult = .success(parked("p", slot: 1, mine: true))

        XCTAssertTrue(model.canPark("a"))
        XCTAssertEqual(model.parkAccount?.id, "a")

        await model.parkCall(CallSession(id: UUID(), engineCallID: CallID("sip-id-9"), direction: .outgoing, accountId: "a", accountLabel: "Jan", remoteNumber: "0612345678", remoteName: nil, phase: .active, createdAt: Date()))

        XCTAssertEqual(service.parkRequests, ["sip-id-9"])
        XCTAssertEqual(model.notice?.message, String(format: L10n.string("park.notice.parked"), "01"))
        XCTAssertEqual(model.notice?.isError, false)
        XCTAssertFalse(model.park?.isAdmin("a") ?? true, "a user may not hang up the calls of colleagues")
    }

    func testAnAdminMayHangUpColleaguesCalls() async throws {
        let model = try await appModel(me: meResponse(park: true, role: "admin"))

        XCTAssertTrue(model.park?.isAdmin("a") ?? false)
    }

    // MARK: Formatting

    func testElapsedAndRemainingTime() {
        let now = Date(timeIntervalSince1970: 1_000)

        XCTAssertEqual(ParkFormat.elapsed(since: now.addingTimeInterval(-83), now: now), "1:23")
        XCTAssertNil(ParkFormat.elapsed(since: nil, now: now))
        XCTAssertEqual(ParkFormat.remaining(until: now.addingTimeInterval(37), now: now), "0:37")
        XCTAssertNil(ParkFormat.remaining(until: now.addingTimeInterval(-1), now: now), "passed: nothing to count down")
        XCTAssertEqual(ParkFormat.slot(1), "01")
    }

    func testTheStringsExistInBothLanguages() throws {
        let keys = ["call.park", "park.notice.parked", "park.error.uncertain", "onHold.scope.all", "onHold.scope.mine", "onHold.empty.title", "onHold.unavailable.message", "onHold.notice.gone", "onHold.hangUp.confirm"]

        for language in ["nl", "en"] {
            let path = try XCTUnwrap(L10n.bundle.path(forResource: language, ofType: "lproj"))
            let bundle = try XCTUnwrap(Bundle(path: path))

            for key in keys {
                XCTAssertNotEqual(bundle.localizedString(forKey: key, value: "MISSING", table: nil), "MISSING", "\(key) in \(language)")
            }
        }
    }
}
