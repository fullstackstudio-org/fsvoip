// SPDX-License-Identifier: AGPL-3.0-or-later
import CallController
import Core
import Foundation
import Pairing
import SipEngine
import XCTest
@testable import UI

private final class RefreshingAccounts: AccountServicing, @unchecked Sendable {
    let store: AccountStore

    init(store: AccountStore) {
        self.store = store
    }

    func pair(_ link: PairingLink, device: DeviceDescriptor) async throws -> StoredAccount { throw APIError.notFound }
    func refresh(_ account: StoredAccount) async throws -> AccountRefreshResult { .updated(account, internalContacts: []) }
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

    func testOnlyAnAdminSeesTheSection() async {
        let hub = PbxHub(service: service, gate: gate)
        let acc = account("a")

        XCTAssertFalse(hub.isAvailable("a"), "unknown until the server says admin")

        service.meResult = PbxFixtures.meUser
        await hub.refreshAccess(for: [acc])
        XCTAssertEqual(hub.access(for: "a"), .hidden)

        service.meResult = PbxFixtures.me
        await hub.refreshAccess(for: [acc])
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
        let acc = account("a")
        service.meResult = PbxFixtures.me
        await hub.refreshAccess(for: [acc])
        _ = await gate.ensureUnlocked(reason: "t")
        XCTAssertTrue(gate.isUnlocked)

        // The portal took the role away: `/me` now says `user` (after a `refresh` push).
        service.meResult = PbxFixtures.meUser
        await hub.refreshAccess(for: [acc])

        XCTAssertEqual(hub.access(for: "a"), .hidden)
        XCTAssertEqual(hub.lostAccessFor, "a")
        XCTAssertFalse(gate.isUnlocked, "the check is closed with it")
    }

    func testOfflineKeepsTheLastAnswer() async {
        let hub = PbxHub(service: service, gate: gate)
        let acc = account("a")
        await hub.refreshAccess(for: [acc])
        XCTAssertTrue(hub.isAvailable("a"))

        service.failures["me"] = [APIError.transport("offline")]
        await hub.refreshAccess(for: [acc])
        XCTAssertTrue(hub.isAvailable("a"))
    }

    func testAForbiddenOrUnauthorizedMeHides() async {
        let hub = PbxHub(service: service, gate: gate)
        let acc = account("a")
        await hub.refreshAccess(for: [acc])

        service.failures["me"] = [APIError.forbidden]
        await hub.refreshAccess(for: [acc])
        XCTAssertFalse(hub.isAvailable("a"))
    }

    func testAnAccountThatIsGoneIsForgotten() async {
        let hub = PbxHub(service: service, gate: gate)
        await hub.refreshAccess(for: [account("a"), account("b")])
        XCTAssertTrue(hub.isAvailable("b"))

        await hub.refreshAccess(for: [account("a")])
        XCTAssertEqual(hub.access(for: "b"), .unknown)
    }

    // MARK: Through the app model

    func testAppModelAsksTheHubAfterRefreshingItsAccounts() async throws {
        let store = AccountStore(secrets: InMemorySecretStore())
        try store.save(account("a"))
        let preferences = InMemoryPreferencesStore()
        let phone = PhoneController(engine: NullSipEngine(), system: ImmediateCallSystem(), audioRouting: MemoryAudioRouting(), preferences: preferences, endedLinger: 0)
        let hub = PbxHub(service: service, gate: gate)
        let model = FSVoipAppModel(
            phone: phone,
            accountStore: store,
            service: RefreshingAccounts(store: store),
            preferences: preferences,
            recentsStore: RecentCallsStore(defaults: UserDefaults(suiteName: "fsvoip.pbxtests.\(UUID().uuidString)")!),
            device: { DeviceDescriptor(model: "iPhone17,1", osVersion: "26.0", appVersion: "0.1.0 (1)", installId: "a1b2c3d4e5f60718") },
            requestMicrophone: { true },
            pbx: hub
        )

        // A plain user: the section does not exist.
        service.meResult = PbxFixtures.meUser
        await model.refreshAccounts()
        XCTAssertFalse(hub.isAvailable("a"))

        // Made admin in the portal and a `refresh` push arrives: it appears.
        service.meResult = PbxFixtures.me
        model.handleNotification(payload: ["fsvoip": ["v": 1, "type": "refresh", "accountId": "a"]])
        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertTrue(hub.isAvailable("a"))

        // Role taken away: gone, with a notice.
        service.meResult = PbxFixtures.meUser
        await model.refreshAccounts()
        XCTAssertFalse(hub.isAvailable("a"))
        XCTAssertNotNil(model.notice)
        XCTAssertEqual(model.notice?.isError, true)
    }
}
