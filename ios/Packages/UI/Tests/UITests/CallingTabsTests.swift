// SPDX-License-Identifier: AGPL-3.0-or-later
import CallController
import Core
import Foundation
import Pairing
import SipEngine
import XCTest
@testable import UI

// MARK: - Helpers

private func testAccount(_ id: String = "a") -> StoredAccount {
    StoredAccount(id: id, label: "Jan", pbxName: "Voorbeeld Bouw", extensionName: "Jan de Vries", extensionNumber: "102", customerName: "Voorbeeld", deviceToken: Secret("fss_vapp_x"), installId: "a1b2c3d4e5f60718", sip: SIPCredentials(username: "102", password: Secret("pw"), domain: "x.powervoip.nl", proxy: "sip.powervoip.nl", port: 5061, transport: .tls, srv: true), pairedAt: Date(timeIntervalSince1970: 1_790_000_000))
}

private func capabilities(callerChoice: Bool, role: String = "admin") -> AppCapabilities {
    let json = #"{"pbxManage": false, "recordings": \#(role == "admin"), "voicemail": "own", "calls": "team", "selfExtension": true, "callerChoice": \#(callerChoice)}"#

    return try! FSVoipJSON.decoder().decode(AppCapabilities.self, from: Data(json.utf8))
}

/// `GET /me` of the shared admin fixture, with the capabilities of the test.
private func me(callerChoice: Bool, role: String = "admin") -> MeResponse {
    var object = try! JSONSerialization.jsonObject(with: PbxFixtures.data(role == "admin" ? "me-response-admin" : "me-response-user")) as! [String: Any]
    var caps = object["capabilities"] as! [String: Any]
    caps["callerChoice"] = callerChoice
    caps["selfExtension"] = true
    object["capabilities"] = caps

    return try! FSVoipJSON.decoder().decode(MeResponse.self, from: JSONSerialization.data(withJSONObject: object))
}

private final class FakeNumbersService: OutboundNumbersServicing, @unchecked Sendable {
    private let lock = NSLock()
    private(set) var requests = 0
    var result = OutboundNumbers(
        numbers: [
            SelfNumber(id: "1", number: "0850607848", name: "Hoofdnummer", isDefault: true),
            SelfNumber(id: "2", number: "0850607849", name: nil),
        ],
        defaultNumber: "0850607848"
    )

    func numbers(for account: StoredAccount) async throws -> OutboundNumbers {
        lock.withLock { requests += 1 }

        return result
    }
}

/// Records the options of every outgoing call; registrations succeed at once.
private final class RecordingEngine: SipEngine {
    weak var delegate: SipEngineDelegate?

    private final class Audio: SipAudioControl {
        func configure() {}
        func activate(_ active: Bool) {}
    }

    let audio: SipAudioControl = Audio()
    private(set) var started: [(number: String, options: CallOptions)] = []

    func start() throws {}
    func stop() {}
    func enterBackground() {}
    func enterForeground() {}
    func refreshRegistrations() {}
    func register(_ account: SipAccountConfig) throws {
        // After the phone has noted "registering", like a real network answer.
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.delegate?.sipEngine(self, registrationChanged: .registered, for: account.id)
        }
    }
    func unregister(_ account: SipAccountID) {}
    func registrationState(of account: SipAccountID) -> RegistrationState { .registered }
    func setRegistrationEnabled(_ enabled: Bool, for account: SipAccountID) {}
    func refreshRegistration(of account: SipAccountID) {}

    func call(number: String, from account: SipAccountID, options: CallOptions) throws -> CallID {
        started.append((number, options))

        return CallID("out-1")
    }

    func answer(_ call: CallID) throws {}
    func decline(_ call: CallID, reason: DeclineReason) throws {}
    func hangup(_ call: CallID) throws {}
    func setHold(_ call: CallID, onHold: Bool) throws {}
    func setMuted(_ muted: Bool) {}
    func sendDTMF(_ digit: DTMFDigit, on call: CallID) throws {}
    func transfer(_ call: CallID, to number: String) throws {}
    func calls() -> [CallInfo] { [] }
}

private final class MeAccountService: AccountServicing, @unchecked Sendable {
    let store: AccountStore
    var me: MeResponse?

    init(store: AccountStore) { self.store = store }

    func pair(_ link: PairingLink, device: DeviceDescriptor) async throws -> StoredAccount { throw APIError.notFound }
    func refresh(_ account: StoredAccount) async throws -> AccountRefreshResult { .updated(account, internalContacts: [], me: me) }
    func rename(_ account: StoredAccount, alias: String?) async throws -> StoredAccount { account }
    func unpair(_ account: StoredAccount) async throws {}
    func forget(_ account: StoredAccount) throws {}
}

// MARK: - Uitbellen via

@MainActor
final class OutboundChoiceModelTests: XCTestCase {
    private var service: FakeNumbersService!
    private var preferences: InMemoryPreferencesStore!
    private var outbound: OutboundChoiceModel!
    private var callerChoice = true

    override func setUp() async throws {
        service = FakeNumbersService()
        preferences = InMemoryPreferencesStore()
        outbound = OutboundChoiceModel(service: service, preferences: preferences)
        callerChoice = true
        outbound.capabilities = { [unowned self] _ in capabilities(callerChoice: callerChoice) }
    }

    func testTheChoiceBecomesTheFromNumberOfTheNextCall() async {
        await outbound.load(testAccount())

        XCTAssertEqual(outbound.options(for: "a"), .none, "nothing chosen yet: the extension's own default number")

        outbound.select("0850607849", accountId: "a")

        XCTAssertEqual(outbound.options(for: "a"), CallOptions(fromNumber: "0850607849"))
        XCTAssertEqual(outbound.options(for: "a").headers, [CallHeader(name: "X-FSS-From", value: "0850607849")])
        XCTAssertEqual(preferences.preferences(for: "a").outboundNumber, "0850607849", "the choice is kept per account")
    }

    func testTheDefaultNumberIsShownUntilSomethingIsChosen() async {
        await outbound.load(testAccount())

        XCTAssertEqual(outbound.selected("a")?.number, "0850607848")
        XCTAssertEqual(outbound.label("a"), "Hoofdnummer · 085 060 7848")
        XCTAssertTrue(outbound.canChoose("a"))

        outbound.select("0850607849", accountId: "a")
        XCTAssertEqual(outbound.label("a"), "085 060 7849", "a number without a name is just the number")
    }

    func testSwitchingNumbersNeverTouchesTheNetwork() async {
        await outbound.load(testAccount())
        let before = service.requests

        outbound.select("0850607849", accountId: "a")
        outbound.select("0850607848", accountId: "a")
        outbound.select("0850607849", accountId: "a")

        XCTAssertEqual(service.requests, before)
    }

    func testWithoutTheCapabilityThereIsNoChooserAndNoHeader() async {
        callerChoice = false
        preferences.setPreferences(AccountPreferences(outboundNumber: "0850607849"), for: "a")

        await outbound.load(testAccount())

        XCTAssertEqual(service.requests, 0, "no capability, no request")
        XCTAssertFalse(outbound.canChoose("a"))
        XCTAssertFalse(outbound.offersChoice("a"))
        XCTAssertEqual(outbound.label("a"), L10n.string("outbound.default"))
        XCTAssertEqual(outbound.options(for: "a"), .none, "even an old stored choice sends nothing")
        XCTAssertTrue(outbound.options(for: "a").headers.isEmpty)
    }

    func testAnUnknownCapabilityIsTreatedAsNo() async {
        outbound.capabilities = { _ in nil }

        await outbound.load(testAccount())

        XCTAssertFalse(outbound.canChoose("a"))
        XCTAssertEqual(outbound.options(for: "a"), .none)
    }

    func testOneNumberIsALabelOnly() async {
        service.result = OutboundNumbers(numbers: [SelfNumber(id: "1", number: "0850607848", name: "Hoofdnummer", isDefault: true)], defaultNumber: "0850607848")

        await outbound.load(testAccount())

        XCTAssertFalse(outbound.canChoose("a"), "nothing to choose from: no chevron")
        XCTAssertEqual(outbound.label("a"), "Hoofdnummer · 085 060 7848")
    }

    func testANumberThePbxNoLongerListsFallsBackToTheDefault() async {
        await outbound.load(testAccount())
        outbound.select("0850607849", accountId: "a")

        service.result = OutboundNumbers(numbers: [SelfNumber(id: "1", number: "0850607848", name: nil, isDefault: true), SelfNumber(id: "3", number: "0201234567")], defaultNumber: "0850607848")
        await outbound.load(testAccount())

        XCTAssertEqual(outbound.options(for: "a"), .none)
        XCTAssertEqual(outbound.selected("a")?.number, "0850607848")
    }

    func testAChoiceIsOnlyAcceptedFromTheListedNumbers() async {
        await outbound.load(testAccount())

        outbound.select("0612345678", accountId: "a")

        XCTAssertNil(preferences.preferences(for: "a").outboundNumber)
        XCTAssertEqual(outbound.options(for: "a"), .none)
    }

    func testTheChoiceIsPerAccount() async {
        await outbound.load(testAccount("a"))
        await outbound.load(testAccount("b"))
        outbound.select("0850607849", accountId: "a")

        XCTAssertEqual(outbound.options(for: "a").fromNumber, "0850607849")
        XCTAssertNil(outbound.options(for: "b").fromNumber)
    }

    // MARK: The call itself

    func testAppModelPutsTheChoiceOnTheOutgoingCallOnly() async throws {
        let store = AccountStore(secrets: InMemorySecretStore())
        let accountService = MeAccountService(store: store)
        accountService.me = me(callerChoice: true)
        try store.save(testAccount())
        let engine = RecordingEngine()
        let model = FSVoipAppModel(
            phone: PhoneController(engine: engine, system: ImmediateCallSystem(), audioRouting: MemoryAudioRouting(), preferences: preferences, endedLinger: 0),
            accountStore: store,
            service: accountService,
            preferences: preferences,
            recentsStore: RecentCallsStore(defaults: UserDefaults(suiteName: "fsvoip.uitests.\(UUID().uuidString)")!),
            device: { DeviceDescriptor(model: "iPhone17,1", osVersion: "26.0", appVersion: "0.1.0 (1)", installId: "a1b2c3d4e5f60718") },
            outboundNumbers: service
        )

        await model.refreshAccounts()
        try await Task.sleep(nanoseconds: 100_000_000)
        model.outbound.select("0850607849", accountId: "a")

        XCTAssertTrue(model.call("0612345678", from: "a"))
        XCTAssertEqual(engine.started.last?.options, CallOptions(fromNumber: "0850607849"))
        XCTAssertEqual(model.phone.activeSession?.viaNumber, "0850607849", "the call screen says via which number")

        // Without the capability the same choice sends nothing.
        model.phone.hangUp(try XCTUnwrap(model.phone.activeSession?.id))
        engine.delegate?.sipEngine(engine, callChanged: CallInfo(id: CallID("out-1"), direction: .outgoing, accountId: "a", remoteNumber: "0612345678", remoteName: nil, state: .ended(.localHangup)))
        accountService.me = me(callerChoice: false)
        await model.refreshAccounts()
        try await Task.sleep(nanoseconds: 100_000_000)

        XCTAssertTrue(model.call("0612345679", from: "a"))
        XCTAssertEqual(engine.started.last?.options, CallOptions.none)
        XCTAssertNil(model.phone.activeSession?.viaNumber)
    }
}

// MARK: - Beschikbaar

private final class ScriptedAvailability: AvailabilityServicing, @unchecked Sendable {
    private(set) var patches: [(dnd: Bool, version: Int)] = []
    private(set) var loads = 0
    /// What `load` returns, in order (the last one repeats).
    var loadStates: [AvailabilityHub.State] = [AvailabilityHub.State(doNotDisturb: false, version: 3)]
    /// Errors for the next patches, in order.
    var patchErrors: [Error] = []

    func load(for account: StoredAccount) async throws -> AvailabilityHub.State {
        loads += 1

        return loadStates[min(loads - 1, loadStates.count - 1)]
    }

    func setDoNotDisturb(_ dnd: Bool, version: Int, for account: StoredAccount) async throws -> AvailabilityHub.State {
        patches.append((dnd, version))

        if !patchErrors.isEmpty {
            throw patchErrors.removeFirst()
        }

        return AvailabilityHub.State(doNotDisturb: dnd, version: version + 1)
    }
}

@MainActor
final class AvailabilityHubTests: XCTestCase {
    func testDoNotDisturbSendsTheFlagAndTheVersion() async {
        let service = ScriptedAvailability()
        let hub = AvailabilityHub(service: service)
        await hub.load(testAccount())

        await hub.setAvailable(false, account: testAccount())

        XCTAssertEqual(service.patches.count, 1)
        XCTAssertEqual(service.patches.first?.dnd, true)
        XCTAssertEqual(service.patches.first?.version, 3)
        XCTAssertEqual(hub.state(for: "a"), AvailabilityHub.State(doNotDisturb: true, version: 4))
        XCTAssertEqual(hub.isAvailable("a"), false)
    }

    func testAStaleVersionIsRetriedOnceOnTheFreshVersion() async {
        let service = ScriptedAvailability()
        service.loadStates = [AvailabilityHub.State(doNotDisturb: false, version: 3), AvailabilityHub.State(doNotDisturb: false, version: 7)]
        service.patchErrors = [APIError.stale(version: 7)]
        let hub = AvailabilityHub(service: service)
        await hub.load(testAccount())

        await hub.setAvailable(false, account: testAccount())

        XCTAssertEqual(service.patches.map(\.version), [3, 7], "one retry, with the version the server has now")
        XCTAssertEqual(service.patches.map(\.dnd), [true, true])
        XCTAssertEqual(hub.state(for: "a"), AvailabilityHub.State(doNotDisturb: true, version: 8))
        XCTAssertNil(hub.failedFor)
    }

    func testAStaleVersionThatAlreadyHasTheWantedValueNeedsNoSecondPatch() async {
        let service = ScriptedAvailability()
        service.loadStates = [AvailabilityHub.State(doNotDisturb: false, version: 3), AvailabilityHub.State(doNotDisturb: true, version: 9)]
        service.patchErrors = [APIError.stale(version: 9)]
        let hub = AvailabilityHub(service: service)
        await hub.load(testAccount())

        await hub.setAvailable(false, account: testAccount())

        XCTAssertEqual(service.patches.count, 1)
        XCTAssertEqual(hub.state(for: "a"), AvailabilityHub.State(doNotDisturb: true, version: 9))
    }

    func testTwoStaleAnswersInARowGiveUpAndShowTheServersState() async {
        let service = ScriptedAvailability()
        service.loadStates = [AvailabilityHub.State(doNotDisturb: false, version: 3), AvailabilityHub.State(doNotDisturb: false, version: 7), AvailabilityHub.State(doNotDisturb: false, version: 8)]
        service.patchErrors = [APIError.stale(version: 7), APIError.stale(version: 8)]
        let hub = AvailabilityHub(service: service)
        await hub.load(testAccount())

        await hub.setAvailable(false, account: testAccount())

        XCTAssertEqual(service.patches.count, 2, "never more than one retry")
        XCTAssertEqual(hub.failedFor, "a")
        XCTAssertEqual(hub.isAvailable("a"), true)
    }

    func testAnOfflineAnswerGoesBackAndIsNotRetried() async {
        let service = ScriptedAvailability()
        service.patchErrors = [APIError.transport("offline")]
        let hub = AvailabilityHub(service: service)
        await hub.load(testAccount())

        await hub.setAvailable(false, account: testAccount())

        XCTAssertEqual(service.patches.count, 1)
        XCTAssertEqual(hub.failedFor, "a")
        XCTAssertEqual(hub.isAvailable("a"), true)
    }
}

// MARK: - Geschiedenis

@MainActor
final class HistoryModelTests: XCTestCase {
    private var service: FakeMediaService!
    private var hub: MediaHub!
    private var history: HistoryModel!
    private var cacheDirectory: URL!

    override func setUp() async throws {
        service = FakeMediaService()
        cacheDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("fsvoip-ui-history-\(UUID().uuidString)", isDirectory: true)
        hub = MediaHub(service: service, gate: LocalAccessGate(authenticator: FakeLocalAuth()), cache: MediaCache(directory: cacheDirectory), backend: FakeAudioBackend())
        history = HistoryModel(media: hub)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: cacheDirectory)
    }

    private func item(_ json: String) -> CallItem {
        MediaFixtures.decode(json)
    }

    private func callJSON(direction: String, outcome: String, recording: Bool = false, sort: String = "2026-10-08T07:12:41.000Z") -> String {
        #"{"id": "c1", "direction": "\#(direction)", "outcome": "\#(outcome)", "number": "070 123 45 67", "extension": "102", "extensionName": "Jan de Vries", "startedLabel": "8 okt 09:12", "startedSort": "\#(sort)", "durationLabel": "2:05", "durationSeconds": 125, "hasRecording": \#(recording), "recordingExpired": false}"#
    }

    func testAnUnansweredIncomingCallIsMissedAndAnUnansweredOutgoingCallIsNot() {
        XCTAssertTrue(HistoryEntry(item: item(callJSON(direction: "inbound", outcome: "missed")), accountId: "a").isMissed)
        XCTAssertTrue(HistoryEntry(item: item(callJSON(direction: "inbound", outcome: "voicemail")), accountId: "a").isMissed)
        XCTAssertFalse(HistoryEntry(item: item(callJSON(direction: "outbound", outcome: "missed")), accountId: "a").isMissed)
        XCTAssertFalse(HistoryEntry(item: item(callJSON(direction: "inbound", outcome: "answered")), accountId: "a").isMissed)
    }

    func testTheRowShowsWhoHandledTheCall() {
        let entry = HistoryEntry(item: item(callJSON(direction: "inbound", outcome: "answered")), accountId: "a")

        XCTAssertEqual(entry.extensionName, "Jan de Vries")
        XCTAssertEqual(entry.durationLabel, "2:05")
    }

    func testTheMissedFilterAndTheKindFilter() {
        let entries = [
            HistoryEntry(item: item(callJSON(direction: "inbound", outcome: "missed")), accountId: "a"),
            HistoryEntry(item: item(callJSON(direction: "outbound", outcome: "answered")), accountId: "a"),
            HistoryEntry(item: item(callJSON(direction: "inbound", outcome: "answered")), accountId: "a"),
        ]

        XCTAssertEqual(HistoryModel.filtered(entries, filter: .all).count, 3)
        XCTAssertEqual(HistoryModel.filtered(entries, filter: .missed).count, 1)
        XCTAssertEqual(HistoryModel.filtered(entries, filter: .all, kind: .outgoing).count, 1)
        XCTAssertEqual(HistoryModel.filtered(entries, filter: .missed, kind: .outgoing).count, 0)
    }

    func testTheServersCallsComeFirstAndNewerLocalCallsAreAdded() async {
        await history.load(testAccount("admin-1"))

        let newest = Date(timeIntervalSince1970: 1_800_000_000)
        let local = [
            RecentCall(number: "0612345678", name: nil, accountId: "admin-1", accountLabel: "Jan", direction: .outgoing, outcome: .answered, startedAt: newest, duration: 30),
            RecentCall(number: "0612345679", name: nil, accountId: "admin-1", accountLabel: "Jan", direction: .outgoing, outcome: .answered, startedAt: Date(timeIntervalSince1970: 1_000_000_000), duration: 30),
            RecentCall(number: "0612345680", name: nil, accountId: "other", accountLabel: "Jan", direction: .outgoing, outcome: .answered, startedAt: newest, duration: 30),
        ]
        let entries = history.entries(for: "admin-1", local: local, extensionName: "Jan")

        XCTAssertEqual(history.state(for: "admin-1"), .loaded)
        XCTAssertEqual(entries.filter(\.isLocal).map(\.number), ["0612345678"], "only the newer call of this account, which the PBX has not caught up with")
        XCTAssertEqual(entries.first?.isLocal, true, "newest first")
        XCTAssertEqual(entries.filter { !$0.isLocal }.count, 3)
    }

    func testWhenThePbxDoesNotAnswerThePhonesCallsAreShown() async {
        service.failures["calls"] = [APIError.transport("offline")]

        await history.load(testAccount("admin-1"))

        let local = [RecentCall(number: "0612345678", name: "Piet", accountId: "admin-1", accountLabel: "Jan", direction: .incoming, outcome: .missed, startedAt: Date(), duration: 0)]
        let entries = history.entries(for: "admin-1", local: local, extensionName: "Jan")

        XCTAssertEqual(history.state(for: "admin-1"), .unavailable)
        XCTAssertEqual(entries.count, 1)
        XCTAssertTrue(entries[0].isMissed)
        XCTAssertEqual(history.title(for: entries[0]), "Piet")
    }

    func testOlderMonthsAreLoadedOnRequest() async {
        await history.load(testAccount("admin-1"))

        XCTAssertEqual(history.nextMonth(for: "admin-1"), "2026-09")

        await history.loadMore(testAccount("admin-1"))

        XCTAssertNil(history.nextMonth(for: "admin-1"))
        XCTAssertEqual(history.entries(for: "admin-1", local: [], extensionName: nil).count, 4)
    }

    // MARK: Recordings: admin only

    func testAUserNeverGetsARecordingEvenIfTheServerSaidSo() async {
        hub.apply(me: me(callerChoice: false, role: "user"), accountId: "user-1")
        let entry = HistoryEntry(item: item(callJSON(direction: "inbound", outcome: "answered", recording: true)), accountId: "user-1")

        XCTAssertTrue(entry.hasRecording)
        XCTAssertFalse(history.canPlayRecording(entry))

        await history.play(entry, account: testAccount("user-1"))

        XCTAssertNil(history.nowPlaying)
        XCTAssertEqual(service.count("recordingSource"), 0, "the recording is not even asked for")
    }

    func testAnAdminCanPlayARecordingAfterTheLocalCheck() async {
        hub.apply(me: me(callerChoice: false), accountId: "admin-1")
        let entry = HistoryEntry(item: item(callJSON(direction: "inbound", outcome: "answered", recording: true)), accountId: "admin-1")

        XCTAssertTrue(history.canPlayRecording(entry))

        await history.play(entry, account: testAccount("admin-1"))

        XCTAssertEqual(service.count("recordingSource"), 1)
        XCTAssertNotNil(history.nowPlaying)
    }

    func testACallWithoutARecordingOffersNone() {
        hub.apply(me: me(callerChoice: false), accountId: "admin-1")
        let entry = HistoryEntry(item: item(callJSON(direction: "inbound", outcome: "answered", recording: false)), accountId: "admin-1")

        XCTAssertFalse(history.canPlayRecording(entry))
    }
}
