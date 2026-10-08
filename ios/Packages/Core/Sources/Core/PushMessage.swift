// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The `fsvoip` object every FSVoip push carries (see `shared/push-payload.schema.json`).
// A push never contains the SIP password, a device token or a push token.

import Foundation

public struct PushCaller: Codable, Equatable, Sendable {
    /// Caller number as the PBX knows it; `nil` = anonymous.
    public var number: String?
    /// Caller name from the PBX caller id; `nil` = unknown.
    public var name: String?

    public init(number: String?, name: String?) {
        self.number = number
        self.name = name
    }

    private enum CodingKeys: String, CodingKey {
        case number, name
    }

    // The schema requires both keys, with an explicit null.
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(number, forKey: .number)
        try container.encode(name, forKey: .name)
    }
}

public struct RingPush: Codable, Equatable, Sendable {
    /// FreeSWITCH call UUID; equals the `X-FSS-Call` header of the matching SIP INVITE.
    public var callRef: String
    public var from: PushCaller
    public var accountId: String
    public var accountLabel: String
    /// After this moment (about 12 s) a call without INVITE ends as unanswered.
    public var expiresAt: Date

    public init(callRef: String, from: PushCaller, accountId: String, accountLabel: String, expiresAt: Date) {
        self.callRef = callRef
        self.from = from
        self.accountId = accountId
        self.accountLabel = accountLabel
        self.expiresAt = expiresAt
    }
}

public struct RevokedPush: Codable, Equatable, Sendable {
    public var accountId: String
    public var accountLabel: String
}

public struct RefreshPush: Codable, Equatable, Sendable {
    public var accountId: String
}

public enum PushMessage: Equatable, Sendable {
    case ring(RingPush)
    case revoked(RevokedPush)
    case refresh(RefreshPush)
}

public enum PushMessageError: Error, Equatable, Sendable {
    case missingPayload
    case unsupportedVersion(Int)
    case unknownType(String)
}

extension PushMessage: Decodable {
    private enum Keys: String, CodingKey {
        case v, type
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: Keys.self)
        let version = try container.decode(Int.self, forKey: .v)

        guard version == 1 else {
            throw PushMessageError.unsupportedVersion(version)
        }

        let type = try container.decode(String.self, forKey: .type)

        switch type {
        case "ring":
            self = .ring(try RingPush(from: decoder))
        case "revoked":
            self = .revoked(try RevokedPush(from: decoder))
        case "refresh":
            self = .refresh(try RefreshPush(from: decoder))
        default:
            throw PushMessageError.unknownType(type)
        }
    }
}

extension PushMessage {
    /// Decode the `fsvoip` object from the JSON of an APNs payload (`{ "aps": {...}, "fsvoip": {...} }`).
    public static func decode(apnsPayload data: Data) throws -> PushMessage {
        struct Envelope: Decodable {
            let fsvoip: PushMessage?
        }

        guard let message = try FSVoipJSON.decoder().decode(Envelope.self, from: data).fsvoip else {
            throw PushMessageError.missingPayload
        }

        return message
    }

    /// Decode from the dictionary of a PushKit push (`PKPushPayload.dictionaryPayload`).
    public static func decode(apnsDictionary dictionary: [AnyHashable: Any]) throws -> PushMessage {
        guard let object = dictionary["fsvoip"], JSONSerialization.isValidJSONObject(["fsvoip": object]) else {
            throw PushMessageError.missingPayload
        }

        return try decode(apnsPayload: JSONSerialization.data(withJSONObject: ["fsvoip": object]))
    }

    /// Decode from the `data` of an FCM message, where `fsvoip` is a JSON string.
    public static func decode(fcmData: [String: String]) throws -> PushMessage {
        guard let text = fcmData["fsvoip"], let data = text.data(using: .utf8) else {
            throw PushMessageError.missingPayload
        }

        return try FSVoipJSON.decoder().decode(PushMessage.self, from: data)
    }
}
