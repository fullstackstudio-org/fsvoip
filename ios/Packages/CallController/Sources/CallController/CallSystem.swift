// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation

/// Why a call ended, as the system call UI is told (mirrors `CXCallEndedReason` without importing CallKit here).
public enum CallSystemEndReason: Equatable, Sendable {
    case failed
    case remoteEnded
    case unanswered
    case answeredElsewhere
    case declinedElsewhere
}

/// What the user did on the system call UI (or on our in-call screen, which goes through the same system
/// transactions so both stay in sync). The handler returns `false` when the action cannot be performed; the system
/// then fails the action.
@MainActor
public protocol CallSystemActionHandler: AnyObject {
    func performStartCall(uuid: UUID, handle: String) -> Bool
    func performAnswerCall(uuid: UUID) -> Bool
    func performEndCall(uuid: UUID) -> Bool
    func performSetHeld(uuid: UUID, onHold: Bool) -> Bool
    func performSetMuted(uuid: UUID, muted: Bool) -> Bool
    func performPlayDTMF(uuid: UUID, digits: String) -> Bool
    /// The system activated (`true`) or deactivated (`false`) the audio session.
    func audioSessionActivated(_ active: Bool)
    /// The system dropped all calls (rare).
    func systemDidReset()
}

/// The system call UI as `PhoneController` sees it: CallKit on a phone (`CallKitSystem`), or a loop-back for tests
/// and the demo mode (`ImmediateCallSystem`). Everything happens on the main actor.
@MainActor
public protocol CallSystem: AnyObject {
    var handler: CallSystemActionHandler? { get set }

    /// Report an incoming call. For a PushKit push this must happen inside the push callback (Task 6).
    func reportIncomingCall(uuid: UUID, handle: String, displayName: String, completion: @escaping (Error?) -> Void)
    func reportCallUpdated(uuid: UUID, displayName: String)
    /// The call ended without the user ending it on the call UI (remote hang-up, failure).
    func reportCallEnded(uuid: UUID, reason: CallSystemEndReason)
    func reportOutgoingCallStartedConnecting(uuid: UUID)
    func reportOutgoingCallConnected(uuid: UUID)

    /// Ask the system to start/answer/end/hold/mute/play DTMF; it then calls the handler's `perform…`.
    func requestStartCall(uuid: UUID, handle: String, displayName: String?, completion: @escaping (Error?) -> Void)
    func requestAnswerCall(uuid: UUID, completion: @escaping (Error?) -> Void)
    func requestEndCall(uuid: UUID, completion: @escaping (Error?) -> Void)
    func requestSetHeld(uuid: UUID, onHold: Bool, completion: @escaping (Error?) -> Void)
    func requestSetMuted(uuid: UUID, muted: Bool, completion: @escaping (Error?) -> Void)
    func requestPlayDTMF(uuid: UUID, digits: String, completion: @escaping (Error?) -> Void)
}

public struct CallSystemActionFailed: Error, Equatable {
    public init() {}
}

/// Performs every request straight away on the handler and activates the audio like CallKit would. Used by the tests
/// and the demo mode; the app on a phone uses `CallKitSystem`.
@MainActor
public final class ImmediateCallSystem: CallSystem {
    public weak var handler: CallSystemActionHandler?

    public enum Event: Equatable {
        case reportedIncoming(UUID, handle: String, displayName: String)
        case updated(UUID, displayName: String)
        case ended(UUID, CallSystemEndReason)
        case startedConnecting(UUID)
        case connected(UUID)
    }

    public private(set) var events: [Event] = []
    /// Make `reportIncomingCall` fail (the system refuses, e.g. Do Not Disturb or a blocked number).
    public var refuseIncoming = false
    private var audioActive = false

    public init() {}

    public func reportIncomingCall(uuid: UUID, handle: String, displayName: String, completion: @escaping (Error?) -> Void) {
        events.append(.reportedIncoming(uuid, handle: handle, displayName: displayName))
        completion(refuseIncoming ? CallSystemActionFailed() : nil)
    }

    public func reportCallUpdated(uuid: UUID, displayName: String) {
        events.append(.updated(uuid, displayName: displayName))
    }

    public func reportCallEnded(uuid: UUID, reason: CallSystemEndReason) {
        events.append(.ended(uuid, reason))
        deactivateAudioIfIdle()
    }

    public func reportOutgoingCallStartedConnecting(uuid: UUID) {
        events.append(.startedConnecting(uuid))
    }

    public func reportOutgoingCallConnected(uuid: UUID) {
        events.append(.connected(uuid))
    }

    public func requestStartCall(uuid: UUID, handle: String, displayName: String?, completion: @escaping (Error?) -> Void) {
        let ok = handler?.performStartCall(uuid: uuid, handle: handle) ?? false
        completion(ok ? nil : CallSystemActionFailed())

        if ok {
            activateAudio()
        }
    }

    public func requestAnswerCall(uuid: UUID, completion: @escaping (Error?) -> Void) {
        let ok = handler?.performAnswerCall(uuid: uuid) ?? false
        completion(ok ? nil : CallSystemActionFailed())

        if ok {
            activateAudio()
        }
    }

    public func requestEndCall(uuid: UUID, completion: @escaping (Error?) -> Void) {
        let ok = handler?.performEndCall(uuid: uuid) ?? false
        completion(ok ? nil : CallSystemActionFailed())
        deactivateAudioIfIdle()
    }

    public func requestSetHeld(uuid: UUID, onHold: Bool, completion: @escaping (Error?) -> Void) {
        completion((handler?.performSetHeld(uuid: uuid, onHold: onHold) ?? false) ? nil : CallSystemActionFailed())
    }

    public func requestSetMuted(uuid: UUID, muted: Bool, completion: @escaping (Error?) -> Void) {
        completion((handler?.performSetMuted(uuid: uuid, muted: muted) ?? false) ? nil : CallSystemActionFailed())
    }

    public func requestPlayDTMF(uuid: UUID, digits: String, completion: @escaping (Error?) -> Void) {
        completion((handler?.performPlayDTMF(uuid: uuid, digits: digits) ?? false) ? nil : CallSystemActionFailed())
    }

    /// Simulate the user pressing "end" on the system UI.
    public func simulateUserEnd(uuid: UUID) {
        requestEndCall(uuid: uuid) { _ in }
    }

    private func activateAudio() {
        guard !audioActive else {
            return
        }

        audioActive = true
        handler?.audioSessionActivated(true)
    }

    private func deactivateAudioIfIdle() {
        // CallKit deactivates the session once the last call is gone; good enough for a loop-back.
        guard audioActive else {
            return
        }

        audioActive = false
        handler?.audioSessionActivated(false)
    }
}
