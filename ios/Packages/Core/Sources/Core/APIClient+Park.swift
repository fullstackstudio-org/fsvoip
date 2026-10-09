// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Parking: `POST /calls/park`, `GET /parked`, `DELETE /parked/{id}` (every role).
//
// 🚨 `APIError.parkUncertain` (503) means the PBX failed while parking and the call MAY be parked. Never call `parkCall` again for
// it: refresh `parkedCalls()` and look. Picking a call up is not an API call: dial `ParkedCall.retrieveNumber` as a normal call.

import Foundation

private struct NoBody: Encodable {}

extension FSVoipAPIClient {
    /// Parks the own running call. `callId` = the SIP `Call-ID` of the app's leg. Only when `AppCapabilities.park`.
    /// Failures: `.callNotFound`, `.noFreeSlot`, `.parkUnavailable`, `.parkUncertain`, `.readOnly`.
    public func parkCall(callId: String) async throws -> ParkedCall {
        try await perform("POST", "calls/park", body: ParkRequest(callId: callId), authenticated: true)
    }

    public func parkedCalls() async throws -> ParkedCallsPage {
        try await get("parked")
    }

    /// Hangs up a parked call. A `user` may only hang up a call that is `mine` (`APIError.forbidden` otherwise).
    @discardableResult
    public func hangupParkedCall(id: String) async throws -> OkResponse {
        try await perform("DELETE", "parked/\(id)", body: Optional<NoBody>.none, authenticated: true)
    }
}
