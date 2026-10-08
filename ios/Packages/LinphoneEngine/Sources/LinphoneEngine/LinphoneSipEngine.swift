// SPDX-License-Identifier: AGPL-3.0-or-later
//
// `SipEngine` on top of linphone-sdk (liblinphone). THE ONLY FILE(S) in the repository that may `import linphonesw`.
//
// Deliberately switched off, see plan D3/D16:
//   - linphone's own CallKit integration (`callkitEnabled = false`): CallKit belongs to `CallController`;
//   - linphone's push model (`pushNotificationEnabled = false`, no `pn-*` parameters): that model needs a Flexisip
//     push gateway in front of the PBX; FSVoip uses the PBX push gate + PushKit instead.
//
// Threading: liblinphone is not thread safe. Call every method on the main thread (the core iterates on it too);
// the delegate callbacks arrive on the main thread.
//
// This is the Task 4 skeleton: it compiles against the real SDK and implements the protocol in full, but it has not
// been exercised against a PBX yet (Task 5 does that on real devices).

import Foundation
import SipEngine
import linphonesw

public final class LinphoneSipEngine: SipEngine {
    public weak var delegate: SipEngineDelegate?
    public let audio: SipAudioControl

    private let appName: String
    private let appVersion: String

    private var core: Core?
    private var coreDelegate: CoreDelegateStub?
    private var accounts: [SipAccountID: Account] = [:]
    private var accountConfigs: [SipAccountID: SipAccountConfig] = [:]
    private var callsByID: [CallID: Call] = [:]
    private var idsByCall: [ObjectIdentifier: CallID] = [:]
    /// Why a call ended when WE ended it (otherwise it is a remote hang-up).
    private var localEndReasons: [CallID: CallEndReason] = [:]
    private var answered: Set<CallID> = []
    private var callAccounts: [CallID: SipAccountID] = [:]

    public init(appName: String = "FSVoip", appVersion: String = "0") {
        self.appName = appName
        self.appVersion = appVersion
        audio = LinphoneAudio()
        (audio as? LinphoneAudio)?.engine = self
    }

    // MARK: Lifecycle

    public func start() throws {
        guard core == nil else {
            return
        }

        do {
            let core = try Factory.Instance.createCore(configPath: "", factoryConfigPath: "", systemContext: nil)

            core.callkitEnabled = false
            core.pushNotificationEnabled = false
            core.setUserAgent(name: appName, version: appVersion)
            core.maxCalls = 2

            let stub = CoreDelegateStub(
                onCallStateChanged: { [weak self] _, call, state, message in
                    self?.handle(call: call, state: state, message: message)
                },
                onAccountRegistrationStateChanged: { [weak self] _, account, state, message in
                    self?.handle(account: account, state: state, message: message)
                }
            )
            core.addDelegate(delegate: stub)
            coreDelegate = stub
            self.core = core

            try core.start()
        } catch {
            core = nil
            coreDelegate = nil
            throw SipEngineError.engine("\(error)")
        }
    }

    public func stop() {
        core?.stop()
        core = nil
        coreDelegate = nil
        accounts = [:]
        accountConfigs = [:]
        callsByID = [:]
        idsByCall = [:]
        localEndReasons = [:]
        answered = []
        callAccounts = [:]
    }

    public func enterBackground() {
        core?.enterBackground()
    }

    public func enterForeground() {
        core?.enterForeground()
    }

    public func refreshRegistrations() {
        core?.refreshRegisters()
    }

    // MARK: Accounts

    public func register(_ config: SipAccountConfig) throws {
        let core = try requireCore()

        // Re-registering an account replaces it.
        unregister(config.id)

        do {
            let factory = Factory.Instance
            let params = try core.createAccountParams()

            try params.setIdentityaddress(newValue: try factory.createAddress(addr: config.identity))
            try params.setServeraddress(newValue: try factory.createAddress(addr: "sip:\(config.domain)"))
            try params.setRoutesaddresses(newValue: [try factory.createAddress(addr: config.route)])
            params.transport = Self.transport(config.transport)
            params.registerEnabled = true
            params.expires = config.expiresSeconds
            params.publishEnabled = false
            // The marker the PBX push gate looks for in the stored contact (`;fss-dev=<installId>`).
            params.contactUriParameters = "fss-dev=\(config.installId)"
            params.pushNotificationAllowed = false
            params.remotePushNotificationAllowed = false

            let auth = try factory.createAuthInfo(
                username: config.username,
                userid: nil,
                passwd: config.password.reveal(),
                ha1: nil,
                realm: config.domain,
                domain: config.domain
            )
            core.addAuthInfo(info: auth)

            let account = try core.createAccount(params: params)
            try core.addAccount(account: account)

            accounts[config.id] = account
            accountConfigs[config.id] = config

            applyMedia(for: config, core: core)
        } catch let error as SipEngineError {
            throw error
        } catch {
            throw SipEngineError.engine("\(error)")
        }
    }

    public func unregister(_ id: SipAccountID) {
        guard let core, let account = accounts[id] else {
            return
        }

        core.removeAccount(account: account)
        accounts[id] = nil
        accountConfigs[id] = nil
    }

    public func registrationState(of id: SipAccountID) -> OurRegistrationState {
        guard let account = accounts[id] else {
            return .unregistered
        }

        return Self.registrationState(account.state, message: nil)
    }

    // MARK: Calls

    public func call(number: String, from id: SipAccountID) throws -> CallID {
        let core = try requireCore()

        guard let account = accounts[id], let config = accountConfigs[id] else {
            throw SipEngineError.unknownAccount(id)
        }

        guard Self.isDialable(number) else {
            throw SipEngineError.invalidNumber
        }

        do {
            let address = try Factory.Instance.createAddress(addr: "sip:\(number)@\(config.domain)")
            let params = try core.createCallParams(call: nil)
            params.account = account
            params.videoEnabled = false

            guard let call = core.inviteAddressWithParams(addr: address, params: params) else {
                throw SipEngineError.engine("The call could not be started")
            }

            let callID = track(call)
            callAccounts[callID] = id
            return callID
        } catch let error as SipEngineError {
            throw error
        } catch {
            throw SipEngineError.engine("\(error)")
        }
    }

    public func answer(_ id: CallID) throws {
        let call = try requireCall(id)
        let params = try requireCore().createCallParams(call: call)
        params.videoEnabled = false

        do {
            try call.acceptWithParams(params: params)
            answered.insert(id)
        } catch {
            throw SipEngineError.engine("\(error)")
        }
    }

    public func decline(_ id: CallID) throws {
        let call = try requireCall(id)
        localEndReasons[id] = .declined

        do {
            try call.decline(reason: .Declined)
        } catch {
            throw SipEngineError.engine("\(error)")
        }
    }

    public func hangup(_ id: CallID) throws {
        let call = try requireCall(id)
        localEndReasons[id] = .localHangup

        do {
            try call.terminate()
        } catch {
            throw SipEngineError.engine("\(error)")
        }
    }

    public func setHold(_ id: CallID, onHold: Bool) throws {
        let call = try requireCall(id)

        do {
            if onHold {
                try call.pause()
            } else {
                try call.resume()
            }
        } catch {
            throw SipEngineError.engine("\(error)")
        }
    }

    public func setMuted(_ muted: Bool) {
        core?.micEnabled = !muted
    }

    public func sendDTMF(_ digit: DTMFDigit, on id: CallID) throws {
        let call = try requireCall(id)

        guard let ascii = digit.character.asciiValue else {
            throw SipEngineError.invalidNumber
        }

        do {
            try call.sendDtmf(dtmf: CChar(ascii))
        } catch {
            throw SipEngineError.engine("\(error)")
        }
    }

    public func transfer(_ id: CallID, to number: String) throws {
        let call = try requireCall(id)

        guard Self.isDialable(number) else {
            throw SipEngineError.invalidNumber
        }

        guard let accountId = callAccounts[id], let config = accountConfigs[accountId] else {
            throw SipEngineError.invalidState("No account to transfer from")
        }

        do {
            try call.transferTo(referTo: try Factory.Instance.createAddress(addr: "sip:\(number)@\(config.domain)"))
        } catch {
            throw SipEngineError.engine("\(error)")
        }
    }

    public func calls() -> [CallInfo] {
        (core?.calls ?? []).compactMap { call in
            guard let id = idsByCall[ObjectIdentifier(call)] else {
                return nil
            }

            return info(for: call, id: id, state: Self.callState(call.state, ended: nil))
        }
    }

    // MARK: Audio session hooks (called by LinphoneAudio)

    fileprivate func configureAudioSession() {
        core?.configureAudioSession()
    }

    fileprivate func activateAudioSession(_ active: Bool) {
        core?.activateAudioSession(activated: active)
    }

    // MARK: Event handling

    private func handle(account: Account, state: linphonesw.RegistrationState, message: String) {
        guard let id = accounts.first(where: { $0.value === account })?.key else {
            return
        }

        delegate?.sipEngine(self, registrationChanged: Self.registrationState(state, message: message), for: id)
    }

    private func handle(call: Call, state: Call.State, message: String) {
        let id = track(call)

        switch state {
        case .IncomingReceived:
            guard let accountId = accountID(of: call, id: id) else {
                return
            }

            let incoming = IncomingCall(
                id: id,
                from: call.remoteAddress?.username,
                displayName: Self.nonEmpty(call.remoteAddress?.displayName),
                accountId: accountId,
                fssCallRef: Self.nonEmpty(call.remoteParams?.getCustomHeader(headerName: "X-FSS-Call"))
            )
            delegate?.sipEngine(self, didReceiveIncomingCall: incoming)
        case .End, .Released, .Error:
            let reason = endReason(for: id, call: call, state: state, message: message)

            if let info = info(for: call, id: id, state: .ended(reason)) {
                delegate?.sipEngine(self, callChanged: info)
            }

            forget(call, id: id)
        default:
            if let info = info(for: call, id: id, state: Self.callState(state, ended: nil)) {
                delegate?.sipEngine(self, callChanged: info)
            }
        }
    }

    private func endReason(for id: CallID, call: Call, state: Call.State, message: String) -> CallEndReason {
        if let local = localEndReasons[id] {
            return local
        }

        if state == .Error {
            return call.reason == .Busy ? .busy : .failed(message)
        }

        if call.dir == .Incoming, !answered.contains(id) {
            return .unanswered
        }

        return call.reason == .Busy ? .busy : .remoteHangup
    }

    // MARK: Helpers

    private func requireCore() throws -> Core {
        guard let core else {
            throw SipEngineError.notStarted
        }

        return core
    }

    private func requireCall(_ id: CallID) throws -> Call {
        guard let call = callsByID[id] else {
            throw SipEngineError.unknownCall(id)
        }

        return call
    }

    @discardableResult
    private func track(_ call: Call) -> CallID {
        if let existing = idsByCall[ObjectIdentifier(call)] {
            return existing
        }

        let id = CallID(Self.nonEmpty(call.callLog?.callId) ?? UUID().uuidString)
        callsByID[id] = call
        idsByCall[ObjectIdentifier(call)] = id

        return id
    }

    private func forget(_ call: Call, id: CallID) {
        callsByID[id] = nil
        idsByCall[ObjectIdentifier(call)] = nil
        localEndReasons[id] = nil
        answered.remove(id)
        callAccounts[id] = nil
    }

    /// The account a call belongs to: the one we placed it with, or (incoming) the one whose identity is the callee.
    private func accountID(of call: Call, id: CallID) -> SipAccountID? {
        if let known = callAccounts[id] {
            return known
        }

        let callee = call.toAddress
        let match = accountConfigs.first { _, config in
            config.username == callee?.username && config.domain == callee?.domain
        }?.key ?? accountConfigs.keys.first

        if let match {
            callAccounts[id] = match
        }

        return match
    }

    private func info(for call: Call, id: CallID, state: CallState) -> CallInfo? {
        guard let accountId = accountID(of: call, id: id) else {
            return nil
        }

        return CallInfo(
            id: id,
            direction: call.dir == .Incoming ? .incoming : .outgoing,
            accountId: accountId,
            remoteNumber: call.remoteAddress?.username,
            remoteName: Self.nonEmpty(call.remoteAddress?.displayName),
            state: state,
            fssCallRef: Self.nonEmpty(call.remoteParams?.getCustomHeader(headerName: "X-FSS-Call"))
        )
    }

    private func applyMedia(for config: SipAccountConfig, core: Core) {
        // Codecs: only what the account asks for (plus DTMF events), in the account's own spirit.
        let wanted: [String: AudioCodec] = ["opus": .opus, "g722": .g722, "pcma": .pcma, "pcmu": .pcmu]

        for payload in core.audioPayloadTypes {
            let mime = payload.mimeType.lowercased()

            if mime == "telephone-event" {
                _ = payload.enable(enabled: true)
            } else if let codec = wanted[mime] {
                _ = payload.enable(enabled: config.codecs.contains(codec))
            } else {
                _ = payload.enable(enabled: false)
            }
        }

        // SRTP is a core-level setting in liblinphone (shared by all accounts): the strictest request wins.
        switch config.srtp {
        case .disabled:
            try? core.setMediaencryption(newValue: .None)
            core.mediaEncryptionMandatory = false
        case .optional:
            try? core.setMediaencryption(newValue: .SRTP)
            core.mediaEncryptionMandatory = false
        case .mandatory:
            try? core.setMediaencryption(newValue: .SRTP)
            core.mediaEncryptionMandatory = true
        }
    }

    static func transport(_ transport: SipTransport) -> TransportType {
        switch transport {
        case .udp: return .Udp
        case .tcp: return .Tcp
        case .tls: return .Tls
        }
    }

    static func registrationState(_ state: linphonesw.RegistrationState, message: String?) -> OurRegistrationState {
        switch state {
        case .None, .Cleared:
            return .unregistered
        case .Progress, .Refreshing:
            return .registering
        case .Ok:
            return .registered
        case .Failed:
            let text = (message ?? "").lowercased()

            if text.contains("forbidden") || text.contains("unauthorized") || text.contains("403") || text.contains("401") {
                return .failed(.authentication)
            }

            if text.contains("timeout") || text.contains("unreachable") || text.contains("network") || text.contains("io error") {
                return .failed(.network)
            }

            return .failed(.other(message ?? "registration failed"))
        }
    }

    static func callState(_ state: Call.State, ended: CallEndReason?) -> CallState {
        switch state {
        case .IncomingReceived, .PushIncomingReceived, .IncomingEarlyMedia:
            return .incomingRinging
        case .OutgoingInit:
            return .outgoingInitiated
        case .OutgoingProgress, .OutgoingRinging, .OutgoingEarlyMedia:
            return .outgoingRinging
        case .Connected, .Resuming, .Updating, .UpdatedByRemote, .Referred, .EarlyUpdating, .EarlyUpdatedByRemote:
            return .connecting
        case .StreamsRunning:
            return .active
        case .Pausing, .Paused:
            return .held
        case .PausedByRemote:
            return .heldByRemote
        case .End, .Released, .Error, .Idle:
            return .ended(ended ?? .remoteHangup)
        @unknown default:
            return .connecting
        }
    }

    /// Digits, `+`, `*`, `#`, 1 to 32 characters. Anything else could smuggle SIP syntax into the request URI.
    static func isDialable(_ number: String) -> Bool {
        guard (1 ... 32).contains(number.count) else {
            return false
        }

        return number.allSatisfy { $0.isASCII && ($0.isNumber || $0 == "+" || $0 == "*" || $0 == "#") }
    }

    private static func nonEmpty(_ text: String?) -> String? {
        guard let text, !text.isEmpty else {
            return nil
        }

        return text
    }
}

/// Audio session hooks for CallKit (plan D16): the engine never touches `AVAudioSession` itself.
final class LinphoneAudio: SipAudioControl {
    weak var engine: LinphoneSipEngine?

    func configure() {
        engine?.configureAudioSession()
    }

    func activate(_ active: Bool) {
        engine?.activateAudioSession(active)
    }
}
