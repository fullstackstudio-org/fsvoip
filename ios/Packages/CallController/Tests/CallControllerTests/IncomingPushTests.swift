// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The background path (Task 6): VoIP push → CallKit → wake the account → INVITE with X-FSS-Call. PushKit delivers
// nothing on the simulator, so the pushes are injected (the fixtures from shared/fixtures, as APNs delivers them).

import Core
import SipEngine
import XCTest
@testable import CallController

/// Loads `shared/fixtures/*.json` from the repository.
private enum Fixture {
    static func data(_ name: String) throws -> Data {
        var url = URL(fileURLWithPath: #filePath)

        for _ in 0 ..< 6 {
            url.deleteLastPathComponent()
        }

        return try Data(contentsOf: url.appendingPathComponent("shared/fixtures/\(name).json"))
    }

    /// As `PKPushPayload.dictionaryPayload` delivers it.
    static func dictionary(_ name: String) throws -> [AnyHashable: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: data(name)) as? [String: Any])
    }

    static let callRef = "9d3f5c52-7b1e-4a0c-8e6d-2f4a6b8c0d1e"
    static let accountId = "3f0c2b1e-8a4d-4d6f-9b7a-1c2d3e4f5a6b"
    /// `expiresAt` of the fixtures.
    static let expiresAt = ISO8601DateFormatter().date(from: "2026-10-08T12:35:08Z")!
}

/// Time is ours: timers only fire when the test says so.
@MainActor
private final class ManualScheduler {
    struct Entry {
        let delay: TimeInterval
        let action: @MainActor () -> Void
        var cancelled = false
    }

    private(set) var entries: [Entry] = []

    var pending: [Entry] {
        entries.filter { !$0.cancelled }
    }

    lazy var scheduler: CallScheduler = { [unowned self] delay, action in
        entries.append(Entry(delay: delay, action: action))
        let index = entries.count - 1

        return { [weak self] in self?.entries[index].cancelled = true }
    }

    func fireAll() {
        for index in entries.indices where !entries[index].cancelled {
            entries[index].cancelled = true
            entries[index].action()
        }
    }
}

final class IncomingPushPolicyTests: XCTestCase {
    private let policy = IncomingPushPolicy()

    private func ring(callRef: String = Fixture.callRef, account: String = Fixture.accountId, expiresAt: Date = Fixture.expiresAt) -> PushMessage {
        .ring(RingPush(callRef: callRef, from: PushCaller(number: "+31701234567", name: "Bakkerij Smit"), accountId: account, accountLabel: "Voorbeeld Bouw · Jan de Vries", expiresAt: expiresAt))
    }

    private func decide(_ message: PushMessage?, at now: Date, accounts: Set<String> = [Fixture.accountId], busy: Bool = false, handled: Set<String> = []) -> RingPushDecision {
        policy.decide(message, now: now, knownAccounts: accounts, busy: busy, isHandled: { handled.contains($0) })
    }

    func testFixturesDecodeAsTheyArriveFromAPNs() throws {
        let voip = try PushMessage.decode(apnsDictionary: Fixture.dictionary("apns-voip-body"))
        guard case let .ring(ring) = voip else {
            return XCTFail("expected a ring")
        }

        XCTAssertEqual(ring.callRef, Fixture.callRef)
        XCTAssertEqual(ring.from, PushCaller(number: "+31701234567", name: "Bakkerij Smit"))
        XCTAssertEqual(ring.accountId, Fixture.accountId)
        XCTAssertEqual(ring.expiresAt, Fixture.expiresAt)

        // The bare `fsvoip` object of an anonymous call (FCM carries it like this).
        let anonymous = try FSVoipJSON.decoder().decode(PushMessage.self, from: Fixture.data("push-ring-anonymous"))
        guard case let .ring(hidden) = anonymous else {
            return XCTFail("expected a ring")
        }

        XCTAssertNil(hidden.from.number)
        XCTAssertNil(hidden.from.name)

        let alert = try PushMessage.decode(apnsDictionary: Fixture.dictionary("apns-alert-body"))
        XCTAssertEqual(alert, .revoked(RevokedPush(accountId: Fixture.accountId, accountLabel: "Voorbeeld Bouw · Jan de Vries")))
    }

    func testAFreshPushRingsAndWaitsTenSecondsForTheInvite() {
        let now = Fixture.expiresAt.addingTimeInterval(-11)

        guard case let .ring(_, deadline) = decide(ring(), at: now) else {
            return XCTFail("expected ring")
        }

        XCTAssertEqual(deadline.timeIntervalSince(now), 10, accuracy: 0.001)
    }

    func testALatePushWaitsUntilShortlyAfterExpiryButAtLeastTwoSeconds() {
        // Arrived 1 s before expiry: wait 1 s + 5 s tolerance.
        guard case let .ring(_, late) = decide(ring(), at: Fixture.expiresAt.addingTimeInterval(-1)) else {
            return XCTFail("expected ring")
        }

        XCTAssertEqual(late.timeIntervalSince(Fixture.expiresAt.addingTimeInterval(-1)), 6, accuracy: 0.001)

        // Within the tolerance after expiry: still rings, but waits the minimum.
        let now = Fixture.expiresAt.addingTimeInterval(4)
        guard case let .ring(_, minimum) = decide(ring(), at: now) else {
            return XCTFail("expected ring")
        }

        XCTAssertEqual(minimum.timeIntervalSince(now), 2, accuracy: 0.001)
    }

    func testAnExpiredPushIsRejected() {
        XCTAssertEqual(decide(ring(), at: Fixture.expiresAt.addingTimeInterval(6)), .reject(.expired, ring: ring().ring))
    }

    func testUnreadableOrNonRingPushesAreInvalid() {
        let now = Fixture.expiresAt.addingTimeInterval(-11)

        XCTAssertEqual(decide(nil, at: now), .reject(.invalidPayload, ring: nil))
        XCTAssertEqual(decide(.refresh(RefreshPush(accountId: Fixture.accountId)), at: now), .reject(.invalidPayload, ring: nil))
        XCTAssertEqual(decide(ring(callRef: "not-a-uuid"), at: now), .reject(.invalidPayload, ring: nil))
    }

    func testUnknownAccountBusyAndDuplicateAreRejectedInThatOrder() {
        let now = Fixture.expiresAt.addingTimeInterval(-11)

        XCTAssertEqual(decide(ring(account: "gone"), at: now), .reject(.unknownAccount, ring: ring(account: "gone").ring))
        XCTAssertEqual(decide(ring(), at: now, busy: true), .reject(.busy, ring: ring().ring))
        // A known call wins over everything else (its INVITE came first, or the push came twice).
        XCTAssertEqual(decide(ring(), at: Fixture.expiresAt.addingTimeInterval(60), busy: true, handled: [Fixture.callRef]), .reject(.alreadyHandled, ring: ring().ring))
    }

    func testCallUUIDIsTheCallRef() {
        XCTAssertEqual(IncomingPushPolicy.callUUID(for: Fixture.callRef), UUID(uuidString: Fixture.callRef))
        XCTAssertNil(IncomingPushPolicy.callUUID(for: nil))
        XCTAssertNil(IncomingPushPolicy.callUUID(for: "x"))
    }

    func testCallerMatchForInvitesWithoutHeader() {
        XCTAssertTrue(IncomingPushPolicy.callersMatch("+31701234567", "0701234567"))
        XCTAssertTrue(IncomingPushPolicy.callersMatch(nil, "0701234567"))
        XCTAssertTrue(IncomingPushPolicy.callersMatch("0701234567", nil))
        XCTAssertFalse(IncomingPushPolicy.callersMatch("+31701234567", "0612345678"))
    }
}

private extension PushMessage {
    var ring: RingPush? {
        if case let .ring(ring) = self {
            return ring
        }

        return nil
    }
}

@MainActor
final class PhoneControllerPushTests: XCTestCase {
    private var engine: FakeSipEngine!
    private var system: ImmediateCallSystem!
    private var preferences: InMemoryPreferencesStore!
    private var timers: ManualScheduler!
    private var phone: PhoneController!
    private var finished: [RecentCall] = []
    private var clock = Fixture.expiresAt.addingTimeInterval(-11)

    private var callUUID: UUID {
        UUID(uuidString: Fixture.callRef)!
    }

    override func setUp() async throws {
        engine = FakeSipEngine()
        system = ImmediateCallSystem()
        preferences = InMemoryPreferencesStore()
        timers = ManualScheduler()
        finished = []
        phone = PhoneController(
            engine: engine,
            system: system,
            audioRouting: MemoryAudioRouting(),
            preferences: preferences,
            endedLinger: 0,
            now: { [unowned self] in clock },
            schedule: timers.scheduler
        )
        phone.onCallFinished = { [unowned self] in finished.append($0) }
    }

    private func account(_ id: String, alias: String? = nil) -> StoredAccount {
        StoredAccount(
            id: id,
            label: "Voorbeeld Bouw · Jan de Vries",
            labelOverride: alias,
            pbxName: "Voorbeeld Bouw",
            extensionName: "Jan de Vries",
            extensionNumber: "102",
            customerName: "Voorbeeld Bouw B.V.",
            deviceToken: Secret("fss_vapp_test"),
            installId: "a1b2c3d4e5f60718",
            sip: SIPCredentials(username: "102", password: Secret("pw"), domain: "voorbeeld.powervoip.nl", proxy: "sip.powervoip.nl", port: 5061, transport: .tls, srv: true),
            pairedAt: Date(timeIntervalSince1970: id == Fixture.accountId ? 1 : 2)
        )
    }

    private func pushFixture() throws -> IncomingPushOutcome {
        phone.handleVoipPush(payload: try Fixture.dictionary("apns-voip-body"))
    }

    // MARK: Reporting

    func testPushReportsTheCallAtOnceAndWakesThePushedAccount() throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        engine.fakeAudio.log = []
        engine.log = []

        XCTAssertEqual(try pushFixture(), .ringing(callUUID))

        // Reported inside the callback, keyed on the callRef; one account = no arrow (D10).
        XCTAssertEqual(system.events, [.reportedIncoming(callUUID, handle: "+31701234567", displayName: "Bakkerij Smit")])
        XCTAssertEqual(engine.fakeAudio.log, ["configure"], "audio configured before the report, not activated (that is didActivate)")
        XCTAssertEqual(engine.log, ["refresh \(Fixture.accountId)"], "a fresh REGISTER for the pushed account")

        let session = try XCTUnwrap(phone.activeSession)
        XCTAssertEqual(session.fssCallRef, Fixture.callRef)
        XCTAssertTrue(session.awaitingInvite)
        XCTAssertEqual(session.phase, .incoming)
        XCTAssertEqual(timers.pending.map(\.delay), [10])
    }

    func testCallScreenShowsTheDialledAccountWhenTheSettingIsOn() throws {
        phone.sync(accounts: [account(Fixture.accountId, alias: "Jan (balie)")])
        preferences.setPreferences(AccountPreferences(showCalledAccount: true), for: Fixture.accountId)

        try pushFixture()

        // The local alias wins over the label in the push.
        XCTAssertEqual(system.events, [.reportedIncoming(callUUID, handle: "+31701234567", displayName: "Bakkerij Smit → Jan (balie)")])
    }

    func testWithSeveralAccountsTheAccountIsShownUnlessSwitchedOff() throws {
        phone.sync(accounts: [account(Fixture.accountId), account("other")])

        try pushFixture()
        XCTAssertEqual(system.events.first, .reportedIncoming(callUUID, handle: "+31701234567", displayName: "Bakkerij Smit → Voorbeeld Bouw · Jan de Vries"))

        // Switched off for this account: caller only.
        phone.hangUp(callUUID)
        preferences.setPreferences(AccountPreferences(showCalledAccount: false), for: Fixture.accountId)
        let next = "1d3f5c52-7b1e-4a0c-8e6d-2f4a6b8c0d1e"
        var payload = try Fixture.dictionary("apns-voip-body")
        var fsvoip = try XCTUnwrap(payload["fsvoip"] as? [String: Any])
        fsvoip["callRef"] = next
        payload["fsvoip"] = fsvoip

        phone.handleVoipPush(payload: payload)
        XCTAssertEqual(system.events.last, .reportedIncoming(UUID(uuidString: next)!, handle: "+31701234567", displayName: "Bakkerij Smit"))
    }

    func testALocalContactNameWinsOverThePushName() throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        phone.lookupName = { $0 == "+31701234567" ? "Henk Smit" : nil }

        try pushFixture()

        XCTAssertEqual(system.events, [.reportedIncoming(callUUID, handle: "+31701234567", displayName: "Henk Smit")])
    }

    // MARK: The INVITE

    func testInviteWithTheCallRefJoinsTheReportedCallAndAnswers() throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        try pushFixture()

        engine.emitIncoming(id: "in-1", from: "0701234567", name: "Bakkerij Smit", account: Fixture.accountId, callRef: Fixture.callRef)

        XCTAssertEqual(system.events.count, 1, "the INVITE does not report a second call")
        XCTAssertEqual(phone.activeSession?.engineCallID, CallID("in-1"))
        XCTAssertEqual(phone.activeSession?.awaitingInvite, false)
        XCTAssertTrue(timers.pending.isEmpty, "the missed-call timer is cancelled")

        phone.answer(callUUID)
        XCTAssertEqual(engine.log.last, "answer in-1")
        XCTAssertEqual(engine.fakeAudio.log.last, "activate", "audio starts when the system activates the session")

        engine.emitState(.active, id: "in-1", direction: .incoming, account: Fixture.accountId)
        XCTAssertEqual(phone.activeSession?.phase, .active)
    }

    func testAnsweredOnTheLockScreenBeforeTheInviteArrives() throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        try pushFixture()

        // CallKit's answer action is fulfilled (so it activates the audio session) although there is no SIP call yet.
        phone.answer(callUUID)
        XCTAssertEqual(phone.activeSession?.phase, .connecting)
        XCTAssertFalse(engine.log.contains { $0.hasPrefix("answer") })
        XCTAssertEqual(engine.fakeAudio.log.last, "activate")

        engine.emitIncoming(id: "in-1", from: "0701234567", name: nil, account: Fixture.accountId, callRef: Fixture.callRef)

        XCTAssertEqual(engine.log.last, "answer in-1", "answered the moment the INVITE arrived")
        engine.emitState(.active, id: "in-1", direction: .incoming, account: Fixture.accountId)
        XCTAssertEqual(phone.activeSession?.phase, .active)
    }

    func testDeclinedBeforeTheInviteArrivesGivesTheInviteA603() throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        try pushFixture()

        phone.hangUp(callUUID)
        XCTAssertNil(phone.activeSession)
        XCTAssertEqual(finished.last?.outcome, .declined)

        engine.emitIncoming(id: "in-1", from: "0701234567", name: nil, account: Fixture.accountId, callRef: Fixture.callRef)
        XCTAssertEqual(engine.log.last, "decline in-1")
        XCTAssertNil(phone.activeSession, "the late INVITE does not ring")
    }

    func testNoInviteInTimeEndsTheCallAsMissed() throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        try pushFixture()

        timers.fireAll()

        XCTAssertEqual(system.events.last, .ended(callUUID, .unanswered))
        XCTAssertNil(phone.activeSession)
        XCTAssertEqual(finished.last?.outcome, .missed)
        XCTAssertEqual(finished.last?.number, "+31701234567")

        // The INVITE comes after all (the caller had not hung up yet): 486, never a new ring.
        engine.emitIncoming(id: "in-1", from: "0701234567", name: nil, account: Fixture.accountId, callRef: Fixture.callRef)
        XCTAssertEqual(engine.log.last, "busy in-1")
        XCTAssertNil(phone.activeSession)
    }

    func testAnsweredButTheInviteNeverCameEndsAsFailed() throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        try pushFixture()
        phone.answer(callUUID)

        timers.fireAll()

        XCTAssertEqual(system.events.last, .ended(callUUID, .failed))
    }

    func testCallerHangsUpAfterTheInviteBeforeAnswer() throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        try pushFixture()
        engine.emitIncoming(id: "in-1", from: "0701234567", name: nil, account: Fixture.accountId, callRef: Fixture.callRef)

        engine.emitState(.ended(.unanswered), id: "in-1", direction: .incoming, account: Fixture.accountId)

        XCTAssertEqual(system.events.last, .ended(callUUID, .unanswered))
        XCTAssertEqual(finished.last?.outcome, .missed)
    }

    func testInviteWithoutHeaderJoinsByAccountAndCaller() throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        try pushFixture()

        engine.emitIncoming(id: "in-1", from: "0701234567", name: nil, account: Fixture.accountId, callRef: nil)

        XCTAssertEqual(system.events.count, 1)
        XCTAssertEqual(phone.activeSession?.engineCallID, CallID("in-1"))
    }

    func testAnInviteFromAnotherCallerDoesNotJoinAndHearsBusy() throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        try pushFixture()

        engine.emitIncoming(id: "in-9", from: "0612345678", name: nil, account: Fixture.accountId, callRef: nil)

        XCTAssertEqual(engine.log.last, "busy in-9")
        XCTAssertEqual(phone.activeSession?.engineCallID, nil, "the pushed call still waits for its own INVITE")
    }

    func testCallerNameFromTheInviteUpdatesAnAnonymousPushCall() throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        phone.handleVoipPush(payload: try Fixture.dictionary("apns-voip-body").replacingCaller(number: "0701234567", name: nil))

        engine.emitIncoming(id: "in-1", from: "0701234567", name: "Bakkerij Smit", account: Fixture.accountId, callRef: Fixture.callRef)

        XCTAssertEqual(system.events.last, .updated(callUUID, displayName: "Bakkerij Smit"))
    }

    // MARK: Rejected pushes (still reported: Apple's rule)

    func testExpiredPushIsReportedAndEndedAtOnce() throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        clock = Fixture.expiresAt.addingTimeInterval(30)

        XCTAssertEqual(try pushFixture(), .rejected(.expired))

        guard case let .reportedIncoming(uuid, _, display)? = system.events.first else {
            return XCTFail("expected a report")
        }

        XCTAssertEqual(display, "Bakkerij Smit")
        XCTAssertEqual(system.events.last, .ended(uuid, .unanswered))
        XCTAssertNil(phone.activeSession)
        XCTAssertEqual(finished.last?.outcome, .missed)
    }

    func testUnreadablePushIsReportedAndEndedAsFailed() {
        phone.sync(accounts: [account(Fixture.accountId)])

        XCTAssertEqual(phone.handleVoipPush(payload: ["aps": [:]]), .rejected(.invalidPayload))

        XCTAssertEqual(system.events.count, 2)
        guard case let .reportedIncoming(uuid, _, display)? = system.events.first else {
            return XCTFail("expected a report")
        }

        XCTAssertEqual(display, phone.anonymousCallerText)
        XCTAssertEqual(system.events.last, .ended(uuid, .failed))
        XCTAssertTrue(finished.isEmpty)
    }

    func testPushForAnAccountThatIsGoneIsReportedAndEnded() throws {
        phone.sync(accounts: [account("other")])

        XCTAssertEqual(try pushFixture(), .rejected(.unknownAccount))
        XCTAssertEqual(system.events.count, 2)
        XCTAssertNil(phone.activeSession)
    }

    func testPushDuringACallIsReportedEndedAndItsInviteHearsBusy() throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        engine.emitRegistration(.registered, Fixture.accountId)
        try phone.startCall(number: "0612345678", accountId: Fixture.accountId)

        XCTAssertEqual(try pushFixture(), .rejected(.busy))
        XCTAssertEqual(phone.sessions.count, 1)
        XCTAssertEqual(finished.last?.outcome, .missed)

        engine.emitIncoming(id: "in-1", from: "0701234567", name: nil, account: Fixture.accountId, callRef: Fixture.callRef)
        XCTAssertEqual(engine.log.last, "busy in-1")
    }

    func testRepeatedPushReusesTheCall() throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        try pushFixture()

        XCTAssertEqual(try pushFixture(), .rejected(.alreadyHandled))

        // Reported again with the SAME UUID (CallKit answers "already exists"); nothing is ended.
        XCTAssertEqual(system.events.count, 2)
        guard case let .reportedIncoming(uuid, _, _) = system.events[1] else {
            return XCTFail("expected a second report")
        }

        XCTAssertEqual(uuid, callUUID)
        XCTAssertEqual(phone.sessions.count, 1)
    }

    func testPushThatArrivesAfterItsInviteMapsOntoTheSameCall() throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        engine.emitIncoming(id: "in-1", from: "0701234567", name: "Bakkerij Smit", account: Fixture.accountId, callRef: Fixture.callRef)

        XCTAssertEqual(phone.activeSession?.id, callUUID, "the INVITE's call is keyed on its X-FSS-Call")

        XCTAssertEqual(try pushFixture(), .rejected(.alreadyHandled))
        XCTAssertEqual(phone.sessions.count, 1)
        XCTAssertFalse(system.events.contains { if case .ended = $0 { return true } else { return false } })
    }

    func testSystemRefusalEndsThePushCallAndRejectsItsInvite() throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        system.refuseIncoming = true

        try pushFixture()
        XCTAssertNil(phone.activeSession)
        XCTAssertTrue(timers.pending.isEmpty)

        engine.emitIncoming(id: "in-1", from: "0701234567", name: nil, account: Fixture.accountId, callRef: Fixture.callRef)
        XCTAssertEqual(engine.log.last, "decline in-1")
    }

    // MARK: Background registration

    func testBackgroundWithoutACallUnregistersAndAPushWakesOnlyThatAccount() throws {
        phone.sync(accounts: [account(Fixture.accountId), account("other")])
        engine.log = []

        phone.enterBackground()
        XCTAssertEqual(engine.disabled, [SipAccountID(Fixture.accountId), "other"])
        XCTAssertTrue(phone.registrationsSuspended)

        try pushFixture()
        XCTAssertEqual(engine.disabled, ["other"], "only the pushed account registers")
        XCTAssertEqual(engine.log.suffix(2), ["enable \(Fixture.accountId)", "refresh \(Fixture.accountId)"])

        // The call ends: quiet again until the next push.
        engine.emitIncoming(id: "in-1", from: "0701234567", name: nil, account: Fixture.accountId, callRef: Fixture.callRef)
        engine.emitState(.ended(.remoteHangup), id: "in-1", direction: .incoming, account: Fixture.accountId)
        XCTAssertEqual(engine.disabled, [SipAccountID(Fixture.accountId), "other"])

        phone.enterForeground()
        XCTAssertTrue(engine.disabled.isEmpty)
        XCTAssertFalse(phone.registrationsSuspended)
    }

    func testBackgroundDuringACallKeepsRegistrationsUntilTheCallEnds() throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        try pushFixture()
        engine.emitIncoming(id: "in-1", from: "0701234567", name: nil, account: Fixture.accountId, callRef: Fixture.callRef)

        phone.enterBackground()
        XCTAssertTrue(engine.disabled.isEmpty)

        engine.emitState(.ended(.remoteHangup), id: "in-1", direction: .incoming, account: Fixture.accountId)
        XCTAssertEqual(engine.disabled, [SipAccountID(Fixture.accountId)])
    }

    func testLaunchedInTheBackgroundAddsAccountsWithoutRegistering() throws {
        phone.enterBackground()

        phone.sync(accounts: [account(Fixture.accountId)])

        XCTAssertEqual(engine.disabled, [SipAccountID(Fixture.accountId)])
        XCTAssertNotNil(engine.registered[SipAccountID(Fixture.accountId)])

        try pushFixture()
        XCTAssertTrue(engine.disabled.isEmpty)
    }
    // MARK: The customer card (KP-T11)

    private static let card = CallerContext(contactId: "6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c", name: "Jansen Bakkerij", company: nil, openRequests: 1, openOrders: 2)

    /// Records the lookups (the closure of the phone is `@Sendable`).
    private final class Asked: @unchecked Sendable {
        private let lock = NSLock()
        private var values: [String] = []

        func add(_ number: String, _ account: String = "") {
            lock.withLock { values.append(account.isEmpty ? number : "\(number)|\(account)") }
        }

        var all: [String] { lock.withLock { values } }
    }

    func testTheCardNamesTheCallerAndUpdatesTheCallScreen() async throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        let asked = Asked()
        phone.lookupCaller = { number, account in
            asked.add(number, account.id)

            return Self.card
        }

        try pushFixture()
        await phone.finishCallerLookup(callUUID)

        XCTAssertEqual(asked.all, ["+31701234567|\(Fixture.accountId)"])
        XCTAssertEqual(phone.activeSession?.callerContext, Self.card)
        XCTAssertEqual(phone.activeSession?.remoteName, "Jansen Bakkerij")
        // The system call screen is updated in place (CXCallUpdate), not reported again.
        XCTAssertEqual(system.events, [
            .reportedIncoming(callUUID, handle: "+31701234567", displayName: "Bakkerij Smit"),
            .updated(callUUID, displayName: "Jansen Bakkerij"),
        ])
    }

    func testAContactNameOfThePhoneWinsOverTheCard() async throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        phone.lookupName = { $0 == "+31701234567" ? "Henk Smit" : nil }
        phone.lookupCaller = { _, _ in Self.card }

        try pushFixture()
        await phone.finishCallerLookup(callUUID)

        XCTAssertEqual(phone.activeSession?.remoteName, "Henk Smit")
        XCTAssertNotNil(phone.activeSession?.callerContext, "the context still shows on the in-app screen")
        XCTAssertEqual(system.events.count, 1, "no update: the name did not change")
    }

    func testASlowLookupNeverBlocksTheCall() async throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        phone.callerLookupTimeout = 0.05
        phone.lookupCaller = { _, _ in
            try? await Task.sleep(nanoseconds: 5_000_000_000)

            return Self.card
        }

        // The call is reported and ringing before the lookup is even looked at.
        XCTAssertEqual(try pushFixture(), .ringing(callUUID))
        XCTAssertEqual(system.events, [.reportedIncoming(callUUID, handle: "+31701234567", displayName: "Bakkerij Smit")])

        await phone.finishCallerLookup(callUUID)

        XCTAssertNil(phone.activeSession?.callerContext)
        XCTAssertEqual(phone.activeSession?.remoteName, "Bakkerij Smit")
        XCTAssertEqual(system.events.count, 1)

        // And it can still be answered.
        engine.emitIncoming(id: "in-1", from: "0701234567", name: "Bakkerij Smit", account: Fixture.accountId, callRef: Fixture.callRef)
        phone.answer(callUUID)
        XCTAssertEqual(engine.log.last, "answer in-1")
    }

    func testAFailedOrEmptyLookupChangesNothing() async throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        phone.lookupCaller = { _, _ in nil }

        try pushFixture()
        await phone.finishCallerLookup(callUUID)

        XCTAssertNil(phone.activeSession?.callerContext)
        XCTAssertEqual(system.events.count, 1)
    }

    func testAnAnswerAfterTheCallEndedIsDropped() async throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        phone.callerLookupTimeout = 2
        phone.lookupCaller = { _, _ in
            try? await Task.sleep(nanoseconds: 200_000_000)

            return Self.card
        }

        try pushFixture()
        timers.fireAll()
        XCTAssertNil(phone.activeSession, "no INVITE in time: the call ended")
        await phone.finishCallerLookup(callUUID)

        XCTAssertFalse(system.events.contains(.updated(callUUID, displayName: "Jansen Bakkerij")))
    }

    func testAnAnonymousCallerIsNotLookedUp() async throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        let asked = Asked()
        phone.lookupCaller = { number, _ in
            asked.add(number)

            return Self.card
        }

        phone.handleVoipPush(payload: try Fixture.dictionary("apns-voip-body").replacingCaller(number: nil, name: nil))
        await phone.finishCallerLookup(callUUID)

        XCTAssertEqual(asked.all, [])
        XCTAssertNil(phone.activeSession?.callerContext)
    }

    func testAColleagueIsNotLookedUp() async throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        let asked = Asked()
        phone.lookupCaller = { number, _ in
            asked.add(number)

            return Self.card
        }

        // An internal extension number is a colleague, not a customer.
        phone.handleVoipPush(payload: try Fixture.dictionary("apns-voip-body").replacingCaller(number: "103", name: "Piet"))
        await phone.finishCallerLookup(callUUID)

        XCTAssertEqual(asked.all, [])
    }

    func testAnAnonymousPushIsLookedUpOnceTheInviteBringsTheNumber() async throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        let asked = Asked()
        phone.lookupCaller = { number, _ in
            asked.add(number)

            return Self.card
        }

        phone.handleVoipPush(payload: try Fixture.dictionary("apns-voip-body").replacingCaller(number: nil, name: nil))
        engine.emitIncoming(id: "in-1", from: "0701234567", name: nil, account: Fixture.accountId, callRef: Fixture.callRef)
        await phone.finishCallerLookup(callUUID)

        XCTAssertEqual(asked.all, ["0701234567"])
        XCTAssertEqual(phone.activeSession?.remoteName, "Jansen Bakkerij")
    }

    func testAnInviteWithoutAPushIsLookedUpToo() async throws {
        phone.sync(accounts: [account(Fixture.accountId)])
        phone.lookupCaller = { _, _ in Self.card }

        engine.emitIncoming(id: "in-1", from: "0701234567", name: nil, account: Fixture.accountId, callRef: Fixture.callRef)
        await phone.finishCallerLookup(callUUID)

        XCTAssertEqual(phone.activeSession?.callerContext?.openOrders, 2)
    }
}

private extension Dictionary where Key == AnyHashable, Value == Any {
    func replacingCaller(number: String?, name: String?) -> [AnyHashable: Any] {
        var copy = self
        var fsvoip = copy["fsvoip"] as? [String: Any] ?? [:]
        fsvoip["from"] = ["number": number.map { $0 as Any } ?? NSNull(), "name": name.map { $0 as Any } ?? NSNull()]
        copy["fsvoip"] = fsvoip

        return copy
    }
}
