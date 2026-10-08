// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Value types of the SIP engine boundary. They are OURS: nothing in here (or anywhere outside the
// `LinphoneEngine` package) may mention a type of the SIP stack.

import Foundation

/// A string that never prints (SIP password). Deliberately NOT shared with `Core`: this package has no dependencies.
public struct SipSecret: Hashable, Sendable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    private let value: String

    public init(_ value: String) {
        self.value = value
    }

    public func reveal() -> String {
        value
    }

    public var description: String {
        "•••"
    }

    public var debugDescription: String {
        "SipSecret(•••)"
    }

    public var customMirror: Mirror {
        Mirror(self, children: [], displayStyle: .struct)
    }
}

public enum SipTransport: String, Sendable, Codable {
    case udp
    case tcp
    case tls
}

public enum SrtpMode: String, Sendable, Codable {
    case disabled
    /// Offer SRTP, accept plain RTP.
    case optional
    /// Refuse to talk without SRTP.
    case mandatory
}

public enum AudioCodec: String, Sendable, Codable, CaseIterable {
    case opus
    case g722
    case pcma
    case pcmu
}

/// Stable id of a SIP account inside the app (the app pairing id from the API, `account.id`).
public struct SipAccountID: Hashable, Sendable, Codable, ExpressibleByStringLiteral, CustomStringConvertible {
    public let rawValue: String

    public init(_ rawValue: String) {
        self.rawValue = rawValue
    }

    public init(stringLiteral value: String) {
        rawValue = value
    }

    public var description: String {
        rawValue
    }
}

/// Everything the engine needs to register one account.
public struct SipAccountConfig: Sendable, Equatable {
    public var id: SipAccountID
    public var username: String
    public var password: SipSecret
    /// SIP domain (registrar domain / realm), e.g. `acme.powervoip.nl`.
    public var domain: String
    /// Outbound proxy host, e.g. `sip.powervoip.nl`. The engine resolves SRV records for it.
    public var proxy: String
    public var port: Int
    public var transport: SipTransport
    /// The proxy publishes DNS SRV records: resolve them (failover between the two PBX nodes) instead of using `port`.
    public var useSRV: Bool
    /// Marker the PBX push gate uses to recognise this installation: Contact URI parameter `fss-dev=<installId>`.
    public var installId: String
    /// Registration lifetime. 120 s keeps a dead contact short-lived on a suspended phone.
    public var expiresSeconds: Int
    public var codecs: [AudioCodec]
    public var srtp: SrtpMode

    public init(
        id: SipAccountID,
        username: String,
        password: SipSecret,
        domain: String,
        proxy: String,
        port: Int,
        transport: SipTransport,
        useSRV: Bool = true,
        installId: String,
        expiresSeconds: Int = 120,
        codecs: [AudioCodec] = [.g722, .pcma, .pcmu],
        srtp: SrtpMode = .optional
    ) {
        self.id = id
        self.username = username
        self.password = password
        self.domain = domain
        self.proxy = proxy
        self.port = port
        self.transport = transport
        self.useSRV = useSRV
        self.installId = installId
        self.expiresSeconds = expiresSeconds
        self.codecs = codecs
        self.srtp = srtp
    }

    /// `sip:<user>@<domain>`
    public var identity: String {
        "sip:\(username)@\(domain)"
    }

    /// `sip:<proxy>;transport=<transport>` with SRV (the port comes from DNS), otherwise `sip:<proxy>:<port>;transport=<transport>`.
    public var route: String {
        useSRV ? "sip:\(proxy);transport=\(transport.rawValue)" : "sip:\(proxy):\(port);transport=\(transport.rawValue)"
    }
}

public enum RegistrationState: Equatable, Sendable {
    case unregistered
    case registering
    case registered
    case failed(RegistrationFailure)
}

public enum RegistrationFailure: Equatable, Sendable {
    /// Wrong credentials (SIP 401/403 after authentication).
    case authentication
    /// The server could not be reached (network down, DNS, TLS).
    case network
    case other(String)
}

public struct CallID: Hashable, Sendable, CustomStringConvertible {
    public let rawValue: String

    public init(_ rawValue: String = UUID().uuidString) {
        self.rawValue = rawValue
    }

    public var description: String {
        rawValue
    }
}

public enum CallDirection: Sendable, Equatable {
    case incoming
    case outgoing
}

public enum CallEndReason: Equatable, Sendable {
    /// We hung up.
    case localHangup
    /// The other side hung up (or cancelled before we answered).
    case remoteHangup
    /// We declined an incoming call.
    case declined
    /// Nobody answered an outgoing call, or an incoming call timed out.
    case unanswered
    case busy
    case failed(String)
}

public enum CallState: Equatable, Sendable {
    /// Incoming call, ringing, not yet answered.
    case incomingRinging
    /// Outgoing call, INVITE sent.
    case outgoingInitiated
    /// Outgoing call, the other side is ringing.
    case outgoingRinging
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
}

public enum AudioRoute: String, Sendable, Codable, CaseIterable {
    case receiver
    case speaker
    case bluetooth
    case wiredHeadset
}

/// A DTMF digit (`0-9`, `*`, `#`, `A-D`).
public struct DTMFDigit: Hashable, Sendable {
    public let character: Character

    public init?(_ character: Character) {
        guard "0123456789*#ABCD".contains(character) else {
            return nil
        }

        self.character = character
    }
}

/// An incoming call as the engine reports it.
public struct IncomingCall: Equatable, Sendable {
    public var id: CallID
    /// Caller number (user part of the SIP address); `nil` = anonymous.
    public var from: String?
    /// Caller name from the SIP display name.
    public var displayName: String?
    public var accountId: SipAccountID
    /// Value of the `X-FSS-Call` header the PBX sets on the INVITE; equals `callRef` of the push that woke the app.
    public var fssCallRef: String?

    public init(id: CallID, from: String?, displayName: String?, accountId: SipAccountID, fssCallRef: String?) {
        self.id = id
        self.from = from
        self.displayName = displayName
        self.accountId = accountId
        self.fssCallRef = fssCallRef
    }
}

/// Snapshot of one call.
public struct CallInfo: Equatable, Sendable {
    public var id: CallID
    public var direction: CallDirection
    public var accountId: SipAccountID
    public var remoteNumber: String?
    public var remoteName: String?
    public var state: CallState
    public var fssCallRef: String?

    public init(
        id: CallID,
        direction: CallDirection,
        accountId: SipAccountID,
        remoteNumber: String?,
        remoteName: String?,
        state: CallState,
        fssCallRef: String? = nil
    ) {
        self.id = id
        self.direction = direction
        self.accountId = accountId
        self.remoteNumber = remoteNumber
        self.remoteName = remoteName
        self.state = state
        self.fssCallRef = fssCallRef
    }
}

public enum SipEngineError: Error, Equatable, Sendable {
    case notStarted
    case unknownAccount(SipAccountID)
    case unknownCall(CallID)
    case invalidNumber
    case invalidState(String)
    case engine(String)
}
