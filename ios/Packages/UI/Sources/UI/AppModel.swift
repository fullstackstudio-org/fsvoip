// SPDX-License-Identifier: AGPL-3.0-or-later
import CallController
import Combine
import Core
import Foundation
import Pairing
import SipEngine
import SwiftUI

/// State of the app: paired accounts, the pairing flow, settings and recents. Calls and registrations live in
/// `phone` (CallController); the screens observe both.
@MainActor
public final class FSVoipAppModel: ObservableObject {
    /// The pairing flow, shown on top of whatever screen is open.
    public enum PairingPhase: Equatable {
        case idle
        /// A link arrived (QR scan, universal link or `fsvoip://`); nothing was claimed yet.
        case linkReceived(PairingLink)
        case pairing(PairingLink)
        case paired(StoredAccount)
        /// `link` is kept when trying again makes sense (the code was not consumed).
        case failed(PairingLink?, PairingFailure)

        public var isActive: Bool {
            self != .idle
        }
    }

    public enum Tab: Hashable {
        case dialer
        case recents
        case settings
    }

    public struct Notice: Identifiable, Equatable {
        public let id = UUID()
        public let message: String
        public let isError: Bool
    }

    public enum UnpairResult: Equatable {
        case done
        /// The server could not be reached: the user may remove the account from this phone only.
        case failed(PairingFailure)
    }

    @Published public private(set) var pairing: PairingPhase = .idle
    @Published public var isScannerPresented = false
    /// Error shown inside the scanner sheet (not a link we know).
    @Published public private(set) var scannerError: String?
    @Published public private(set) var accounts: [StoredAccount] = []
    @Published public private(set) var recents: [RecentCall] = []
    /// Internal contacts per account id (from `GET /me`).
    @Published public private(set) var internalContacts: [String: [InternalContact]] = [:]
    @Published public var notice: Notice?
    @Published public var selectedTab: Tab = .dialer
    /// Bumped whenever a per-account setting changes, so the settings screens redraw.
    @Published public private(set) var settingsRevision = 0

    public let phone: PhoneController
    /// The "Centrale" section of admin pairings (`nil` = not offered, e.g. in a build without it).
    public let pbx: PbxHub?

    private let accountStore: AccountStore
    private let service: AccountServicing
    private let preferences: PreferencesStore
    private let recentsStore: RecentCallsStore
    private let device: () -> DeviceDescriptor
    private let requestMicrophone: () async -> Bool
    private let pushTokens: PushTokenReporting?
    private let requestNotifications: () async -> Void
    private let logger: FSLogger
    private var phoneChanges: AnyCancellable?
    private var pbxAccessLost: AnyCancellable?

    public init(
        phone: PhoneController,
        accountStore: AccountStore,
        service: AccountServicing,
        preferences: PreferencesStore,
        recentsStore: RecentCallsStore,
        device: @escaping () -> DeviceDescriptor,
        requestMicrophone: @escaping () async -> Bool = { await MicrophonePermission.request() },
        pushTokens: PushTokenReporting? = nil,
        requestNotifications: @escaping () async -> Void = {},
        pbx: PbxHub? = nil,
        logger: FSLogger = FSLogger(category: "app")
    ) {
        self.phone = phone
        self.pbx = pbx
        self.accountStore = accountStore
        self.service = service
        self.preferences = preferences
        self.recentsStore = recentsStore
        self.device = device
        self.requestMicrophone = requestMicrophone
        self.pushTokens = pushTokens
        self.requestNotifications = requestNotifications
        self.logger = logger

        phone.anonymousCallerText = L10n.string("call.anonymous")
        phone.lookupName = { [weak self] number in self?.name(forNumber: number) }
        phone.onCallFinished = { [weak self] call in
            self?.recentsStore.add(call)
            self?.recents = self?.recentsStore.all() ?? []
        }
        pbx?.onRevoked = { [weak self] in
            Task { await self?.refreshAccounts() }
        }
        pbxAccessLost = pbx?.$lostAccessFor.compactMap { $0 }.sink { [weak self] accountId in
            guard let self else { return }
            self.pbx?.lostAccessFor = nil

            if let account = account(id: accountId) {
                notice = Notice(message: String(format: L10n.string("pbx.notice.lostAccess"), account.pbxName), isError: true)
            }
        }
        // Republish the phone's changes so screens that only watch the app model still redraw.
        phoneChanges = phone.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }

        reloadAccounts()
        recents = recentsStore.all()
    }

    // MARK: Accounts

    public func reloadAccounts() {
        do {
            accounts = try accountStore.accounts()
        } catch {
            logger.error("Accounts could not be read: \(error)")
            accounts = []
        }

        phone.sync(accounts: accounts)
    }

    public func account(id: String) -> StoredAccount? {
        accounts.first { $0.id == id }
    }

    public func registration(for accountId: String) -> RegistrationState {
        phone.registrationState(for: accountId)
    }

    /// `GET /me` for every account: labels, SIP server, internal contacts. Revoked accounts are removed.
    public func refreshAccounts() async {
        for account in accounts {
            do {
                switch try await service.refresh(account) {
                case let .updated(updated, contacts):
                    internalContacts[updated.id] = contacts
                case .revoked:
                    internalContacts[account.id] = nil
                    cleanUp(accountId: account.id)
                    notice = Notice(message: String(format: L10n.string("notice.revoked"), account.displayLabel), isError: true)
                }
            } catch {
                // Offline or a server hiccup: keep what we have, try again next time.
                logger.notice("Refresh of account \(account.id) failed: \(error)")
            }
        }

        reloadAccounts()
        // Which accounts may manage the centrale is decided by their role at this moment (a role that was taken away
        // hides the section right away).
        await pbx?.refreshAccess(for: accounts)
    }

    public func rename(accountId: String, alias: String?) async -> Bool {
        guard let account = account(id: accountId) else {
            return false
        }

        do {
            _ = try await service.rename(account, alias: alias)
            reloadAccounts()
            return true
        } catch {
            handleAccountError(error, account: account)
            return false
        }
    }

    public func unpair(accountId: String) async -> UnpairResult {
        guard let account = account(id: accountId) else {
            return .done
        }

        do {
            try await service.unpair(account)
            cleanUp(accountId: accountId)
            reloadAccounts()
            notice = Notice(message: String(format: L10n.string("notice.unpaired"), account.displayLabel), isError: false)
            return .done
        } catch {
            return .failed(PairingFailure(error))
        }
    }

    /// Remove from this phone only (when unpairing on the server did not work).
    public func forget(accountId: String) {
        guard let account = account(id: accountId) else {
            return
        }

        do {
            try service.forget(account)
        } catch {
            logger.error("Account could not be removed: \(error)")
        }

        cleanUp(accountId: accountId)
        reloadAccounts()
    }

    // MARK: Settings

    /// Whether the incoming call screen shows the dialled account for this account (the value in effect).
    public func showsCalledAccount(_ accountId: String) -> Bool {
        CallDisplay.shouldShowAccount(setting: preferences.preferences(for: accountId).showCalledAccount, accountCount: accounts.count)
    }

    public func setShowsCalledAccount(_ accountId: String, _ show: Bool) {
        var value = preferences.preferences(for: accountId)
        value.showCalledAccount = show
        preferences.setPreferences(value, for: accountId)
        settingsRevision += 1
    }

    /// The account outgoing calls use unless the user picks another one.
    public var defaultOutgoingAccountId: String? {
        if let stored = preferences.defaultOutgoingAccountId, accounts.contains(where: { $0.id == stored }) {
            return stored
        }

        return accounts.first?.id
    }

    public func setDefaultOutgoing(_ accountId: String) {
        preferences.defaultOutgoingAccountId = accountId
        settingsRevision += 1
    }

    // MARK: Calls

    /// Start a call. Returns `false` (with a notice) when it could not start.
    @discardableResult
    public func call(_ number: String, from accountId: String?) -> Bool {
        guard let accountId = accountId ?? defaultOutgoingAccountId else {
            notice = Notice(message: L10n.string("call.error.noAccount"), isError: true)
            return false
        }

        do {
            try phone.startCall(number: number, accountId: accountId)
            return true
        } catch let error as PhoneError {
            notice = Notice(message: Self.message(for: error), isError: true)
            return false
        } catch {
            notice = Notice(message: L10n.string("error.generic"), isError: true)
            return false
        }
    }

    public func clearRecents() {
        recentsStore.clear()
        recents = []
    }

    /// Name of an internal contact (the other extensions of the same PBX) for a number.
    public func name(forNumber number: String) -> String? {
        for contacts in internalContacts.values {
            if let match = contacts.first(where: { $0.number == number }) {
                return match.name
            }
        }

        return nil
    }

    // MARK: Lifecycle

    public func didBecomeActive() {
        phone.enterForeground()
        Task {
            await refreshAccounts()
            await reportPushTokens()
        }
    }

    public func didEnterBackground() {
        phone.enterBackground()
    }

    // MARK: Push

    /// A push token changed (or arrived for the first time in this launch): tell the server, per account.
    public func pushTokensChanged() {
        Task { await reportPushTokens() }
    }

    public func reportPushTokens() async {
        guard let pushTokens else {
            return
        }

        let report = await pushTokens.report()

        // A 401 means the pairing is gone on the server: `GET /me` removes the account here.
        if !report.revoked.isEmpty {
            await refreshAccounts()
        }
    }

    /// A regular (alert) push: "unpaired" removes the account, "refresh" re-reads the account. VoIP pushes (calls) go
    /// to `phone.handleVoipPush`, never here.
    public func handleNotification(payload: [AnyHashable: Any]) {
        let message: PushMessage

        do {
            message = try PushMessage.decode(apnsDictionary: payload)
        } catch {
            logger.notice("Notification without a readable payload: \(error)")
            return
        }

        switch message {
        case let .revoked(revoked):
            removeRevoked(accountId: revoked.accountId)
        case .refresh:
            Task { await refreshAccounts() }
        case .ring:
            logger.notice("A ring message arrived as a regular notification: ignored")
        }
    }

    /// The server says this pairing was removed (portal, admin, or another phone took over the extension).
    public func removeRevoked(accountId: String) {
        guard let account = account(id: accountId) else {
            return
        }

        do {
            try service.forget(account)
        } catch {
            logger.error("Revoked account could not be removed: \(error)")
        }

        cleanUp(accountId: accountId)
        reloadAccounts()
        notice = Notice(message: String(format: L10n.string("notice.revoked"), account.displayLabel), isError: true)
    }

    // MARK: Pairing links

    /// An `onOpenURL` / universal link / `NSUserActivity` URL.
    public func handleIncoming(url: URL) {
        do {
            let link = try PairingLinkParser.parse(url)
            show(link)
        } catch {
            logger.notice("Pairing link rejected")
            notice = Notice(message: Self.message(for: error), isError: true)
        }
    }

    /// Text from the QR scanner or the paste field. Returns `true` when it was a pairing link.
    @discardableResult
    public func handleScanned(_ text: String) -> Bool {
        do {
            let link = try PairingLinkParser.parse(scanned: text)
            isScannerPresented = false
            scannerError = nil
            show(link)
            return true
        } catch {
            scannerError = Self.message(for: error)
            return false
        }
    }

    public func clearScannerError() {
        scannerError = nil
    }

    /// Exchange the received link for an account.
    public func confirmPairing() async {
        let link: PairingLink

        switch pairing {
        case let .linkReceived(received):
            link = received
        case let .failed(received?, failure) where failure.isRetryable:
            link = received
        default:
            return
        }

        pairing = .pairing(link)

        do {
            let account = try await service.pair(link, device: device())
            reloadAccounts()
            pairing = .paired(account)
            // The new account needs this phone's push tokens before it can ring with the app closed.
            await reportPushTokens()
            // Calls need the microphone; ask now, not in the middle of the first call.
            _ = await requestMicrophone()
            // Notifications are only used to tell that a phone was unpaired: ask once there is an account.
            await requestNotifications()
        } catch {
            let failure = PairingFailure(error)
            logger.notice("Pairing failed: \(failure)")
            pairing = .failed(failure.isRetryable ? link : nil, failure)
        }
    }

    /// Close the pairing flow (after success, failure or cancel).
    public func closePairing() {
        if case .pairing = pairing {
            return
        }

        if case .paired = pairing {
            selectedTab = .dialer
        }

        pairing = .idle
    }

    /// After a failed pairing: back to the scanner for a new code.
    public func restartScan() {
        if case .pairing = pairing {
            return
        }

        pairing = .idle
        scannerError = nil
        isScannerPresented = true
    }

    private func show(_ link: PairingLink) {
        // Never log the link: the token is a one-time credential.
        logger.notice("Pairing link received")

        if case .pairing = pairing {
            return
        }

        pairing = .linkReceived(link)
    }

    // MARK: Helpers

    private func cleanUp(accountId: String) {
        pbx?.forget(accountId: accountId)
        preferences.removePreferences(for: accountId)
        internalContacts[accountId] = nil
        settingsRevision += 1
    }

    private func handleAccountError(_ error: Error, account: StoredAccount) {
        let failure = PairingFailure(error)

        if failure == .revoked {
            try? service.forget(account)
            cleanUp(accountId: account.id)
            reloadAccounts()
            notice = Notice(message: String(format: L10n.string("notice.revoked"), account.displayLabel), isError: true)
        } else {
            notice = Notice(message: Self.message(for: failure), isError: true)
        }
    }

    static func message(for error: Error) -> String {
        switch error {
        case let error as PairingLinkError:
            switch error {
            case .notAPairingLink: return L10n.string("error.notAPairingLink")
            case .missingToken: return L10n.string("error.missingToken")
            case .malformedToken: return L10n.string("error.malformedToken")
            }
        case let error as PhoneError:
            return message(for: error)
        case let failure as PairingFailure:
            return message(for: failure)
        default:
            return message(for: PairingFailure(error))
        }
    }

    static func message(for error: PhoneError) -> String {
        switch error {
        case .invalidNumber: return L10n.string("call.error.invalidNumber")
        case .unknownAccount: return L10n.string("call.error.noAccount")
        case .lineNotConnected: return L10n.string("call.error.notConnected")
        case .callInProgress: return L10n.string("call.error.inProgress")
        case .systemRefused, .engine: return L10n.string("call.error.failed")
        }
    }

    static func message(for failure: PairingFailure) -> String {
        switch failure {
        case .codeExpiredOrUsed: return L10n.string("pairing.error.expired")
        case .tooManyAttempts: return L10n.string("pairing.error.tooMany")
        case .temporarilyUnavailable: return L10n.string("pairing.error.unavailable")
        case .network: return L10n.string("pairing.error.network")
        case .revoked: return L10n.string("pairing.error.revoked")
        case .storage: return L10n.string("pairing.error.storage")
        case .other: return L10n.string("error.generic")
        }
    }
}

