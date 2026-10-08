// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation
import SipEngine

/// One call as the app shows it: the CallKit side (`uuid`) joined with the SIP side (`engineCallID`).
public struct CallSession: Identifiable, Equatable, Sendable {
    public enum Phase: Equatable, Sendable {
        /// Outgoing, waiting for the system to start the call.
        case starting
        /// Outgoing, INVITE sent / the other side is ringing.
        case ringing
        /// Incoming, ringing here.
        case incoming
        /// Answered, media is being set up.
        case connecting
        case active
        /// We put the call on hold.
        case held
        /// The other side put us on hold.
        case heldByRemote
        case ended(CallEndReason)

        public var isEnded: Bool {
            if case .ended = self {
                return true
            }

            return false
        }

        /// Talking (or on hold): the timer runs.
        public var isConnected: Bool {
            switch self {
            case .active, .held, .heldByRemote:
                return true
            default:
                return false
            }
        }
    }

    public let id: UUID
    public var engineCallID: CallID?
    public let direction: CallDirection
    public let accountId: SipAccountID
    public var accountLabel: String
    public var remoteNumber: String?
    public var remoteName: String?
    public var phase: Phase
    public var isMuted = false
    public var isOnHold = false
    public let createdAt: Date
    public var connectedAt: Date?
    /// `callRef` of the push that announced this call (= the `X-FSS-Call` header of its INVITE).
    public var fssCallRef: String?
    /// Reported to the system from a push; the SIP INVITE has not arrived yet (`engineCallID == nil`).
    public var awaitingInvite = false
    /// The user answered on the call UI before the INVITE arrived: answer it as soon as it does.
    var answerPending = false
    /// The user ended (or declined) the call on the call UI: the system must not be told again.
    var endedByUser = false
    var reportedConnected = false

    public init(id: UUID, engineCallID: CallID?, direction: CallDirection, accountId: SipAccountID, accountLabel: String, remoteNumber: String?, remoteName: String?, phase: Phase, createdAt: Date) {
        self.id = id
        self.engineCallID = engineCallID
        self.direction = direction
        self.accountId = accountId
        self.accountLabel = accountLabel
        self.remoteNumber = remoteNumber
        self.remoteName = remoteName
        self.phase = phase
        self.createdAt = createdAt
    }

    /// Name if known, otherwise the number, otherwise `nil` (anonymous).
    public var remoteTitle: String? {
        if let remoteName, !remoteName.isEmpty {
            return remoteName
        }

        if let remoteNumber, !remoteNumber.isEmpty {
            return remoteNumber
        }

        return nil
    }

    /// Map an engine state onto the phase (`nil` = ended, handled separately).
    static func phase(for state: CallState, direction: CallDirection) -> Phase {
        switch state {
        case .incomingRinging:
            return .incoming
        case .outgoingInitiated, .outgoingRinging:
            return .ringing
        case .connecting:
            return .connecting
        case .active:
            return .active
        case .held:
            return .held
        case .heldByRemote:
            return .heldByRemote
        case let .ended(reason):
            return .ended(reason)
        }
    }
}

/// Why the phone refused to start a call.
public enum PhoneError: Error, Equatable, Sendable {
    case invalidNumber
    case unknownAccount
    /// The line is not registered right now (no network, wrong credentials).
    case lineNotConnected
    /// There is already a call.
    case callInProgress
    /// The system refused the call (e.g. a cellular call is active).
    case systemRefused
    case engine(String)
}
