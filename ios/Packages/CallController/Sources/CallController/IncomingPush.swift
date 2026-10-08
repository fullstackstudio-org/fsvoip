// SPDX-License-Identifier: AGPL-3.0-or-later
//
// What to do with a VoIP push (plan D1, D9, D16). Pure: no CallKit, no SIP, no clock of its own, so every path is
// unit tested. `PhoneController` (PhoneController+Push.swift) carries the decision out.
//
// Apple's rule: EVERY PushKit push must report a call to CallKit inside the push callback, also a push the app cannot
// use (unreadable, too late, for an account that is gone). A rejected push is therefore still reported, and ended
// straight away with the reason below.

import Core
import Foundation

/// Why a VoIP push does not become a ringing call.
public enum RingPushRejection: Equatable, Sendable {
    /// Not a readable `ring` message (no `fsvoip` object, wrong version, a `revoked`/`refresh` that came in as VoIP).
    case invalidPayload
    /// `expiresAt` has passed: the PBX stopped waiting for this phone long ago.
    case expired
    /// The push is for an account that is not (or no longer) on this phone.
    case unknownAccount
    /// The app already has a call (one call at a time in this version).
    case busy
    /// This call is already known: its INVITE came first, the push came twice, or the call already ended.
    case alreadyHandled
    /// Reported, but the system refused the call (Do Not Disturb, a blocked number, a cellular call).
    case refusedBySystem

    /// How the reported-and-ended call is shown to the system.
    var systemReason: CallSystemEndReason {
        switch self {
        case .invalidPayload, .unknownAccount, .refusedBySystem:
            return .failed
        case .expired, .busy, .alreadyHandled:
            return .unanswered
        }
    }
}

public enum RingPushDecision: Equatable, Sendable {
    /// Report the call as ringing and wait for its INVITE until `inviteDeadline`.
    case ring(RingPush, inviteDeadline: Date)
    /// Report and end at once. `ring` is the payload when it could be read (for the call screen and the recents).
    case reject(RingPushRejection, ring: RingPush?)
}

public struct IncomingPushPolicy: Equatable, Sendable {
    /// How long to wait for the INVITE after the push arrived (the PBX waits at most 10 s for this phone, plan D1).
    public var inviteTimeout: TimeInterval
    /// Clocks of the phone and the server differ a little; a push is only "expired" this long after `expiresAt`.
    public var clockTolerance: TimeInterval
    /// Never wait less than this, even for a push that arrived late (the INVITE may be on its way).
    public var minimumWait: TimeInterval

    public init(inviteTimeout: TimeInterval = 10, clockTolerance: TimeInterval = 5, minimumWait: TimeInterval = 2) {
        self.inviteTimeout = inviteTimeout
        self.clockTolerance = clockTolerance
        self.minimumWait = minimumWait
    }

    /// - Parameters:
    ///   - message: the decoded push, `nil` when it could not be decoded.
    ///   - knownAccounts: ids of the accounts on this phone.
    ///   - busy: the app already has a call.
    ///   - isHandled: whether a call with this `callRef` is already known (live or recently ended).
    public func decide(
        _ message: PushMessage?,
        now: Date,
        knownAccounts: Set<String>,
        busy: Bool,
        isHandled: (String) -> Bool
    ) -> RingPushDecision {
        guard case let .ring(ring)? = message, UUID(uuidString: ring.callRef) != nil else {
            return .reject(.invalidPayload, ring: nil)
        }

        if isHandled(ring.callRef) {
            return .reject(.alreadyHandled, ring: ring)
        }

        if now > ring.expiresAt.addingTimeInterval(clockTolerance) {
            return .reject(.expired, ring: ring)
        }

        guard knownAccounts.contains(ring.accountId) else {
            return .reject(.unknownAccount, ring: ring)
        }

        if busy {
            return .reject(.busy, ring: ring)
        }

        return .ring(ring, inviteDeadline: inviteDeadline(for: ring, now: now))
    }

    /// `inviteTimeout` after now, but not much past `expiresAt` (plus the clock tolerance), and never less than
    /// `minimumWait`.
    public func inviteDeadline(for ring: RingPush, now: Date) -> Date {
        let untilExpiry = ring.expiresAt.addingTimeInterval(clockTolerance).timeIntervalSince(now)
        let wait = max(minimumWait, min(inviteTimeout, untilExpiry))

        return now.addingTimeInterval(wait)
    }

    /// The CallKit UUID of a call with this `callRef`. The callRef is the FreeSWITCH call UUID, so the push and its
    /// INVITE map onto the SAME CallKit call whichever arrives first (a second report is refused by CallKit as
    /// "already exists", which is harmless).
    public static func callUUID(for callRef: String?) -> UUID? {
        callRef.flatMap(UUID.init(uuidString:))
    }

    /// Does the caller of an INVITE without `X-FSS-Call` plausibly belong to a push (fallback match, plan D16)? Equal
    /// when one side is unknown, or when the last 9 digits agree (`+31612345678` vs `0612345678`).
    public static func callersMatch(_ pushed: String?, _ invited: String?) -> Bool {
        let a = digits(pushed)
        let b = digits(invited)

        guard !a.isEmpty, !b.isEmpty else {
            return true
        }

        return a == b || String(a.suffix(9)) == String(b.suffix(9))
    }

    private static func digits(_ number: String?) -> String {
        String((number ?? "").filter { $0.isASCII && $0.isNumber })
    }
}

/// Runs a closure after a delay; the returned closure cancels it. Injected so tests control time.
public typealias CallScheduler = @MainActor (TimeInterval, @escaping @MainActor () -> Void) -> @MainActor () -> Void

public enum CallSchedulers {
    /// `Task.sleep` on the main actor.
    public static let live: CallScheduler = { delay, action in
        let task = Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(max(0, delay) * 1_000_000_000))

            if !Task.isCancelled {
                action()
            }
        }

        return { task.cancel() }
    }
}
