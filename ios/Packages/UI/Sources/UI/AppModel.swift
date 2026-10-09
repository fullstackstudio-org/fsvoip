// SPDX-License-Identifier: AGPL-3.0-or-later
import CallController
import Combine
import Core
import FSContacts
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

    /// The five tabs. Settings is a sheet (`isSettingsPresented`), not a tab.
    public enum Tab: Hashable, CaseIterable {
        case dialer
        case onHold
        /// "Geschiedenis".
        case recents
        case voicemail
        case contacts
    }

    /// Where the settings sheet opens.
    public enum SettingsStart: Equatable {
        case root
        /// The "Centrale" section of the first admin account (demo screens and links).
        case centrale
        case recordings
        /// "Geluiden" of the first admin account (demo screens).
        case sounds
        case appearance
        /// "Profiel", "Oproepvoorkeuren" and "Gebruiker uitnodigen" of the first account (demo screens).
        case profile
        case callPreferences
        case invite
        /// Single parts of the Centrale (demo screens).
        case numbers
        case devices
        case ringGroups
        case hours
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
    @Published public var isSettingsPresented = false
    @Published public var settingsStart: SettingsStart = .root
    /// What `GET /me` said each pairing may do (role and capabilities), from the last refresh. Not stored on the phone: after a restart
    /// nothing is offered until the server has answered (fail closed).
    @Published public private(set) var meByAccount: [String: MeResponse] = [:]
    /// Bumped whenever a per-account setting changes, so the settings screens redraw.
    @Published public private(set) var settingsRevision = 0

    public let phone: PhoneController
    /// The customer's address books, the phone's contacts and the colleagues; answers "who is this number".
    public let contacts: ContactsHub
    /// The "Centrale" section of admin pairings (`nil` = not offered, e.g. in a build without it).
    public let pbx: PbxHub?
    /// Voicemail and recordings with their player (`nil` = not offered).
    public let media: MediaHub?
    /// Parking and the "On hold" tab; `nil` = not offered.
    public let park: ParkModel?
    /// Do-not-disturb of the own extension ("Beschikbaar"); `nil` = not offered.
    public let availability: AvailabilityHub?
    /// The own extension (e-mail, forwarding, voicemail) and inviting a colleague. Never `nil`: without a service it simply has nothing.
    public let selfExtension: SelfExtensionHub
    /// "Uitbellen via": the number the next call goes out with.
    public let outbound: OutboundChoiceModel
    /// Calls of the PBX (the team history) per account, for "Geschiedenis".
    let history: HistoryModel

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
    private var contactsChanges: AnyCancellable?
    private var contactsConfiguration: Task<Void, Never>?
    private var contactsTimer: Task<Void, Never>?
    private var pbxAccessLost: AnyCancellable?
    private var mediaAccessLost: AnyCancellable?
    private var callActivity: AnyCancellable?
    private var outboundChanges: AnyCancellable?
    private var selfExtensionChanges: AnyCancellable?
    private var parkChanges: AnyCancellable?
    @Published private var chosenParkAccountId: String?

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
        contacts: ContactsHub? = nil,
        pbx: PbxHub? = nil,
        media: MediaHub? = nil,
        availability: AvailabilityHub? = nil,
        selfExtension: SelfExtensionHub? = nil,
        park: ParkServicing? = nil,
        outboundNumbers: OutboundNumbersServicing? = nil,
        logger: FSLogger = FSLogger(category: "app")
    ) {
        self.phone = phone
        self.contacts = contacts ?? ContactsHub()
        self.pbx = pbx
        self.media = media
        self.availability = availability
        self.selfExtension = selfExtension ?? .unavailable
        self.park = park.map { ParkModel(service: $0) }
        outbound = OutboundChoiceModel(service: outboundNumbers, preferences: preferences)
        history = HistoryModel(media: media)
        self.accountStore = accountStore
        self.service = service
        self.preferences = preferences
        self.recentsStore = recentsStore
        self.device = device
        self.requestMicrophone = requestMicrophone
        self.pushTokens = pushTokens
        self.requestNotifications = requestNotifications
        self.logger = logger

        outbound.capabilities = { [weak self] accountId in self?.capabilities(for: accountId) }
        parkChanges = self.park?.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        outboundChanges = outbound.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        history.nameLookup = { [weak self] number in self?.name(forNumber: number) }
        phone.anonymousCallerText = L10n.string("call.anonymous")
        phone.lookupName = { [weak self] number in self?.name(forNumber: number) }
        phone.onCallFinished = { [weak self] call in
            self?.recentsStore.add(call)
            self?.recents = self?.recentsStore.all() ?? []
            self?.park?.callFinished(call, account: self?.account(id: call.accountId))
        }
        self.park?.dial = { [weak self] number, accountId in
            self?.call(number, from: accountId, useChosenNumber: false) ?? false
        }
        self.park?.notify = { [weak self] message, isError in
            self?.notice = Notice(message: message, isError: isError)
        }
        self.park?.isAdmin = { [weak self] accountId in
            self?.meByAccount[accountId]?.effectiveRole == .admin
        }
        self.park?.onRevoked = { [weak self] in
            Task { await self?.refreshAccounts() }
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
        media?.onRevoked = { [weak self] in
            Task { await self?.refreshAccounts() }
        }
        self.selfExtension.onRevoked = { [weak self] in
            Task { await self?.refreshAccounts() }
        }
        selfExtensionChanges = self.selfExtension.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        media?.nameLookup = { [weak self] number in self?.name(forNumber: number) }
        mediaAccessLost = media?.$lostAccessFor.compactMap { $0 }.sink { [weak self] accountId in
            guard let self else { return }
            self.media?.lostAccessFor = nil

            if let account = account(id: accountId) {
                notice = Notice(message: String(format: L10n.string("media.notice.lostAccess"), account.pbxName), isError: true)
            }
        }
        // A call (or a ringing call) takes the audio: voicemail and recordings pause and do not start.
        callActivity = phone.$sessions
            .map { sessions in sessions.contains { !$0.phase.isEnded } }
            .removeDuplicates()
            .sink { [weak self] active in self?.media?.callStateChanged(isActive: active) }
        // Republish the phone's changes so screens that only watch the app model still redraw.
        phoneChanges = phone.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        contactsChanges = self.contacts.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        // A 401 on the contacts means the pairing is gone: `GET /me` removes the account here (and with it its contacts).
        self.contacts.onUnauthorized = { [weak self] _ in
            Task { await self?.refreshAccounts() }
        }

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
        configureContacts()
    }

    /// Tell the contacts which accounts exist. Runs one after the other, and a sync waits for it.
    private func configureContacts() {
        let ids = accounts.map(\.id)
        let previous = contactsConfiguration
        let hub = contacts

        contactsConfiguration = Task {
            await previous?.value
            await hub.configure(accountIds: ids)
        }
    }

    /// Sync the address books of all accounts. `force`: also when one was synced a moment ago (after a refresh push, on pull to refresh).
    public func syncContacts(force: Bool = false) async {
        await contactsConfiguration?.value
        await contacts.syncAll(force: force)
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
                case let .updated(updated, contacts, me):
                    internalContacts[updated.id] = contacts
                    self.contacts.setInternalContacts(contacts, accountId: updated.id)

                    // The one `GET /me` of this refresh feeds the contacts and the "Centrale" section alike.
                    if let me {
                        meByAccount[updated.id] = me
                        self.contacts.apply(capabilities: me.capabilities?.contacts ?? ContactCapabilities(), accountId: updated.id)
                        pbx?.apply(me: me, accountId: updated.id)
                        media?.apply(me: me, accountId: updated.id)
                        await outbound.load(updated)
                    }
                case .revoked:
                    internalContacts[account.id] = nil
                    cleanUp(accountId: account.id)
                    notice = Notice(message: String(format: L10n.string("notice.revoked"), account.displayLabel), isError: true)
                }
            } catch APIError.forbidden {
                // The role that allowed the section is gone: hide it right away.
                pbx?.accessDenied(accountId: account.id)
                media?.accessDenied(accountId: account.id)
                logger.notice("Refresh of account \(account.id) was refused (403)")
            } catch {
                // Offline or a server hiccup: keep what we have, try again next time.
                logger.notice("Refresh of account \(account.id) failed: \(error)")
            }
        }

        reloadAccounts()
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

    // MARK: Roles and capabilities

    /// What this pairing may do. `nil` until the server has answered.
    public func capabilities(for accountId: String) -> AppCapabilities? {
        meByAccount[accountId]?.capabilities
    }

    /// Fail closed: only an `admin` the server confirmed.
    public func isAdmin(_ accountId: String) -> Bool {
        meByAccount[accountId]?.effectiveRole == .admin
    }

    /// Show "Beheer" for this account: the admin role AND the `pbxManage` capability (what the "Centrale" hub concluded from the same
    /// `GET /me`; it also hides at once on a 403).
    public func canManagePbx(_ accountId: String) -> Bool {
        (pbx?.isAvailable(accountId) ?? false) && (meByAccount[accountId]?.canManagePbx ?? false)
    }

    public func canManageSounds(_ accountId: String) -> Bool {
        canManagePbx(accountId) && capabilities(for: accountId)?.sounds == .manage
    }

    public func canInvite(_ accountId: String) -> Bool {
        canManagePbx(accountId) && (capabilities(for: accountId)?.invite ?? false)
    }

    /// Does any pairing offer parking ("On hold")?
    public var canPark: Bool {
        meByAccount.values.contains { $0.canPark }
    }

    /// Does this pairing offer parking (the park button in a call, the "On hold" tab)?
    public func canPark(_ accountId: String) -> Bool {
        park != nil && (meByAccount[accountId]?.canPark ?? false)
    }

    /// The accounts that can park, in the order of the accounts.
    var parkAccounts: [StoredAccount] {
        accounts.filter { canPark($0.id) }
    }

    /// The account the "On hold" tab shows: the one the user chose, else the default outgoing one if it can park, else the first.
    var parkAccount: StoredAccount? {
        let candidates = parkAccounts

        if let chosenParkAccountId, let chosen = candidates.first(where: { $0.id == chosenParkAccountId }) {
            return chosen
        }

        if let preferred = defaultOutgoingAccountId, let account = candidates.first(where: { $0.id == preferred }) {
            return account
        }

        return candidates.first
    }

    func chooseParkAccount(_ accountId: String) {
        chosenParkAccountId = accountId
    }

    /// Parks the running call of `session` and says what happened. The PBX ends our leg of the call after a successful park; this
    /// never shows that as a failure.
    public func parkCall(_ session: CallSession) async {
        guard let park, canPark(session.accountId.rawValue), let account = account(id: session.accountId.rawValue) else {
            return
        }

        // The live SIP Call-ID, never the engine's own id (for an outgoing call that can be a UUID the PBX does not know).
        guard let callId = phone.sipCallID(for: session), !callId.isEmpty else {
            notice = Notice(message: ParkFailure.callNotFound.message, isError: true)

            return
        }

        switch await park.park(callId: callId, account: account) {
        case let .parked(slot):
            notice = Notice(message: String(format: L10n.string("park.notice.parked"), ParkFormat.slot(slot)), isError: false)
        case let .failed(failure):
            notice = Notice(message: failure.message, isError: true)
        case .ignored:
            break
        }
    }

    public func openSettings(_ start: SettingsStart = .root) {
        settingsStart = start
        isSettingsPresented = true
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
    public func call(_ number: String, from accountId: String?, useChosenNumber: Bool = true) -> Bool {
        guard let accountId = accountId ?? defaultOutgoingAccountId else {
            notice = Notice(message: L10n.string("call.error.noAccount"), isError: true)
            return false
        }

        do {
            // The chosen number rides along with this one call; without the capability the options are empty.
            try phone.startCall(number: number, accountId: accountId, options: useChosenNumber ? outbound.options(for: accountId) : .none)
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

    /// The name for a number: the customer's address book, then the phone's contacts, then the colleagues of the PBX.
    public func name(forNumber number: String) -> String? {
        if let name = contacts.name(forNumber: number) {
            return name
        }

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
            await syncContacts()
            await contacts.refreshDeviceContacts()
        }
        startContactsTimer()
    }

    public func didEnterBackground() {
        phone.enterBackground()
        // No polling in the background: the address book is read again when the app comes to the front.
        contactsTimer?.cancel()
        contactsTimer = nil
    }

    /// While the app is in front, look for changes every few minutes.
    private func startContactsTimer() {
        contactsTimer?.cancel()
        contactsTimer = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 300 * 1_000_000_000)

                guard !Task.isCancelled else {
                    return
                }

                await self?.syncContacts()
            }
        }
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
            // The portal changed something about this pairing (a role, a name, the address book): read it all again.
            Task {
                await refreshAccounts()
                await syncContacts(force: true)
            }
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
        media?.forget(accountId: accountId)
        preferences.removePreferences(for: accountId)
        internalContacts[accountId] = nil
        meByAccount[accountId] = nil
        availability?.forget(accountId: accountId)
        selfExtension.forget(accountId: accountId)
        outbound.forget(accountId: accountId)
        park?.forget(accountId: accountId)
        history.forget(accountId: accountId)
        // Unpairing removes the address book of that account from this phone.
        contacts.forget(accountId: accountId)
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

