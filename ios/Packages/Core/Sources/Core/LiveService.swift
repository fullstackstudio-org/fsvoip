// SPDX-License-Identifier: AGPL-3.0-or-later
//
// What the Live screen needs from the API, per paired account. One implementation talks to the server, tests and the demo mode bring
// their own. Every method throws `APIError`.

import Foundation

public protocol LiveServicing: Sendable {
    func calls(for account: StoredAccount) async throws -> LiveCallsPage
    func targets(for account: StoredAccount) async throws -> LiveTargetsPage
    /// Takes the ringing call `ref` over to the OWN extension of `account`.
    func pickup(ref: String, for account: StoredAccount) async throws -> LivePickupResponse
    func transfer(ref: String, to target: LiveTarget, for account: StoredAccount) async throws -> LiveTransferResponse
}

public struct LiveLiveService: LiveServicing {
    private let api: FSVoipAPIClient

    public init(api: FSVoipAPIClient = FSVoipAPIClient()) {
        self.api = api
    }

    private func client(_ account: StoredAccount) -> FSVoipAPIClient {
        api.authenticated(with: account.deviceToken)
    }

    public func calls(for account: StoredAccount) async throws -> LiveCallsPage {
        try await client(account).liveCalls()
    }

    public func targets(for account: StoredAccount) async throws -> LiveTargetsPage {
        try await client(account).liveTargets()
    }

    public func pickup(ref: String, for account: StoredAccount) async throws -> LivePickupResponse {
        try await client(account).pickupLiveCall(ref: ref)
    }

    public func transfer(ref: String, to target: LiveTarget, for account: StoredAccount) async throws -> LiveTransferResponse {
        try await client(account).transferLiveCall(ref: ref, to: target)
    }
}

/// Why the Live screen failed, in the user's terms.
public enum LiveFailure: Equatable, Sendable {
    /// 404 `call_not_found`: the call is gone.
    case callNotFound
    /// 409 `call_not_ringing`: somebody else answered, or the caller hung up.
    case notRinging
    /// 409 `call_not_transferable`.
    case notTransferable
    /// 409 `live_unavailable`: the PBX cannot do live calls now.
    case unavailable
    /// 403: a `user` may only transfer the call of the own extension.
    case forbidden
    /// 401: the pairing is gone.
    case revoked
    case readOnly
    case invalidTarget
    case rateLimited(retryAfterSeconds: Int?)
    case offline
    case other

    public static func classify(_ error: Error) -> LiveFailure {
        guard let api = error as? APIError else {
            return .other
        }

        switch api {
        case .callNotFound, .notFound: return .callNotFound
        case let .conflict(code) where code == "call_not_ringing": return .notRinging
        case let .conflict(code) where code == "call_not_transferable": return .notTransferable
        case let .conflict(code) where code == "live_unavailable": return .unavailable
        case let .conflict(code) where code == "read_only": return .readOnly
        case .readOnly: return .readOnly
        case .forbidden: return .forbidden
        case .unauthorized: return .revoked
        case .invalid, .invalidRequest: return .invalidTarget
        case let .rateLimited(seconds): return .rateLimited(retryAfterSeconds: seconds)
        case .transport: return .offline
        default: return .other
        }
    }
}
