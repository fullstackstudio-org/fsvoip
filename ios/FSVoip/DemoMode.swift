// SPDX-License-Identifier: AGPL-3.0-or-later
//
// DEBUG builds only: a demo of the app without a phone system, for the simulator, screenshots and UI checks.
// Start with the launch argument `-FSVoipDemo YES` (two paired example extensions) or `-FSVoipDemo onboarding`
// (nothing paired yet). `-FSVoipDemoScreen <dialer|onhold|recents|voicemail|contacts|settings|pbx|recordings|sounds|appearance|incall|incoming|push|pairing|failed|scanner>`
// opens a screen directly. `-FSVoipDemoContactsScreen <detail|edit|new|sources|phone|filter>` goes one step further inside the Contacts tab.
// Nothing here talks to a server or a PBX, and nothing is written to the Keychain.

#if DEBUG
import CallController
import Core
import Foundation
import FSContacts
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

        let demoContacts = DemoContactsAPI()
        let gate = LocalAccessGate(authenticator: DemoLocalAuth())
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
            contacts: ContactsHub(store: InMemoryContactsStore(), api: { _ in demoContacts }, settings: InMemoryContactsSettings(), minimumInterval: 0),
            pbx: PbxHub(service: DemoPbxService(adminAccountId: exampleAccounts[0].id), gate: gate),
            media: MediaHub(service: DemoMediaService(adminAccountId: exampleAccounts[0].id), gate: gate, soundService: DemoSoundService()),
            availability: AvailabilityHub(service: DemoAvailabilityService()),
            park: DemoParkService(),
            outboundNumbers: DemoOutboundNumbersService()
        )

        open(defaults.string(forKey: "FSVoipDemoScreen"), model: model, engine: engine)

        return model
    }

    private static func open(_ screen: String?, model: FSVoipAppModel, engine: DemoSipEngine) {
        let link = URL(string: "https://fullstackstudio.nl/fsvoip/pair?t=fss_vpair_DEMOdemoDEMOdemoDEMOdemoDEMOdemoDEMOdemoDEM")!

        switch screen {
        case "recents":
            model.selectedTab = .recents
        case "contacts":
            model.selectedTab = .contacts
        case "onhold":
            model.selectedTab = .onHold
        case "voicemail":
            model.selectedTab = .voicemail
        case "settings":
            model.openSettings()
        case "pbx":
            model.openSettings(.centrale)
        case "recordings":
            model.openSettings(.recordings)
        case "sounds":
            model.openSettings(.sounds)
        case "appearance":
            model.openSettings(.appearance)
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
        .updated(
            account,
            internalContacts: [InternalContact(number: "100", name: "Receptie"), InternalContact(number: "103", name: "Werkplaats")],
            me: DemoPbxService.meResponse(isAdmin: account.id == DemoMode.exampleAccounts[0].id)
        )
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

    func call(number: String, from account: SipAccountID, options: CallOptions) throws -> CallID {
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

/// A small address book in memory, for the demo: reads, adds, changes and deletes like the real one.
final class DemoContactsAPI: ContactsAPI, @unchecked Sendable {
    private let lock = NSLock()
    private var contacts: [StoredContact]
    private var notes: [String: String] = [:]
    private var lists: [(id: String, name: String, members: Set<String>)]
    private var clock = 0

    init() {
        func person(_ id: String, _ name: String, _ company: String?, _ numbers: [String], email: String? = nil) -> StoredContact {
            StoredContact(
                id: id,
                name: name,
                firstName: company == name ? nil : name.split(separator: " ").first.map(String.init),
                lastName: company == name ? nil : name.split(separator: " ").dropFirst().joined(separator: " "),
                company: company,
                email: email,
                phones: numbers.enumerated().map { StoredContactPhone(number: $0.element, label: $0.offset == 0 ? "mobile" : "work", isPrimary: $0.offset == 0) },
                updatedAt: "2026-10-09T09:00:00.000Z"
            )
        }

        contacts = [
            person("c01", "Bakkerij Smit", "Bakkerij Smit", ["+31701234567"], email: "info@bakkerijsmit.nl"),
            person("c02", "Pieter de Groot", "De Groot Installatie", ["+31612345678", "+31201234567"], email: "pieter@degroot-installatie.nl"),
            person("c03", "Anja Bakker", "Gemeente Voorbeeld", ["+31623456789"]),
            person("c04", "Henk van Dijk", nil, ["+31634567890"]),
            person("c05", "Mireille Jansen", "Jansen Advies", ["+31645678901", "+31302345678"], email: "m.jansen@jansenadvies.nl"),
            person("c06", "Karim El Amrani", nil, ["+31656789012"]),
            person("c07", "Sanne Visser", "Visser & Zonen", ["+31667890123"]),
            person("c08", "Willem Mulder", nil, ["+31678901234"]),
            person("c09", "Loodgietersbedrijf De Waal", "Loodgietersbedrijf De Waal", ["+31107654321"]),
            person("c10", "Inge Smeets", "Tandartspraktijk Smeets", ["+31689012345"]),
            person("c11", "Ahmed Yilmaz", nil, ["+31690123456"]),
            person("c12", "Tom Brouwer", "Brouwer Transport", ["+31611223344"]),
            person("c13", "Femke de Vries", nil, ["+31622334455"]),
            person("c14", "Joost Kramer", "Kramer Groenvoorziening", ["+31633445566"]),
        ]
        notes["c02"] = "Heeft de cv-ketel in het magazijn vervangen. Bellen na 16:00 lukt het best."
        lists = [
            ("l-klanten", "Klanten", ["c01", "c05", "c07", "c10"]),
            ("l-leveranciers", "Leveranciers", ["c02", "c09", "c12", "c14"]),
        ]
    }

    func contactCapabilities() async throws -> ContactCapabilities {
        try Self.decode(ContactCapabilities.self, ["read": true, "write": true, "delete": true])
    }

    func syncAddressBook(since: String?) async throws -> ContactsSyncResult {
        try await Task.sleep(nanoseconds: 300_000_000)

        return try lock.withLock {
            let page = since == nil ? contacts : []

            return ContactsSyncResult(contacts: try page.map(contact), deleted: [], serverTime: "2026-10-09T10:00:00.000Z", isFull: since == nil)
        }
    }

    func contact(id: String) async throws -> ContactDetail {
        try lock.withLock { try detail(id) }
    }

    func createContact(_ request: ContactCreate) async throws -> ContactDetail {
        try lock.withLock {
            let body = try Self.object(request)
            let id = "c\(100 + contacts.count)"
            contacts.append(try stored(id: id, from: body))
            notes[id] = body["notes"] as? String
            setMembership(id, body["listIds"] as? [String] ?? [])

            return try detail(id)
        }
    }

    func updateContact(id: String, _ update: ContactUpdate) async throws -> ContactUpdateResponse {
        try lock.withLock {
            guard let position = contacts.firstIndex(where: { $0.id == id }) else { throw APIError.notFound }
            guard contacts[position].updatedAt == update.expectedUpdatedAt else { throw APIError.stale(version: nil) }

            let body = try Self.object(update)
            contacts[position] = try stored(id: id, from: body)
            notes[id] = body["notes"] as? String

            if let ids = body["listIds"] as? [String] { setMembership(id, ids) }

            var object = Self.json(contacts[position])
            object["notes"] = notes[id]
            object["listIds"] = lists.filter { $0.members.contains(id) }.map(\.id)

            return try Self.decode(ContactUpdateResponse.self, ["contact": object, "changed": true])
        }
    }

    func deleteContact(id: String) async throws -> Int {
        lock.withLock {
            contacts.removeAll { $0.id == id }

            return 1
        }
    }

    func contactLists() async throws -> [ContactListInfo] {
        try lock.withLock {
            try lists.map { try Self.decode(ContactListInfo.self, ["id": $0.id, "name": $0.name, "version": 1, "contactCount": $0.members.count]) }
        }
    }

    func contactListSnapshot(listId: String, cursor: String?, etag: String?) async throws -> ContactListSnapshotOutcome {
        try lock.withLock {
            guard let list = lists.first(where: { $0.id == listId }) else { throw APIError.notFound }

            let snapshot = try Self.decode(ContactListSnapshot.self, [
                "list": ["id": list.id, "name": list.name, "version": 1],
                "contacts": list.members.sorted().map { ["id": $0, "name": "", "phones": [[String: Any]]()] },
            ])

            return .snapshot(snapshot, etag: "\"1\"")
        }
    }

    // MARK: Helpers (called with the lock held)

    private func setMembership(_ id: String, _ listIds: [String]) {
        for position in lists.indices {
            if listIds.contains(lists[position].id) { lists[position].members.insert(id) } else { lists[position].members.remove(id) }
        }
    }

    private func stored(id: String, from body: [String: Any]) throws -> StoredContact {
        clock += 1
        let phones = (body["phones"] as? [[String: Any]] ?? []).map { StoredContactPhone(number: $0["number"] as? String ?? "", label: $0["label"] as? String ?? "other", isPrimary: $0["isPrimary"] as? Bool ?? false) }

        return StoredContact(
            id: id,
            name: body["name"] as? String ?? "",
            firstName: body["firstName"] as? String,
            lastName: body["lastName"] as? String,
            company: body["company"] as? String,
            email: body["email"] as? String,
            phones: phones,
            updatedAt: "2026-10-09T10:\(String(format: "%02d", clock)):00.000Z"
        )
    }

    private func contact(_ stored: StoredContact) throws -> Contact {
        try Self.decode(Contact.self, Self.json(stored))
    }

    private func detail(_ id: String) throws -> ContactDetail {
        guard let stored = contacts.first(where: { $0.id == id }) else { throw APIError.notFound }

        var object = Self.json(stored)
        object["notes"] = notes[id]
        object["listIds"] = lists.filter { $0.members.contains(id) }.map(\.id)

        return try Self.decode(ContactDetail.self, object)
    }

    private static func json(_ stored: StoredContact) -> [String: Any] {
        var object: [String: Any] = ["id": stored.id, "name": stored.name, "phones": stored.phones.map { ["number": $0.number, "label": $0.label, "isPrimary": $0.isPrimary] }, "tags": stored.tags, "updatedAt": stored.updatedAt]
        if let value = stored.firstName { object["firstName"] = value }
        if let value = stored.lastName { object["lastName"] = value }
        if let value = stored.company { object["company"] = value }
        if let value = stored.email { object["email"] = value }

        return object
    }

    private static func object<T: Encodable>(_ value: T) throws -> [String: Any] {
        (try JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(value)) as? [String: Any]) ?? [:]
    }

    private static func decode<T: Decodable>(_ type: T.Type, _ object: [String: Any]) throws -> T {
        try FSVoipJSON.decoder().decode(type, from: JSONSerialization.data(withJSONObject: object))
    }
}

/// The numbers of the example phone system: three, so the chooser has something to choose from.
final class DemoOutboundNumbersService: OutboundNumbersServicing, @unchecked Sendable {
    func numbers(for account: StoredAccount) async throws -> OutboundNumbers {
        try await Task.sleep(nanoseconds: 150_000_000)

        return OutboundNumbers(
            numbers: [
                SelfNumber(id: "n1", number: "0850607848", name: "Hoofdnummer", isDefault: true),
                SelfNumber(id: "n2", number: "0850607849", name: "Werkplaats"),
                SelfNumber(id: "n3", number: "0201234567", name: nil),
            ],
            defaultNumber: "0850607848"
        )
    }
}

/// Do not disturb in memory: switching works, nothing leaves the phone.
final class DemoAvailabilityService: AvailabilityServicing, @unchecked Sendable {
    func load(for account: StoredAccount) async throws -> AvailabilityHub.State {
        AvailabilityHub.State(doNotDisturb: false, version: 1)
    }

    func setDoNotDisturb(_ dnd: Bool, version: Int, for account: StoredAccount) async throws -> AvailabilityHub.State {
        AvailabilityHub.State(doNotDisturb: dnd, version: version + 1)
    }
}
#endif
