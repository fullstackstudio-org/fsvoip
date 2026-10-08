// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Swift mirror of `shared/openapi.yaml` (API v1). Keep the two in sync; the contract tests decode
// `shared/fixtures/*` with these types. Responses are decoded leniently (unknown fields are ignored,
// additive API changes must not break the app); requests are encoded exactly as the server accepts them.

import Foundation

// MARK: - Vocabulary

public enum APIPlatform: String, Codable, Sendable {
    case ios
    case android
}

public enum PushKind: String, Codable, Sendable {
    /// iOS PushKit (VoIP) token.
    case apnsVoip = "apns_voip"
    /// Firebase Cloud Messaging (Android).
    case fcm
}

public enum PushEnvironment: String, Codable, Sendable {
    case production
    case sandbox
}

/// Transport of the SIP connection as the server tells us (`sip.transport`).
public enum ServerTransport: String, Codable, Sendable {
    case udp
    case tcp
    case tls
}

// MARK: - Pairing

public struct DeviceInfo: Codable, Equatable, Sendable {
    public var platform: APIPlatform
    public var model: String?
    public var osVersion: String?
    public var appVersion: String?
    public var sipInstanceId: String?
    public var installId: String?
    public var pushToken: String?
    public var pushKind: PushKind?
    public var pushEnv: PushEnvironment?
    public var alertPushToken: String?

    public init(
        platform: APIPlatform,
        model: String? = nil,
        osVersion: String? = nil,
        appVersion: String? = nil,
        sipInstanceId: String? = nil,
        installId: String? = nil,
        pushToken: String? = nil,
        pushKind: PushKind? = nil,
        pushEnv: PushEnvironment? = nil,
        alertPushToken: String? = nil
    ) {
        self.platform = platform
        self.model = model
        self.osVersion = osVersion
        self.appVersion = appVersion
        self.sipInstanceId = sipInstanceId
        self.installId = installId
        self.pushToken = pushToken
        self.pushKind = pushKind
        self.pushEnv = pushEnv
        self.alertPushToken = alertPushToken
    }
}

public struct PairRequest: Codable, Equatable, Sendable {
    public var token: String
    public var device: DeviceInfo

    public init(token: String, device: DeviceInfo) {
        self.token = token
        self.device = device
    }
}

public struct PairedDevice: Codable, Equatable, Sendable {
    public var id: String
    public var installId: String
}

public struct AccountInfo: Codable, Equatable, Sendable {
    /// Id of this app pairing; equals `PairedDevice.id` and is what pushes call `accountId`.
    public var id: String
    /// What the app shows: the alias if set, otherwise `<centrale> · <toestel>`.
    public var label: String
    /// The alias chosen on the phone (only present in `GET /me`).
    public var labelOverride: String?
    public var pbxName: String
    public var extensionName: String
    public var extensionNumber: String?
    public var customerName: String
}

/// The SIP credentials, returned exactly once by `POST /pair`.
public struct SIPCredentials: Codable, Equatable, Sendable {
    public var username: String
    public var password: Secret
    /// SIP domain of the tenant (registrar domain / realm). NOT the outbound proxy.
    public var domain: String
    /// Outbound proxy host.
    public var proxy: String
    public var port: Int
    public var transport: ServerTransport
    /// DNS SRV records exist for the proxy.
    public var srv: Bool

    public init(username: String, password: Secret, domain: String, proxy: String, port: Int, transport: ServerTransport, srv: Bool) {
        self.username = username
        self.password = password
        self.domain = domain
        self.proxy = proxy
        self.port = port
        self.transport = transport
        self.srv = srv
    }
}

/// `sip` in `GET /me`: the server side only, no username or password.
public struct SIPServer: Codable, Equatable, Sendable {
    public var domain: String
    public var proxy: String
    public var port: Int
    public var transport: ServerTransport
    public var srv: Bool
}

public struct ContactsInfo: Codable, Equatable, Sendable {
    /// Internal contacts (the other extensions) are available via `GET /me`.
    public var hasInternal: Bool
    /// Number of customer contact lists in the portal.
    public var listsAvailable: Int

    private enum CodingKeys: String, CodingKey {
        case hasInternal = "internal"
        case listsAvailable
    }
}

public struct PairResponse: Codable, Equatable, Sendable {
    public var deviceToken: Secret
    public var device: PairedDevice
    public var account: AccountInfo
    public var sip: SIPCredentials
    public var contacts: ContactsInfo
}

// MARK: - /me

public struct InternalContact: Codable, Equatable, Sendable {
    public var number: String
    public var name: String

    public init(number: String, name: String) {
        self.number = number
        self.name = name
    }
}

public struct PushStatus: Equatable, Sendable, Decodable {
    public var registered: Bool
    public var kind: PushKind?
    public var env: PushEnvironment?
    /// The push service rejected the token: the app must register a fresh one.
    public var invalid: Bool

    private enum CodingKeys: String, CodingKey {
        case registered, kind, env, invalid
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        registered = try container.decode(Bool.self, forKey: .registered)
        invalid = try container.decode(Bool.self, forKey: .invalid)
        // Informational: a value this app version does not know must not make the whole `/me` fail.
        kind = (try? container.decodeIfPresent(PushKind.self, forKey: .kind)) ?? nil
        env = (try? container.decodeIfPresent(PushEnvironment.self, forKey: .env)) ?? nil
    }
}

public struct MeResponse: Decodable, Equatable, Sendable {
    public var account: AccountInfo
    /// `nil` when the server could not determine the PBX connection details right now.
    public var sip: SIPServer?
    public var internalContacts: [InternalContact]
    public var contacts: ContactsInfo
    public var push: PushStatus
    public var serverTime: Date

    private enum CodingKeys: String, CodingKey {
        case account, sip, contacts, push, serverTime
        case internalContacts = "internal"
    }
}

public struct MePatchRequest: Codable, Equatable, Sendable {
    /// The alias. `nil` clears it (encoded as an explicit `null`; the server requires the key).
    public var labelOverride: String?

    public init(labelOverride: String?) {
        self.labelOverride = labelOverride
    }

    private enum CodingKeys: String, CodingKey {
        case labelOverride
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(labelOverride, forKey: .labelOverride)
    }
}

public struct MePatchResponse: Codable, Equatable, Sendable {
    public var label: String
    public var labelOverride: String?
}

public struct PushTokenUpdate: Codable, Equatable, Sendable {
    public var pushKind: PushKind?
    public var pushToken: String?
    public var pushEnv: PushEnvironment?
    public var alertPushToken: String?

    public init(pushKind: PushKind?, pushToken: String?, pushEnv: PushEnvironment?, alertPushToken: String? = nil) {
        self.pushKind = pushKind
        self.pushToken = pushToken
        self.pushEnv = pushEnv
        self.alertPushToken = alertPushToken
    }

    /// Unregister push for this installation (`{"pushToken": null}`).
    public static let clear = PushTokenUpdate(pushKind: nil, pushToken: nil, pushEnv: nil)

    private enum CodingKeys: String, CodingKey {
        case pushKind, pushToken, pushEnv, alertPushToken
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)

        if pushToken == nil {
            // Explicit null is the documented way to unregister.
            try container.encodeNil(forKey: .pushToken)

            return
        }

        try container.encodeIfPresent(pushKind, forKey: .pushKind)
        try container.encode(pushToken, forKey: .pushToken)
        try container.encodeIfPresent(pushEnv, forKey: .pushEnv)
        try container.encodeIfPresent(alertPushToken, forKey: .alertPushToken)
    }
}

public struct OkResponse: Codable, Equatable, Sendable {
    public var ok: Bool
}

/// Error body of every non-2xx answer.
public struct APIErrorBody: Codable, Equatable, Sendable {
    public var error: String
    public var message: String?
    public var retryable: Bool?
}
