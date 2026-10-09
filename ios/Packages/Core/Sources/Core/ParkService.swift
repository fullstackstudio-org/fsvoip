// SPDX-License-Identifier: AGPL-3.0-or-later
//
// What the "On hold" tab and the park button need from the API, per paired account. One implementation talks to the server, tests and the
// demo mode bring their own. Every method throws `APIError`.

import Foundation

public protocol ParkServicing: Sendable {
    /// `POST /calls/park`. `callId` = the SIP `Call-ID` of the running call.
    func park(callId: String, for account: StoredAccount) async throws -> ParkedCall
    func parked(for account: StoredAccount) async throws -> ParkedCallsPage
    func hangup(id: String, for account: StoredAccount) async throws
}

public struct LiveParkService: ParkServicing {
    private let api: FSVoipAPIClient

    public init(api: FSVoipAPIClient = FSVoipAPIClient()) {
        self.api = api
    }

    private func client(_ account: StoredAccount) -> FSVoipAPIClient {
        api.authenticated(with: account.deviceToken)
    }

    public func park(callId: String, for account: StoredAccount) async throws -> ParkedCall {
        try await client(account).parkCall(callId: callId)
    }

    public func parked(for account: StoredAccount) async throws -> ParkedCallsPage {
        try await client(account).parkedCalls()
    }

    public func hangup(id: String, for account: StoredAccount) async throws {
        try await client(account).hangupParkedCall(id: id)
    }
}

/// Why parking, listing or hanging up failed, in the user's terms.
public enum ParkFailure: Equatable, Sendable {
    /// 404: the call is no longer running (or it is not a call of this extension).
    case callNotFound
    /// 409 `no_free_slot` / `park_busy`.
    case noFreeSlot
    /// 409 `park_unavailable`.
    case unavailable
    /// 503 `park_uncertain`: the call MAY be parked. NEVER try again; refresh the list.
    case uncertain
    /// 403: a `user` may only hang up a call that is theirs, or the pairing lost its rights.
    case forbidden
    /// 401: the pairing is gone.
    case revoked
    case readOnly
    /// 404 when hanging up: already gone.
    case notFound
    case rateLimited(retryAfterSeconds: Int?)
    case offline
    case other

    public static func classify(_ error: Error) -> ParkFailure {
        guard let api = error as? APIError else {
            return .other
        }

        switch api {
        case .callNotFound: return .callNotFound
        case .noFreeSlot: return .noFreeSlot
        case .conflict(let code) where code == "park_busy": return .noFreeSlot
        case .parkUnavailable: return .unavailable
        case .parkUncertain: return .uncertain
        case .forbidden: return .forbidden
        case .unauthorized: return .revoked
        case .readOnly: return .readOnly
        case .conflict(let code) where code == "read_only": return .readOnly
        case .notFound, .gone: return .notFound
        case let .rateLimited(seconds): return .rateLimited(retryAfterSeconds: seconds)
        case .transport: return .offline
        case .unavailable, .unexpectedStatus: return .other
        default: return .other
        }
    }
}
