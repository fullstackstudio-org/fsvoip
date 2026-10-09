// SPDX-License-Identifier: AGPL-3.0-or-later
import CallController
import Core
import Foundation
import Pairing
import SipEngine
import XCTest
@testable import UI

/// An account service whose `GET /me` answer (or failure) the test sets.
private final class RefreshingAccounts: AccountServicing, @unchecked Sendable {
    let store: AccountStore
    private let lock = NSLock()
    private var _me: MeResponse = PbxFixtures.me
    private var _failure: Error?
    private var _requests = 0

    init(store: AccountStore) {
        self.store = store
    }

    var me: MeResponse {
        get { lock.withLock { _me } }
        set { lock.withLock { _me = newValue } }
    }

    var failure: Error? {
        get { lock.withLock { _failure } }
        set { lock.withLock { _failure = newValue } }
    }

    var requests: Int { lock.withLock { _requests } }

    func pair(_ link: PairingLink, device: DeviceDescriptor) async throws -> StoredAccount { throw APIError.notFound }

    func refresh(_ account: StoredAccount) async throws -> AccountRefreshResult {
        let (me, failure) = lock.withLock { () -> (MeResponse, Error?) in
            _requests += 1
            return (_me, _failure)
        }

        if let failure {
            throw failure
        }

        return .updated(account, internalContacts: [], me: me)
    }

    func rename(_ account: StoredAccount, alias: String?) async throws -> StoredAccount { account }
    func unpair(_ account: StoredAccount) async throws { try store.remove(id: account.id) }
    func forget(_ account: StoredAccount) throws { try store.remove(id: account.id) }
}

@MainActor
final class PbxHubTests: XCTestCase {
    private var service: FakePbxService!
    private var gate: LocalAccessGate!

    override func setUp() async throws {
        service = FakePbxService()
        gate = LocalAccessGate(authenticator: FakeLocalAuth())
    }

    private func account(_ id: String) -> StoredAccount {
        StoredAccount(id: id, label: "Jan", pbxName: "Voorbeeld Bouw", extensionName: "Jan", extensionNumber: "102", customerName: "Voorbeeld", deviceToken: Secret("fss_vapp_x"), installId: "a1b2c3d4e5f60718", sip: SIPCredentials(username: "102", password: Secret("pw"), domain: "x.powervoip.nl", proxy: "sip.powervoip.nl", port: 5061, transport: .tls, srv: true), pairedAt: Date(timeIntervalSince1970: 1_790_000_000))
    }

    private func makeModel(accounts ids: [String]) throws -> (model: FSVoipAppModel, hub: PbxHub, accounts: RefreshingAccounts) {
        let store = AccountStore(secrets: InMemorySecretStore())

        for id in ids {
            try store.save(account(id))
        }

        let preferences = InMemoryPreferencesStore()
        let phone = PhoneController(engine: NullSipEngine(), system: ImmediateCallSystem(), audioRouting: MemoryAudioRouting(), preferences: preferences, endedLinger: 0)
        let hub = PbxHub(service: service, gate: gate)
        let accounts = RefreshingAccounts(store: store)
        let model = FSVoipAppModel(
            phone: phone,
            accountStore: store,
            service: accounts,
            preferences: preferences,
            recentsStore: RecentCallsStore(defaults: UserDefaults(suiteName: "fsvoip.pbxtests.\(UUID().uuidString)")!),
            device: { DeviceDescriptor(model: "iPhone17,1", osVersion: "26.0", appVersion: "0.1.0 (1)", installId: "a1b2c3d4e5f60718") },
            requestMicrophone: { true },
            pbx: hub
        )

        return (model, hub, accounts)
    }

    func testOnlyAnAdminSeesTheSection() {
        let hub = PbxHub(service: service, gate: gate)

        XCTAssertFalse(hub.isAvailable("a"), "unknown until the server says admin")

        hub.apply(me: PbxFixtures.meUser, accountId: "a")
        XCTAssertEqual(hub.access(for: "a"), .hidden)

        hub.apply(me: PbxFixtures.me, accountId: "a")
        XCTAssertEqual(hub.access(for: "a"), .available(readOnly: false))
    }

    func testAnAdminWithoutTheCapabilityDoesNotSeeIt() {
        let hub = PbxHub(service: service, gate: gate)
        var me = PbxFixtures.me
        me.capabilities?.pbxManage = false

        hub.apply(me: me, accountId: "a")
        XCTAssertFalse(hub.isAvailable("a"))
    }

    func testAnOldServerWithoutARoleFailsClosed() {
        let hub = PbxHub(service: service, gate: gate)
        var me = PbxFixtures.me
        me.role = nil
        me.capabilities = nil

        hub.apply(me: me, accountId: "a")
        XCTAssertFalse(hub.isAvailable("a"))
    }

    func testAFrozenCentraleIsAvailableButReadOnly() {
        let hub = PbxHub(service: service, gate: gate)
        hub.apply(me: PbxFixtures.meFrozen, accountId: "a")

        XCTAssertEqual(hub.access(for: "a"), .available(readOnly: true))
    }

    func testARevokedRoleHidesTheSectionAndSaysSo() async {
        let hub = PbxHub(service: service, gate: gate)
        hub.apply(me: PbxFixtures.me, accountId: "a")
        _ = await gate.ensureUnlocked(reason: "t")
        XCTAssertTrue(gate.isUnlocked)

        // The portal took the role away: `/me` now says `user` (after a `refresh` push).
        hub.apply(me: PbxFixtures.meUser, accountId: "a")

        XCTAssertEqual(hub.access(for: "a"), .hidden)
        XCTAssertEqual(hub.lostAccessFor, "a")
        XCTAssertFalse(gate.isUnlocked, "the check is closed with it")
    }

    func testAForbiddenAnswerHidesTheSection() {
        let hub = PbxHub(service: service, gate: gate)
        hub.apply(me: PbxFixtures.me, accountId: "a")

        hub.accessDenied(accountId: "a")
        XCTAssertFalse(hub.isAvailable("a"))
        XCTAssertEqual(hub.lostAccessFor, "a")
    }

    // MARK: Through the app model (one `GET /me` per account per refresh)

    func testAppModelGivesTheOneMeToTheHubAfterRefreshingItsAccounts() async throws {
        let (model, hub, accounts) = try makeModel(accounts: ["a"])

        // A plain user: the section does not exist.
        accounts.me = PbxFixtures.meUser
        await model.refreshAccounts()
        XCTAssertFalse(hub.isAvailable("a"))
        XCTAssertEqual(accounts.requests, 1, "one /me per refresh")

        // Made admin in the portal and a `refresh` push arrives: it appears.
        accounts.me = PbxFixtures.me
        model.handleNotification(payload: ["fsvoip": ["v": 1, "type": "refresh", "accountId": "a"]])
        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertTrue(hub.isAvailable("a"))

        // Role taken away: gone, with a notice.
        accounts.me = PbxFixtures.meUser
        await model.refreshAccounts()
        XCTAssertFalse(hub.isAvailable("a"))
        XCTAssertNotNil(model.notice)
        XCTAssertEqual(model.notice?.isError, true)
    }

    func testOfflineKeepsTheLastAnswer() async throws {
        let (model, hub, accounts) = try makeModel(accounts: ["a"])
        await model.refreshAccounts()
        XCTAssertTrue(hub.isAvailable("a"))

        accounts.failure = APIError.transport("offline")
        await model.refreshAccounts()
        XCTAssertTrue(hub.isAvailable("a"))
    }

    func testAForbiddenMeHidesThroughTheAppModel() async throws {
        let (model, hub, accounts) = try makeModel(accounts: ["a"])
        await model.refreshAccounts()
        XCTAssertTrue(hub.isAvailable("a"))

        accounts.failure = APIError.forbidden
        await model.refreshAccounts()
        XCTAssertFalse(hub.isAvailable("a"))
    }

    func testAnUnpairedAccountIsForgotten() async throws {
        let (model, hub, _) = try makeModel(accounts: ["a", "b"])
        await model.refreshAccounts()
        XCTAssertTrue(hub.isAvailable("b"))

        _ = await model.unpair(accountId: "b")
        XCTAssertEqual(hub.access(for: "b"), .unknown)
    }
}

/// The settings sheet: "Beheer" exists for an admin only.
@MainActor
final class SettingsOutlineTests: XCTestCase {
    private func outline(me: MeResponse, service: FakePbxService = FakePbxService()) async throws -> SettingsOutline {
        let store = AccountStore(secrets: InMemorySecretStore())
        let account = StoredAccount(id: "a", label: "Jan", pbxName: "Voorbeeld Bouw", extensionName: "Jan", extensionNumber: "102", customerName: "Voorbeeld", deviceToken: Secret("fss_vapp_x"), installId: "a1b2c3d4e5f60718", sip: SIPCredentials(username: "102", password: Secret("pw"), domain: "x.powervoip.nl", proxy: "sip.powervoip.nl", port: 5061, transport: .tls, srv: true), pairedAt: Date(timeIntervalSince1970: 1_790_000_000))
        try store.save(account)

        let preferences = InMemoryPreferencesStore()
        let accounts = RefreshingAccounts(store: store)
        accounts.me = me
        let model = FSVoipAppModel(
            phone: PhoneController(engine: NullSipEngine(), system: ImmediateCallSystem(), audioRouting: MemoryAudioRouting(), preferences: preferences, endedLinger: 0),
            accountStore: store,
            service: accounts,
            preferences: preferences,
            recentsStore: RecentCallsStore(defaults: UserDefaults(suiteName: "fsvoip.settingstests.\(UUID().uuidString)")!),
            device: { DeviceDescriptor(model: "iPhone17,1", osVersion: "26.0", appVersion: "0.1.0 (1)", installId: "a1b2c3d4e5f60718") },
            requestMicrophone: { true },
            pbx: PbxHub(service: service, gate: LocalAccessGate(authenticator: FakeLocalAuth()))
        )
        await model.refreshAccounts()

        return SettingsOutline(model: model, accountId: "a")
    }

    func testAUserSeesNoManagement() async throws {
        let outline = try await outline(me: PbxFixtures.meUser)

        XCTAssertFalse(outline.showsAdmin)
        XCTAssertTrue(outline.admin.isEmpty)
    }

    func testAnAdminSeesNumbersDevicesAndRingGroups() async throws {
        let outline = try await outline(me: PbxFixtures.me)

        XCTAssertTrue(outline.showsAdmin)
        XCTAssertTrue(outline.admin.starts(with: [.numbers, .devices, .ringGroups]))
    }

    func testAnOldServerWithoutARoleShowsNoManagement() async throws {
        var me = PbxFixtures.me
        me.role = nil
        me.capabilities = nil

        let outline = try await outline(me: me)

        XCTAssertFalse(outline.showsAdmin)
    }

    func testStartRequestsOpenTheRightPage() {
        XCTAssertEqual(SettingsOutline.pages(for: .root), [])
        XCTAssertEqual(SettingsOutline.pages(for: .centrale), [.centrale(.overview)])
        XCTAssertEqual(SettingsOutline.pages(for: .recordings), [.recordings])
    }
}
