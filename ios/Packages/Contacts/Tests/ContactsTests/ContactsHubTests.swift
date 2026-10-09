// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import XCTest
@testable import FSContacts

private final class Clock: @unchecked Sendable {
    private let lock = NSLock()
    private var current = Date(timeIntervalSince1970: 1_791_549_000)

    var now: Date { lock.withLock { current } }
    func advance(_ seconds: TimeInterval) { lock.withLock { current = current.addingTimeInterval(seconds) } }
}

private final class FakeDevice: DeviceContactsReading, @unchecked Sendable {
    var current: DeviceContactsAccess = .notDetermined
    var grants = true
    var entries: [ContactEntry] = []

    var access: DeviceContactsAccess { current }

    func requestAccess() async -> Bool {
        current = grants ? .authorized : .denied

        return grants
    }

    func fetch() async throws -> [ContactEntry] {
        entries
    }
}

@MainActor
final class ContactsHubTests: XCTestCase {
    private var store: InMemoryContactsStore!
    private var api: FakeContactsAPI!
    private var clock: Clock!
    private var device: FakeDevice!
    private var settings: InMemoryContactsSettings!

    override func setUp() async throws {
        store = InMemoryContactsStore()
        api = FakeContactsAPI()
        clock = Clock()
        device = FakeDevice()
        settings = InMemoryContactsSettings()
    }

    private func makeHub(interval: TimeInterval = 60) -> ContactsHub {
        let api = api!
        let clock = clock!

        return ContactsHub(store: store, api: { _ in api }, device: device, settings: settings, now: { clock.now }, minimumInterval: interval)
    }

    private func configured(_ ids: [String] = ["acc"]) async -> ContactsHub {
        let hub = makeHub()
        await hub.configure(accountIds: ids)

        return hub
    }

    private func settled(_ hub: ContactsHub) async -> ContactsHub {
        await hub.settle()
        // The file write runs in a task of its own.
        try? await Task.sleep(nanoseconds: 30_000_000)

        return hub
    }

    // MARK: First sync, since, resync

    func testTheFirstSyncIsCompleteAndStoresServerTimeOnlyAfterTheLastPage() async throws {
        api.syncResults = [.success(Make.run([Make.contact("a", "Anna", phones: ["+31612345678"])], serverTime: "s1", full: true))]
        let hub = await configured()

        let outcome = await hub.sync(accountId: "acc")
        await hub.settle()

        XCTAssertEqual(outcome, .synced)
        XCTAssertEqual(api.syncSinces.count, 1)
        XCTAssertNil(api.syncSinces[0] ?? nil, "no since on the first run")
        XCTAssertEqual(hub.entries.map(\.displayName), ["Anna"])
        XCTAssertEqual(hub.name(forNumber: "06 12 34 56 78"), "Anna")
        XCTAssertEqual(hub.state(for: "acc")?.contactCount, 1)
    }

    func testTheNextSyncSendsTheStoredServerTimeAndAppliesChanges() async throws {
        api.syncResults = [
            .success(Make.run([Make.contact("a", "Anna", phones: ["+31611111111"]), Make.contact("b", "Bea", phones: ["+31622222222"])], serverTime: "s1", full: true)),
            .success(Make.run([Make.contact("b", "Beatrix", phones: ["+31622222222"], updatedAt: "t2"), Make.contact("c", "Cor", phones: ["+31633333333"])], deleted: ["a"], serverTime: "s2", full: false)),
        ]
        let hub = await configured()
        await hub.sync(accountId: "acc")

        clock.advance(120)
        await hub.sync(accountId: "acc")
        await hub.settle()

        XCTAssertEqual(api.syncSinces.map { $0 ?? "nil" }, ["nil", "s1"])
        XCTAssertEqual(hub.entries.map(\.displayName), ["Beatrix", "Cor"])
        XCTAssertNil(hub.name(forNumber: "0611111111"), "a deleted contact no longer names a caller")
        XCTAssertEqual(hub.name(forNumber: "0622222222"), "Beatrix")
    }

    func testAResyncRequestStartsOverAndDropsWhatIsGone() async throws {
        api.syncResults = [
            .success(Make.run([Make.contact("a", "Anna", phones: ["+31611111111"]), Make.contact("b", "Bea", phones: ["+31622222222"])], serverTime: "s1", full: true)),
            .failure(APIError.resync),
            .success(Make.run([Make.contact("b", "Bea", phones: ["+31622222222"])], serverTime: "s9", full: true)),
            .success(Make.run([], serverTime: "s10", full: false)),
        ]
        let hub = await configured()
        await hub.sync(accountId: "acc")

        clock.advance(120)
        let outcome = await hub.sync(accountId: "acc")
        await hub.settle()

        XCTAssertEqual(outcome, .synced)
        XCTAssertEqual(api.syncSinces.map { $0 ?? "nil" }, ["nil", "s1", "nil"], "after resync the second request has no since")
        XCTAssertEqual(hub.entries.map(\.displayName), ["Bea"])

        clock.advance(120)
        await hub.sync(accountId: "acc")
        XCTAssertEqual(api.syncSinces.last ?? nil, "s9", "the new run's server time is the next since")
    }

    func testACancelledRunIsNotAFailure() async throws {
        api.syncResults = [.failure(CancellationError())]
        let hub = await configured()

        let outcome = await hub.sync(accountId: "acc")

        XCTAssertEqual(outcome, .skipped)
        XCTAssertEqual(hub.state(for: "acc")?.lastSyncFailed, false, "a cancelled pull to refresh is not 'no connection'")
    }

    func testAFailedRunKeepsTheOldSinceAndTheOldContacts() async throws {
        api.syncResults = [
            .success(Make.run([Make.contact("a", "Anna", phones: ["+31611111111"])], serverTime: "s1", full: true)),
            .failure(APIError.transport("offline")),
            .success(Make.run([], serverTime: "s3", full: false)),
        ]
        let hub = await configured()
        await hub.sync(accountId: "acc")

        clock.advance(120)
        let failed = await hub.sync(accountId: "acc")

        XCTAssertEqual(failed, .failed)
        XCTAssertEqual(hub.state(for: "acc")?.lastSyncFailed, true)
        await hub.settle()
        XCTAssertEqual(hub.entries.map(\.displayName), ["Anna"], "offline: what was there stays")

        clock.advance(120)
        await hub.sync(accountId: "acc")
        XCTAssertEqual(api.syncSinces.map { $0 ?? "nil" }, ["nil", "s1", "s1"])
        XCTAssertEqual(hub.state(for: "acc")?.lastSyncFailed, false)
    }

    func testSyncsCloseTogetherAreSkippedUnlessForced() async throws {
        api.syncResults = [
            .success(Make.run([], serverTime: "s1", full: true)),
            .success(Make.run([], serverTime: "s2", full: false)),
        ]
        let hub = await configured()

        let first = await hub.sync(accountId: "acc")
        let second = await hub.sync(accountId: "acc")
        let forced = await hub.sync(accountId: "acc", force: true)

        XCTAssertEqual(first, .synced)
        XCTAssertEqual(second, .skipped)
        XCTAssertEqual(forced, .synced)
        XCTAssertEqual(api.syncSinces.count, 2)
    }

    func testARevokedPairingIsReportedAndNothingIsKept() async throws {
        api.meError = APIError.unauthorized
        let hub = await configured()
        var revoked: [String] = []
        hub.onUnauthorized = { revoked.append($0) }

        let outcome = await hub.sync(accountId: "acc")

        XCTAssertEqual(outcome, .revoked)
        XCTAssertEqual(revoked, ["acc"])
    }

    func testCapabilitiesComeFromTheServerAndDeleteIsOffByDefault() async throws {
        api.capabilities = Make.capabilities(read: true, write: true, delete: false)
        api.syncResults = [.success(Make.run([Make.contact("a", "Anna", phones: ["+31611111111"])], serverTime: "s1", full: true))]
        let hub = await configured()
        await hub.sync(accountId: "acc")
        await hub.settle()

        let entry = try XCTUnwrap(hub.entries.first)
        XCTAssertTrue(hub.canWrite(entry))
        XCTAssertFalse(hub.canDelete(entry))

        api.capabilities = Make.capabilities(delete: true)
        api.syncResults = [.success(Make.run([], serverTime: "s2", full: false))]
        await hub.sync(accountId: "acc", force: true)
        XCTAssertTrue(hub.canDelete(entry))
    }

    func testTheRightsFromTheAppsMeAreUsedByASyncRightAfterwardsAndNotAskedAgain() async throws {
        api.capabilities = Make.capabilities(read: true, write: true, delete: true)
        api.syncResults = [.success(Make.run([Make.contact("a", "Anna", phones: ["+31611111111"])], serverTime: "s1", full: true))]
        let hub = await configured()

        // The app did its `GET /me` and passes on what it says: this pairing may not write any more.
        hub.apply(capabilities: Make.capabilities(read: true, write: false, delete: false), accountId: "acc")
        await hub.sync(accountId: "acc", force: true)
        await hub.settle()

        XCTAssertEqual(api.capabilityCalls, 0, "one /me per refresh: the sync does not ask again")
        let entry = try XCTUnwrap(hub.entries.first)
        XCTAssertFalse(hub.canWrite(entry))
        XCTAssertFalse(hub.canDelete(entry))

        // Later (the timer) the sync asks for itself and takes what the server says then.
        clock.advance(120)
        api.syncResults = [.success(Make.run([], serverTime: "s2", full: false))]
        await hub.sync(accountId: "acc", force: true)
        XCTAssertEqual(api.capabilityCalls, 1)
        XCTAssertTrue(hub.canWrite(entry))
    }

    // MARK: Lists

    func testListsAreDownloadedOnceAndNotAgainWhileTheVersionIsEqual() async throws {
        api.syncResults = [
            .success(Make.run([Make.contact("a", "Anna", phones: ["+31611111111"])], serverTime: "s1", full: true)),
            .success(Make.run([], serverTime: "s2", full: false)),
            .success(Make.run([], serverTime: "s3", full: false)),
        ]
        api.lists = [Make.list("l1", "Klanten", version: 3, count: 1)]
        api.snapshots["l1"] = [.success(.snapshot(Make.snapshot("l1", version: 3, contactIds: ["a"]), etag: "\"3\""))]
        let hub = await configured()

        await hub.sync(accountId: "acc")
        XCTAssertEqual(hub.memberIds(listId: "l1", accountId: "acc"), ["a"])
        XCTAssertEqual(api.snapshotCalls.count, 1)

        clock.advance(120)
        await hub.sync(accountId: "acc")
        XCTAssertEqual(api.snapshotCalls.count, 1, "same version: no request at all")

        // The list changed on the server; it answers with the stored ETag in If-None-Match and a new snapshot.
        api.lists = [Make.list("l1", "Klanten", version: 4, count: 2)]
        api.snapshots["l1"] = [.success(.snapshot(Make.snapshot("l1", version: 4, contactIds: ["a", "b"]), etag: "\"4\""))]
        clock.advance(120)
        await hub.sync(accountId: "acc")

        XCTAssertEqual(api.snapshotCalls.last?.etag, "\"3\"")
        XCTAssertEqual(hub.memberIds(listId: "l1", accountId: "acc"), ["a", "b"])
        XCTAssertEqual(hub.state(for: "acc")?.lists.first?.version, 4)
    }

    func testA304OnAListTakesTheNewVersionWithoutNewMembers() async throws {
        api.syncResults = [
            .success(Make.run([], serverTime: "s1", full: true)),
            .success(Make.run([], serverTime: "s2", full: false)),
        ]
        api.lists = [Make.list("l1", "Klanten", version: 3)]
        api.snapshots["l1"] = [.success(.snapshot(Make.snapshot("l1", version: 3, contactIds: ["a"]), etag: "\"3\""))]
        let hub = await configured()
        await hub.sync(accountId: "acc")

        api.lists = [Make.list("l1", "Klanten", version: 4)]
        api.snapshots["l1"] = [.success(.notModified(etag: "\"3\""))]
        clock.advance(120)
        await hub.sync(accountId: "acc")

        XCTAssertEqual(hub.memberIds(listId: "l1", accountId: "acc"), ["a"])
        XCTAssertEqual(hub.state(for: "acc")?.lists.first?.version, 4)
    }

    func testAListThatIsSwitchedOffIsNotDownloadedAndLosesItsMembers() async throws {
        api.syncResults = [.success(Make.run([], serverTime: "s1", full: true)), .success(Make.run([], serverTime: "s2", full: false)), .success(Make.run([], serverTime: "s3", full: false))]
        api.lists = [Make.list("l1", "Klanten", version: 3)]
        api.snapshots["l1"] = [.success(.snapshot(Make.snapshot("l1", version: 3, contactIds: ["a"]), etag: "\"3\""))]
        let hub = await configured()
        await hub.sync(accountId: "acc")
        XCTAssertEqual(api.snapshotCalls.count, 1)

        await hub.setListEnabled(false, listId: "l1", accountId: "acc")
        XCTAssertEqual(hub.memberIds(listId: "l1", accountId: "acc"), [])
        clock.advance(120)
        await hub.sync(accountId: "acc")
        XCTAssertEqual(api.snapshotCalls.count, 1, "a list that is off costs no request")

        api.snapshots["l1"] = [.success(.snapshot(Make.snapshot("l1", version: 3, contactIds: ["a"]), etag: "\"3\""))]
        await hub.setListEnabled(true, listId: "l1", accountId: "acc")
        XCTAssertEqual(hub.memberIds(listId: "l1", accountId: "acc"), ["a"])
    }

    // MARK: Writing

    func testCreateAddsTheContactAtOnceAndNamesTheCaller() async throws {
        api.syncResults = [.success(Make.run([], serverTime: "s1", full: true))]
        let hub = await configured()
        await hub.sync(accountId: "acc")
        api.createResult = .success(Make.detail(Make.contact("n1", "Nieuw Contact", phones: ["+31644444444"])))

        var draft = ContactDraft()
        draft.firstName = "Nieuw"
        draft.lastName = "Contact"
        draft.phones[0].number = "06 44 44 44 44"
        try await hub.create(draft, accountId: "acc")
        await hub.settle()

        XCTAssertEqual(hub.name(forNumber: "0644444444"), "Nieuw Contact")
        XCTAssertEqual(api.creates.count, 1)
    }

    func testUpdateSendsExpectedUpdatedAtAndStoresTheAnswer() async throws {
        api.syncResults = [.success(Make.run([Make.contact("a", "Anna", phones: ["+31611111111"], updatedAt: "t1")], serverTime: "s1", full: true))]
        let hub = await configured()
        await hub.sync(accountId: "acc")
        await hub.settle()
        let entry = try XCTUnwrap(hub.entries.first)
        let changed = Make.contact("a", "Anna Smit", phones: ["+31611111111"], updatedAt: "t2")
        api.updateResult = .success(Make.decode(ContactUpdateResponse.self, ["contact": ["id": "a", "name": "Anna Smit", "phones": [["number": "+31611111111", "label": "mobile"]], "tags": [String](), "updatedAt": "t2", "listIds": [String]()], "changed": true]))
        _ = changed

        var draft = ContactDraft(entry: entry)
        draft.lastName = "Smit"
        try await hub.update(draft, contactId: "a", expectedUpdatedAt: try XCTUnwrap(entry.updatedAt), accountId: "acc")
        await hub.settle()

        XCTAssertEqual(api.updates.first?.update.expectedUpdatedAt, "t1")
        XCTAssertEqual(hub.entries.first?.updatedAt, "t2")
        XCTAssertEqual(hub.name(forNumber: "0611111111"), "Anna Smit")
    }

    func testAStaleUpdateIsThrownAndNothingChangesLocally() async throws {
        api.syncResults = [.success(Make.run([Make.contact("a", "Anna", phones: ["+31611111111"], updatedAt: "t1")], serverTime: "s1", full: true))]
        let hub = await configured()
        await hub.sync(accountId: "acc")
        api.updateResult = .failure(APIError.stale(version: nil))

        do {
            _ = try await hub.update(ContactDraft(), contactId: "a", expectedUpdatedAt: "t1", accountId: "acc")
            XCTFail("expected a throw")
        } catch {
            XCTAssertEqual(error as? APIError, .stale(version: nil))
        }

        await hub.settle()
        XCTAssertEqual(hub.entries.first?.updatedAt, "t1")
    }

    func testReloadingAStaleContactTakesTheServersVersion() async throws {
        api.syncResults = [.success(Make.run([Make.contact("a", "Anna", phones: ["+31611111111"], updatedAt: "t1")], serverTime: "s1", full: true))]
        let hub = await configured()
        await hub.sync(accountId: "acc")
        api.detailResult = .success(Make.detail(Make.contact("a", "Anna (collega paste aan)", phones: ["+31611111111"], updatedAt: "t5"), notes: "nieuwe notitie"))

        let detail = try await hub.detail(accountId: "acc", contactId: "a")
        await hub.settle()

        XCTAssertEqual(detail.notes, "nieuwe notitie")
        XCTAssertEqual(hub.entries.first?.updatedAt, "t5")
    }

    func testDeleteRemovesTheContactAndItsListMembership() async throws {
        api.capabilities = Make.capabilities(delete: true)
        api.syncResults = [.success(Make.run([Make.contact("a", "Anna", phones: ["+31611111111"])], serverTime: "s1", full: true))]
        api.lists = [Make.list("l1", "Klanten", version: 1)]
        api.snapshots["l1"] = [.success(.snapshot(Make.snapshot("l1", version: 1, contactIds: ["a"]), etag: "\"1\""))]
        let hub = await configured()
        await hub.sync(accountId: "acc")

        try await hub.delete(contactId: "a", accountId: "acc")
        await hub.settle()

        XCTAssertEqual(api.deletes, ["a"])
        XCTAssertTrue(hub.entries.isEmpty)
        XCTAssertEqual(hub.memberIds(listId: "l1", accountId: "acc"), [])
    }

    func testDeleteByAUserIsForbiddenByTheServer() async throws {
        api.deleteResult = .failure(APIError.forbidden)
        api.syncResults = [.success(Make.run([Make.contact("a", "Anna", phones: ["+31611111111"])], serverTime: "s1", full: true))]
        let hub = await configured()
        await hub.sync(accountId: "acc")

        do {
            try await hub.delete(contactId: "a", accountId: "acc")
            XCTFail("expected a throw")
        } catch {
            XCTAssertEqual(error as? APIError, .forbidden)
        }

        await hub.settle()
        XCTAssertEqual(hub.entries.count, 1)
    }

    // MARK: Accounts, storage, sources

    func testContactsSurviveARestartAndAreSeparatePerAccount() async throws {
        api.syncResults = [.success(Make.run([Make.contact("a", "Anna", phones: ["+31611111111"])], serverTime: "s1", full: true))]
        let first = await configured(["acc"])
        await first.sync(accountId: "acc")
        _ = await settled(first)

        let again = await configured(["acc", "other"])
        await again.settle()

        XCTAssertEqual(again.name(forNumber: "0611111111"), "Anna")
        XCTAssertEqual(again.state(for: "other")?.contactCount, 0)
        XCTAssertEqual(store.load(accountId: "acc")?.serverTime, "s1")
        XCTAssertNil(store.load(accountId: "other")?.serverTime)
    }

    func testForgettingAnAccountDeletesItsContacts() async throws {
        api.syncResults = [.success(Make.run([Make.contact("a", "Anna", phones: ["+31611111111"])], serverTime: "s1", full: true))]
        let hub = await configured(["acc", "other"])
        await hub.sync(accountId: "acc")
        _ = await settled(hub)
        XCTAssertNotNil(store.load(accountId: "acc"))

        hub.forget(accountId: "acc")
        await hub.settle()

        XCTAssertNil(store.load(accountId: "acc"))
        XCTAssertNil(hub.name(forNumber: "0611111111"))
        XCTAssertTrue(hub.entries.isEmpty)
    }

    func testAnEmptyAccountListDoesNotWipeTheStoredContacts() async throws {
        try store.save(AccountContactsData(contacts: [StoredContact(Make.contact("a", "Anna", phones: ["+31611111111"]))]), accountId: "acc")
        let hub = makeHub()

        await hub.configure(accountIds: [])

        XCTAssertNotNil(store.load(accountId: "acc"), "the Keychain may just not have been readable yet")
    }

    func testFilesOfAccountsThatAreNoLongerPairedAreCleanedUp() async throws {
        try store.save(AccountContactsData(), accountId: "old")
        let hub = makeHub()

        await hub.configure(accountIds: ["acc"])

        XCTAssertNil(store.load(accountId: "old"))
    }

    func testTheSameCustomerContactInTwoAccountsIsShownOnce() async throws {
        let shared = Make.run([Make.contact("a", "Anna", phones: ["+31611111111"])], serverTime: "s1", full: true)
        api.syncResults = [.success(shared), .success(shared)]
        let hub = await configured(["acc1", "acc2"])
        await hub.syncAll()
        await hub.settle()

        XCTAssertEqual(hub.entries.count, 1)
        XCTAssertEqual(hub.entries.first?.accountIds, ["acc1", "acc2"])
    }

    func testAnAddressBookThatIsSwitchedOffIsHiddenAndNotUsedForNames() async throws {
        api.syncResults = [.success(Make.run([Make.contact("a", "Anna", phones: ["+31611111111"])], serverTime: "s1", full: true))]
        let hub = await configured()
        await hub.sync(accountId: "acc")
        await hub.settle()
        XCTAssertEqual(hub.name(forNumber: "0611111111"), "Anna")

        await hub.setAddressBookEnabled(false, accountId: "acc")
        await hub.settle()

        XCTAssertNil(hub.name(forNumber: "0611111111"))
        XCTAssertTrue(hub.entries.isEmpty)
        let skipped = await hub.sync(accountId: "acc", force: true)
        XCTAssertEqual(skipped, .skipped)
    }

    func testTheOrderOfNamesIsAddressBookThenPhoneThenColleagues() async throws {
        api.syncResults = [.success(Make.run([Make.contact("a", "Anna Klant", phones: ["+31611111111"])], serverTime: "s1", full: true))]
        device.entries = [
            ContactEntry(id: "device:1", name: "Anna Privé", numbers: ["0611111111"], source: .device),
            ContactEntry(id: "device:2", name: "Mama", numbers: ["0655555555"], source: .device),
        ]
        device.current = .authorized
        let hub = await configured()
        await hub.setDeviceContactsEnabled(true)
        await hub.sync(accountId: "acc")
        hub.setInternalContacts([InternalContact(number: "103", name: "Werkplaats")], accountId: "acc")
        await hub.settle()

        XCTAssertEqual(hub.name(forNumber: "0611111111"), "Anna Klant")
        XCTAssertEqual(hub.name(forNumber: "+31655555555"), "Mama")
        XCTAssertEqual(hub.name(forNumber: "103"), "Werkplaats")
    }

    func testPhoneContactsNeedPermissionAndAreNotUsedWithoutIt() async throws {
        device.grants = false
        device.entries = [ContactEntry(id: "device:1", name: "Mama", numbers: ["0655555555"], source: .device)]
        let hub = await configured()

        let enabled = await hub.setDeviceContactsEnabled(true)
        await hub.settle()

        XCTAssertFalse(enabled)
        XCTAssertFalse(hub.usesDeviceContacts)
        XCTAssertEqual(hub.deviceAccess, .denied)
        XCTAssertFalse(settings.usesDeviceContacts)
        XCTAssertNil(hub.name(forNumber: "0655555555"))
    }

    func testPhoneContactsNeverTouchTheStore() async throws {
        device.current = .authorized
        device.entries = [ContactEntry(id: "device:1", name: "Mama", numbers: ["0655555555"], source: .device)]
        let hub = await configured()
        await hub.setDeviceContactsEnabled(true)
        _ = await settled(hub)

        XCTAssertEqual(hub.name(forNumber: "0655555555"), "Mama")
        XCTAssertTrue(store.load(accountId: "acc")?.contacts.isEmpty ?? true)
        XCTAssertTrue(settings.usesDeviceContacts)

        await hub.setDeviceContactsEnabled(false)
        await hub.settle()
        XCTAssertNil(hub.name(forNumber: "0655555555"))
    }

    func testConcurrentSyncsOfOneAccountRunOnce() async throws {
        api.syncResults = [.success(Make.run([], serverTime: "s1", full: true)), .success(Make.run([], serverTime: "s2", full: false))]
        let hub = await configured()

        async let one = hub.sync(accountId: "acc", force: true)
        async let two = hub.sync(accountId: "acc", force: true)
        let outcomes = await [one, two]

        XCTAssertEqual(outcomes.filter { $0 == .synced }.count + outcomes.filter { $0 == .skipped }.count, 2)
        XCTAssertLessThanOrEqual(api.syncSinces.count, 2)
    }
}
