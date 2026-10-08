// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation

/// Why pairing (or another account request) did not work, in the terms the user can act on.
public enum PairingFailure: Error, Equatable, Sendable {
    /// The code is unknown, expired (10 minutes) or already used: make a new one in the portal.
    case codeExpiredOrUsed
    /// Too many attempts from this network.
    case tooManyAttempts(retryAfterSeconds: Int?)
    /// The server or the PBX could not finish right now; the code was NOT used, so trying again is fine.
    case temporarilyUnavailable
    /// No connection to the server.
    case network
    /// The pairing was revoked (portal, admin, or another phone took over the extension).
    case revoked
    /// The secure storage of the phone refused to save the account.
    case storage
    /// Anything else (an app bug or an unexpected answer).
    case other

    public init(_ error: Error) {
        switch error {
        case let error as APIError:
            switch error {
            case .notFound:
                self = .codeExpiredOrUsed
            case .unauthorized, .missingDeviceToken:
                self = .revoked
            case let .rateLimited(retryAfter):
                self = .tooManyAttempts(retryAfterSeconds: retryAfter)
            case .unavailable:
                self = .temporarilyUnavailable
            case .transport:
                self = .network
            case .invalidRequest, .payloadTooLarge, .decoding, .unexpectedStatus:
                self = .other
            }
        case is SecretStoreError:
            self = .storage
        case let error as URLError:
            self = Self.isNetwork(error) ? .network : .other
        default:
            self = .other
        }
    }

    /// Whether repeating the same request may work.
    public var isRetryable: Bool {
        switch self {
        case .temporarilyUnavailable, .network, .tooManyAttempts, .storage:
            return true
        case .codeExpiredOrUsed, .revoked, .other:
            return false
        }
    }

    private static func isNetwork(_ error: URLError) -> Bool {
        [.notConnectedToInternet, .networkConnectionLost, .timedOut, .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed, .internationalRoamingOff, .dataNotAllowed]
            .contains(error.code)
    }
}
