// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation
import SwiftUI

/// Who may see the "Centrale" section, per paired account, and the state of the sections that are open.
///
/// The role comes from the app's `GET /me` (`apply(me:accountId:)`, one request per account per refresh) and is never stored on the phone: after a restart the section stays hidden until the server
/// has said `admin` again (fail closed). A 403 from any `/pbx` call, or a `refresh` push followed by a `/me` without the
/// role, hides it at once.
@MainActor
public final class PbxHub: ObservableObject {
    public enum Access: Equatable {
        /// Not asked yet (or the server could not be reached): nothing is shown.
        case unknown
        case hidden
        case available(readOnly: Bool)
    }

    @Published public private(set) var access: [String: Access] = [:]
    /// Set when the role of an account was taken away, so the app can say so.
    @Published public var lostAccessFor: String?

    public let gate: LocalAccessGate

    private let service: PbxServicing
    private let sleep: @Sendable (TimeInterval) async -> Void
    private var sections: [String: PbxSectionModel] = [:]
    /// Asks the app to re-read its accounts (a 401 on a `/pbx` call).
    public var onRevoked: (() -> Void)?

    public init(
        service: PbxServicing,
        gate: LocalAccessGate,
        sleep: @escaping @Sendable (TimeInterval) async -> Void = { seconds in try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)) }
    ) {
        self.service = service
        self.gate = gate
        self.sleep = sleep
    }

    public func access(for accountId: String) -> Access {
        access[accountId] ?? .unknown
    }

    /// Show the section for this account?
    public func isAvailable(_ accountId: String) -> Bool {
        if case .available = access(for: accountId) {
            return true
        }

        return false
    }

    /// `GET /me` said 403: the pairing may not use the section any more.
    public func accessDenied(accountId: String) {
        setAccess(.hidden, for: accountId)
    }

    /// The answer of `GET /me`: the section exists for an `admin` whose capabilities allow it.
    public func apply(me: MeResponse, accountId: String) {
        if me.canManagePbx {
            let readOnly = me.pbx?.readOnly ?? true
            setAccess(.available(readOnly: readOnly), for: accountId)
        } else {
            setAccess(.hidden, for: accountId)
        }
    }

    private func setAccess(_ value: Access, for accountId: String) {
        let previous = access[accountId]

        guard previous != value else {
            return
        }

        access[accountId] = value

        if case let .available(readOnly) = value {
            sections[accountId]?.noteReadOnly(readOnly)
        }

        if value == .hidden {
            // A role that was there and is gone: close the open section, lock, and say so.
            if case .available = previous ?? .unknown {
                lostAccessFor = accountId
            }

            sections[accountId]?.stopPolling()
            sections[accountId] = nil
            gate.lock()
        }
    }

    /// The model of the open section of this account (made on first use, dropped when the role goes).
    func section(for account: StoredAccount) -> PbxSectionModel {
        if let existing = sections[account.id] {
            return existing
        }

        let readOnly: Bool

        if case let .available(value) = access(for: account.id) {
            readOnly = value
        } else {
            readOnly = true
        }

        let model = PbxSectionModel(account: account, readOnly: readOnly, service: service, gate: gate, sleep: sleep)
        model.onAccessLost = { [weak self] in
            self?.setAccess(.hidden, for: account.id)
        }
        model.onRevoked = { [weak self] in
            self?.onRevoked?()
        }
        sections[account.id] = model

        return model
    }

    /// The account is gone from this phone.
    public func forget(accountId: String) {
        sections[accountId]?.stopPolling()
        sections[accountId] = nil
        access[accountId] = nil
    }
}
