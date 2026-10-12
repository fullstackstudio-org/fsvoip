// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Swift mirror of `/pbx/live`, `/pbx/live/targets`, `/pbx/live/pickup` and `/pbx/live/transfer` in `shared/openapi.yaml` (plan
// `fss-features-2026-10`, Task 4). Every role sees the running calls of the PBX, takes a ringing call over to the OWN extension and
// transfers (blind, internal only) the call the own extension answered; an `admin` transfers every answered call.
//
// 🚨 `ref` is sealed per PBX and different in every response: always send the `ref` of the latest list. There is no push for this; the
// app polls while the screen is visible.

import Foundation

public enum LiveState: String, WireEnum {
    case ringing
    case active
    case held
    case parked
    case unknown

    public static var unknownValue: LiveState { .unknown }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

public enum LiveDirection: String, WireEnum {
    case inbound
    case outbound
    case `internal`
    case unknown

    public static var unknownValue: LiveDirection { .unknown }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

public struct LiveParty: Decodable, Equatable, Sendable {
    public var number: String
    public var name: String?

    public init(number: String, name: String? = nil) {
        self.number = number
        self.name = name
    }

    /// `Receptie (101)`, or just the number.
    public var label: String {
        if let name, !name.isEmpty { return "\(name) (\(number))" }

        return number
    }
}

public struct LiveParked: Decodable, Equatable, Sendable {
    public var slot: Int
    public var retrieveNumber: String?

    public init(slot: Int, retrieveNumber: String? = nil) {
        self.slot = slot
        self.retrieveNumber = retrieveNumber
    }
}

public struct LiveCall: Decodable, Equatable, Sendable, Identifiable {
    /// Sealed, different in every response.
    public var ref: String
    public var direction: LiveDirection
    public var state: LiveState
    public var callerNumber: String?
    public var callerName: String?
    public var calleeNumber: String?
    public var ringing: [LiveParty]
    public var answeredBy: LiveParty?
    public var startedAt: Date
    public var answeredAt: Date?
    public var parked: LiveParked?
    public var canPickup: Bool
    public var canTransfer: Bool
    /// The call rings at, or runs on, the own extension.
    public var mine: Bool

    /// Stable between polls (the `ref` is not): rows and open sheets stay put.
    public var id: String {
        "\(startedAt.timeIntervalSince1970)|\(callerNumber ?? "-")|\(calleeNumber ?? "-")"
    }

    public init(
        ref: String,
        direction: LiveDirection = .inbound,
        state: LiveState,
        callerNumber: String? = nil,
        callerName: String? = nil,
        calleeNumber: String? = nil,
        ringing: [LiveParty] = [],
        answeredBy: LiveParty? = nil,
        startedAt: Date,
        answeredAt: Date? = nil,
        parked: LiveParked? = nil,
        canPickup: Bool = false,
        canTransfer: Bool = false,
        mine: Bool = false
    ) {
        self.ref = ref
        self.direction = direction
        self.state = state
        self.callerNumber = callerNumber
        self.callerName = callerName
        self.calleeNumber = calleeNumber
        self.ringing = ringing
        self.answeredBy = answeredBy
        self.startedAt = startedAt
        self.answeredAt = answeredAt
        self.parked = parked
        self.canPickup = canPickup
        self.canTransfer = canTransfer
        self.mine = mine
    }

    private enum CodingKeys: String, CodingKey {
        case ref, direction, state, callerNumber, callerName, calleeNumber, ringing, answeredBy, startedAt, answeredAt, parked, canPickup, canTransfer, mine
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        ref = try container.decode(String.self, forKey: .ref)
        direction = try container.decodeIfPresent(LiveDirection.self, forKey: .direction) ?? .inbound
        state = try container.decodeIfPresent(LiveState.self, forKey: .state) ?? .unknown
        callerNumber = try container.decodeIfPresent(String.self, forKey: .callerNumber)
        callerName = try container.decodeIfPresent(String.self, forKey: .callerName)
        calleeNumber = try container.decodeIfPresent(String.self, forKey: .calleeNumber)
        ringing = try container.decodeIfPresent([LiveParty].self, forKey: .ringing) ?? []
        answeredBy = try? container.decodeIfPresent(LiveParty.self, forKey: .answeredBy)
        startedAt = try container.decode(Date.self, forKey: .startedAt)
        answeredAt = try? container.decodeIfPresent(Date.self, forKey: .answeredAt)
        parked = try? container.decodeIfPresent(LiveParked.self, forKey: .parked)
        // The restrictive choice when the server leaves a flag out.
        canPickup = try container.decodeIfPresent(Bool.self, forKey: .canPickup) ?? false
        canTransfer = try container.decodeIfPresent(Bool.self, forKey: .canTransfer) ?? false
        mine = try container.decodeIfPresent(Bool.self, forKey: .mine) ?? false
    }

    public var isRinging: Bool { state == .ringing }
    public var isParked: Bool { state == .parked }
    public var isRunning: Bool { state == .active || state == .held }

    /// Where the elapsed time counts from: answered, else started.
    public var clockStart: Date {
        isRunning ? (answeredAt ?? startedAt) : startedAt
    }
}

/// `GET /pbx/live`.
public struct LiveCallsPage: Decodable, Equatable, Sendable {
    /// `false` = live calls are off for this PBX: hide the screen.
    public var enabled: Bool
    public var fresh: Bool
    public var calls: [LiveCall]

    public init(enabled: Bool = true, fresh: Bool = true, calls: [LiveCall] = []) {
        self.enabled = enabled
        self.fresh = fresh
        self.calls = calls
    }

    private enum CodingKeys: String, CodingKey {
        case enabled, fresh, calls
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        // A tolerant list: one row we cannot read is not a reason to lose the others.
        enabled = try container.decodeIfPresent(Bool.self, forKey: .enabled) ?? false
        fresh = try container.decodeIfPresent(Bool.self, forKey: .fresh) ?? false
        calls = (try? container.decodeIfPresent([LiveCall].self, forKey: .calls)) ?? []
    }
}

public enum LiveTargetType: String, WireEnum {
    case `extension`
    case ringGroup = "ring_group"
    case queue
    case ivr
    case businessHours = "business_hours"
    case voicemail
    case unknown

    public static var unknownValue: LiveTargetType { .unknown }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

public struct LiveTarget: Decodable, Equatable, Sendable, Identifiable, Hashable {
    public var type: LiveTargetType
    public var id: String
    public var name: String
    public var `extension`: String

    public init(type: LiveTargetType, id: String, name: String, extension number: String) {
        self.type = type
        self.id = id
        self.name = name
        self.extension = number
    }
}

/// `GET /pbx/live/targets`.
public struct LiveTargetsPage: Decodable, Equatable, Sendable {
    public var readOnly: Bool
    public var targets: [LiveTarget]

    public init(readOnly: Bool = false, targets: [LiveTarget] = []) {
        self.readOnly = readOnly
        self.targets = targets
    }

    private enum CodingKeys: String, CodingKey {
        case readOnly, targets
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        readOnly = try container.decodeIfPresent(Bool.self, forKey: .readOnly) ?? false
        // An entry of a type this version does not know cannot be chosen; leave it out.
        targets = ((try? container.decodeIfPresent([LiveTarget].self, forKey: .targets)) ?? []).filter { $0.type != .unknown }
    }
}

/// `POST /pbx/live/pickup`. The extension is never sent: it comes from the pairing.
public struct LivePickupRequest: Encodable, Equatable, Sendable {
    public var ref: String

    public init(ref: String) {
        self.ref = ref
    }
}

public struct LivePickupResponse: Decodable, Equatable, Sendable {
    public var pickedBy: LiveParty?

    public init(pickedBy: LiveParty? = nil) {
        self.pickedBy = pickedBy
    }
}

/// `POST /pbx/live/transfer`.
public struct LiveTransferRequest: Encodable, Equatable, Sendable {
    public struct Target: Encodable, Equatable, Sendable {
        public var type: LiveTargetType
        public var id: String

        public init(type: LiveTargetType, id: String) {
            self.type = type
            self.id = id
        }
    }

    public var ref: String
    public var target: Target

    public init(ref: String, target: Target) {
        self.ref = ref
        self.target = target
    }
}

public struct LiveTransferResponse: Decodable, Equatable, Sendable {
    /// `internal` or `voicemail`.
    public var destinationType: String?

    public init(destinationType: String? = nil) {
        self.destinationType = destinationType
    }
}
