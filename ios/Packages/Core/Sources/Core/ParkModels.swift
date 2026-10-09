// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Swift mirror of `/calls/park` and `/parked` in `shared/openapi.yaml`. Parking puts the own running call on hold in a numbered slot;
// picking it up is dialling `retrieveNumber` (`*5901`) as a normal call. After the park time-out the call rings back at the extension
// that parked it.
//
// 🚨 `APIError.parkUncertain`: the PBX failed while parking and the call MAY be parked. Never park again: refresh the list.

import Foundation

/// `POST /calls/park`. `callId` is the SIP `Call-ID` of the leg of the app; the extension that parks comes from the pairing.
public struct ParkRequest: Encodable, Equatable, Sendable {
    public var callId: String

    public init(callId: String) {
        self.callId = callId
    }
}

public struct ParkedBy: Decodable, Equatable, Sendable {
    /// The app pairing that parked it; `nil` = parked from another phone of the PBX.
    public var deviceId: String?
    public var name: String

    public init(deviceId: String?, name: String) {
        self.deviceId = deviceId
        self.name = name
    }

    private enum CodingKeys: String, CodingKey {
        case deviceId, name
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        deviceId = try container.decodeIfPresent(String.self, forKey: .deviceId)
        name = try container.decodeIfPresent(String.self, forKey: .name) ?? ""
    }
}

public struct ParkedCall: Decodable, Equatable, Sendable, Identifiable {
    /// Opaque, sealed per PBX (never a channel id). Goes into `DELETE /parked/{id}`.
    public var id: String
    public var slot: Int
    /// The number that picks the call up when dialled as a normal call (`*5901`); `nil` = unknown.
    public var retrieveNumber: String?
    public var callerNumber: String?
    public var callerName: String?
    public var parkedAt: Date?
    /// After this the call rings back at the extension that parked it.
    public var expiresAt: Date?
    /// `nil` = parked outside FullStack Studio.
    public var parkedBy: ParkedBy?
    /// Parked by this pairing.
    public var mine: Bool

    public init(id: String, slot: Int, retrieveNumber: String? = nil, callerNumber: String? = nil, callerName: String? = nil, parkedAt: Date? = nil, expiresAt: Date? = nil, parkedBy: ParkedBy? = nil, mine: Bool = false) {
        self.id = id
        self.slot = slot
        self.retrieveNumber = retrieveNumber
        self.callerNumber = callerNumber
        self.callerName = callerName
        self.parkedAt = parkedAt
        self.expiresAt = expiresAt
        self.parkedBy = parkedBy
        self.mine = mine
    }

    private enum CodingKeys: String, CodingKey {
        case id, slot, retrieveNumber, callerNumber, callerName, parkedAt, expiresAt, parkedBy, mine
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        slot = try container.decode(Int.self, forKey: .slot)
        retrieveNumber = try container.decodeIfPresent(String.self, forKey: .retrieveNumber)
        callerNumber = try container.decodeIfPresent(String.self, forKey: .callerNumber)
        callerName = try container.decodeIfPresent(String.self, forKey: .callerName)
        // A time the PBX sent in a shape we cannot read is "unknown", not a reason to lose the whole list.
        parkedAt = try? container.decodeIfPresent(Date.self, forKey: .parkedAt)
        expiresAt = try? container.decodeIfPresent(Date.self, forKey: .expiresAt)
        parkedBy = try? container.decodeIfPresent(ParkedBy.self, forKey: .parkedBy)
        mine = try container.decodeIfPresent(Bool.self, forKey: .mine) ?? false
    }
}

/// `GET /parked`.
public struct ParkedCallsPage: Decodable, Equatable, Sendable {
    public var calls: [ParkedCall]
    /// `false` = parking does not work on this PBX right now: hide the section.
    public var available: Bool

    public init(calls: [ParkedCall], available: Bool = true) {
        self.calls = calls
        self.available = available
    }

    private enum CodingKeys: String, CodingKey {
        case calls, available
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        calls = try container.decodeIfPresent([ParkedCall].self, forKey: .calls) ?? []
        available = try container.decodeIfPresent(Bool.self, forKey: .available) ?? false
    }
}
