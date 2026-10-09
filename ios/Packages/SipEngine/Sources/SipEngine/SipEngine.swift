// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation

/// Hooks for an app that owns the system call UI and the audio session itself (CallKit on iOS, Telecom on Android).
/// The engine must NOT touch the audio session on its own; `CallController` calls these at the moments the system
/// tells it to (see plan D16).
public protocol SipAudioControl: AnyObject {
    /// Prepare the engine's audio for a call: called in the PushKit callback, before the call is reported.
    func configure()
    /// `true` from `provider(_:didActivate:)`, `false` from `provider(_:didDeactivate:)`.
    func activate(_ active: Bool)
}

/// Receives everything the engine reports. Callbacks may arrive on any thread.
public protocol SipEngineDelegate: AnyObject {
    func sipEngine(_ engine: SipEngine, registrationChanged state: RegistrationState, for account: SipAccountID)
    func sipEngine(_ engine: SipEngine, didReceiveIncomingCall call: IncomingCall)
    func sipEngine(_ engine: SipEngine, callChanged call: CallInfo)
}

/// The strict boundary between the app and the SIP/media stack. Implemented by `LinphoneEngine`; a `BaresipEngine`
/// would implement the same protocol (documented fallback). UI, Pairing, Contacts and CallController depend on this
/// protocol and on nothing from the stack.
public protocol SipEngine: AnyObject {
    var delegate: SipEngineDelegate? { get set }
    var audio: SipAudioControl { get }

    // Lifecycle
    func start() throws
    func stop()
    /// The app moved to the background / foreground (the engine adjusts its timers; it does not hold a connection
    /// open in the background, pushes wake the app).
    func enterBackground()
    func enterForeground()
    /// Re-register all accounts now (after a push woke the app, or after a network change).
    func refreshRegistrations()

    // Accounts
    func register(_ account: SipAccountConfig) throws
    func unregister(_ account: SipAccountID)
    func registrationState(of account: SipAccountID) -> RegistrationState
    /// Keep the account but stop (`false`: un-REGISTER, `Expires: 0`) or resume (`true`) its registration. Used in the
    /// background: without a call the app does not stay registered, the PBX push gate wakes it (plan D1/D4). May be
    /// called before `register`: the account is then added with the registration in this state.
    func setRegistrationEnabled(_ enabled: Bool, for account: SipAccountID)
    /// Send a fresh REGISTER for this account now, on a new connection if the old one may be stale (the app was
    /// suspended). The PBX push gate waits for exactly this after a push (a new Call-ID or a full `Expires`).
    func refreshRegistration(of account: SipAccountID)

    // Calls
    /// Start an outgoing call. `options` ride along on the INVITE (`CallOptions.headers`); an incoming call never has them.
    func call(number: String, from account: SipAccountID, options: CallOptions) throws -> CallID
    func answer(_ call: CallID) throws
    /// Reject an incoming call that was not answered: `.declined` = 603 Decline (the user said no), `.busy` = 486 Busy
    /// Here (the app already has a call, or the call it belonged to is over).
    func decline(_ call: CallID, reason: DeclineReason) throws
    func hangup(_ call: CallID) throws
    func setHold(_ call: CallID, onHold: Bool) throws
    /// Microphone mute (applies to the active call).
    func setMuted(_ muted: Bool)
    func sendDTMF(_ digit: DTMFDigit, on call: CallID) throws
    func transfer(_ call: CallID, to number: String) throws
    func calls() -> [CallInfo]
}

extension SipEngine {
    /// An outgoing call without options.
    public func call(number: String, from account: SipAccountID) throws -> CallID {
        try call(number: number, from: account, options: .none)
    }

    /// Decline with 603 (the user said no).
    public func decline(_ call: CallID) throws {
        try decline(call, reason: .declined)
    }
}
