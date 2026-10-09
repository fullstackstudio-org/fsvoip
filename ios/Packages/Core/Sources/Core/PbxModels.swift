// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Swift mirror of the `/pbx/*` part of `shared/openapi.yaml` (the "Centrale" section for admin pairings). Responses decode
// leniently (unknown fields ignored, unknown enum values -> `.unknown`); requests are strict (only the keys the server knows,
// `version` always present, `.unknown` can never be sent).
//
// The words are the portal's: a customer sees "toestel", "belgroep", "openingstijden" - never PBX terms.

import Foundation

// MARK: - Vocabulary

/// "Wordt bijgewerkt" / "Niet gelukt": whether the PBX already runs this object's latest settings.
public enum SyncState: String, WireEnum {
    case ok
    case pending
    case error
    case unknown

    public static var unknownValue: SyncState { .unknown }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

/// What a call can be sent to.
public enum TargetType: String, WireEnum {
    case device = "extension"
    case ringGroup = "ring_group"
    case queue
    case ivr
    case businessHours = "business_hours"
    case voicemail
    case recording
    case hangup
    case external
    case unknown

    public static var unknownValue: TargetType { .unknown }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

/// A destination. `id` = an object of this PBX (never a PBX uuid), `number` = an external number (`external` only).
public struct PbxTarget: Codable, Equatable, Sendable {
    public var type: TargetType
    public var id: String?
    public var number: String?

    public init(type: TargetType, id: String? = nil, number: String? = nil) {
        self.type = type
        self.id = id
        self.number = number
    }

    public static let hangup = PbxTarget(type: .hangup)

    public static func external(_ number: String) -> PbxTarget {
        PbxTarget(type: .external, number: number)
    }

    public static func object(_ type: TargetType, id: String) -> PbxTarget {
        PbxTarget(type: type, id: id)
    }

    private enum CodingKeys: String, CodingKey {
        case type, id, number
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        type = try container.decode(TargetType.self, forKey: .type)
        id = try container.decodeIfPresent(String.self, forKey: .id)
        number = try container.decodeIfPresent(String.self, forKey: .number)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(type, forKey: .type)
        try container.encodeIfPresent(id, forKey: .id)
        try container.encodeIfPresent(number, forKey: .number)
    }
}

/// A choice for "where does the call go" (`value` is `type:id`, `external` or `none`).
public struct PbxTargetOption: Decodable, Equatable, Sendable, Identifiable {
    public var value: String
    public var type: TargetType
    public var id: String?
    public var name: String
    public var extensionNumber: String?
    /// The voicemail of an extension (as opposed to a shared mailbox).
    public var ofDevice: Bool

    public var identity: String { value }

    private enum CodingKeys: String, CodingKey {
        case value, type, id, name, ofDevice
        case extensionNumber = "extension"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        value = try container.decode(String.self, forKey: .value)
        type = try container.decode(TargetType.self, forKey: .type)
        id = try container.decodeIfPresent(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        extensionNumber = try container.decodeIfPresent(String.self, forKey: .extensionNumber)
        ofDevice = try container.decodeIfPresent(Bool.self, forKey: .ofDevice) ?? false
    }
}

public struct PbxNumberRef: Decodable, Equatable, Sendable {
    public var id: String
    public var number: String
}

// MARK: - Overview and flow

public enum FlowKind: String, WireEnum {
    case device = "extension"
    case ringGroup = "ring_group"
    case queue
    case ivr
    case businessHours = "business_hours"
    case voicemail
    case recording
    case hangup
    case external
    /// The object this step pointed at no longer exists.
    case missing
    /// The flow comes back to a step it already visited.
    case loop
    /// A number without a destination (wire value `none`; not called `.none`, which would clash with `Optional.none`).
    case noDestination = "none"
    /// The preview was cut off here (too large to draw).
    case more
    case unknown

    public static var unknownValue: FlowKind { .unknown }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

public enum FlowWhenKind: String, WireEnum {
    case open
    case closed
    case noAnswer = "no_answer"
    case busy
    case timeout
    case key
    case noChoice = "no_choice"
    case forwardAlways = "forward_always"
    case unknown

    public static var unknownValue: FlowWhenKind { .unknown }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

public struct FlowWhen: Decodable, Equatable, Sendable {
    public var kind: FlowWhenKind
    /// `no_answer`: after how many seconds.
    public var seconds: Int?
    /// `key`: the pressed key.
    public var digit: String?
}

public struct FlowBranch: Decodable, Equatable, Sendable {
    public var when: FlowWhen
    public var node: FlowNode
}

/// One step of the call flow of a number ("openingstijden -> belgroep -> voicemail"), as a tree.
public struct FlowNode: Decodable, Equatable, Sendable {
    public var kind: FlowKind
    public var id: String?
    public var name: String?
    public var extensionNumber: String?
    public var ofDevice: Bool
    public var number: String?
    /// An extension with do-not-disturb on (and no "always forward"): it will not ring.
    public var dnd: Bool
    public var branches: [FlowBranch]

    private enum CodingKeys: String, CodingKey {
        case kind, id, name, ofDevice, number, dnd, branches
        case extensionNumber = "extension"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        kind = try container.decode(FlowKind.self, forKey: .kind)
        id = try container.decodeIfPresent(String.self, forKey: .id)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        extensionNumber = try container.decodeIfPresent(String.self, forKey: .extensionNumber)
        ofDevice = try container.decodeIfPresent(Bool.self, forKey: .ofDevice) ?? false
        number = try container.decodeIfPresent(String.self, forKey: .number)
        dnd = try container.decodeIfPresent(Bool.self, forKey: .dnd) ?? false
        branches = try container.decodeIfPresent([FlowBranch].self, forKey: .branches) ?? []
    }
}

/// A phone number of the PBX with where its callers go. `version` goes back with a routing change.
public struct PbxNumber: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    /// National, without country code (`0850607848`).
    public var number: String
    /// `nil` = the start of the flow of the PBX (the entry).
    public var routing: PbxTarget?
    public var blockListId: String?
    public var isDefault: Bool
    public var version: Int
    public var sync: SyncState
    public var recordCalls: Bool?
    public var recordAnnouncementSoundId: String?
    public var recordBillingActive: Bool?
    /// The call flow behind this number.
    public var flow: FlowNode?
}

/// `GET /pbx/overview`.
public struct PbxOverview: Decodable, Equatable, Sendable {
    public var pbx: PbxStatus
    public var numbers: [PbxNumber]
    /// The flow without a number (the entry of the PBX), for a PBX that has no number yet.
    public var entryFlow: FlowNode?
    public var deviceCount: Int
    /// Registered extensions; `nil` = the PBX did not answer.
    public var connectedCount: Int?
    public var ringGroupCount: Int
    /// Calling out is temporarily off (fraud guard or an administrative hold).
    public var outboundBlocked: Bool

    private enum CodingKeys: String, CodingKey {
        case pbx, numbers, entryFlow, deviceCount, connectedCount, ringGroupCount, outboundBlocked
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        pbx = try container.decode(PbxStatus.self, forKey: .pbx)
        numbers = try container.decodeIfPresent([PbxNumber].self, forKey: .numbers) ?? []
        entryFlow = try container.decodeIfPresent(FlowNode.self, forKey: .entryFlow)
        deviceCount = try container.decodeIfPresent(Int.self, forKey: .deviceCount) ?? 0
        connectedCount = try container.decodeIfPresent(Int.self, forKey: .connectedCount)
        ringGroupCount = try container.decodeIfPresent(Int.self, forKey: .ringGroupCount) ?? 0
        outboundBlocked = try container.decodeIfPresent(Bool.self, forKey: .outboundBlocked) ?? false
    }
}

// MARK: - Devices

public struct DeviceRegistration: Decodable, Equatable, Sendable {
    public var connected: Bool
    public var count: Int
    public var agent: String?
}

public struct FollowMeStep: Codable, Equatable, Sendable {
    public var target: PbxTarget
    public var delaySeconds: Int
    public var timeoutSeconds: Int

    public init(target: PbxTarget, delaySeconds: Int = 0, timeoutSeconds: Int = 25) {
        self.target = target
        self.delaySeconds = delaySeconds
        self.timeoutSeconds = timeoutSeconds
    }
}

/// An extension ("toestel") with the settings an admin app may change.
public struct PbxDevice: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    /// Goes back with every change (`stale` = changed in the meantime).
    public var version: Int
    /// Internal number (101, 102, ...).
    public var extensionNumber: String?
    public var email: String?
    public var voicemailEnabled: Bool
    public var voicemailToEmail: Bool
    public var voicemailAttachFile: Bool
    /// The own phone number used for calling out; `nil` = the first number of the PBX.
    public var outboundNumberId: String?
    public var dnd: Bool
    public var suspended: Bool
    public var recordCalls: Bool
    public var noAnswerSeconds: Int
    public var noAnswerTarget: PbxTarget?
    public var busyTarget: PbxTarget?
    public var notRegisteredTarget: PbxTarget?
    public var forwardAlways: PbxTarget?
    public var followMe: [FollowMeStep]
    public var sync: SyncState
    /// `nil` = unknown (the PBX did not answer).
    public var registration: DeviceRegistration?

    private enum CodingKeys: String, CodingKey {
        case id, name, version, email, voicemailEnabled, voicemailToEmail, voicemailAttachFile, outboundNumberId, dnd, suspended
        case recordCalls, noAnswerSeconds, noAnswerTarget, busyTarget, notRegisteredTarget, forwardAlways, followMe, sync, registration
        case extensionNumber = "extension"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        version = try container.decode(Int.self, forKey: .version)
        extensionNumber = try container.decodeIfPresent(String.self, forKey: .extensionNumber)
        email = try container.decodeIfPresent(String.self, forKey: .email)
        voicemailEnabled = try container.decodeIfPresent(Bool.self, forKey: .voicemailEnabled) ?? false
        voicemailToEmail = try container.decodeIfPresent(Bool.self, forKey: .voicemailToEmail) ?? false
        voicemailAttachFile = try container.decodeIfPresent(Bool.self, forKey: .voicemailAttachFile) ?? false
        outboundNumberId = try container.decodeIfPresent(String.self, forKey: .outboundNumberId)
        dnd = try container.decodeIfPresent(Bool.self, forKey: .dnd) ?? false
        suspended = try container.decodeIfPresent(Bool.self, forKey: .suspended) ?? false
        recordCalls = try container.decodeIfPresent(Bool.self, forKey: .recordCalls) ?? false
        noAnswerSeconds = try container.decodeIfPresent(Int.self, forKey: .noAnswerSeconds) ?? 25
        noAnswerTarget = try container.decodeIfPresent(PbxTarget.self, forKey: .noAnswerTarget)
        busyTarget = try container.decodeIfPresent(PbxTarget.self, forKey: .busyTarget)
        notRegisteredTarget = try container.decodeIfPresent(PbxTarget.self, forKey: .notRegisteredTarget)
        forwardAlways = try container.decodeIfPresent(PbxTarget.self, forKey: .forwardAlways)
        followMe = try container.decodeIfPresent([FollowMeStep].self, forKey: .followMe) ?? []
        sync = try container.decodeIfPresent(SyncState.self, forKey: .sync) ?? .unknown
        registration = try container.decodeIfPresent(DeviceRegistration.self, forKey: .registration)
    }
}

/// `GET /pbx/devices`.
public struct PbxDevicesResponse: Decodable, Equatable, Sendable {
    public var devices: [PbxDevice]
    /// Everything a call can be forwarded to.
    public var targets: [PbxTargetOption]
    public var numbers: [PbxNumberRef]
}

/// `PATCH /pbx/devices/{id}`. Only these keys exist: name, e-mail, recording, suspending and sounds stay in the portal.
/// `version` is required; a `Change.clear` sends an explicit `null` ("no target" / "first number").
public struct PbxDevicePatch: Encodable, Equatable, Sendable {
    public var version: Int
    public var voicemailEnabled: Bool?
    public var voicemailToEmail: Bool?
    public var voicemailAttachFile: Bool?
    public var outboundNumberId: Change<String>
    public var dnd: Bool?
    public var noAnswerSeconds: Int?
    public var noAnswerTarget: Change<PbxTarget>
    public var busyTarget: Change<PbxTarget>
    public var notRegisteredTarget: Change<PbxTarget>
    public var forwardAlways: Change<PbxTarget>
    public var followMe: [FollowMeStep]?

    public init(
        version: Int,
        voicemailEnabled: Bool? = nil,
        voicemailToEmail: Bool? = nil,
        voicemailAttachFile: Bool? = nil,
        outboundNumberId: Change<String> = .keep,
        dnd: Bool? = nil,
        noAnswerSeconds: Int? = nil,
        noAnswerTarget: Change<PbxTarget> = .keep,
        busyTarget: Change<PbxTarget> = .keep,
        notRegisteredTarget: Change<PbxTarget> = .keep,
        forwardAlways: Change<PbxTarget> = .keep,
        followMe: [FollowMeStep]? = nil
    ) {
        self.version = version
        self.voicemailEnabled = voicemailEnabled
        self.voicemailToEmail = voicemailToEmail
        self.voicemailAttachFile = voicemailAttachFile
        self.outboundNumberId = outboundNumberId
        self.dnd = dnd
        self.noAnswerSeconds = noAnswerSeconds
        self.noAnswerTarget = noAnswerTarget
        self.busyTarget = busyTarget
        self.notRegisteredTarget = notRegisteredTarget
        self.forwardAlways = forwardAlways
        self.followMe = followMe
    }

    private enum CodingKeys: String, CodingKey {
        case version, voicemailEnabled, voicemailToEmail, voicemailAttachFile, outboundNumberId, dnd, noAnswerSeconds
        case noAnswerTarget, busyTarget, notRegisteredTarget, forwardAlways, followMe
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(version, forKey: .version)
        try container.encodeIfPresent(voicemailEnabled, forKey: .voicemailEnabled)
        try container.encodeIfPresent(voicemailToEmail, forKey: .voicemailToEmail)
        try container.encodeIfPresent(voicemailAttachFile, forKey: .voicemailAttachFile)
        try container.encodeChange(outboundNumberId, forKey: .outboundNumberId)
        try container.encodeIfPresent(dnd, forKey: .dnd)
        try container.encodeIfPresent(noAnswerSeconds, forKey: .noAnswerSeconds)
        try container.encodeChange(noAnswerTarget, forKey: .noAnswerTarget)
        try container.encodeChange(busyTarget, forKey: .busyTarget)
        try container.encodeChange(notRegisteredTarget, forKey: .notRegisteredTarget)
        try container.encodeChange(forwardAlways, forKey: .forwardAlways)
        try container.encodeIfPresent(followMe, forKey: .followMe)
    }
}

// MARK: - Ring groups

public enum RingStrategy: String, WireEnum {
    /// Everybody at once.
    case all
    /// One after the other.
    case sequence
    /// Take turns.
    case round
    case unknown

    public static var unknownValue: RingStrategy { .unknown }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

public struct RingGroupMember: Codable, Equatable, Sendable {
    public var extensionId: String
    public var delaySeconds: Int
    public var timeoutSeconds: Int

    public init(extensionId: String, delaySeconds: Int = 0, timeoutSeconds: Int = 25) {
        self.extensionId = extensionId
        self.delaySeconds = delaySeconds
        self.timeoutSeconds = timeoutSeconds
    }
}

public struct PbxRingGroup: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var version: Int
    public var extensionNumber: String?
    public var strategy: RingStrategy
    public var members: [RingGroupMember]
    public var timeoutTarget: PbxTarget?
    public var sync: SyncState

    private enum CodingKeys: String, CodingKey {
        case id, name, version, strategy, members, timeoutTarget, sync
        case extensionNumber = "extension"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        version = try container.decode(Int.self, forKey: .version)
        extensionNumber = try container.decodeIfPresent(String.self, forKey: .extensionNumber)
        strategy = try container.decodeIfPresent(RingStrategy.self, forKey: .strategy) ?? .all
        members = try container.decodeIfPresent([RingGroupMember].self, forKey: .members) ?? []
        timeoutTarget = try container.decodeIfPresent(PbxTarget.self, forKey: .timeoutTarget)
        sync = try container.decodeIfPresent(SyncState.self, forKey: .sync) ?? .unknown
    }
}

public struct PbxDeviceRef: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var extensionNumber: String?

    private enum CodingKeys: String, CodingKey {
        case id, name
        case extensionNumber = "extension"
    }
}

/// `GET /pbx/ring-groups`.
public struct PbxRingGroupsResponse: Decodable, Equatable, Sendable {
    public var ringGroups: [PbxRingGroup]
    /// The extensions that can become a member.
    public var devices: [PbxDeviceRef]
    public var targets: [PbxTargetOption]
}

/// `POST /pbx/ring-groups`. No `version` (it is a new object); `name` is required.
public struct PbxRingGroupCreate: Encodable, Equatable, Sendable {
    public var name: String
    public var strategy: RingStrategy?
    public var members: [RingGroupMember]?
    public var timeoutTarget: Change<PbxTarget>

    public init(name: String, strategy: RingStrategy? = nil, members: [RingGroupMember]? = nil, timeoutTarget: Change<PbxTarget> = .keep) {
        self.name = name
        self.strategy = strategy
        self.members = members
        self.timeoutTarget = timeoutTarget
    }

    private enum CodingKeys: String, CodingKey {
        case name, strategy, members, timeoutTarget
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(name, forKey: .name)
        try container.encodeIfPresent(strategy, forKey: .strategy)
        try container.encodeIfPresent(members, forKey: .members)
        try container.encodeChange(timeoutTarget, forKey: .timeoutTarget)
    }
}

/// `PATCH /pbx/ring-groups/{id}`: `version` required, at least one other key.
public struct PbxRingGroupPatch: Encodable, Equatable, Sendable {
    public var version: Int
    public var name: String?
    public var strategy: RingStrategy?
    public var members: [RingGroupMember]?
    public var timeoutTarget: Change<PbxTarget>

    public init(version: Int, name: String? = nil, strategy: RingStrategy? = nil, members: [RingGroupMember]? = nil, timeoutTarget: Change<PbxTarget> = .keep) {
        self.version = version
        self.name = name
        self.strategy = strategy
        self.members = members
        self.timeoutTarget = timeoutTarget
    }

    private enum CodingKeys: String, CodingKey {
        case version, name, strategy, members, timeoutTarget
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(version, forKey: .version)
        try container.encodeIfPresent(name, forKey: .name)
        try container.encodeIfPresent(strategy, forKey: .strategy)
        try container.encodeIfPresent(members, forKey: .members)
        try container.encodeChange(timeoutTarget, forKey: .timeoutTarget)
    }
}

/// `201` of `POST /pbx/ring-groups`.
public struct PbxRingGroupCreated: Decodable, Equatable, Sendable {
    public var ringGroupId: String
}

// MARK: - Opening hours

public struct HoursWeekEntry: Codable, Equatable, Sendable {
    /// 1 = Monday ... 7 = Sunday.
    public var day: Int
    /// `HH:MM`; `to` may be `24:00` (until midnight).
    public var from: String
    public var to: String

    public init(day: Int, from: String, to: String) {
        self.day = day
        self.from = from
        self.to = to
    }
}

/// A holiday in the opening hours: a fixed rule (Easter, King's Day, ...) or an own date.
public struct PbxHoliday: Decodable, Equatable, Sendable {
    public var name: String
    /// `YYYY-MM-DD` for an own date.
    public var date: String?
    /// Fixed rule key (see `HoursResponse.holidayRules`).
    public var rule: String?
    /// `nil` = the same as outside opening hours.
    public var target: PbxTarget?
}

public struct PbxHours: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var extensionNumber: String?
    public var timezone: String
    public var week: [HoursWeekEntry]
    public var openTarget: PbxTarget?
    public var closedTarget: PbxTarget?
    public var holidays: [PbxHoliday]
    public var version: Int
    public var sync: SyncState

    private enum CodingKeys: String, CodingKey {
        case id, name, timezone, week, openTarget, closedTarget, holidays, version, sync
        case extensionNumber = "extension"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        extensionNumber = try container.decodeIfPresent(String.self, forKey: .extensionNumber)
        timezone = try container.decodeIfPresent(String.self, forKey: .timezone) ?? "Europe/Amsterdam"
        week = try container.decodeIfPresent([HoursWeekEntry].self, forKey: .week) ?? []
        openTarget = try container.decodeIfPresent(PbxTarget.self, forKey: .openTarget)
        closedTarget = try container.decodeIfPresent(PbxTarget.self, forKey: .closedTarget)
        holidays = try container.decodeIfPresent([PbxHoliday].self, forKey: .holidays) ?? []
        version = try container.decode(Int.self, forKey: .version)
        sync = try container.decodeIfPresent(SyncState.self, forKey: .sync) ?? .unknown
    }
}

public struct HolidayRuleLabel: Decodable, Equatable, Sendable {
    public var rule: String
    public var label: String
}

/// `GET /pbx/hours`.
public struct PbxHoursResponse: Decodable, Equatable, Sendable {
    public var hours: [PbxHours]
    public var targets: [PbxTargetOption]
    /// The fixed holidays the PBX knows, with a label for the form.
    public var holidayRules: [HolidayRuleLabel]
}

/// A holiday in a PATCH: `{ "rule": ... }` or `{ "name": ..., "date": ... }`.
public enum PbxHolidayInput: Encodable, Equatable, Sendable {
    case rule(String)
    case custom(name: String, date: String)

    private enum CodingKeys: String, CodingKey {
        case rule, name, date
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)

        switch self {
        case let .rule(rule):
            try container.encode(rule, forKey: .rule)
        case let .custom(name, date):
            try container.encode(name, forKey: .name)
            try container.encode(date, forKey: .date)
        }
    }
}

/// `PATCH /pbx/hours/{id}`: week, targets and holidays (the name stays in the portal). `version` required.
/// `holidays` REPLACES the list: send the existing ones along (as `.rule` / `.custom`) to keep them.
public struct PbxHoursPatch: Encodable, Equatable, Sendable {
    public var version: Int
    public var week: [HoursWeekEntry]?
    public var openTarget: Change<PbxTarget>
    public var closedTarget: Change<PbxTarget>
    public var holidays: [PbxHolidayInput]?
    /// What happens on a holiday; `nil`/clear = the same as outside opening hours.
    public var holidayTarget: Change<PbxTarget>

    public init(
        version: Int,
        week: [HoursWeekEntry]? = nil,
        openTarget: Change<PbxTarget> = .keep,
        closedTarget: Change<PbxTarget> = .keep,
        holidays: [PbxHolidayInput]? = nil,
        holidayTarget: Change<PbxTarget> = .keep
    ) {
        self.version = version
        self.week = week
        self.openTarget = openTarget
        self.closedTarget = closedTarget
        self.holidays = holidays
        self.holidayTarget = holidayTarget
    }

    private enum CodingKeys: String, CodingKey {
        case version, week, openTarget, closedTarget, holidays, holidayTarget
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(version, forKey: .version)
        try container.encodeIfPresent(week, forKey: .week)
        try container.encodeChange(openTarget, forKey: .openTarget)
        try container.encodeChange(closedTarget, forKey: .closedTarget)
        try container.encodeIfPresent(holidays, forKey: .holidays)
        try container.encodeChange(holidayTarget, forKey: .holidayTarget)
    }
}

// MARK: - Number routing

/// `PATCH /pbx/numbers/{id}/routing`. `target: nil` is sent as an explicit `null` = "the start of the flow".
public struct PbxRoutingPatch: Encodable, Equatable, Sendable {
    public var target: PbxTarget?
    public var version: Int

    public init(target: PbxTarget?, version: Int) {
        self.target = target
        self.version = version
    }

    private enum CodingKeys: String, CodingKey {
        case target, version
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(target, forKey: .target)
        try container.encode(version, forKey: .version)
    }
}
