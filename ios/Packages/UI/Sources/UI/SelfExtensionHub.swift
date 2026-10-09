// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation

/// What "Profiel", "Oproepvoorkeuren" and "Gebruiker uitnodigen" need from the API for the own extension. One implementation talks to
/// the server, tests and the demo mode bring their own. Every method throws `APIError`.
public protocol SelfExtensionServicing: Sendable {
    func load(for account: StoredAccount) async throws -> SelfExtension
    /// `PATCH /me/extension`: the fresh state, or `nil` when the server could not read it right now.
    func patch(_ patch: SelfExtensionPatch, for account: StoredAccount) async throws -> SelfExtension?
}

public protocol InviteServicing: Sendable {
    /// `POST /pbx/devices/{id}/app-pairing`.
    func invite(deviceId: String, for account: StoredAccount) async throws -> AppPairingResponse
}

public struct LiveSelfExtensionService: SelfExtensionServicing, InviteServicing {
    private let api: FSVoipAPIClient

    public init(api: FSVoipAPIClient = FSVoipAPIClient()) {
        self.api = api
    }

    public func load(for account: StoredAccount) async throws -> SelfExtension {
        try await api.authenticated(with: account.deviceToken).selfExtension()
    }

    public func patch(_ patch: SelfExtensionPatch, for account: StoredAccount) async throws -> SelfExtension? {
        try await api.authenticated(with: account.deviceToken).updateSelfExtension(patch).extensionState
    }

    public func invite(deviceId: String, for account: StoredAccount) async throws -> AppPairingResponse {
        try await api.authenticated(with: account.deviceToken).createAppPairing(deviceId: deviceId)
    }
}

// MARK: - The form

/// What the two pages show of the own extension, as one value, and the translation to a PATCH body that holds only what changed.
public struct SelfExtensionDraft: Equatable, Sendable {
    public var forwardAlways: PbxTarget?
    public var noAnswerSeconds: Int
    public var noAnswerTarget: PbxTarget?
    public var voicemailEnabled: Bool
    public var voicemailToEmail: Bool
    public var email: String

    public static let noAnswerRange = 5 ... 120

    /// Before the state has loaded (nothing is shown from it).
    public init() {
        forwardAlways = nil
        noAnswerSeconds = 25
        noAnswerTarget = nil
        voicemailEnabled = false
        voicemailToEmail = false
        email = ""
    }

    public init(_ state: SelfExtension) {
        forwardAlways = state.forwardAlways
        noAnswerSeconds = state.noAnswerSeconds
        noAnswerTarget = state.noAnswerTarget
        voicemailEnabled = state.voicemailEnabled
        voicemailToEmail = state.voicemailToEmail
        email = state.email ?? ""
    }

    public var cleanEmail: String {
        email.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Only the keys that differ from `original`, with `version`. `nil` = nothing changed. The number to call out with is not a setting
    /// and "dnd" belongs to the switch on the avatar, so neither is ever sent from here.
    public func patch(from original: SelfExtensionDraft, version: Int) -> SelfExtensionPatch? {
        guard self != original else {
            return nil
        }

        func change(_ value: PbxTarget?, _ initial: PbxTarget?) -> Change<PbxTarget> {
            value == initial ? .keep : Change(value)
        }

        let emailChange: Change<String>

        if cleanEmail == original.cleanEmail {
            emailChange = .keep
        } else {
            emailChange = cleanEmail.isEmpty ? .clear : .set(cleanEmail)
        }

        let patch = SelfExtensionPatch(
            version: version,
            forwardAlways: change(forwardAlways, original.forwardAlways),
            noAnswerSeconds: noAnswerSeconds == original.noAnswerSeconds ? nil : noAnswerSeconds,
            noAnswerTarget: change(noAnswerTarget, original.noAnswerTarget),
            voicemailEnabled: voicemailEnabled == original.voicemailEnabled ? nil : voicemailEnabled,
            voicemailToEmail: voicemailToEmail == original.voicemailToEmail ? nil : voicemailToEmail,
            email: emailChange
        )

        // A difference that only the trimming removes ("a@b.nl " against "a@b.nl") is no change.
        return patch.isEmpty ? nil : patch
    }

    /// The server has a newer state: every field the user did not touch follows it, every field they did touch stays as typed.
    public mutating func rebase(onto fresh: SelfExtension, baseline: inout SelfExtensionDraft) {
        let freshDraft = SelfExtensionDraft(fresh)

        if forwardAlways == baseline.forwardAlways { forwardAlways = freshDraft.forwardAlways }
        if noAnswerSeconds == baseline.noAnswerSeconds { noAnswerSeconds = freshDraft.noAnswerSeconds }
        if noAnswerTarget == baseline.noAnswerTarget { noAnswerTarget = freshDraft.noAnswerTarget }
        if voicemailEnabled == baseline.voicemailEnabled { voicemailEnabled = freshDraft.voicemailEnabled }
        if voicemailToEmail == baseline.voicemailToEmail { voicemailToEmail = freshDraft.voicemailToEmail }
        if cleanEmail == baseline.cleanEmail { email = freshDraft.email }

        baseline = freshDraft
    }
}

extension SelfExtensionPatch {
    /// No key besides `version`.
    var isEmpty: Bool {
        dnd == nil && forwardAlways.isKept && noAnswerSeconds == nil && noAnswerTarget.isKept && voicemailEnabled == nil && voicemailToEmail == nil && email.isKept
    }
}

// MARK: - Failures

/// Why the own extension or an invitation did not work, in the user's terms.
public enum SelfExtensionFailure: Equatable, Sendable {
    /// 403: this pairing may not do this (any more).
    case accessDenied
    /// 401: the pairing is gone.
    case revoked
    case readOnly
    /// 409 `conflict` on an invitation: the own extension cannot be invited.
    case ownDevice
    case conflict
    /// 400 / 422: something the server will not accept (a bad e-mail address, a blocked number).
    case invalid
    case rateLimited(retryAfterSeconds: Int?)
    case offline
    case unavailable
    case other

    /// `forInvite`: a 409 on the invitation call means "you cannot invite your own extension"; anywhere else it is a generic conflict.
    public static func classify(_ error: Error, forInvite: Bool = false) -> SelfExtensionFailure {
        guard let api = error as? APIError else {
            return .other
        }

        switch api {
        case .forbidden: return .accessDenied
        case .unauthorized: return .revoked
        case .readOnly: return .readOnly
        case let .conflict(code):
            if code == "read_only" { return .readOnly }

            return forInvite ? .ownDevice : .conflict
        case .stale, .staleChain: return .conflict
        case .invalid, .invalidRequest, .blockedDestination, .payloadTooLarge: return .invalid
        case let .rateLimited(seconds): return .rateLimited(retryAfterSeconds: seconds)
        case .transport: return .offline
        case .unavailable, .unexpectedStatus: return .unavailable
        default: return .other
        }
    }

    var message: String {
        switch self {
        case .accessDenied: return L10n.string("self.error.denied")
        case .revoked: return L10n.string("self.error.revoked")
        case .readOnly: return L10n.string("pbx.readOnly.message")
        case .ownDevice: return L10n.string("invite.error.ownDevice")
        case .conflict: return L10n.string("self.error.conflict")
        case .invalid: return L10n.string("self.error.invalid")
        case let .rateLimited(seconds):
            if let seconds, seconds > 60 { return String(format: L10n.string("self.error.rateLimited.minutes"), (seconds + 59) / 60) }
            return L10n.string("self.error.rateLimited")
        case .offline: return L10n.string("self.error.offline")
        case .unavailable: return L10n.string("self.error.unavailable")
        case .other: return L10n.string("self.error.other")
        }
    }
}

// MARK: - The hub

/// The own extension per paired account: the state the Profiel and Oproepvoorkeuren pages edit, and the invitation of a colleague.
@MainActor
public final class SelfExtensionHub: ObservableObject {
    public enum SaveOutcome: Equatable {
        case saved(SelfExtension?)
        /// Nothing differed: nothing was sent.
        case unchanged
        /// Changed again while retrying: the fresh state is in `states`; what the user typed has not been sent.
        case stale(SelfExtension)
        case failed(SelfExtensionFailure)
    }

    public enum InviteOutcome: Equatable {
        case created(AppPairingResponse)
        case failed(SelfExtensionFailure)
    }

    @Published public private(set) var states: [String: SelfExtension] = [:]
    @Published public private(set) var saving: Set<String> = []
    /// Set when the role/pairing was taken away (403/401), so the app can react.
    public var onRevoked: (() -> Void)?

    private let service: SelfExtensionServicing
    private let invites: InviteServicing

    public init(service: SelfExtensionServicing, invites: InviteServicing) {
        self.service = service
        self.invites = invites
    }

    public convenience init(service: SelfExtensionServicing & InviteServicing) {
        self.init(service: service, invites: service)
    }

    /// A hub without a server behind it (builds and tests that do not offer the own extension): nothing loads, nothing shows.
    public static var unavailable: SelfExtensionHub { SelfExtensionHub(service: UnavailableSelfExtensionService()) }

    public func state(for accountId: String) -> SelfExtension? {
        states[accountId]
    }

    /// Loads the state; `false` when the server did not answer (the old state, if any, stays).
    @discardableResult
    public func load(_ account: StoredAccount) async -> Bool {
        do {
            states[account.id] = try await service.load(for: account)

            return true
        } catch {
            if SelfExtensionFailure.classify(error) == .revoked { onRevoked?() }

            return false
        }
    }

    /// Saves what differs between `draft` and `original`, with the version of the state. A `stale` answer gets ONE retry on the fresh
    /// version: only the touched fields go again, so nothing the user did not touch is overwritten. When that is stale as well the
    /// fresh state is shown and the draft is kept by the caller (see `SelfExtensionDraft.rebase`).
    public func save(_ draft: SelfExtensionDraft, original: SelfExtensionDraft, account: StoredAccount) async -> SaveOutcome {
        guard let current = states[account.id], !saving.contains(account.id) else {
            return .failed(.other)
        }

        guard let first = draft.patch(from: original, version: current.version) else {
            return .unchanged
        }

        saving.insert(account.id)
        defer { saving.remove(account.id) }

        do {
            return try await apply(first, account: account)
        } catch APIError.stale {
            return await retry(draft, original: original, account: account)
        } catch {
            let failure = SelfExtensionFailure.classify(error)
            if failure == .revoked { onRevoked?() }

            return .failed(failure)
        }
    }

    private func retry(_ draft: SelfExtensionDraft, original: SelfExtensionDraft, account: StoredAccount) async -> SaveOutcome {
        do {
            let fresh = try await service.load(for: account)
            states[account.id] = fresh

            guard let patch = draft.patch(from: original, version: fresh.version) else {
                return .unchanged
            }

            do {
                return try await apply(patch, account: account)
            } catch APIError.stale {
                states[account.id] = (try? await service.load(for: account)) ?? fresh

                return .stale(states[account.id] ?? fresh)
            }
        } catch {
            let failure = SelfExtensionFailure.classify(error)
            if failure == .revoked { onRevoked?() }

            return .failed(failure)
        }
    }

    private func apply(_ patch: SelfExtensionPatch, account: StoredAccount) async throws -> SaveOutcome {
        if let fresh = try await service.patch(patch, for: account) {
            states[account.id] = fresh

            return .saved(fresh)
        }

        // The server could not read the fresh state: ask once more.
        let fresh = try? await service.load(for: account)

        if let fresh { states[account.id] = fresh }

        return .saved(fresh)
    }

    /// An invitation link for a colleague's extension. The link is a credential: it is returned to the caller and kept nowhere.
    public func invite(deviceId: String, account: StoredAccount) async -> InviteOutcome {
        do {
            return .created(try await invites.invite(deviceId: deviceId, for: account))
        } catch {
            let failure = SelfExtensionFailure.classify(error, forInvite: true)
            if failure == .revoked { onRevoked?() }

            return .failed(failure)
        }
    }

    public func forget(accountId: String) {
        states[accountId] = nil
    }
}

struct UnavailableSelfExtensionService: SelfExtensionServicing, InviteServicing {
    func load(for account: StoredAccount) async throws -> SelfExtension { throw APIError.notFound }
    func patch(_ patch: SelfExtensionPatch, for account: StoredAccount) async throws -> SelfExtension? { throw APIError.notFound }
    func invite(deviceId: String, for account: StoredAccount) async throws -> AppPairingResponse { throw APIError.notFound }
}
