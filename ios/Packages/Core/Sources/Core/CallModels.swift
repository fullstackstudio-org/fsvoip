// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Swift mirror of `/calls` and `/voicemail` in `shared/openapi.yaml`. An admin pairing sees every call (with recordings) and every
// voicemail box of the PBX; a `user` pairing only its own extension's calls (never a recording) and its own box. The server decides;
// these types are the same for both.

import Foundation

public enum CallDirection: String, WireEnum {
    case inbound
    case outbound
    case `internal`
    case unknown

    public static var unknownValue: CallDirection { .unknown }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

public enum CallOutcome: String, WireEnum {
    case answered
    /// Not answered (inbound: missed; outbound/internal: not picked up).
    case missed
    case voicemail
    /// The server does not know, or this app version does not know the value.
    case unknown

    public static var unknownValue: CallOutcome { .unknown }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

public enum CallLineType: String, WireEnum {
    case fixed
    case mobile
    case unknown

    public static var unknownValue: CallLineType { .unknown }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

public enum CallDestinationCategory: String, WireEnum {
    case fixed
    case mobile
    case special
    case short
    case emergency
    case satellite
    case unknownDestination = "unknown"
    /// A category this app version does not know.
    case other

    public static var unknownValue: CallDestinationCategory { .other }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

/// One call of the PBX. The labels are formatted by the server in the language of `?locale=` (`nl`/`en`).
public struct CallItem: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var direction: CallDirection
    public var outcome: CallOutcome
    /// The other party, formatted; `nil` = anonymous/unknown (or internal: then see `extensionNumber`).
    public var number: String?
    /// One of our own numbers that was called / used.
    public var ourNumber: String?
    public var extensionNumber: String?
    public var extensionName: String?
    /// ISO country of the other number; `countryName` is in the reader's language.
    public var country: String?
    public var countryName: String?
    public var lineType: CallLineType?
    public var category: CallDestinationCategory?
    public var startedLabel: String
    /// ISO instant for sorting; `nil` = unknown.
    public var startedSort: String?
    public var durationLabel: String
    public var durationSeconds: Int?
    /// There is a recording to play (admin only; always `false` for a `user` pairing).
    public var hasRecording: Bool
    /// There was a recording but it is older than the retention period (admin only).
    public var recordingExpired: Bool

    private enum CodingKeys: String, CodingKey {
        case id, direction, outcome, number, ourNumber, country, countryName, lineType, category, startedLabel, startedSort
        case durationLabel, durationSeconds, hasRecording, recordingExpired, extensionName
        case extensionNumber = "extension"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        direction = try container.decode(CallDirection.self, forKey: .direction)
        outcome = try container.decode(CallOutcome.self, forKey: .outcome)
        number = try container.decodeIfPresent(String.self, forKey: .number)
        ourNumber = try container.decodeIfPresent(String.self, forKey: .ourNumber)
        extensionNumber = try container.decodeIfPresent(String.self, forKey: .extensionNumber)
        extensionName = try container.decodeIfPresent(String.self, forKey: .extensionName)
        country = try container.decodeIfPresent(String.self, forKey: .country)
        countryName = try container.decodeIfPresent(String.self, forKey: .countryName)
        lineType = try container.decodeIfPresent(CallLineType.self, forKey: .lineType)
        category = try container.decodeIfPresent(CallDestinationCategory.self, forKey: .category)
        startedLabel = try container.decodeIfPresent(String.self, forKey: .startedLabel) ?? ""
        startedSort = try container.decodeIfPresent(String.self, forKey: .startedSort)
        durationLabel = try container.decodeIfPresent(String.self, forKey: .durationLabel) ?? ""
        durationSeconds = try container.decodeIfPresent(Int.self, forKey: .durationSeconds)
        hasRecording = try container.decodeIfPresent(Bool.self, forKey: .hasRecording) ?? false
        recordingExpired = try container.decodeIfPresent(Bool.self, forKey: .recordingExpired) ?? false
    }
}

/// `GET /calls?month=YYYY-MM`.
public struct CallsPage: Decodable, Equatable, Sendable {
    /// `YYYY-MM` (Europe/Amsterdam).
    public var month: String
    /// The months you can choose, newest first.
    public var months: [String]
    public var calls: [CallItem]
    /// There are more calls than the list shows.
    public var truncated: Bool

    private enum CodingKeys: String, CodingKey {
        case month, months, calls, truncated
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        month = try container.decode(String.self, forKey: .month)
        months = try container.decodeIfPresent([String].self, forKey: .months) ?? []
        calls = try container.decodeIfPresent([CallItem].self, forKey: .calls) ?? []
        truncated = try container.decodeIfPresent(Bool.self, forKey: .truncated) ?? false
    }
}

// MARK: - Voicemail

public struct VoicemailBox: Decodable, Equatable, Sendable, Identifiable {
    /// For a `user` pairing this equals the id of its own extension.
    public var id: String
    public var name: String
    public var number: String?
    /// A shared mailbox (as opposed to the box of one extension).
    public var shared: Bool
}

public struct VoicemailMessage: Decodable, Equatable, Sendable, Identifiable {
    /// A sealed reference, valid for this PBX and box only. Never the message id of the PBX.
    public var ref: String
    public var boxId: String
    public var boxName: String
    public var boxNumber: String?
    public var caller: String?
    public var callerName: String?
    public var receivedLabel: String
    public var receivedSort: String?
    public var durationLabel: String
    public var isNew: Bool
    public var transcription: String?
    /// Whole days left before the PBX removes the message; `nil` = unknown.
    public var daysLeft: Int?

    public var id: String { "\(boxId)/\(ref)" }

    private enum CodingKeys: String, CodingKey {
        case ref, boxId, boxName, boxNumber, caller, callerName, receivedLabel, receivedSort, durationLabel, isNew, transcription, daysLeft
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        ref = try container.decode(String.self, forKey: .ref)
        boxId = try container.decode(String.self, forKey: .boxId)
        boxName = try container.decodeIfPresent(String.self, forKey: .boxName) ?? ""
        boxNumber = try container.decodeIfPresent(String.self, forKey: .boxNumber)
        caller = try container.decodeIfPresent(String.self, forKey: .caller)
        callerName = try container.decodeIfPresent(String.self, forKey: .callerName)
        receivedLabel = try container.decodeIfPresent(String.self, forKey: .receivedLabel) ?? ""
        receivedSort = try container.decodeIfPresent(String.self, forKey: .receivedSort)
        durationLabel = try container.decodeIfPresent(String.self, forKey: .durationLabel) ?? ""
        isNew = try container.decodeIfPresent(Bool.self, forKey: .isNew) ?? false
        transcription = try container.decodeIfPresent(String.self, forKey: .transcription)
        daysLeft = try container.decodeIfPresent(Int.self, forKey: .daysLeft)
    }
}

/// `GET /voicemail?box=<boxId>`.
public struct VoicemailPage: Decodable, Equatable, Sendable {
    public var boxes: [VoicemailBox]
    /// The chosen box; `nil` = all boxes (admin).
    public var boxId: String?
    public var messages: [VoicemailMessage]
    /// `false` = the PBX did not answer: show a notice, not an empty list.
    public var available: Bool

    private enum CodingKeys: String, CodingKey {
        case boxes, boxId, messages, available
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        boxes = try container.decodeIfPresent([VoicemailBox].self, forKey: .boxes) ?? []
        boxId = try container.decodeIfPresent(String.self, forKey: .boxId)
        messages = try container.decodeIfPresent([VoicemailMessage].self, forKey: .messages) ?? []
        available = try container.decodeIfPresent(Bool.self, forKey: .available) ?? true
    }
}
