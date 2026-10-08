// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation

/// An engine that does nothing and reports nothing. The app shell uses it until the real engine is wired in
/// (Task 5), and SwiftUI previews and tests use it as a safe default.
public final class NullSipEngine: SipEngine {
    public weak var delegate: SipEngineDelegate?

    private final class NullAudio: SipAudioControl {
        func configure() {}
        func activate(_ active: Bool) {}
    }

    public let audio: SipAudioControl = NullAudio()

    public init() {}

    public func start() throws {}
    public func stop() {}
    public func enterBackground() {}
    public func enterForeground() {}
    public func refreshRegistrations() {}

    public func register(_ account: SipAccountConfig) throws {}
    public func unregister(_ account: SipAccountID) {}

    public func registrationState(of account: SipAccountID) -> RegistrationState {
        .unregistered
    }

    public func call(number: String, from account: SipAccountID) throws -> CallID {
        throw SipEngineError.notStarted
    }

    public func answer(_ call: CallID) throws {
        throw SipEngineError.unknownCall(call)
    }

    public func decline(_ call: CallID) throws {
        throw SipEngineError.unknownCall(call)
    }

    public func hangup(_ call: CallID) throws {
        throw SipEngineError.unknownCall(call)
    }

    public func setHold(_ call: CallID, onHold: Bool) throws {
        throw SipEngineError.unknownCall(call)
    }

    public func setMuted(_ muted: Bool) {}

    public func sendDTMF(_ digit: DTMFDigit, on call: CallID) throws {
        throw SipEngineError.unknownCall(call)
    }

    public func transfer(_ call: CallID, to number: String) throws {
        throw SipEngineError.unknownCall(call)
    }

    public func calls() -> [CallInfo] {
        []
    }
}
