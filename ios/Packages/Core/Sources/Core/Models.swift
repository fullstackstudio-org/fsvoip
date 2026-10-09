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

    /// Show "Mijn toestel" (`/me/extension`)? Only when the server says so (an older server has no such route).
    public var canEditOwnExtension: Bool {
        capabilities?.selfExtension ?? false
    }

    /// Show the park button and the "On hold" tab?
    public var canPark: Bool {
        capabilities?.park ?? false
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
    /// Every call of the PBX, with recordings (admin).
    case all
    /// Every call of the PBX without recordings: the team history (`user`).
    case team
    /// Only the calls of the own extension (a server from before the team history).
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

/// Who may manage the sounds of the PBX (`capabilities.sounds`).
public enum SoundsAccess: String, WireEnum {
    case manage
    /// No access (wire value `none`; not called `.none`, which would clash with `Optional.none`).
    case noAccess = "none"
    case unknown

    public static var unknownValue: SoundsAccess { .unknown }

    public init(from decoder: Decoder) throws { self = try Self.decodeTolerant(from: decoder) }
    public func encode(to encoder: Encoder) throws { try encodeStrict(to: encoder) }
}

public struct AppCapabilities: Decodable, Equatable, Sendable {
    /// The "Centrale" section (`/pbx/*`).
    public var pbxManage: Bool
    /// Call recordings (`/calls/{id}/recording`).
    public var recordings: Bool
    public var voicemail: VoicemailAccess
    public var calls: CallsAccess
    public var contacts: ContactCapabilities
    /// `GET`/`PATCH /me/extension`: the own extension (every role). A server from before it leaves it out: `false`.
    public var selfExtension: Bool
    /// The sounds routes (`/pbx/sounds/**`).
    public var sounds: SoundsAccess
    /// Invite a colleague (`POST /pbx/devices/{id}/app-pairing`).
    public var invite: Bool
    /// Parking works on this PBX; `false` = do not show the park button or the "On hold" tab.
    public var park: Bool
    /// The PBX understands `X-FSS-From`: only then may the app choose the number to call out with (`CallerChoice`).
    public var callerChoice: Bool

    public init(
        pbxManage: Bool,
        recordings: Bool,
        voicemail: VoicemailAccess,
        calls: CallsAccess,
        contacts: ContactCapabilities = ContactCapabilities(),
        selfExtension: Bool = false,
        sounds: SoundsAccess = .noAccess,
        invite: Bool = false,
        park: Bool = false,
        callerChoice: Bool = false
    ) {
        self.pbxManage = pbxManage
        self.recordings = recordings
        self.voicemail = voicemail
        self.calls = calls
        self.contacts = contacts
        self.selfExtension = selfExtension
        self.sounds = sounds
        self.invite = invite
        self.park = park
        self.callerChoice = callerChoice
    }

    private enum CodingKeys: String, CodingKey {
        case pbxManage, recordings, voicemail, calls, contacts, selfExtension, sounds, invite, park, callerChoice
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
        selfExtension = try container.decodeIfPresent(Bool.self, forKey: .selfExtension) ?? false

        let sounds = try container.decodeIfPresent(SoundsAccess.self, forKey: .sounds) ?? .noAccess
        self.sounds = sounds == .unknown ? .noAccess : sounds

        invite = try container.decodeIfPresent(Bool.self, forKey: .invite) ?? false
        park = try container.decodeIfPresent(Bool.self, forKey: .park) ?? false
        callerChoice = try container.decodeIfPresent(Bool.self, forKey: .callerChoice) ?? false
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
public struct APIErrorBody: Decodable, Equatable, Sendable {
    public var error: String
    public var message: String?
    public var retryable: Bool?
    /// Detail of `invalid_request` (e.g. `invalid_phone`, `resync`, `greeting_required`) and `conflict` (e.g. `limit_reached`).
    public var code: String?
    /// The offending request field of an `invalid_request`, or the extension field a `user` may not change (`403`).
    public var field: String?
    /// `forbidden`: the role that is needed (`admin`).
    public var requiredRole: String?
    /// `stale`: the current version of the object.
    public var version: Int?
    /// `in_use`: where it is still used.
    public var places: [APIErrorPlace]?
    /// `blocked_destination`: the external numbers a block list stops.
    public var blocked: [String]?
    /// `cost_not_accepted`: the price to accept.
    public var cost: RecordingCost?
    /// `stale` of a chain step: the fresh chain. Decoded leniently: an unreadable chain must not hide the error itself.
    public var chain: NumberChain?

    private enum CodingKeys: String, CodingKey {
        case error, message, retryable, code, field, version, places, blocked, cost, chain
        case requiredRole = "required"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        error = try container.decode(String.self, forKey: .error)
        message = try? container.decodeIfPresent(String.self, forKey: .message)
        retryable = try? container.decodeIfPresent(Bool.self, forKey: .retryable)
        code = try? container.decodeIfPresent(String.self, forKey: .code)
        field = try? container.decodeIfPresent(String.self, forKey: .field)
        requiredRole = try? container.decodeIfPresent(String.self, forKey: .requiredRole)
        version = try? container.decodeIfPresent(Int.self, forKey: .version)
        places = try? container.decodeIfPresent([APIErrorPlace].self, forKey: .places)
        blocked = try? container.decodeIfPresent([String].self, forKey: .blocked)
        cost = try? container.decodeIfPresent(RecordingCost.self, forKey: .cost)
        chain = try? container.decodeIfPresent(NumberChain.self, forKey: .chain)
    }
}

// MARK: - The own extension (`/me/extension`)

/// A number of the PBX as `GET /me/extension` lists it.
public struct SelfNumber: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    /// National, without country code (`0850607848`).
    public var number: String
    public var name: String?
    /// The default number of the PBX.
    public var isDefault: Bool

    private enum CodingKeys: String, CodingKey {
        case id, number, name, isDefault
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        number = try container.decode(String.self, forKey: .number)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        isDefault = try container.decodeIfPresent(Bool.self, forKey: .isDefault) ?? false
    }
}

/// The own extension: do not disturb, forwarding, voicemail. Every role may read and change it.
public struct SelfExtension: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var extensionNumber: String?
    public var dnd: Bool
    public var forwardAlways: PbxTarget?
    public var noAnswerSeconds: Int
    public var noAnswerTarget: PbxTarget?
    public var voicemailEnabled: Bool
    public var voicemailToEmail: Bool
    public var email: String?
    public var sync: SyncState
    /// Goes back with a change; another version is `409 stale`.
    public var version: Int
    /// The numbers of the PBX. Calling out with one of them: `CallerChoice`.
    public var numbers: [SelfNumber]
    /// The number this extension calls out with by default; `nil` = none.
    public var defaultNumber: String?
    /// Where a call may be forwarded to (a colleague, a ring group, a voicemail box, an external number). Never the extension itself.
    public var targets: [PbxTargetOption]

    private enum CodingKeys: String, CodingKey {
        case id, name, dnd, forwardAlways, noAnswerSeconds, noAnswerTarget, voicemailEnabled, voicemailToEmail, email, sync, version
        case numbers, defaultNumber, targets
        case extensionNumber = "extension"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        extensionNumber = try container.decodeIfPresent(String.self, forKey: .extensionNumber)
        dnd = try container.decodeIfPresent(Bool.self, forKey: .dnd) ?? false
        forwardAlways = try container.decodeIfPresent(PbxTarget.self, forKey: .forwardAlways)
        noAnswerSeconds = try container.decodeIfPresent(Int.self, forKey: .noAnswerSeconds) ?? 25
        noAnswerTarget = try container.decodeIfPresent(PbxTarget.self, forKey: .noAnswerTarget)
        voicemailEnabled = try container.decodeIfPresent(Bool.self, forKey: .voicemailEnabled) ?? false
        voicemailToEmail = try container.decodeIfPresent(Bool.self, forKey: .voicemailToEmail) ?? false
        email = try container.decodeIfPresent(String.self, forKey: .email)
        sync = try container.decodeIfPresent(SyncState.self, forKey: .sync) ?? .unknown
        version = try container.decode(Int.self, forKey: .version)
        numbers = try container.decodeIfPresent([SelfNumber].self, forKey: .numbers) ?? []
        defaultNumber = try container.decodeIfPresent(String.self, forKey: .defaultNumber)
        targets = try container.decodeIfPresent([PbxTargetOption].self, forKey: .targets) ?? []
    }
}

/// `PATCH /me/extension`: only these keys (the number to call out with is not a setting: the app chooses it per call).
/// `version` is required; at least one other key.
public struct SelfExtensionPatch: Encodable, Equatable, Sendable {
    public var version: Int
    public var dnd: Bool?
    /// `.clear` = no forwarding.
    public var forwardAlways: Change<PbxTarget>
    public var noAnswerSeconds: Int?
    public var noAnswerTarget: Change<PbxTarget>
    public var voicemailEnabled: Bool?
    public var voicemailToEmail: Bool?
    public var email: Change<String>

    public init(
        version: Int,
        dnd: Bool? = nil,
        forwardAlways: Change<PbxTarget> = .keep,
        noAnswerSeconds: Int? = nil,
        noAnswerTarget: Change<PbxTarget> = .keep,
        voicemailEnabled: Bool? = nil,
        voicemailToEmail: Bool? = nil,
        email: Change<String> = .keep
    ) {
        self.version = version
        self.dnd = dnd
        self.forwardAlways = forwardAlways
        self.noAnswerSeconds = noAnswerSeconds
        self.noAnswerTarget = noAnswerTarget
        self.voicemailEnabled = voicemailEnabled
        self.voicemailToEmail = voicemailToEmail
        self.email = email
    }

    private enum CodingKeys: String, CodingKey {
        case version, dnd, forwardAlways, noAnswerSeconds, noAnswerTarget, voicemailEnabled, voicemailToEmail, email
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(version, forKey: .version)
        try container.encodeIfPresent(dnd, forKey: .dnd)
        try container.encodeChange(forwardAlways, forKey: .forwardAlways)
        try container.encodeIfPresent(noAnswerSeconds, forKey: .noAnswerSeconds)
        try container.encodeChange(noAnswerTarget, forKey: .noAnswerTarget)
        try container.encodeIfPresent(voicemailEnabled, forKey: .voicemailEnabled)
        try container.encodeIfPresent(voicemailToEmail, forKey: .voicemailToEmail)
        try container.encodeChange(email, forKey: .email)
    }
}

/// Answer of `PATCH /me/extension`: `extension` is the fresh state, best effort (`nil` when the server could not read it right now).
public struct SelfExtensionPatchResponse: Decodable, Equatable, Sendable {
    public var ok: Bool
    public var extensionState: SelfExtension?

    private enum CodingKeys: String, CodingKey {
        case ok
        case extensionState = "extension"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        ok = try container.decode(Bool.self, forKey: .ok)
        extensionState = try? container.decodeIfPresent(SelfExtension.self, forKey: .extensionState)
    }
}

// MARK: - Caller choice (`X-FSS-From`)

/// "Calling out via": the SIP header that tells the PBX which of its numbers an outgoing call uses. 🚨 Only sent when
/// `AppCapabilities.callerChoice` is `true`; a PBX without the dialplan would forward an unknown header to the provider.
public enum CallerChoice {
    public static let headerName = "X-FSS-From"

    /// A national number of the PBX: ten digits, the first a 0 and the second not (`0850607848`).
    public static func isValidNumber(_ number: String) -> Bool {
        let digits = Array(number.utf8)

        return digits.count == 10 && digits.allSatisfy { $0 >= 48 && $0 <= 57 } && digits[0] == 48 && digits[1] != 48
    }

    /// The SIP headers to add to an outgoing INVITE. Empty unless the PBX supports the choice AND `number` is a number it lists.
    /// `numbers` = `SelfExtension.numbers`.
    public static func headers(choosing number: String?, capabilities: AppCapabilities?, numbers: [SelfNumber]) -> [String: String] {
        guard capabilities?.callerChoice == true, let number, isValidNumber(number), numbers.contains(where: { $0.number == number }) else {
            return [:]
        }

        return [headerName: number]
    }
}
