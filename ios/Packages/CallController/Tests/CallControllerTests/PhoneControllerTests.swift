// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SipEngine
import XCTest
@testable import CallController

@MainActor
final class PhoneControllerTests: XCTestCase {
    private var engine: FakeSipEngine!
    private var system: ImmediateCallSystem!
    private var preferences: InMemoryPreferencesStore!
    private var phone: PhoneController!
    private var finished: [RecentCall] = []
    private var clock = Date(timeIntervalSince1970: 1_791_400_000)

    override func setUp() async throws {
        engine = FakeSipEngine()
        system = ImmediateCallSystem()
        preferences = InMemoryPreferencesStore()
        finished = []
        phone = PhoneController(engine: engine, system: system, audioRouting: MemoryAudioRouting(), preferences: preferences, endedLinger: 0, now: { [unowned self] in clock })
        phone.onCallFinished = { [unowned self] in finished.append($0) }
    }

    private func account(_ id: String, label: String = "Jan (balie)", transport: ServerTransport = .tls) -> StoredAccount {
        StoredAccount(
            id: id,
            label: label,
            pbxName: "Voorbeeld",
            extensionName: "Jan",
            extensionNumber: "102",
            customerName: "Voorbeeld B.V.",
            deviceToken: Secret("fss_vapp_test"),
            installId: "a1b2c3d4e5f60718",
            sip: SIPCredentials(username: "102", password: Secret("pw"), domain: "voorbeeld.powervoip.nl", proxy: "sip.powervoip.nl", port: transport == .tls ? 5061 : 5060, transport: transport, srv: true),
            pairedAt: Date(timeIntervalSince1970: 1)
        )
    }

    private func registered(_ ids: String...) {
        phone.sync(accounts: ids.map { account($0) })
        for id in ids { engine.emitRegistration(.registered, id) }
    }

    // MARK: Accounts

    func testSyncRegistersNewChangedAndRemovedAccounts() {
        phone.sync(accounts: [account("a"), account("b")])

        XCTAssertTrue(engine.started)
        XCTAssertEqual(Set(engine.registered.keys), ["a", "b"])
        XCTAssertEqual(phone.registrationState(for: "a"), .registering)

        // Same accounts again: nothing is re-registered.
        phone.sync(accounts: [account("a"), account("b")])
        XCTAssertEqual(engine.registerCount, 2)

        // A changed server (e.g. the host moved to TLS) re-registers that account only; a removed one is dropped.
        phone.sync(accounts: [account("a", transport: .tcp)])
        XCTAssertEqual(engine.registerCount, 3)
        XCTAssertEqual(engine.registered["a"]?.transport, .tcp)
        XCTAssertNil(engine.registered["b"])
        XCTAssertEqual(phone.registrationState(for: "b"), .unregistered)
    }

    func testRegistrationEventsOfUnknownAccountsAreIgnored() {
        registered("a")
        engine.emitRegistration(.registered, "ghost")

        XCTAssertEqual(phone.registrationState(for: "a"), .registered)
        XCTAssertNil(phone.registrations["ghost"])
    }

    // MARK: Outgoing

    func testOutgoingCallGoesThroughTheSystemAndConnects() throws {
        registered("a")

        try phone.startCall(number: "+31 (0)6-1234 5678", accountId: "a")

        XCTAssertEqual(engine.log.last, "call +31612345678 from a", "the number is cleaned before it reaches the engine")
        XCTAssertEqual(engine.fakeAudio.log, ["configure", "activate"], "audio is configured before the call and started by the system")
        let uuid = try XCTUnwrap(phone.activeSession?.id)
        XCTAssertEqual(phone.activeSession?.phase, .ringing)
        XCTAssertEqual(system.events, [.startedConnecting(uuid)])

        clock = clock.addingTimeInterval(5)
        engine.emitState(.active, id: "out-1", direction: .outgoing, account: "a")
        XCTAssertEqual(phone.activeSession?.phase, .active)
        XCTAssertEqual(system.events.last, .connected(uuid))

        clock = clock.addingTimeInterval(65)
        engine.emitState(.ended(.remoteHangup), id: "out-1", direction: .outgoing, account: "a")

        XCTAssertNil(phone.activeSession)
        XCTAssertEqual(system.events.last, .ended(uuid, .remoteEnded))
        XCTAssertEqual(finished.first?.outcome, .answered)
        XCTAssertEqual(finished.first?.duration, 65)
        XCTAssertEqual(finished.first?.direction, .outgoing)
    }

    func testTheChosenNumberTravelsWithTheOutgoingCallOnly() throws {
        registered("a")

        try phone.startCall(number: "0612345678", accountId: "a", options: CallOptions(fromNumber: "0850607848"))

        XCTAssertEqual(engine.callOptions, [CallOptions(fromNumber: "0850607848")])
        XCTAssertEqual(engine.callOptions.first?.headers.filter { $0.name == "X-FSS-From" }.count, 1)
        XCTAssertEqual(phone.activeSession?.viaNumber, "0850607848", "the call screen says which number it goes out with")

        let uuid = try XCTUnwrap(phone.activeSession?.id)
        phone.hangUp(uuid)
        engine.emitState(.ended(.localHangup), id: "out-1", direction: .outgoing, account: "a")

        // The next call without a choice carries nothing: the choice is never remembered by the controller.
        try phone.startCall(number: "0612345678", accountId: "a")

        XCTAssertEqual(engine.callOptions.last, CallOptions.none)
        XCTAssertNil(phone.activeSession?.viaNumber)
    }

    func testAnIncomingCallNeverGetsTheCallerChoice() {
        registered("a")

        engine.emitIncoming(id: "in-1", from: "0701234567", name: nil, account: "a")

        XCTAssertTrue(engine.callOptions.isEmpty)
        XCTAssertNil(phone.activeSession?.viaNumber)
    }

    func testUserHangUpIsNotReportedBackToTheSystem() throws {
        registered("a")
        try phone.startCall(number: "0612345678", accountId: "a")
        let uuid = try XCTUnwrap(phone.activeSession?.id)

        phone.hangUp(uuid)
        XCTAssertEqual(engine.log.last, "hangup out-1")

        engine.emitState(.ended(.localHangup), id: "out-1", direction: .outgoing, account: "a")

        XCTAssertFalse(system.events.contains(.ended(uuid, .remoteEnded)), "CallKit already knows: the user ended it")
        XCTAssertEqual(finished.first?.outcome, .notAnswered)
        XCTAssertEqual(engine.fakeAudio.log.last, "deactivate")
    }

    func testCallsAreRefusedWithAReason() {
        registered("a")
        phone.sync(accounts: [account("a"), account("b")])

        XCTAssertThrowsError(try phone.startCall(number: "abc", accountId: "a")) { XCTAssertEqual($0 as? PhoneError, .invalidNumber) }
        XCTAssertThrowsError(try phone.startCall(number: "100", accountId: "nope")) { XCTAssertEqual($0 as? PhoneError, .unknownAccount) }
        XCTAssertThrowsError(try phone.startCall(number: "100", accountId: "b")) { XCTAssertEqual($0 as? PhoneError, .lineNotConnected) }

        XCTAssertNoThrow(try phone.startCall(number: "100", accountId: "a"))
        XCTAssertThrowsError(try phone.startCall(number: "101", accountId: "a")) { XCTAssertEqual($0 as? PhoneError, .callInProgress) }
    }

    func testEngineFailureFailsTheSystemActionAndCleansUp() throws {
        registered("a")
        engine.failNextCall = true

        try phone.startCall(number: "100", accountId: "a")

        XCTAssertNil(phone.activeSession)
        XCTAssertEqual(finished.first?.outcome, .failed)
    }

    // MARK: Incoming

    func testIncomingCallIsReportedAnsweredAndEnded() throws {
        preferences.setPreferences(AccountPreferences(showCalledAccount: true), for: "a")
        registered("a")

        engine.emitIncoming(id: "in-1", from: "0701234567", name: "Bakkerij Smit", account: "a")

        let uuid = try XCTUnwrap(phone.activeSession?.id)
        XCTAssertEqual(phone.activeSession?.phase, .incoming)
        XCTAssertEqual(system.events, [.reportedIncoming(uuid, handle: "0701234567", displayName: "Bakkerij Smit → Jan (balie)")])

        phone.answer(uuid)
        XCTAssertEqual(engine.log.last, "answer in-1")
        XCTAssertEqual(engine.fakeAudio.log, ["configure", "activate"], "answer configures, the system activates")

        engine.emitState(.active, id: "in-1", direction: .incoming, account: "a")
        XCTAssertEqual(phone.activeSession?.phase, .active)
        XCTAssertFalse(system.events.contains(.connected(uuid)), "only outgoing calls report connected")

        engine.emitState(.ended(.remoteHangup), id: "in-1", direction: .incoming, account: "a")
        XCTAssertEqual(system.events.last, .ended(uuid, .remoteEnded))
        XCTAssertEqual(finished.first?.outcome, .answered)
        XCTAssertEqual(finished.first?.name, "Bakkerij Smit")
    }

    func testCalledAccountIsHiddenWithOneAccountByDefault() throws {
        registered("a")

        engine.emitIncoming(id: "in-1", from: "0701234567", name: nil, account: "a")

        let uuid = try XCTUnwrap(phone.activeSession?.id)
        XCTAssertEqual(system.events.first, .reportedIncoming(uuid, handle: "0701234567", displayName: "0701234567"))
    }

    func testDecliningAnIncomingCall() throws {
        registered("a")
        engine.emitIncoming(id: "in-1", from: nil, name: nil, account: "a")
        let uuid = try XCTUnwrap(phone.activeSession?.id)

        phone.hangUp(uuid)
        XCTAssertEqual(engine.log.last, "decline in-1")

        engine.emitState(.ended(.declined), id: "in-1", direction: .incoming, account: "a")
        XCTAssertEqual(finished.first?.outcome, .declined)
        XCTAssertEqual(finished.first?.number, "")
    }

    func testMissedCallWhenTheCallerGivesUp() throws {
        registered("a")
        engine.emitIncoming(id: "in-1", from: "0201234567", name: nil, account: "a")
        let uuid = try XCTUnwrap(phone.activeSession?.id)

        engine.emitState(.ended(.unanswered), id: "in-1", direction: .incoming, account: "a")

        XCTAssertEqual(system.events.last, .ended(uuid, .unanswered))
        XCTAssertEqual(finished.first?.outcome, .missed)
    }

    func testSystemRefusalDeclinesTheSipCall() {
        registered("a")
        system.refuseIncoming = true

        engine.emitIncoming(id: "in-1", from: "0201234567", name: nil, account: "a")

        XCTAssertEqual(engine.log.last, "decline in-1")
        XCTAssertNil(phone.activeSession)
    }

    func testSecondCallerIsDeclinedWhileOnACall() throws {
        registered("a")
        engine.emitIncoming(id: "in-1", from: "0201234567", name: nil, account: "a")

        engine.emitIncoming(id: "in-2", from: "0207654321", name: nil, account: "a")

        XCTAssertEqual(engine.log.last, "busy in-2", "486 Busy Here, not 603")
        XCTAssertEqual(phone.sessions.count, 1)
        XCTAssertEqual(finished.first?.number, "0207654321")
        XCTAssertEqual(finished.first?.outcome, .missed)
    }

    func testCallForUnknownAccountIsDeclined() {
        registered("a")

        engine.emitIncoming(id: "in-1", from: "0201234567", name: nil, account: "ghost")

        XCTAssertEqual(engine.log.last, "decline in-1")
        XCTAssertNil(phone.activeSession)
    }

    // MARK: In-call controls

    func testMuteHoldDtmfAndSpeaker() throws {
        registered("a")
        try phone.startCall(number: "100", accountId: "a")
        let uuid = try XCTUnwrap(phone.activeSession?.id)
        engine.emitState(.active, id: "out-1", direction: .outgoing, account: "a")

        phone.setMuted(uuid, true)
        XCTAssertTrue(engine.muted)
        XCTAssertEqual(phone.activeSession?.isMuted, true)

        phone.setHeld(uuid, true)
        XCTAssertEqual(engine.log.last, "hold true")
        XCTAssertEqual(phone.activeSession?.isOnHold, true)

        phone.sendDTMF(uuid, "1#x")
        XCTAssertEqual(engine.log.suffix(2), ["dtmf 1", "dtmf #"], "only valid DTMF digits are sent")

        phone.setSpeaker(true)
        XCTAssertTrue(phone.isSpeakerOn)

        engine.emitState(.ended(.remoteHangup), id: "out-1", direction: .outgoing, account: "a")
        XCTAssertFalse(engine.muted, "the next call starts unmuted")
        XCTAssertFalse(phone.isSpeakerOn, "and on the earpiece")
    }

    func testSystemResetEndsEverything() throws {
        registered("a")
        try phone.startCall(number: "100", accountId: "a")

        phone.systemDidReset()

        XCTAssertNil(phone.activeSession)
        XCTAssertEqual(engine.log.last, "hangup out-1")
    }
}

final class SipAccountMappingTests: XCTestCase {
    private func account(_ transport: ServerTransport, port: Int, srv: Bool = true) -> StoredAccount {
        StoredAccount(id: "a", label: "L", pbxName: "P", extensionName: "E", extensionNumber: "102", customerName: "C", deviceToken: Secret("t"), installId: "a1b2c3d4e5f60718", sip: SIPCredentials(username: "102", password: Secret("pw"), domain: "acme.powervoip.nl", proxy: "sip.powervoip.nl", port: port, transport: transport, srv: srv), pairedAt: Date())
    }

    func testTlsWithSrv() {
        let config = SipAccountMapping.config(for: account(.tls, port: 5061))

        XCTAssertEqual(config.transport, .tls)
        XCTAssertEqual(config.srtp, .optional)
        XCTAssertEqual(config.identity, "sip:102@acme.powervoip.nl")
        XCTAssertEqual(config.route, "sip:sip.powervoip.nl;transport=tls")
        XCTAssertEqual(config.installId, "a1b2c3d4e5f60718")
        XCTAssertEqual(config.expiresSeconds, 120)
        XCTAssertEqual(config.password.reveal(), "pw")
    }

    func testUdpIsRegisteredOverTcpWithoutSrtp() {
        let config = SipAccountMapping.config(for: account(.udp, port: 5060, srv: false))

        XCTAssertEqual(config.transport, .tcp)
        XCTAssertEqual(config.srtp, .disabled)
        XCTAssertEqual(config.route, "sip:sip.powervoip.nl:5060;transport=tcp")
    }

    func testUnusablePortFallsBack() {
        XCTAssertEqual(SipAccountMapping.config(for: account(.tls, port: 0, srv: false)).port, 5061)
        XCTAssertEqual(SipAccountMapping.config(for: account(.tcp, port: 70000, srv: false)).port, 5060)
    }
}

final class DialNumberTests: XCTestCase {
    func testSanitize() {
        XCTAssertEqual(DialNumber.sanitize("+31 (0)70-123 45 67"), "+31701234567")
        XCTAssertEqual(DialNumber.sanitize("070 123 4567"), "0701234567")
        XCTAssertEqual(DialNumber.sanitize("12+34"), "1234", "a + is only kept at the start")
        XCTAssertEqual(DialNumber.sanitize("*72#"), "*72#")
        XCTAssertEqual(DialNumber.sanitize("１２３"), "123", "full-width digits")
        XCTAssertEqual(DialNumber.sanitize("100@evil.example;transport=udp"), "100")
        XCTAssertEqual(DialNumber.sanitize(String(repeating: "1", count: 40)).count, 32)
    }

    func testDialable() {
        for ok in ["100", "+31701234567", "*72", "#1", "112"] {
            XCTAssertTrue(DialNumber.isDialable(ok), ok)
        }

        for bad in ["", "+", "1+2", "abc", "100@x", "100 200", String(repeating: "1", count: 33)] {
            XCTAssertFalse(DialNumber.isDialable(bad), bad)
        }
    }
}
