// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Keeps the server's push tokens of every account up to date (`PUT /push-token`, plan D9/D11).
//
// iOS has two tokens: the PushKit (VoIP) token for incoming calls, and the regular APNs token for notices such as
// "this phone was unpaired" (a PushKit token can only receive VoIP pushes, and Apple forbids VoIP pushes that are
// not a call). Both go in one request, per account (the device token is per account), with the push environment of
// this build (sandbox for debug builds, production for TestFlight and the App Store).
//
// When: the first time in every app launch, and whenever a token changes. A token that did not change is not sent
// again (a fingerprint per account is kept; the tokens themselves are not stored).

import CryptoKit
import Core
import Foundation

/// What one `report()` did, by account id.
public struct PushTokenReport: Equatable, Sendable {
    public var sent: [String] = []
    /// 401: the pairing was revoked on the server. The app must remove these accounts (a `GET /me` does it).
    public var revoked: [String] = []
    /// Not sent now (offline, server trouble); tried again at the next report.
    public var failed: [String] = []

    public init(sent: [String] = [], revoked: [String] = [], failed: [String] = []) {
        self.sent = sent
        self.revoked = revoked
        self.failed = failed
    }
}

public protocol PushTokenReporting: Sendable {
    /// The PushKit token (`nil` = invalidated).
    func setVoipToken(_ token: Data?) async
    /// The regular APNs token (`nil` = none).
    func setAlertToken(_ token: Data?) async
    /// Send the tokens to every account that does not have the current ones yet (or not yet in this launch).
    @discardableResult
    func report() async -> PushTokenReport
}

/// The fingerprint of what each account last received. Not secret (a hash), so `UserDefaults` is fine.
public protocol PushTokenLedger: AnyObject, Sendable {
    func fingerprints() -> [String: String]
    func setFingerprints(_ values: [String: String])
}

public final class UserDefaultsPushTokenLedger: PushTokenLedger, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key = "fsvoip.push-token-fingerprints"

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func fingerprints() -> [String: String] {
        defaults.dictionary(forKey: key) as? [String: String] ?? [:]
    }

    public func setFingerprints(_ values: [String: String]) {
        defaults.set(values, forKey: key)
    }
}

public final class InMemoryPushTokenLedger: PushTokenLedger, @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: String] = [:]

    public init() {}

    public func fingerprints() -> [String: String] {
        lock.withLock { values }
    }

    public func setFingerprints(_ values: [String: String]) {
        lock.withLock { self.values = values }
    }
}

public actor PushTokenReporter: PushTokenReporting {
    private let api: FSVoipAPIClient
    private let accounts: AccountStore
    private let ledger: PushTokenLedger
    private let environment: PushEnvironment
    private let logger: FSLogger

    private var voipToken: String?
    /// PushKit answered at least once (with a token or an invalidation). Until then nothing is sent: a report at app
    /// start must not clear the server's token just because PushKit has not answered yet.
    private var voipTokenKnown = false
    private var alertToken: String?
    /// Accounts that got their tokens in this launch (the first report of a launch is always sent).
    private var reportedThisLaunch: Set<String> = []

    public init(api: FSVoipAPIClient, accounts: AccountStore, ledger: PushTokenLedger, environment: PushEnvironment, logger: FSLogger = FSLogger(category: "push")) {
        self.api = api
        self.accounts = accounts
        self.ledger = ledger
        self.environment = environment
        self.logger = logger
    }

    public func setVoipToken(_ token: Data?) {
        voipToken = token.map(PushTokenEncoding.hex)
        voipTokenKnown = true
    }

    public func setAlertToken(_ token: Data?) {
        alertToken = token.map(PushTokenEncoding.hex)
    }

    /// What the server is sent: both tokens and the environment, or a "clear" when there is no VoIP token.
    func update() -> PushTokenUpdate {
        guard let voipToken else {
            return .clear
        }

        return PushTokenUpdate(pushKind: .apnsVoip, pushToken: voipToken, pushEnv: environment, alertPushToken: alertToken)
    }

    static func fingerprint(_ update: PushTokenUpdate) -> String {
        let text = [update.pushKind?.rawValue ?? "-", update.pushToken ?? "-", update.pushEnv?.rawValue ?? "-", update.alertPushToken ?? "-"].joined(separator: "|")

        return SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    @discardableResult
    public func report() async -> PushTokenReport {
        guard voipTokenKnown else {
            return PushTokenReport()
        }

        let list: [StoredAccount]

        do {
            list = try accounts.accounts()
        } catch {
            logger.error("Accounts could not be read for the push tokens: \(error)")
            return PushTokenReport()
        }

        let update = update()
        let fingerprint = Self.fingerprint(update)
        var known = ledger.fingerprints()
        var result = PushTokenReport()

        // Forget accounts that are gone.
        known = known.filter { id, _ in list.contains { $0.id == id } }

        for account in list {
            let unchanged = known[account.id] == fingerprint && reportedThisLaunch.contains(account.id)

            // Nothing to clear for an account that never had a token.
            if unchanged || (voipToken == nil && known[account.id] == nil) {
                continue
            }

            do {
                try await api.authenticated(with: account.deviceToken).updatePushToken(update)
                // After a "clear" nothing is registered any more: nothing to clear next time either.
                known[account.id] = voipToken == nil ? nil : fingerprint
                reportedThisLaunch.insert(account.id)
                result.sent.append(account.id)
            } catch APIError.unauthorized {
                known[account.id] = nil
                result.revoked.append(account.id)
            } catch {
                logger.notice("Push token of account \(account.id) not sent: \(error)")
                result.failed.append(account.id)
            }
        }

        ledger.setFingerprints(known)

        if !result.sent.isEmpty {
            logger.notice("Push tokens sent for \(result.sent.count) account(s) (\(environment.rawValue))")
        }

        return result
    }
}
