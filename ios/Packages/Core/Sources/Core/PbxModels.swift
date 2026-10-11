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

// MARK: - Numbers as a chain (`/pbx/numbers`, v2)

/// A number is shown as a chain: opening hours, welcome message, forwarding. `advanced` = the flow behind the number is more than
/// the chain can show: it is read-only (only the name can still be changed).
public enum ChainMode: String, WireEnum {
    case simple
    case advanced
    case unknown

    public static var unknownValue: ChainMode { .unknown }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

public enum ForwardingKind: String, WireEnum {
    case standard
    case menu
    case advanced
    case unknown

    public static var unknownValue: ForwardingKind { .unknown }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

/// One line of `GET /pbx/numbers`: the number with a one-line summary of its chain.
public struct PbxNumberEntry: Decodable, Equatable, Sendable, Identifiable {
    public struct Summary: Decodable, Equatable, Sendable {
        /// A label in the language of the server ("ma-vr 9:00-17:00", "Uit").
        public var hours: String
        public var welcome: Bool
        public var forwarding: ForwardingKind
        public var recording: Bool

        public init(hours: String, welcome: Bool, forwarding: ForwardingKind, recording: Bool) {
            self.hours = hours
            self.welcome = welcome
            self.forwarding = forwarding
            self.recording = recording
        }

        private enum CodingKeys: String, CodingKey {
            case hours, welcome, forwarding, recording
        }

        public init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            hours = try container.decodeIfPresent(String.self, forKey: .hours) ?? ""
            welcome = try container.decodeIfPresent(Bool.self, forKey: .welcome) ?? false
            forwarding = try container.decodeIfPresent(ForwardingKind.self, forKey: .forwarding) ?? .unknown
            recording = try container.decodeIfPresent(Bool.self, forKey: .recording) ?? false
        }
    }

    public var id: String
    /// National, without country code.
    public var number: String
    public var name: String?
    public var mode: ChainMode
    public var summary: Summary
    public var sync: SyncState
    public var version: Int

    private enum CodingKeys: String, CodingKey {
        case id, number, name, mode, summary, sync, version
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        number = try container.decode(String.self, forKey: .number)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        mode = try container.decodeIfPresent(ChainMode.self, forKey: .mode) ?? .unknown
        summary = try container.decode(Summary.self, forKey: .summary)
        sync = try container.decodeIfPresent(SyncState.self, forKey: .sync) ?? .unknown
        version = try container.decode(Int.self, forKey: .version)
    }
}

/// `GET /pbx/numbers`.
public struct PbxNumbersPage: Decodable, Equatable, Sendable {
    public var numbers: [PbxNumberEntry]

    private enum CodingKeys: String, CodingKey {
        case numbers
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        numbers = try container.decodeIfPresent([PbxNumberEntry].self, forKey: .numbers) ?? []
    }
}

/// What happens when it is closed or nobody answers. Decoded leniently; encoded strictly: `other` and `unknown` are things the chain
/// has no choice for, they are shown read-only and can never be sent back (`isSelectable == false`).
public enum Fallback: Codable, Equatable, Sendable {
    /// A voicemail box. `boxId == nil` on the way out = the shared box of the PBX.
    case voicemail(boxId: String?, ofDevice: Bool)
    case message(soundId: String)
    case forward(number: String)
    case hangup
    case device(deviceId: String)
    /// Something else (a queue, a menu, ...): read-only. `target` is what it points at, when the server tells.
    case other(target: PbxTarget?)
    /// A `mode` this app version does not know.
    case unknown(mode: String)

    /// Can the user choose this one (and does it survive being sent back)?
    public var isSelectable: Bool {
        switch self {
        case .other, .unknown:
            return false
        default:
            return true
        }
    }

    private enum CodingKeys: String, CodingKey {
        case mode, boxId, ofDevice, soundId, number, deviceId, target
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let mode = try container.decode(String.self, forKey: .mode)

        switch mode {
        case "voicemail":
            self = .voicemail(boxId: try? container.decodeIfPresent(String.self, forKey: .boxId), ofDevice: (try? container.decodeIfPresent(Bool.self, forKey: .ofDevice)) ?? false)
        case "message":
            self = .message(soundId: try container.decode(String.self, forKey: .soundId))
        case "forward":
            self = .forward(number: try container.decode(String.self, forKey: .number))
        case "hangup":
            self = .hangup
        case "device":
            self = .device(deviceId: try container.decode(String.self, forKey: .deviceId))
        case "other":
            self = .other(target: try? container.decodeIfPresent(PbxTarget.self, forKey: .target))
        default:
            self = .unknown(mode: mode)
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)

        switch self {
        case let .voicemail(boxId, _):
            try container.encode("voicemail", forKey: .mode)
            try container.encodeIfPresent(boxId, forKey: .boxId)
        case let .message(soundId):
            try container.encode("message", forKey: .mode)
            try container.encode(soundId, forKey: .soundId)
        case let .forward(number):
            try container.encode("forward", forKey: .mode)
            try container.encode(number, forKey: .number)
        case .hangup:
            try container.encode("hangup", forKey: .mode)
        case let .device(deviceId):
            try container.encode("device", forKey: .mode)
            try container.encode(deviceId, forKey: .deviceId)
        case .other, .unknown:
            throw EncodingError.invalidValue(self, EncodingError.Context(codingPath: encoder.codingPath, debugDescription: "A read-only fallback cannot be sent"))
        }
    }
}

public struct ChainHolidayDate: Codable, Equatable, Sendable {
    public var name: String
    /// `YYYY-MM-DD`.
    public var date: String

    public init(name: String, date: String) {
        self.name = name
        self.date = date
    }
}

public struct ChainHolidays: Decodable, Equatable, Sendable {
    /// All Dutch national holidays use the "closed" behaviour.
    public var national: Bool
    /// The fixed holidays that are on (rule names as in `PbxHoursResponse.holidayRules`).
    public var rules: [String]
    public var dates: [ChainHolidayDate]

    private enum CodingKeys: String, CodingKey {
        case national, rules, dates
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        national = try container.decodeIfPresent(Bool.self, forKey: .national) ?? false
        rules = try container.decodeIfPresent([String].self, forKey: .rules) ?? []
        dates = try container.decodeIfPresent([ChainHolidayDate].self, forKey: .dates) ?? []
    }
}

public struct ChainHours: Decodable, Equatable, Sendable {
    public var id: String
    public var version: Int
    public var week: [HoursWeekEntry]
    public var holidays: ChainHolidays
    public var closed: Fallback
    /// `nil` = on a holiday the same as outside opening hours.
    public var holiday: Fallback?
    /// Labels of the other numbers that use the same opening hours.
    public var sharedWith: [String]

    private enum CodingKeys: String, CodingKey {
        case id, version, week, holidays, closed, holiday, sharedWith
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        version = try container.decode(Int.self, forKey: .version)
        week = try container.decodeIfPresent([HoursWeekEntry].self, forKey: .week) ?? []
        holidays = try container.decode(ChainHolidays.self, forKey: .holidays)
        closed = try container.decode(Fallback.self, forKey: .closed)
        holiday = try container.decodeIfPresent(Fallback.self, forKey: .holiday)
        sharedWith = try container.decodeIfPresent([String].self, forKey: .sharedWith) ?? []
    }
}

public struct ChainWelcome: Decodable, Equatable, Sendable {
    public var menuId: String
    public var version: Int
    public var soundId: String?
    public var sharedWith: [String]

    private enum CodingKeys: String, CodingKey {
        case menuId, version, soundId, sharedWith
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        menuId = try container.decode(String.self, forKey: .menuId)
        version = try container.decode(Int.self, forKey: .version)
        soundId = try container.decodeIfPresent(String.self, forKey: .soundId)
        sharedWith = try container.decodeIfPresent([String].self, forKey: .sharedWith) ?? []
    }
}

/// A member of the ring group behind a number. (Not `RingGroupMember`: that one names its extension `extensionId`.)
public struct ChainMember: Codable, Equatable, Sendable {
    public var deviceId: String
    public var delaySeconds: Int
    public var timeoutSeconds: Int

    public init(deviceId: String, delaySeconds: Int = 0, timeoutSeconds: Int = 25) {
        self.deviceId = deviceId
        self.delaySeconds = delaySeconds
        self.timeoutSeconds = timeoutSeconds
    }

    private enum CodingKeys: String, CodingKey {
        case deviceId, delaySeconds, timeoutSeconds
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        deviceId = try container.decode(String.self, forKey: .deviceId)
        delaySeconds = try container.decodeIfPresent(Int.self, forKey: .delaySeconds) ?? 0
        timeoutSeconds = try container.decodeIfPresent(Int.self, forKey: .timeoutSeconds) ?? 25
    }
}

/// A key of a menu. `editable == false`: the app shows it but cannot change it (it points at a queue, say); the server leaves it
/// as it is when the menu is saved.
public struct ChainKey: Codable, Equatable, Sendable {
    /// `0`-`9`, `*` or `#`.
    public var digit: String
    public var target: PbxTarget
    public var editable: Bool

    public init(digit: String, target: PbxTarget, editable: Bool = true) {
        self.digit = digit
        self.target = target
        self.editable = editable
    }

    private enum CodingKeys: String, CodingKey {
        case digit, target, editable
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        digit = try container.decode(String.self, forKey: .digit)
        target = try container.decode(PbxTarget.self, forKey: .target)
        editable = try container.decodeIfPresent(Bool.self, forKey: .editable) ?? true
    }

    /// Only digit and target go to the server: `editable` is display only.
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(digit, forKey: .digit)
        try container.encode(target, forKey: .target)
    }
}

public struct ChainStandardForwarding: Decodable, Equatable, Sendable {
    /// `nil` = one single extension (no ring group yet); a change turns it into a ring group.
    public var groupId: String?
    public var version: Int
    public var strategy: RingStrategy
    public var members: [ChainMember]
    public var unanswered: Fallback
    public var sharedWith: [String]

    private enum CodingKeys: String, CodingKey {
        case groupId, version, strategy, members, unanswered, sharedWith
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        groupId = try container.decodeIfPresent(String.self, forKey: .groupId)
        version = try container.decode(Int.self, forKey: .version)
        strategy = try container.decodeIfPresent(RingStrategy.self, forKey: .strategy) ?? .all
        members = try container.decodeIfPresent([ChainMember].self, forKey: .members) ?? []
        unanswered = try container.decode(Fallback.self, forKey: .unanswered)
        sharedWith = try container.decodeIfPresent([String].self, forKey: .sharedWith) ?? []
    }
}

public struct ChainMenuForwarding: Decodable, Equatable, Sendable {
    public var menuId: String
    public var version: Int
    public var greetingSoundId: String?
    /// 0-5.
    public var repeats: Int
    public var timeoutSeconds: Int
    /// The key that counts when nothing is pressed; `nil` = none (then `noChoice`).
    public var defaultKey: String?
    public var noChoice: Fallback
    public var keys: [ChainKey]
    public var sharedWith: [String]

    private enum CodingKeys: String, CodingKey {
        case menuId, version, greetingSoundId, repeats, timeoutSeconds, defaultKey, noChoice, keys, sharedWith
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        menuId = try container.decode(String.self, forKey: .menuId)
        version = try container.decode(Int.self, forKey: .version)
        greetingSoundId = try container.decodeIfPresent(String.self, forKey: .greetingSoundId)
        repeats = try container.decodeIfPresent(Int.self, forKey: .repeats) ?? 0
        timeoutSeconds = try container.decodeIfPresent(Int.self, forKey: .timeoutSeconds) ?? 5
        defaultKey = try container.decodeIfPresent(String.self, forKey: .defaultKey)
        noChoice = try container.decode(Fallback.self, forKey: .noChoice)
        keys = try container.decodeIfPresent([ChainKey].self, forKey: .keys) ?? []
        sharedWith = try container.decodeIfPresent([String].self, forKey: .sharedWith) ?? []
    }
}

/// Forwarding of a number: one extension or a ring group (`standard`), or a menu with keys. `unknown` = a `kind` this app version
/// does not know (treat like an advanced chain: read-only).
public enum ChainForwarding: Decodable, Equatable, Sendable {
    case standard(ChainStandardForwarding)
    case menu(ChainMenuForwarding)
    case unknown(kind: String)

    private enum CodingKeys: String, CodingKey {
        case kind
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let kind = try container.decode(String.self, forKey: .kind)

        switch kind {
        case "standard":
            self = .standard(try ChainStandardForwarding(from: decoder))
        case "menu":
            self = .menu(try ChainMenuForwarding(from: decoder))
        default:
            self = .unknown(kind: kind)
        }
    }
}

/// The price of call recording per number per month.
public struct RecordingCost: Decodable, Equatable, Sendable {
    /// Euro x 10 000 (20000 = EUR 2.00).
    public var priceE4: Int
    /// Whether `priceE4` includes VAT.
    public var vatIncluded: Bool

    public init(priceE4: Int, vatIncluded: Bool) {
        self.priceE4 = priceE4
        self.vatIncluded = vatIncluded
    }

    private enum CodingKeys: String, CodingKey {
        case priceE4, vatIncluded
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        priceE4 = try container.decode(Int.self, forKey: .priceE4)
        vatIncluded = try container.decodeIfPresent(Bool.self, forKey: .vatIncluded) ?? false
    }
}

public struct ChainRecording: Decodable, Equatable, Sendable {
    public var enabled: Bool
    public var announcementSoundId: String?
    /// Recording is already billed: changing the announcement needs no new consent.
    public var billingActive: Bool
    /// `false` = recording cannot be switched on on this PBX (there is no price).
    public var available: Bool
    /// `nil` = free for this customer (no consent needed).
    public var cost: RecordingCost?

    private enum CodingKeys: String, CodingKey {
        case enabled, announcementSoundId, billingActive, available, cost
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        enabled = try container.decodeIfPresent(Bool.self, forKey: .enabled) ?? false
        announcementSoundId = try container.decodeIfPresent(String.self, forKey: .announcementSoundId)
        billingActive = try container.decodeIfPresent(Bool.self, forKey: .billingActive) ?? false
        available = try container.decodeIfPresent(Bool.self, forKey: .available) ?? false
        cost = try container.decodeIfPresent(RecordingCost.self, forKey: .cost)
    }
}

/// The choices for the pickers of the chain.
public struct ChainOptions: Decodable, Equatable, Sendable {
    public struct Device: Decodable, Equatable, Sendable, Identifiable {
        public var id: String
        public var name: String
        public var extensionNumber: String?

        private enum CodingKeys: String, CodingKey {
            case id, name
            case extensionNumber = "extension"
        }
    }

    public struct Named: Decodable, Equatable, Sendable, Identifiable {
        public var id: String
        public var name: String
    }

    public var devices: [Device]
    public var sounds: [Named]
    public var groups: [Named]

    public init(devices: [Device] = [], sounds: [Named] = [], groups: [Named] = []) {
        self.devices = devices
        self.sounds = sounds
        self.groups = groups
    }

    private enum CodingKeys: String, CodingKey {
        case devices, sounds, groups
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        devices = try container.decodeIfPresent([Device].self, forKey: .devices) ?? []
        sounds = try container.decodeIfPresent([Named].self, forKey: .sounds) ?? []
        groups = try container.decodeIfPresent([Named].self, forKey: .groups) ?? []
    }
}

/// One number as a chain (`GET /pbx/numbers/{id}/chain`, and the answer of every step).
public struct NumberChain: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var number: String
    public var name: String?
    public var version: Int
    public var mode: ChainMode
    public var sync: SyncState
    /// `nil` = no opening hours (always open).
    public var hours: ChainHours?
    /// `nil` = no welcome message.
    public var welcome: ChainWelcome?
    /// `nil` when `mode` is `advanced`.
    public var forwarding: ChainForwarding?
    /// Only when `mode` is `advanced`: lines that describe the flow in words.
    public var advancedSummary: [String]?
    public var recording: ChainRecording
    public var options: ChainOptions

    /// Can the app change this chain step by step? (`advanced` or an unknown kind of forwarding: read-only.)
    public var isEditable: Bool {
        guard mode == .simple else {
            return false
        }

        if case .unknown = forwarding {
            return false
        }

        return true
    }

    private enum CodingKeys: String, CodingKey {
        case id, number, name, version, mode, sync, hours, welcome, forwarding, advanced, recording, options
    }

    private struct Advanced: Decodable {
        var summary: [String]
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        number = try container.decode(String.self, forKey: .number)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        version = try container.decode(Int.self, forKey: .version)
        mode = try container.decodeIfPresent(ChainMode.self, forKey: .mode) ?? .unknown
        sync = try container.decodeIfPresent(SyncState.self, forKey: .sync) ?? .unknown
        hours = try container.decodeIfPresent(ChainHours.self, forKey: .hours)
        welcome = try container.decodeIfPresent(ChainWelcome.self, forKey: .welcome)
        forwarding = try container.decodeIfPresent(ChainForwarding.self, forKey: .forwarding)
        advancedSummary = (try? container.decodeIfPresent(Advanced.self, forKey: .advanced))?.summary
        recording = try container.decode(ChainRecording.self, forKey: .recording)
        options = (try? container.decodeIfPresent(ChainOptions.self, forKey: .options)) ?? ChainOptions()
    }
}

// MARK: - Chain steps (requests; strict)

public enum ChainStep: String, Sendable {
    case name
    case hours
    case welcome
    case forwarding
    case closed
}

/// The versions the chain showed (`NumberChain` and its parts), sent back with a step so the server can see a lost update.
/// Left out = the server reads them itself.
public struct ChainVersions: Equatable, Sendable {
    /// `{ <objectId>: version }`: `ChainHours.id`, `ChainWelcome.menuId`, the group or menu id of the forwarding.
    public var objects: [String: Int]?
    /// `NumberChain.version`.
    public var number: Int?

    public init(objects: [String: Int]? = nil, number: Int? = nil) {
        self.objects = objects
        self.number = number
    }

    fileprivate func encode(into container: inout KeyedEncodingContainer<ChainStepCodingKeys>) throws {
        try container.encodeIfPresent(objects, forKey: .versions)
        try container.encodeIfPresent(number, forKey: .numberVersion)
    }
}

fileprivate enum ChainStepCodingKeys: String, CodingKey {
    case name, enabled, week, holidays, closed, holiday, soundId, kind, strategy, members, unanswered
    case greetingSoundId, repeats, timeoutSeconds, defaultKey, noChoice, keys, versions, numberVersion
}

/// A body of `PUT /pbx/numbers/{id}/chain/{step}`.
public protocol NumberChainStepRequest: Encodable, Equatable, Sendable {
    var step: ChainStep { get }
}

/// Holidays as the app sends them: every part optional (left out = unchanged).
public struct ChainHolidaysInput: Encodable, Equatable, Sendable {
    public var national: Bool?
    public var rules: [String]?
    public var dates: [ChainHolidayDate]?

    public init(national: Bool? = nil, rules: [String]? = nil, dates: [ChainHolidayDate]? = nil) {
        self.national = national
        self.rules = rules
        self.dates = dates
    }
}

/// `step: name`. A blank name is sent as `null` (the number has no name).
public struct ChainNameStep: NumberChainStepRequest {
    public var name: String?
    public var versions: ChainVersions

    public var step: ChainStep { .name }

    public init(name: String?, versions: ChainVersions = ChainVersions()) {
        self.name = name
        self.versions = versions
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: ChainStepCodingKeys.self)
        let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines)
        try container.encode(trimmed?.isEmpty == false ? trimmed : nil, forKey: .name)
        try versions.encode(into: &container)
    }
}

/// `step: hours`. `enabled == false` unlinks the opening hours (nothing is deleted).
public struct ChainHoursStep: NumberChainStepRequest {
    public var enabled: Bool
    public var week: [HoursWeekEntry]?
    public var holidays: ChainHolidaysInput?
    /// Only a selectable fallback can be sent (`Fallback.isSelectable`); a read-only one throws on encoding.
    public var closed: Fallback?
    /// `.clear` = on a holiday the same as outside opening hours.
    public var holiday: Change<Fallback>
    public var versions: ChainVersions

    public var step: ChainStep { .hours }

    public init(enabled: Bool, week: [HoursWeekEntry]? = nil, holidays: ChainHolidaysInput? = nil, closed: Fallback? = nil, holiday: Change<Fallback> = .keep, versions: ChainVersions = ChainVersions()) {
        self.enabled = enabled
        self.week = week
        self.holidays = holidays
        self.closed = closed
        self.holiday = holiday
        self.versions = versions
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: ChainStepCodingKeys.self)
        try container.encode(enabled, forKey: .enabled)
        try container.encodeIfPresent(week, forKey: .week)
        try container.encodeIfPresent(holidays, forKey: .holidays)
        try container.encodeIfPresent(closed, forKey: .closed)
        try container.encodeChange(holiday, forKey: .holiday)
        try versions.encode(into: &container)
    }
}

/// `step: welcome`. `enabled == true` needs a sound (the one that is set, or `soundId`): otherwise `APIError.greetingRequired`.
public struct ChainWelcomeStep: NumberChainStepRequest {
    public var enabled: Bool
    /// `.keep` = unchanged, `.clear` = no sound.
    public var soundId: Change<String>
    public var versions: ChainVersions

    public var step: ChainStep { .welcome }

    public init(enabled: Bool, soundId: Change<String> = .keep, versions: ChainVersions = ChainVersions()) {
        self.enabled = enabled
        self.soundId = soundId
        self.versions = versions
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: ChainStepCodingKeys.self)
        try container.encode(enabled, forKey: .enabled)
        try container.encodeChange(soundId, forKey: .soundId)
        try versions.encode(into: &container)
    }
}

/// `step: forwarding`, `kind: standard`.
public struct ChainStandardForwardingStep: NumberChainStepRequest {
    public var strategy: RingStrategy?
    public var members: [ChainMember]?
    public var unanswered: Fallback?
    public var versions: ChainVersions

    public var step: ChainStep { .forwarding }

    public init(strategy: RingStrategy? = nil, members: [ChainMember]? = nil, unanswered: Fallback? = nil, versions: ChainVersions = ChainVersions()) {
        self.strategy = strategy
        self.members = members
        self.unanswered = unanswered
        self.versions = versions
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: ChainStepCodingKeys.self)
        try container.encode("standard", forKey: .kind)
        try container.encodeIfPresent(strategy, forKey: .strategy)
        try container.encodeIfPresent(members, forKey: .members)
        try container.encodeIfPresent(unanswered, forKey: .unanswered)
        try versions.encode(into: &container)
    }
}

/// `step: forwarding`, `kind: menu`. A menu has at least one key; keys that are not editable stay as they are on the server.
public struct ChainMenuForwardingStep: NumberChainStepRequest {
    public var greetingSoundId: Change<String>
    public var repeats: Int?
    public var timeoutSeconds: Int?
    public var defaultKey: Change<String>
    public var noChoice: Fallback?
    public var keys: [ChainKey]?
    public var versions: ChainVersions

    public var step: ChainStep { .forwarding }

    public init(
        greetingSoundId: Change<String> = .keep,
        repeats: Int? = nil,
        timeoutSeconds: Int? = nil,
        defaultKey: Change<String> = .keep,
        noChoice: Fallback? = nil,
        keys: [ChainKey]? = nil,
        versions: ChainVersions = ChainVersions()
    ) {
        self.greetingSoundId = greetingSoundId
        self.repeats = repeats
        self.timeoutSeconds = timeoutSeconds
        self.defaultKey = defaultKey
        self.noChoice = noChoice
        self.keys = keys
        self.versions = versions
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: ChainStepCodingKeys.self)
        try container.encode("menu", forKey: .kind)
        try container.encodeChange(greetingSoundId, forKey: .greetingSoundId)
        try container.encodeIfPresent(repeats, forKey: .repeats)
        try container.encodeIfPresent(timeoutSeconds, forKey: .timeoutSeconds)
        try container.encodeChange(defaultKey, forKey: .defaultKey)
        try container.encodeIfPresent(noChoice, forKey: .noChoice)
        try container.encodeIfPresent(keys, forKey: .keys)
        try versions.encode(into: &container)
    }
}

/// `step: closed`: only what happens when it is closed / on a holiday.
public struct ChainClosedStep: NumberChainStepRequest {
    public var closed: Fallback?
    /// `.clear` = on a holiday the same as outside opening hours.
    public var holiday: Change<Fallback>
    public var versions: ChainVersions

    public var step: ChainStep { .closed }

    public init(closed: Fallback? = nil, holiday: Change<Fallback> = .keep, versions: ChainVersions = ChainVersions()) {
        self.closed = closed
        self.holiday = holiday
        self.versions = versions
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: ChainStepCodingKeys.self)
        try container.encodeIfPresent(closed, forKey: .closed)
        try container.encodeChange(holiday, forKey: .holiday)
        try versions.encode(into: &container)
    }
}

/// `PATCH /pbx/numbers/{id}/recording`.
public struct NumberRecordingPatch: Encodable, Equatable, Sendable {
    public var enabled: Bool
    /// `.keep` = unchanged, `.clear` = no announcement.
    public var announcementSoundId: Change<String>
    /// The customer accepted `ChainRecording.cost`. Without it, switching on a paid recording is `APIError.costNotAccepted`.
    public var costAccepted: Bool?
    /// `NumberChain.version`.
    public var version: Int

    public init(enabled: Bool, announcementSoundId: Change<String> = .keep, costAccepted: Bool? = nil, version: Int) {
        self.enabled = enabled
        self.announcementSoundId = announcementSoundId
        self.costAccepted = costAccepted
        self.version = version
    }

    private enum CodingKeys: String, CodingKey {
        case enabled, announcementSoundId, costAccepted, version
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(enabled, forKey: .enabled)
        try container.encodeChange(announcementSoundId, forKey: .announcementSoundId)
        try container.encodeIfPresent(costAccepted, forKey: .costAccepted)
        try container.encode(version, forKey: .version)
    }
}
