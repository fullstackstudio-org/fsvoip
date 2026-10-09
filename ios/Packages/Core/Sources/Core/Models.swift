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
    /// Number of customer contacts (`GET /contacts`). Only in `GET /me` of a server with the contacts API; `nil` otherwise.
    public var total: Int?

    public init(hasInternal: Bool = true, listsAvailable: Int = 0, total: Int? = nil) {
        self.hasInternal = hasInternal
        self.listsAvailable = listsAvailable
        self.total = total
    }

    private enum CodingKeys: String, CodingKey {
        case hasInternal = "internal"
        case listsAvailable
        case total
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
    /// What this pairing may do (set in the portal). `nil` = a server from before roles existed: treat as `user`.
    public var role: AppRole?
    /// What the app may show for that role. Informational: the server enforces every route.
    public var capabilities: AppCapabilities?
    /// State of the PBX. `nil` = an older server.
    public var pbx: PbxStatus?

    private enum CodingKeys: String, CodingKey {
        case account, sip, contacts, push, serverTime, role, capabilities, pbx
        case internalContacts = "internal"
    }

    public init(
        account: AccountInfo,
        sip: SIPServer?,
        internalContacts: [InternalContact],
        contacts: ContactsInfo,
        push: PushStatus,
        serverTime: Date,
        role: AppRole? = nil,
        capabilities: AppCapabilities? = nil,
        pbx: PbxStatus? = nil
    ) {
        self.account = account
        self.sip = sip
        self.internalContacts = internalContacts
        self.contacts = contacts
        self.push = push
        self.serverTime = serverTime
        self.role = role
        self.capabilities = capabilities
        self.pbx = pbx
    }

    /// Fail closed: anything but a role the server explicitly says is `admin` counts as `user`.
    public var effectiveRole: AppRole {
        role == .admin ? .admin : .user
    }

    /// Show the "Centrale" section? Needs the admin role AND the capability (the server decides, `pbxManage`).
    public var canManagePbx: Bool {
        effectiveRole == .admin && (capabilities?.pbxManage ?? true)
    }
}

// MARK: - Role and capabilities (`/me`)

/// The role of this pairing. `admin` may manage the PBX from the app; everything else is `user`.
public enum AppRole: String, WireEnum {
    case user
    case admin
    case unknown

    public static var unknownValue: AppRole { .unknown }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

public enum VoicemailAccess: String, WireEnum {
    case all
    case own
    /// No voicemail (wire value `none`; not called `.none`, which would clash with `Optional.none`).
    case noAccess = "none"
    case unknown

    public static var unknownValue: VoicemailAccess { .unknown }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

public enum CallsAccess: String, WireEnum {
    case all
    case own
    case unknown

    public static var unknownValue: CallsAccess { .unknown }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

public struct ContactCapabilities: Decodable, Equatable, Sendable {
    public var read: Bool
    public var write: Bool
    public var delete: Bool

    public init(read: Bool = true, write: Bool = true, delete: Bool = false) {
        self.read = read
        self.write = write
        self.delete = delete
    }

    private enum CodingKeys: String, CodingKey {
        case read, write, delete
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        read = try container.decodeIfPresent(Bool.self, forKey: .read) ?? true
        write = try container.decodeIfPresent(Bool.self, forKey: .write) ?? true
        // Deleting is the restricted one: a missing value means no.
        delete = try container.decodeIfPresent(Bool.self, forKey: .delete) ?? false
    }
}

public struct AppCapabilities: Decodable, Equatable, Sendable {
    /// The "Centrale" section (`/pbx/*`).
    public var pbxManage: Bool
    /// Call recordings (`/calls/{id}/recording`).
    public var recordings: Bool
    public var voicemail: VoicemailAccess
    public var calls: CallsAccess
    public var contacts: ContactCapabilities

    public init(pbxManage: Bool, recordings: Bool, voicemail: VoicemailAccess, calls: CallsAccess, contacts: ContactCapabilities = ContactCapabilities()) {
        self.pbxManage = pbxManage
        self.recordings = recordings
        self.voicemail = voicemail
        self.calls = calls
        self.contacts = contacts
    }

    private enum CodingKeys: String, CodingKey {
        case pbxManage, recordings, voicemail, calls, contacts
    }

    /// Every missing or unknown value falls back to the restrictive choice.
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        pbxManage = try container.decodeIfPresent(Bool.self, forKey: .pbxManage) ?? false
        recordings = try container.decodeIfPresent(Bool.self, forKey: .recordings) ?? false

        let voicemail = try container.decodeIfPresent(VoicemailAccess.self, forKey: .voicemail) ?? VoicemailAccess.noAccess
        self.voicemail = voicemail == .unknown ? VoicemailAccess.noAccess : voicemail

        let calls = try container.decodeIfPresent(CallsAccess.self, forKey: .calls) ?? .own
        self.calls = calls == .unknown ? .own : calls

        contacts = try container.decodeIfPresent(ContactCapabilities.self, forKey: .contacts) ?? ContactCapabilities()
    }
}

public enum PbxState: String, WireEnum {
    case active
    case frozen
    case setup
    case error
    case unknown

    public static var unknownValue: PbxState { .unknown }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

/// The PBX as the app sees it in `/me` and `/pbx/overview`. `readOnly`: frozen, still being set up or in an error state.
public struct PbxStatus: Decodable, Equatable, Sendable {
    public var name: String
    public var state: PbxState
    public var readOnly: Bool

    public init(name: String, state: PbxState, readOnly: Bool) {
        self.name = name
        self.state = state
        self.readOnly = readOnly
    }

    private enum CodingKeys: String, CodingKey {
        case name, state, readOnly
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = try container.decode(String.self, forKey: .name)
        state = try container.decode(PbxState.self, forKey: .state)
        // Fail closed: an unknown or missing flag means the app does not offer changes.
        readOnly = try container.decodeIfPresent(Bool.self, forKey: .readOnly) ?? true
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

/// A place that still uses something you try to delete (`in_use`); the name is one the customer chose.
public struct APIErrorPlace: Codable, Equatable, Sendable {
    public var kind: String
    public var name: String
}

/// Error body of every non-2xx answer. Everything but `error` is optional: which fields are present depends on the code.
public struct APIErrorBody: Codable, Equatable, Sendable {
    public var error: String
    public var message: String?
    public var retryable: Bool?
    /// Detail of `invalid_request` (e.g. `invalid_phone`, `resync`) and `conflict` (e.g. `limit_reached`).
    public var code: String?
    /// The offending request field of an `invalid_request`.
    public var field: String?
    /// `forbidden`: the role that is needed (`admin`).
    public var requiredRole: String?
    /// `stale`: the current version of the object.
    public var version: Int?
    /// `in_use`: where it is still used.
    public var places: [APIErrorPlace]?
    /// `blocked_destination`: the external numbers a block list stops.
    public var blocked: [String]?

    private enum CodingKeys: String, CodingKey {
        case error, message, retryable, code, field, version, places, blocked
        case requiredRole = "required"
    }
}
