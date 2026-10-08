// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The phone: registers the paired accounts with the SIP engine and runs every call through the system call UI
// (plan D16). Platform logic, not UI: the screens only observe it and ask it to do things.
//
// Flow of an outgoing call:   startCall → system.requestStartCall → performStartCall → engine.call
//                             → engine state changes → system.reportOutgoing… / reportCallEnded
// Flow of an incoming call:   engine.didReceiveIncomingCall → system.reportIncomingCall
//                             → performAnswerCall → engine.answer → audio starts in audioSessionActivated(true)
// Every user action (also from our own in-call screen) goes through a system transaction, so CallKit and the app
// never disagree about a call.

import Combine
import Core
import Foundation
import SipEngine

@MainActor
public final class PhoneController: ObservableObject {
    /// Registration state per account id.
    @Published public private(set) var registrations: [String: RegistrationState] = [:]
    /// Calls that are not ended, oldest first (the MVP has at most one).
    @Published public private(set) var sessions: [CallSession] = []
    /// The call that just ended (kept for `endedLinger` so the in-call screen can say so), or `nil`.
    @Published public private(set) var lastEnded: CallSession?
    @Published public private(set) var isSpeakerOn = false
    @Published public private(set) var engineStarted = false

    /// A finished call, for the local recents.
    public var onCallFinished: (@MainActor (RecentCall) -> Void)?
    /// Name for a number from the local contact sources (Task 7). `nil` = unknown.
    public var lookupName: @MainActor (String) -> String? = { _ in nil }
    /// Text for an anonymous caller (localised by the app).
    public var anonymousCallerText = "Onbekend"

    private let engine: SipEngine
    private let system: CallSystem
    private let audioRouting: AudioRouting
    private let preferences: PreferencesStore
    private let logger: FSLogger
    private let endedLinger: TimeInterval
    private let now: () -> Date

    private var accounts: [String: StoredAccount] = [:]
    private var registeredConfigs: [String: SipAccountConfig] = [:]
    private var bridge: EngineBridge?

    public init(
        engine: SipEngine,
        system: CallSystem,
        audioRouting: AudioRouting = SystemAudioRouting(),
        preferences: PreferencesStore,
        logger: FSLogger = FSLogger(category: "phone"),
        endedLinger: TimeInterval = 1.5,
        now: @escaping () -> Date = Date.init
    ) {
        self.engine = engine
        self.system = system
        self.audioRouting = audioRouting
        self.preferences = preferences
        self.logger = logger
        self.endedLinger = endedLinger
        self.now = now

        let bridge = EngineBridge()
        self.bridge = bridge
        bridge.owner = self
        engine.delegate = bridge
        system.handler = self
    }

    // MARK: Lifecycle

    public func start() {
        guard !engineStarted else {
            return
        }

        do {
            try engine.start()
            engineStarted = true
        } catch {
            logger.error("SIP engine did not start: \(error)")
        }
    }

    public func enterBackground() {
        engine.enterBackground()
    }

    public func enterForeground() {
        engine.enterForeground()
        engine.refreshRegistrations()
    }

    public func refreshRegistrations() {
        engine.refreshRegistrations()
    }

    // MARK: Accounts

    /// Make the engine's accounts match the paired accounts: register new or changed ones, drop removed ones.
    public func sync(accounts list: [StoredAccount]) {
        start()

        let wanted = Dictionary(uniqueKeysWithValues: list.map { ($0.id, $0) })
        accounts = wanted

        for id in registeredConfigs.keys where wanted[id] == nil {
            engine.unregister(SipAccountID(id))
            registeredConfigs[id] = nil
            registrations[id] = nil
        }

        guard engineStarted else {
            return
        }

        for account in list {
            let config = SipAccountMapping.config(for: account)

            guard registeredConfigs[account.id] != config else {
                continue
            }

            do {
                try engine.register(config)
                registeredConfigs[account.id] = config
                registrations[account.id] = .registering
            } catch {
                logger.error("Account \(account.id) could not be registered: \(error)")
                registrations[account.id] = .failed(.other("\(error)"))
            }
        }
    }

    public func registrationState(for accountId: String) -> RegistrationState {
        registrations[accountId] ?? .unregistered
    }

    // MARK: Calls (asked by the UI)

    public var activeSession: CallSession? {
        sessions.last
    }

    /// Start an outgoing call through the system call UI.
    public func startCall(number rawNumber: String, accountId: String) throws {
        let number = DialNumber.sanitize(rawNumber)

        guard DialNumber.isDialable(number) else {
            throw PhoneError.invalidNumber
        }

        guard let account = accounts[accountId] else {
            throw PhoneError.unknownAccount
        }

        guard registrationState(for: accountId) == .registered else {
            throw PhoneError.lineNotConnected
        }

        guard sessions.isEmpty else {
            throw PhoneError.callInProgress
        }

        let uuid = UUID()
        let name = lookupName(number)
        sessions.append(CallSession(
            id: uuid,
            engineCallID: nil,
            direction: .outgoing,
            accountId: SipAccountID(accountId),
            accountLabel: account.displayLabel,
            remoteNumber: number,
            remoteName: name,
            phase: .starting,
            createdAt: now()
        ))

        system.requestStartCall(uuid: uuid, handle: number, displayName: name) { [weak self] error in
            guard let self, error != nil else {
                return
            }

            self.logger.notice("The system refused the outgoing call")

            if let session = self.session(uuid), !session.phase.isEnded {
                self.finish(uuid, reason: .failed("refused"), tellSystem: false)
            }
        }
    }

    public func answer(_ uuid: UUID) {
        system.requestAnswerCall(uuid: uuid) { _ in }
    }

    public func hangUp(_ uuid: UUID) {
        system.requestEndCall(uuid: uuid) { [weak self] error in
            // If the system no longer knows the call, end it on our side anyway.
            if error != nil, let self, self.session(uuid) != nil {
                _ = self.performEndCall(uuid: uuid)
            }
        }
    }

    public func setMuted(_ uuid: UUID, _ muted: Bool) {
        system.requestSetMuted(uuid: uuid, muted: muted) { _ in }
    }

    public func setHeld(_ uuid: UUID, _ held: Bool) {
        system.requestSetHeld(uuid: uuid, onHold: held) { _ in }
    }

    public func sendDTMF(_ uuid: UUID, _ digits: String) {
        system.requestPlayDTMF(uuid: uuid, digits: digits) { _ in }
    }

    public func setSpeaker(_ on: Bool) {
        do {
            try audioRouting.setSpeaker(on)
            isSpeakerOn = on
        } catch {
            logger.error("Speaker could not be switched: \(error)")
            isSpeakerOn = audioRouting.isSpeakerActive
        }
    }

    // MARK: Engine events

    fileprivate func engineRegistrationChanged(_ state: RegistrationState, for account: SipAccountID) {
        guard accounts[account.rawValue] != nil else {
            return
        }

        registrations[account.rawValue] = state
    }

    fileprivate func engineIncomingCall(_ call: IncomingCall) {
        guard let account = accounts[call.accountId.rawValue] else {
            try? engine.decline(call.id)
            return
        }

        // One call at a time in this version: a second caller hears busy.
        guard sessions.isEmpty else {
            try? engine.decline(call.id)
            onCallFinished?(RecentCall(number: call.from ?? "", name: call.displayName, accountId: account.id, accountLabel: account.displayLabel, direction: .incoming, outcome: .missed, startedAt: now(), duration: 0))
            return
        }

        let uuid = UUID()
        let name = call.from.flatMap(lookupName) ?? call.displayName
        let session = CallSession(
            id: uuid,
            engineCallID: call.id,
            direction: .incoming,
            accountId: call.accountId,
            accountLabel: account.displayLabel,
            remoteNumber: call.from,
            remoteName: name,
            phase: .incoming,
            createdAt: now()
        )
        sessions.append(session)

        let display = CallDisplay.callerText(
            callerName: name,
            callerNumber: call.from,
            accountLabel: account.displayLabel,
            showAccount: CallDisplay.shouldShowAccount(setting: preferences.preferences(for: account.id).showCalledAccount, accountCount: accounts.count),
            anonymous: anonymousCallerText
        )

        system.reportIncomingCall(uuid: uuid, handle: call.from ?? anonymousCallerText, displayName: display) { [weak self] error in
            guard let self, error != nil else {
                return
            }

            // The system refused (Do Not Disturb, blocked number): decline the SIP call.
            self.logger.notice("The system refused the incoming call")
            self.markEndedByUser(uuid)
            try? self.engine.decline(call.id)
            self.finish(uuid, reason: .declined, tellSystem: false)
        }
    }

    fileprivate func engineCallChanged(_ info: CallInfo) {
        guard let uuid = sessions.first(where: { $0.engineCallID == info.id })?.id else {
            return
        }

        if case let .ended(reason) = info.state {
            finish(uuid, reason: reason, tellSystem: true)
            return
        }

        update(uuid) { session in
            session.phase = CallSession.phase(for: info.state, direction: session.direction)

            if session.remoteName == nil, let name = info.remoteName {
                session.remoteName = name
            }

            if session.phase.isConnected, session.connectedAt == nil {
                session.connectedAt = now()
            }
        }

        if let session = session(uuid), session.direction == .outgoing, session.phase.isConnected, !session.reportedConnected {
            update(uuid) { $0.reportedConnected = true }
            system.reportOutgoingCallConnected(uuid: uuid)
        }
    }

    // MARK: Helpers

    private func session(_ uuid: UUID) -> CallSession? {
        sessions.first { $0.id == uuid }
    }

    private func update(_ uuid: UUID, _ change: (inout CallSession) -> Void) {
        guard let index = sessions.firstIndex(where: { $0.id == uuid }) else {
            return
        }

        change(&sessions[index])
    }

    private func markEndedByUser(_ uuid: UUID) {
        update(uuid) { $0.endedByUser = true }
    }

    private func finish(_ uuid: UUID, reason: CallEndReason, tellSystem: Bool) {
        guard var session = session(uuid) else {
            return
        }

        session.phase = .ended(reason)
        sessions.removeAll { $0.id == uuid }

        if tellSystem, !session.endedByUser {
            system.reportCallEnded(uuid: uuid, reason: Self.systemReason(reason))
        }

        if sessions.isEmpty {
            // A new call starts unmuted, on the earpiece.
            engine.setMuted(false)

            if isSpeakerOn {
                setSpeaker(false)
            }
        }

        onCallFinished?(Self.recent(for: session, reason: reason, endedAt: now()))

        lastEnded = session

        if endedLinger > 0 {
            let delay = UInt64(endedLinger * 1_000_000_000)

            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: delay)

                if self?.lastEnded?.id == uuid {
                    self?.lastEnded = nil
                }
            }
        } else {
            lastEnded = nil
        }
    }

    static func systemReason(_ reason: CallEndReason) -> CallSystemEndReason {
        switch reason {
        case .localHangup, .remoteHangup:
            return .remoteEnded
        case .declined:
            return .declinedElsewhere
        case .unanswered:
            return .unanswered
        case .busy, .failed:
            return .failed
        }
    }

    static func recent(for session: CallSession, reason: CallEndReason, endedAt: Date) -> RecentCall {
        let outcome: RecentCall.Outcome

        if session.connectedAt != nil {
            outcome = .answered
        } else if session.direction == .incoming {
            outcome = reason == .declined ? .declined : .missed
        } else {
            switch reason {
            case .failed:
                outcome = .failed
            default:
                outcome = .notAnswered
            }
        }

        return RecentCall(
            id: session.id.uuidString,
            number: session.remoteNumber ?? "",
            name: session.remoteName,
            accountId: session.accountId.rawValue,
            accountLabel: session.accountLabel,
            direction: session.direction == .incoming ? .incoming : .outgoing,
            outcome: outcome,
            startedAt: session.createdAt,
            duration: session.connectedAt.map { max(0, endedAt.timeIntervalSince($0)) } ?? 0
        )
    }
}

// MARK: - System actions

extension PhoneController: CallSystemActionHandler {
    public func performStartCall(uuid: UUID, handle: String) -> Bool {
        guard let session = session(uuid), let engineID = startEngineCall(session) else {
            if session(uuid) != nil {
                finish(uuid, reason: .failed("start"), tellSystem: false)
            }

            return false
        }

        update(uuid) {
            $0.engineCallID = engineID
            $0.phase = .ringing
        }
        system.reportOutgoingCallStartedConnecting(uuid: uuid)

        return true
    }

    private func startEngineCall(_ session: CallSession) -> CallID? {
        guard let number = session.remoteNumber else {
            return nil
        }

        engine.audio.configure()

        do {
            return try engine.call(number: number, from: session.accountId)
        } catch {
            logger.error("Outgoing call failed to start: \(error)")
            return nil
        }
    }

    public func performAnswerCall(uuid: UUID) -> Bool {
        guard let session = session(uuid), let engineID = session.engineCallID else {
            return false
        }

        engine.audio.configure()

        do {
            try engine.answer(engineID)
            update(uuid) { $0.phase = .connecting }
            return true
        } catch {
            logger.error("Answer failed: \(error)")
            return false
        }
    }

    public func performEndCall(uuid: UUID) -> Bool {
        guard let session = session(uuid) else {
            // Already gone on our side: let the system drop it too.
            return true
        }

        markEndedByUser(uuid)

        guard let engineID = session.engineCallID else {
            finish(uuid, reason: .localHangup, tellSystem: false)
            return true
        }

        do {
            if session.direction == .incoming, session.phase == .incoming {
                try engine.decline(engineID)
            } else {
                try engine.hangup(engineID)
            }
        } catch {
            // The engine no longer knows the call: end it here.
            finish(uuid, reason: session.phase == .incoming ? .declined : .localHangup, tellSystem: false)
        }

        return true
    }

    public func performSetHeld(uuid: UUID, onHold: Bool) -> Bool {
        guard let engineID = session(uuid)?.engineCallID else {
            return false
        }

        do {
            try engine.setHold(engineID, onHold: onHold)
            update(uuid) { $0.isOnHold = onHold }
            return true
        } catch {
            logger.error("Hold failed: \(error)")
            return false
        }
    }

    public func performSetMuted(uuid: UUID, muted: Bool) -> Bool {
        guard session(uuid) != nil else {
            return false
        }

        engine.setMuted(muted)
        update(uuid) { $0.isMuted = muted }

        return true
    }

    public func performPlayDTMF(uuid: UUID, digits: String) -> Bool {
        guard let engineID = session(uuid)?.engineCallID else {
            return false
        }

        for character in digits {
            guard let digit = DTMFDigit(character) else {
                continue
            }

            try? engine.sendDTMF(digit, on: engineID)
        }

        return true
    }

    public func audioSessionActivated(_ active: Bool) {
        engine.audio.activate(active)
    }

    public func systemDidReset() {
        for session in sessions {
            if let engineID = session.engineCallID {
                try? engine.hangup(engineID)
            }

            markEndedByUser(session.id)
            finish(session.id, reason: .localHangup, tellSystem: false)
        }
    }
}

/// Receives the engine's callbacks (which may come from any thread) and hands them to the phone on the main actor.
private final class EngineBridge: SipEngineDelegate {
    weak var owner: PhoneController?

    func sipEngine(_ engine: SipEngine, registrationChanged state: RegistrationState, for account: SipAccountID) {
        onMain { $0.engineRegistrationChanged(state, for: account) }
    }

    func sipEngine(_ engine: SipEngine, didReceiveIncomingCall call: IncomingCall) {
        onMain { $0.engineIncomingCall(call) }
    }

    func sipEngine(_ engine: SipEngine, callChanged call: CallInfo) {
        onMain { $0.engineCallChanged(call) }
    }

    private func onMain(_ body: @escaping @MainActor (PhoneController) -> Void) {
        if Thread.isMainThread {
            MainActor.assumeIsolated {
                if let owner { body(owner) }
            }
        } else {
            DispatchQueue.main.async { [weak self] in
                MainActor.assumeIsolated {
                    if let owner = self?.owner { body(owner) }
                }
            }
        }
    }
}
