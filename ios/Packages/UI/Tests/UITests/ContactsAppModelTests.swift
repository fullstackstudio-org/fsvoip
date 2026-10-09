// SPDX-License-Identifier: AGPL-3.0-or-later
import CallController
import Core
import FSContacts
import Pairing
import SipEngine
import XCTest
@testable import UI

private final class ScriptedService: AccountServicing, @unchecked Sendable {
    let store: AccountStore
    var revoked: Set<String> = []

    init(store: AccountStore) { self.store = store }

    func pair(_ link: PairingLink, device: DeviceDescriptor) async throws -> StoredAccount { throw APIError.notFound }

    func refresh(_ account: StoredAccount) async throws -> AccountRefreshResult {
        if revoked.contains(account.id) {
            try store.remove(id: account.id)
            return .revoked
        }

        return .updated(account, internalContacts: [InternalContact(number: "103", name: "Werkplaats")])
    }

    func rename(_ account: StoredAccount, alias: String?) async throws -> StoredAccount { account }
    func unpair(_ account: StoredAccount) async throws { try store.remove(id: account.id) }
    func forget(_ account: StoredAccount) throws { try store.remove(id: account.id) }
}

private final class ScriptedContactsAPI: ContactsAPI, @unchecked Sendable {
    var meError: Error?
    var stored: [Contact] = []
    var delete = false

    static func decode<T: Decodable>(_ type: T.Type, _ object: [String: Any]) -> T {
        // swiftlint:disable:next force_try
        try! FSVoipJSON.decoder().decode(type, from: JSONSerialization.data(withJSONObject: object))
    }

    static func contact(_ id: String, _ name: String, _ number: String) -> Contact {
        decode(Contact.self, ["id": id, "name": name, "phones": [["number": number, "label": "mobile", "isPrimary": true]], "tags": [String](), "updatedAt": "2026-10-09T10:00:00.000Z"])
    }

    func contactCapabilities() async throws -> ContactCapabilities {
        if let meError { throw meError }

        return Self.decode(ContactCapabilities.self, ["read": true, "write": true, "delete": delete])
    }

    func syncAddressBook(since: String?) async throws -> ContactsSyncResult {
        ContactsSyncResult(contacts: stored, deleted: [], serverTime: "s1", isFull: since == nil)
    }

    func contact(id: String) async throws -> ContactDetail { throw APIError.notFound }

    func createContact(_ contact: ContactCreate) async throws -> ContactDetail {
        let body = (try JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(contact)) as? [String: Any]) ?? [:]
        let phones = body["phones"] as? [[String: Any]] ?? []
        var object: [String: Any] = ["id": "new-1", "name": body["name"] as? String ?? "", "phones": phones, "tags": [String](), "updatedAt": "2026-10-09T11:00:00.000Z", "listIds": [String]()]
        object["firstName"] = body["firstName"]

        return Self.decode(ContactDetail.self, object)
    }

    func updateContact(id: String, _ update: ContactUpdate) async throws -> ContactUpdateResponse { throw APIError.notFound }
    func deleteContact(id: String) async throws -> Int { 1 }
    func contactLists() async throws -> [ContactListInfo] { [] }
    func contactListSnapshot(listId: String, cursor: String?, etag: String?) async throws -> ContactListSnapshotOutcome { throw APIError.notFound }
}

@MainActor
final class ContactsAppModelTests: XCTestCase {
    private var accounts: AccountStore!
    private var service: ScriptedService!
    private var system: ImmediateCallSystem!
    private var api: ScriptedContactsAPI!
    private var contactStore: InMemoryContactsStore!
    private var model: FSVoipAppModel!

    override func setUp() async throws {
        accounts = AccountStore(secrets: InMemorySecretStore())
        service = ScriptedService(store: accounts)
        system = ImmediateCallSystem()
        api = ScriptedContactsAPI()
        contactStore = InMemoryContactsStore()

        try accounts.save(account("acc-1"))
        model = makeModel()
    }

    private func account(_ id: String) -> StoredAccount {
        StoredAccount(id: id, label: "Voorbeeld · Jan", pbxName: "Voorbeeld", extensionName: "Jan", extensionNumber: "102", customerName: "Voorbeeld B.V.", deviceToken: Secret("fss_vapp_x"), installId: "a1b2c3d4e5f60718", sip: SIPCredentials(username: "102", password: Secret("pw"), domain: "voorbeeld.powervoip.nl", proxy: "sip.powervoip.nl", port: 5061, transport: .tls, srv: true), pairedAt: Date(timeIntervalSince1970: 1_000))
    }

    private func makeModel() -> FSVoipAppModel {
        let preferences = InMemoryPreferencesStore()
        let phone = PhoneController(engine: NullSipEngine(), system: system, audioRouting: MemoryAudioRouting(), preferences: preferences, endedLinger: 0)
        let api = api!
        let hub = ContactsHub(store: contactStore, api: { _ in api }, settings: InMemoryContactsSettings(), minimumInterval: 0)

        return FSVoipAppModel(
            phone: phone,
            accountStore: accounts,
            service: service,
            preferences: preferences,
            recentsStore: RecentCallsStore(defaults: UserDefaults(suiteName: "fsvoip.uitests.\(UUID().uuidString)")!),
            device: { DeviceDescriptor(model: "iPhone17,1", osVersion: "26.0", appVersion: "0.1.0 (1)", installId: "a1b2c3d4e5f60718") },
            requestMicrophone: { true },
            contacts: hub
        )
    }

    /// The app model configures its contacts in a task of its own; wait for the effect instead of guessing.
    private func eventually(_ message: String = "", timeout: TimeInterval = 3, _ condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)

        while !condition(), Date() < deadline {
            try? await Task.sleep(nanoseconds: 10_000_000)
        }

        XCTAssertTrue(condition(), message)
    }

    private func ring(from number: String) -> RingPush {
        RingPush(callRef: "11111111-2222-4333-8444-555555555555", from: PushCaller(number: number, name: nil), accountId: "acc-1", accountLabel: "Voorbeeld · Jan", expiresAt: Date().addingTimeInterval(12))
    }

    func testAContactAddedInTheAppNamesTheNextIncomingCall() async throws {
        await model.syncContacts()

        var draft = ContactDraft()
        draft.firstName = "Pieter"
        draft.lastName = "de Groot"
        draft.phones[0].number = "06 12 34 56 78"
        try await model.contacts.create(draft, accountId: "acc-1")
        await model.contacts.settle()

        XCTAssertEqual(model.contacts.entries.map(\.displayName), ["Pieter de Groot"], "visible in the list")

        // The call comes in through the push path, with a number but no name from the server.
        model.phone.handleVoipPush(.ring(ring(from: "+31612345678")))

        guard case let .reportedIncoming(_, handle, displayName)? = system.events.first else {
            return XCTFail("the call was not reported")
        }

        XCTAssertEqual(handle, "+31612345678")
        XCTAssertEqual(displayName, "Pieter de Groot")
    }

    func testTheAddressBookComesInOnSyncAndNamesBothDirections() async throws {
        api.stored = [ScriptedContactsAPI.contact("c1", "Bakkerij Smit", "+31701234567")]

        await model.syncContacts()
        await model.contacts.settle()

        XCTAssertEqual(model.name(forNumber: "0701234567"), "Bakkerij Smit", "outgoing: the number as typed")
        XCTAssertEqual(model.name(forNumber: "+31701234567"), "Bakkerij Smit", "incoming: the number as the server sends it")
        XCTAssertEqual(model.phone.lookupName("0031701234567"), "Bakkerij Smit", "the phone controller uses the same lookup")
    }

    func testTheAddressBookWinsOverColleaguesAndColleaguesStillResolve() async throws {
        api.stored = [ScriptedContactsAPI.contact("c1", "Jan Receptie", "+31701234567")]
        await model.refreshAccounts()
        await model.syncContacts()
        await model.contacts.settle()

        XCTAssertEqual(model.name(forNumber: "103"), "Werkplaats")
        XCTAssertEqual(model.name(forNumber: "0701234567"), "Jan Receptie")
        XCTAssertNil(model.name(forNumber: "112"))
    }

    func testAContactsRefreshPushReadsTheAddressBookAgain() async throws {
        await model.syncContacts()
        XCTAssertTrue(model.contacts.entries.isEmpty)

        api.stored = [ScriptedContactsAPI.contact("c1", "Anna", "+31611111111")]
        model.handleNotification(payload: ["fsvoip": ["v": 1, "type": "refresh", "accountId": "acc-1"]])

        await eventually("the refresh push starts a sync") { model.contacts.name(forNumber: "0611111111") != nil }
    }

    func testUnpairingDeletesTheContactsOfThatAccountOnly() async throws {
        try accounts.save(account("acc-2"))
        model.reloadAccounts()
        api.stored = [ScriptedContactsAPI.contact("c1", "Anna", "+31611111111")]
        await model.syncContacts(force: true)
        await model.contacts.settle()
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertNotNil(contactStore.load(accountId: "acc-1"))
        XCTAssertNotNil(contactStore.load(accountId: "acc-2"))

        _ = await model.unpair(accountId: "acc-1")
        try await Task.sleep(nanoseconds: 50_000_000)

        XCTAssertNil(contactStore.load(accountId: "acc-1"), "unpairing wipes that address book from the phone")
        XCTAssertNotNil(contactStore.load(accountId: "acc-2"))
    }

    func testARevokedPairingFoundByTheContactsSyncRemovesTheAccount() async throws {
        api.meError = APIError.unauthorized
        service.revoked = ["acc-1"]

        await model.syncContacts()

        await eventually("the account is removed") { model.accounts.isEmpty }
        XCTAssertTrue(model.notice?.isError ?? false)
    }

    func testTheContactsTabExists() {
        XCTAssertNotEqual(FSVoipAppModel.Tab.contacts, .recents)
        XCTAssertEqual(ContactFailure.countText(1), L10n.string("contacts.count.one"))
    }

    func testFailuresAreExplainedInPlainLanguage() {
        XCTAssertEqual(ContactFailure.message(for: APIError.invalid(code: "invalid_phone", field: "phones")), L10n.string("contacts.error.phone"))
        XCTAssertEqual(ContactFailure.message(for: APIError.forbidden), L10n.string("contacts.error.forbidden"))
        XCTAssertEqual(ContactFailure.message(for: APIError.transport("x")), L10n.string("contacts.error.network"))
        XCTAssertEqual(ContactFailure.message(for: APIError.conflict(code: "limit_reached")), L10n.string("contacts.error.limit"))
        XCTAssertEqual(ContactFailure.message(for: APIError.rateLimited(retryAfterSeconds: nil)), L10n.string("contacts.error.rate"))
        XCTAssertEqual(ContactFailure.message(for: APIError.unexpectedStatus(500)), L10n.string("error.generic"))
    }
}
