// SPDX-License-Identifier: AGPL-3.0-or-later
//
// DEBUG builds only: a demo of the app without a phone system, for the simulator, screenshots and UI checks.
// Start with the launch argument `-FSVoipDemo YES` (two paired example extensions) or `-FSVoipDemo onboarding`
// (nothing paired yet). `-FSVoipDemoScreen <dialer|recents|settings|account|incall|incoming|push|pairing|failed|scanner>`
// opens a screen directly. Nothing here talks to a server or a PBX, and nothing is written to the Keychain.

#if DEBUG
import CallController
import Core
import Foundation
import Pairing
import SipEngine
import UI

@MainActor
enum DemoMode {
    static func makeServices() -> FSVoipAppModel? {
        let defaults = UserDefaults.standard

        guard let mode = defaults.string(forKey: "FSVoipDemo") else {
            return nil
        }

        let suite = UserDefaults(suiteName: "nl.fullstackstudio.fsvoip.demo") ?? .standard
        suite.removePersistentDomain(forName: "nl.fullstackstudio.fsvoip.demo")

        let secrets = InMemorySecretStore()
        let accounts = AccountStore(secrets: secrets)
        let recents = RecentCallsStore(defaults: suite)
        let preferences = InMemoryPreferencesStore()
        let withAccounts = mode != "onboarding"

        if withAccounts {
            for account in exampleAccounts {
                try? accounts.save(account)
            }

            for call in exampleRecents {
                recents.add(call)
            }
        }

        let engine = DemoSipEngine()
        let phone = PhoneController(engine: engine, system: ImmediateCallSystem(), audioRouting: MemoryAudioRouting(), preferences: preferences)
        let model = FSVoipAppModel(
            phone: phone,
            accountStore: accounts,
            service: DemoAccountService(accounts: accounts),
            preferences: preferences,
            recentsStore: recents,
            device: { DeviceDescriptor(model: "Simulator", osVersion: nil, appVersion: "demo", installId: "d3m0d3m0d3m0d3m0") },
            requestMicrophone: { true },
            pbx: PbxHub(service: DemoPbxService(adminAccountId: exampleAccounts[0].id), gate: LocalAccessGate(authenticator: DemoLocalAuth()))
        )

        open(defaults.string(forKey: "FSVoipDemoScreen"), model: model, engine: engine)

        return model
    }

    private static func open(_ screen: String?, model: FSVoipAppModel, engine: DemoSipEngine) {
        let link = URL(string: "https://fullstackstudio.nl/fsvoip/pair?t=fss_vpair_DEMOdemoDEMOdemoDEMOdemoDEMOdemoDEMOdemoDEM")!

        switch screen {
        case "recents":
            model.selectedTab = .recents
        case "settings":
            model.selectedTab = .settings
        case "scanner":
            model.isScannerPresented = true
        case "pairing":
            model.handleIncoming(url: link)
        case "failed":
            DemoAccountService.failNextPair = true
            model.handleIncoming(url: link)
            Task { await model.confirmPairing() }
        case "incall":
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                model.call("0612345678", from: exampleAccounts[0].id)
            }
        case "incoming":
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                engine.simulateIncoming(from: "0701234567", name: "Bakkerij Smit", account: exampleAccounts[1].id)
            }
        case "push":
            // The background path: a VoIP push reports the call first, the INVITE (with X-FSS-Call) follows.
            let callRef = UUID().uuidString.lowercased()
            let ring = RingPush(
                callRef: callRef,
                from: PushCaller(number: "+31701234567", name: "Bakkerij Smit"),
                accountId: exampleAccounts[1].id,
                accountLabel: exampleAccounts[1].displayLabel,
                expiresAt: Date().addingTimeInterval(12)
            )

            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                model.phone.handleVoipPush(.ring(ring))
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                engine.simulateIncoming(from: "0701234567", name: nil, account: exampleAccounts[1].id, fssCallRef: callRef)
            }
        default:
            break
        }
    }

    nonisolated static let exampleAccounts: [StoredAccount] = [
        demoAccount(id: "demo-1", label: "JEST Bouw · Jan de Vries", alias: "Jan (balie)", pbx: "JEST Bouw", extensionName: "Jan de Vries", number: "102", customer: "JEST Bouw B.V.", domain: "jest-bouw.powervoip.nl", transport: .tls, offset: 0),
        demoAccount(id: "demo-2", label: "Voorbeeld Installatie · Werkplaats", alias: nil, pbx: "Voorbeeld Installatie", extensionName: "Werkplaats", number: "201", customer: "Voorbeeld Installatie", domain: "voorbeeld.powervoip.nl", transport: .tcp, offset: 60),
    ]

    nonisolated private static func demoAccount(id: String, label: String, alias: String?, pbx: String, extensionName: String, number: String, customer: String, domain: String, transport: ServerTransport, offset: TimeInterval) -> StoredAccount {
        StoredAccount(
            id: id,
            label: alias ?? label,
            labelOverride: alias,
            pbxName: pbx,
            extensionName: extensionName,
            extensionNumber: number,
            customerName: customer,
            deviceToken: Secret("demo"),
            installId: "d3m0d3m0d3m0d3m0",
            sip: SIPCredentials(username: number, password: Secret("demo"), domain: domain, proxy: "sip.powervoip.nl", port: transport == .tls ? 5061 : 5060, transport: transport, srv: true),
            pairedAt: Date(timeIntervalSince1970: 1_791_400_000 + offset)
        )
    }

    private static var exampleRecents: [RecentCall] {
        let now = Date()

        return [
            RecentCall(number: "0612345678", name: nil, accountId: "demo-2", accountLabel: "Voorbeeld Installatie · Werkplaats", direction: .outgoing, outcome: .notAnswered, startedAt: now.addingTimeInterval(-86_400 * 2), duration: 0),
            RecentCall(number: "103", name: "Werkplaats", accountId: "demo-1", accountLabel: "Jan (balie)", direction: .outgoing, outcome: .answered, startedAt: now.addingTimeInterval(-86_400 - 3_000), duration: 74),
            RecentCall(number: "0201234567", name: nil, accountId: "demo-1", accountLabel: "Jan (balie)", direction: .incoming, outcome: .missed, startedAt: now.addingTimeInterval(-9_000), duration: 0),
            RecentCall(number: "0701234567", name: "Bakkerij Smit", accountId: "demo-1", accountLabel: "Jan (balie)", direction: .incoming, outcome: .answered, startedAt: now.addingTimeInterval(-2_400), duration: 312),
        ]
    }
}

/// Pretends to be the FullStack Studio API.
final class DemoAccountService: AccountServicing, @unchecked Sendable {
    nonisolated(unsafe) static var failNextPair = false
    private let accounts: AccountStore

    init(accounts: AccountStore) {
        self.accounts = accounts
    }

    func pair(_ link: PairingLink, device: DeviceDescriptor) async throws -> StoredAccount {
        try await Task.sleep(nanoseconds: 900_000_000)

        if Self.failNextPair {
            Self.failNextPair = false
            throw APIError.notFound
        }

        var account = DemoMode.exampleAccounts[0]
        account.id = "demo-\(UUID().uuidString.prefix(6))"
        account.pairedAt = Date()
        try accounts.save(account)

        return account
    }

    func refresh(_ account: StoredAccount) async throws -> AccountRefreshResult {
        .updated(account, internalContacts: [InternalContact(number: "100", name: "Receptie"), InternalContact(number: "103", name: "Werkplaats")])
    }

    func rename(_ account: StoredAccount, alias: String?) async throws -> StoredAccount {
        var updated = account
        updated.labelOverride = AccountService.cleanAlias(alias)
        updated.label = updated.labelOverride ?? "\(account.pbxName) · \(account.extensionName)"
        try accounts.save(updated)

        return updated
    }

    func unpair(_ account: StoredAccount) async throws {
        try accounts.remove(id: account.id)
    }

    func forget(_ account: StoredAccount) throws {
        try accounts.remove(id: account.id)
    }
}

/// A SIP engine that registers instantly and lets calls ring for two seconds before they connect.
final class DemoSipEngine: SipEngine {
    weak var delegate: SipEngineDelegate?

    private final class Audio: SipAudioControl {
        func configure() {}
        func activate(_ active: Bool) {}
    }

    let audio: SipAudioControl = Audio()
    private var registered: [SipAccountID: SipAccountConfig] = [:]
    private var live: [CallID: CallInfo] = [:]

    func start() throws {}
    func stop() {}
    func enterBackground() {}
    func enterForeground() {}
    func refreshRegistrations() {}

    func register(_ account: SipAccountConfig) throws {
        registered[account.id] = account
        report(.registering, account.id)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in self?.report(.registered, account.id) }
    }

    func unregister(_ account: SipAccountID) {
        registered[account] = nil
    }

    func registrationState(of account: SipAccountID) -> RegistrationState {
        registered[account] == nil ? .unregistered : .registered
    }

    func setRegistrationEnabled(_ enabled: Bool, for account: SipAccountID) {}
    func refreshRegistration(of account: SipAccountID) {}

    func call(number: String, from account: SipAccountID) throws -> CallID {
        let id = CallID()
        live[id] = CallInfo(id: id, direction: .outgoing, accountId: account, remoteNumber: number, remoteName: nil, state: .outgoingInitiated)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in self?.change(id, .outgoingRinging) }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.change(id, .active) }

        return id
    }

    func simulateIncoming(from number: String, name: String?, account: String, fssCallRef: String? = nil) {
        let id = CallID()
        live[id] = CallInfo(id: id, direction: .incoming, accountId: SipAccountID(account), remoteNumber: number, remoteName: name, state: .incomingRinging, fssCallRef: fssCallRef)
        delegate?.sipEngine(self, didReceiveIncomingCall: IncomingCall(id: id, from: number, displayName: name, accountId: SipAccountID(account), fssCallRef: fssCallRef))
    }

    func answer(_ call: CallID) throws {
        change(call, .connecting)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.change(call, .active) }
    }

    func decline(_ call: CallID, reason: DeclineReason) throws {
        change(call, .ended(.declined))
    }

    func hangup(_ call: CallID) throws {
        change(call, .ended(.localHangup))
    }

    func setHold(_ call: CallID, onHold: Bool) throws {
        change(call, onHold ? .held : .active)
    }

    func setMuted(_ muted: Bool) {}

    func sendDTMF(_ digit: DTMFDigit, on call: CallID) throws {}

    func transfer(_ call: CallID, to number: String) throws {}

    func calls() -> [CallInfo] {
        Array(live.values)
    }

    private func report(_ state: RegistrationState, _ account: SipAccountID) {
        guard registered[account] != nil else {
            return
        }

        delegate?.sipEngine(self, registrationChanged: state, for: account)
    }

    private func change(_ id: CallID, _ state: CallState) {
        guard var info = live[id] else {
            return
        }

        info.state = state
        live[id] = state.isEnded ? nil : info
        delegate?.sipEngine(self, callChanged: info)
    }
}
#endif
