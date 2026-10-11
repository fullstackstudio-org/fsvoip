// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Local access control for the sensitive screens (the "Centrale" section, recordings, voicemail): Face ID, Touch ID or the
// device passcode, through `LocalAuthentication`. It is a comfort against a phone that is picked up while unlocked, not a
// server safeguard (the server enforces the role on every route). Nothing here is ever stored: not the result, not a token.

import Foundation
import LocalAuthentication

public enum LocalAuthAvailability: Equatable, Sendable {
    case available
    /// The phone has no passcode: without one there is nothing to check, so the sections stay closed.
    case noPasscode
    case unavailable
}

public enum LocalAuthResult: Equatable, Sendable {
    case success
    /// The user cancelled (or the system did, e.g. the app went to the background).
    case cancelled
    case failed
    /// No passcode on this phone (or the check cannot run at all).
    case unavailable
}

/// The system check, replaceable in tests and the demo.
public protocol LocalAuthenticating: Sendable {
    func availability() -> LocalAuthAvailability
    func evaluate(reason: String) async -> LocalAuthResult
}

/// `LAContext` with `deviceOwnerAuthentication`: biometrics first, the passcode as the fallback.
public struct SystemLocalAuth: LocalAuthenticating {
    public init() {}

    public func availability() -> LocalAuthAvailability {
        let context = LAContext()
        var error: NSError?

        if context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) {
            return .available
        }

        if let error, error.code == LAError.passcodeNotSet.rawValue {
            return .noPasscode
        }

        return .unavailable
    }

    public func evaluate(reason: String) async -> LocalAuthResult {
        let context = LAContext()
        var error: NSError?

        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            return error?.code == LAError.passcodeNotSet.rawValue ? .unavailable : .failed
        }

        do {
            let ok = try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)

            return ok ? .success : .failed
        } catch let error as LAError {
            switch error.code {
            case .userCancel, .systemCancel, .appCancel:
                return .cancelled
            case .passcodeNotSet:
                return .unavailable
            default:
                return .failed
            }
        } catch {
            return .failed
        }
    }
}

/// What `LocalAccessGate.ensureUnlocked` found.
public enum LocalAccessOutcome: Equatable, Sendable {
    case unlocked
    case cancelled
    case failed
    /// The phone has no passcode (or no check is possible): the section is not available.
    case unavailable(LocalAuthAvailability)
}

/// Keeps the check "open" for five minutes of inactivity, so a user who edits several things in a row is asked once.
/// Every use of a protected screen (`touch()`) extends the window; after five idle minutes the next use asks again.
@MainActor
public final class LocalAccessGate {
    public static let validity: TimeInterval = 5 * 60

    private let authenticator: LocalAuthenticating
    private let now: @Sendable () -> Date
    private var lastActivity: Date?
    private var inFlight: Task<LocalAccessOutcome, Never>?

    public init(authenticator: LocalAuthenticating, now: @escaping @Sendable () -> Date = { Date() }) {
        self.authenticator = authenticator
        self.now = now
    }

    public var availability: LocalAuthAvailability {
        authenticator.availability()
    }

    /// Inside the five-minute window.
    public var isUnlocked: Bool {
        guard let lastActivity else {
            return false
        }

        return now().timeIntervalSince(lastActivity) <= Self.validity
    }

    /// The user is still working in a protected screen: keep it open. Does nothing when it is locked.
    public func touch() {
        if isUnlocked {
            lastActivity = now()
        }
    }

    public func lock() {
        lastActivity = nil
    }

    /// Open already (and extend), or ask the system. Concurrent calls share one prompt.
    public func ensureUnlocked(reason: String) async -> LocalAccessOutcome {
        if isUnlocked {
            lastActivity = now()

            return .unlocked
        }

        if let inFlight {
            return await inFlight.value
        }

        switch authenticator.availability() {
        case .available:
            break
        case let other:
            return .unavailable(other)
        }

        let authenticator = authenticator
        let task = Task { () -> LocalAccessOutcome in
            switch await authenticator.evaluate(reason: reason) {
            case .success: return .unlocked
            case .cancelled: return .cancelled
            case .failed: return .failed
            case .unavailable: return .unavailable(authenticator.availability())
            }
        }

        inFlight = task
        let outcome = await task.value
        inFlight = nil

        if outcome == .unlocked {
            lastActivity = now()
        }

        return outcome
    }
}
