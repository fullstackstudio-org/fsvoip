// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation
import SipEngine

/// Records what the phone asks; the test drives the engine's events by hand.
final class FakeSipEngine: SipEngine {
    weak var delegate: SipEngineDelegate?

    final class Audio: SipAudioControl {
        var log: [String] = []
        func configure() { log.append("configure") }
        func activate(_ active: Bool) { log.append(active ? "activate" : "deactivate") }
    }

    let fakeAudio = Audio()
    var audio: SipAudioControl { fakeAudio }

    var started = false
    var registered: [SipAccountID: SipAccountConfig] = [:]
    var registerCount = 0
    var log: [String] = []
    var muted = false
    var failNextCall = false
    /// The options of every call started, in order.
    var callOptions: [CallOptions] = []

    func start() throws { started = true }
    func stop() {}
    func enterBackground() { log.append("background") }
    func enterForeground() { log.append("foreground") }
    func refreshRegistrations() { log.append("refresh") }

    func register(_ account: SipAccountConfig) throws {
        registerCount += 1
        registered[account.id] = account
    }

    func unregister(_ account: SipAccountID) {
        registered[account] = nil
        log.append("unregister \(account)")
    }

    func registrationState(of account: SipAccountID) -> RegistrationState { .unregistered }

    /// Accounts whose registration is switched off.
    var disabled: Set<SipAccountID> = []
    func setRegistrationEnabled(_ enabled: Bool, for account: SipAccountID) {
        if enabled { disabled.remove(account) } else { disabled.insert(account) }
        log.append("\(enabled ? "enable" : "disable") \(account)")
    }

    func refreshRegistration(of account: SipAccountID) { log.append("refresh \(account)") }

    func call(number: String, from account: SipAccountID, options: CallOptions) throws -> CallID {
        if failNextCall {
            failNextCall = false
            throw SipEngineError.engine("boom")
        }

        log.append("call \(number) from \(account)")
        callOptions.append(options)
        return CallID("out-1")
    }

    /// Runs inside `answer`, like liblinphone, which reports Connected and StreamsRunning before `accept` returns.
    var onAnswer: ((CallID) -> Void)?
    func answer(_ call: CallID) throws {
        log.append("answer \(call)")
        onAnswer?(call)
    }
    func decline(_ call: CallID, reason: DeclineReason) throws { log.append(reason == .busy ? "busy \(call)" : "decline \(call)") }
    func hangup(_ call: CallID) throws { log.append("hangup \(call)") }
    func setHold(_ call: CallID, onHold: Bool) throws { log.append("hold \(onHold)") }
    func setMuted(_ muted: Bool) { self.muted = muted }
    func sendDTMF(_ digit: DTMFDigit, on call: CallID) throws { log.append("dtmf \(digit.character)") }
    func transfer(_ call: CallID, to number: String) throws {}
    func calls() -> [CallInfo] { [] }

    /// The live SIP Call-IDs; without an entry the engine id itself.
    var sipCallIDs: [CallID: String] = [:]
    func sipCallID(of call: CallID) -> String? { sipCallIDs[call] ?? call.rawValue }

    // MARK: Driving events

    func emitRegistration(_ state: RegistrationState, _ account: String) {
        delegate?.sipEngine(self, registrationChanged: state, for: SipAccountID(account))
    }

    func emitIncoming(id: String, from: String?, name: String?, account: String, callRef: String? = nil) {
        delegate?.sipEngine(self, didReceiveIncomingCall: IncomingCall(id: CallID(id), from: from, displayName: name, accountId: SipAccountID(account), fssCallRef: callRef))
    }

    func emitState(_ state: CallState, id: String, direction: CallDirection, account: String) {
        delegate?.sipEngine(self, callChanged: CallInfo(id: CallID(id), direction: direction, accountId: SipAccountID(account), remoteNumber: nil, remoteName: nil, state: state))
    }
}
