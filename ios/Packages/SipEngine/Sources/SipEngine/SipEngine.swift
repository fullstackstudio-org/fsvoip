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

    // Calls
    func call(number: String, from account: SipAccountID) throws -> CallID
    func answer(_ call: CallID) throws
    func decline(_ call: CallID) throws
    func hangup(_ call: CallID) throws
    func setHold(_ call: CallID, onHold: Bool) throws
    /// Microphone mute (applies to the active call).
    func setMuted(_ muted: Bool)
    func sendDTMF(_ digit: DTMFDigit, on call: CallID) throws
    func transfer(_ call: CallID, to number: String) throws
    func calls() -> [CallInfo]
}
