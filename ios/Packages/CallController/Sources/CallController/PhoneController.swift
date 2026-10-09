// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The phone: registers the paired accounts with the SIP engine and runs every call through the system call UI
// (plan D16). Platform logic, not UI: the screens only observe it and ask it to do things.
//
// Flow of an outgoing call:   startCall → system.requestStartCall → performStartCall → engine.call
//                             → engine state changes → system.reportOutgoing… / reportCallEnded
// Flow of an incoming call:   engine.didReceiveIncomingCall → system.reportIncomingCall
//                             → performAnswerCall → engine.answer → audio starts in audioSessionActivated(true)
// With the app closed:        PushKit → handleVoipPush (reports at once) → wake the account → INVITE with
//                             X-FSS-Call joins the reported call (PhoneController+Push.swift)
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
    @Published public internal(set) var sessions: [CallSession] = []
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

    let engine: SipEngine
    let system: CallSystem
    private let audioRouting: AudioRouting
    let preferences: PreferencesStore
    let logger: FSLogger
    private let endedLinger: TimeInterval
    let now: () -> Date
    let pushPolicy: IncomingPushPolicy
    let schedule: CallScheduler

    var accounts: [String: StoredAccount] = [:]
    private var registeredConfigs: [String: SipAccountConfig] = [:]
    private var bridge: EngineBridge?

    /// The app is in the background (or was launched there by a push).
    public private(set) var isInBackground = false
    /// In the background without a call the accounts are not registered: the PBX push gate wakes the app (D1/D4).
    public private(set) var registrationsSuspended = false
    /// Accounts woken by a push while the others stay suspended.
    var awakeAccounts: Set<String> = []
    /// Cancels the "no INVITE came" timer of a call reported from a push.
    var inviteTimers: [UUID: @MainActor () -> Void] = [:]
    /// Calls (by `callRef`) that already ended here, and how a late INVITE for them is rejected.
    var closedCallRefs: [String: (at: Date, reject: DeclineReason)] = [:]

    public init(
        engine: SipEngine,
        system: CallSystem,
        audioRouting: AudioRouting = SystemAudioRouting(),
        preferences: PreferencesStore,
        logger: FSLogger = FSLogger(category: "phone"),
        endedLinger: TimeInterval = 1.5,
        now: @escaping () -> Date = Date.init,
        pushPolicy: IncomingPushPolicy = IncomingPushPolicy(),
        schedule: @escaping CallScheduler = CallSchedulers.live
    ) {
        self.engine = engine
        self.system = system
        self.audioRouting = audioRouting
        self.preferences = preferences
        self.logger = logger
        self.endedLinger = endedLinger
        self.now = now
        self.pushPolicy = pushPolicy
        self.schedule = schedule

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

    /// The app went to the background (or was launched there by a push). Without a call the accounts un-register:
    /// staying registered in the background drains the battery and leaves a dead contact on the PBX once iOS
    /// suspends the app; the push gate wakes the app for a call instead.
    public func enterBackground() {
        isInBackground = true

        if sessions.isEmpty {
            suspendRegistrations()
        }

        engine.enterBackground()
    }

    public func enterForeground() {
        isInBackground = false
        resumeRegistrations()
        engine.enterForeground()
        engine.refreshRegistrations()
    }

    func suspendRegistrations() {
        // Already suspended: only the accounts a push woke are still registered.
        let toStop = registrationsSuspended ? awakeAccounts : Set(registeredConfigs.keys)
        registrationsSuspended = true
        awakeAccounts = []

        for id in toStop {
            engine.setRegistrationEnabled(false, for: SipAccountID(id))
        }
    }

    func resumeRegistrations() {
        guard registrationsSuspended else {
            return
        }

        registrationsSuspended = false
        awakeAccounts = []

        for id in registeredConfigs.keys {
            engine.setRegistrationEnabled(true, for: SipAccountID(id))
        }
    }

    /// Register this account now, also when the others stay suspended (a push for it arrived).
    func wakeRegistration(of accountId: String) {
        if registrationsSuspended {
            awakeAccounts.insert(accountId)
            engine.setRegistrationEnabled(true, for: SipAccountID(accountId))
        }

        engine.refreshRegistration(of: SipAccountID(accountId))
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

            if registrationsSuspended, !awakeAccounts.contains(account.id) {
                // Added in the background (e.g. the app was launched by a push): keep it quiet until a push wakes it.
                engine.setRegistrationEnabled(false, for: config.id)
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
    /// `options` (the number to call out with) belong to this outgoing call only.
    public func startCall(number rawNumber: String, accountId: String, options: CallOptions = .none) throws {
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
        var session = CallSession(
            id: uuid,
            engineCallID: nil,
            direction: .outgoing,
            accountId: SipAccountID(accountId),
            accountLabel: account.displayLabel,
            remoteNumber: number,
            remoteName: name,
            phase: .starting,
            createdAt: now()
        )
        session.callOptions = options
        session.viaNumber = options.headers.first?.value
        sessions.append(session)

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

        // The call a push announced: join the INVITE to the call that is already on the screen.
        if attachInvite(call) {
            return
        }

        // The push for this call was handled and the call already ended here (declined, timed out): reject it.
        if let ref = call.fssCallRef, let closed = closedCallRefs[ref] {
            logger.notice("INVITE for an ended call \(ref): rejected")
            try? engine.decline(call.id, reason: closed.reject)
            return
        }

        // One call at a time in this version: a second caller hears busy.
        guard sessions.isEmpty else {
            try? engine.decline(call.id, reason: .busy)
            onCallFinished?(RecentCall(number: call.from ?? "", name: call.displayName, accountId: account.id, accountLabel: account.displayLabel, direction: .incoming, outcome: .missed, startedAt: now(), duration: 0))
            return
        }

        // Keyed on the callRef, so a push that arrives after its INVITE maps onto this same call.
        let uuid = IncomingPushPolicy.callUUID(for: call.fssCallRef) ?? UUID()
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
        var tracked = session
        tracked.fssCallRef = call.fssCallRef
        sessions.append(tracked)

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

    func session(_ uuid: UUID) -> CallSession? {
        sessions.first { $0.id == uuid }
    }

    func update(_ uuid: UUID, _ change: (inout CallSession) -> Void) {
        guard let index = sessions.firstIndex(where: { $0.id == uuid }) else {
            return
        }

        change(&sessions[index])
    }

    func markEndedByUser(_ uuid: UUID) {
        update(uuid) { $0.endedByUser = true }
    }

    func finish(_ uuid: UUID, reason: CallEndReason, tellSystem: Bool) {
        guard var session = session(uuid) else {
            return
        }

        session.phase = .ended(reason)
        sessions.removeAll { $0.id == uuid }
        inviteTimers.removeValue(forKey: uuid)?()

        if let ref = session.fssCallRef {
            // A late INVITE (or a repeated push) for this call must not ring again.
            rememberClosed(ref, reject: reason == .declined ? .declined : .busy)
        }

        if tellSystem, !session.endedByUser {
            system.reportCallEnded(uuid: uuid, reason: Self.systemReason(reason))
        }

        if sessions.isEmpty {
            // A new call starts unmuted, on the earpiece.
            engine.setMuted(false)

            if isSpeakerOn {
                setSpeaker(false)
            }

            // The call kept the app registered in the background; now the push gate takes over again.
            if isInBackground {
                suspendRegistrations()
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
            return try engine.call(number: number, from: session.accountId, options: session.direction == .outgoing ? session.callOptions : .none)
        } catch {
            logger.error("Outgoing call failed to start: \(error)")
            return nil
        }
    }

    public func performAnswerCall(uuid: UUID) -> Bool {
        guard let session = session(uuid) else {
            return false
        }

        guard let engineID = session.engineCallID else {
            // Answered on the lock screen before the INVITE arrived (the usual case after a push): fulfil the action
            // so CallKit activates the audio session, and answer the INVITE as soon as it comes.
            guard session.awaitingInvite else {
                return false
            }

            engine.audio.configure()
            update(uuid) {
                $0.answerPending = true
                $0.phase = .connecting
            }

            return true
        }

        engine.audio.configure()

        do {
            try engine.answer(engineID)
            // liblinphone reports Connected and StreamsRunning synchronously inside `answer` (the callee does not wait
            // for the ACK), so the call can already be active here. Only a call that is still ringing moves to
            // connecting; overwriting an active call left the screen on "Verbinden..." for the whole call.
            update(uuid) {
                if $0.phase == .incoming {
                    $0.phase = .connecting
                }
            }
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
            // Declined (or hung up after answering) before the INVITE arrived: the late INVITE gets a 603.
            let declined = session.direction == .incoming && !session.answerPending
            finish(uuid, reason: declined ? .declined : .localHangup, tellSystem: false)
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
