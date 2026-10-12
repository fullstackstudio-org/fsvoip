// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Live calls: `GET /pbx/live`, `GET /pbx/live/targets`, `POST /pbx/live/pickup`, `POST /pbx/live/transfer` (every role).
//
// 🚨 Send the `ref` of the LATEST list: it changes with every response. Picking up always lands on the own extension (never sent).
// Transferring is blind and internal; attended transfer is done by the app over SIP on its own call.

import Foundation

extension FSVoipAPIClient {
    /// Failures: `.conflict("live_unavailable")` (treat as "off").
    public func liveCalls() async throws -> LiveCallsPage {
        try await get("pbx/live")
    }

    public func liveTargets() async throws -> LiveTargetsPage {
        try await get("pbx/live/targets")
    }

    /// Failures: `.callNotFound`, `.conflict("call_not_ringing")`, `.conflict("read_only")`, `.rateLimited`.
    public func pickupLiveCall(ref: String) async throws -> LivePickupResponse {
        try await perform("POST", "pbx/live/pickup", body: LivePickupRequest(ref: ref), authenticated: true)
    }

    /// Failures: `.forbidden` (a `user` and a call that is not theirs), `.callNotFound`, `.conflict("call_not_transferable")`.
    public func transferLiveCall(ref: String, to target: LiveTarget) async throws -> LiveTransferResponse {
        try await perform("POST", "pbx/live/transfer", body: LiveTransferRequest(ref: ref, target: .init(type: target.type, id: target.id)), authenticated: true)
    }
}
