// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import XCTest
@testable import FSContacts

final class ContactsSyncTests: XCTestCase {
    private func stored(_ id: String, _ name: String, updatedAt: String = "t0") -> StoredContact {
        StoredContact(Make.contact(id, name, phones: ["+31612345678"], updatedAt: updatedAt))
    }

    func testAFullRunReplacesEverything() {
        let result = Make.run([Make.contact("b", "Bea")], serverTime: "s1", full: true)

        let contacts = ContactsSync.apply(result, to: [stored("a", "Anna"), stored("b", "Oud")])

        XCTAssertEqual(contacts.map(\.id), ["b"])
        XCTAssertEqual(contacts.first?.name, "Bea")
    }

    func testAnIncrementalRunUpsertsOnId() {
        let result = Make.run([Make.contact("b", "Bea (nieuw)", updatedAt: "t1"), Make.contact("c", "Cor")], serverTime: "s2", full: false)

        let contacts = ContactsSync.apply(result, to: [stored("a", "Anna"), stored("b", "Bea")])

        XCTAssertEqual(contacts.map(\.id), ["a", "b", "c"], "an existing contact keeps its place, a new one goes last")
        XCTAssertEqual(contacts[1].name, "Bea (nieuw)")
        XCTAssertEqual(contacts[1].updatedAt, "t1")
    }

    func testTombstonesRemoveContacts() {
        let result = Make.run([], deleted: ["a", "unknown"], serverTime: "s2", full: false)

        XCTAssertEqual(ContactsSync.apply(result, to: [stored("a", "Anna"), stored("b", "Bea")]).map(\.id), ["b"])
    }

    func testTheSameContactTwiceIsStoredOnce() {
        let result = Make.run([Make.contact("a", "Anna", updatedAt: "t2")], serverTime: "s2", full: false)
        let once = ContactsSync.apply(result, to: [stored("a", "Anna", updatedAt: "t2")])
        let twice = ContactsSync.apply(result, to: once)

        XCTAssertEqual(twice, once)
        XCTAssertEqual(twice.count, 1)
    }

    func testListsKeepTheLocalChoiceAndMembersAndDropGoneLists() {
        let existing = [
            StoredContactList(id: "l1", name: "Oud", version: 2, etag: "\"2\"", isEnabled: false, memberIds: ["a"], contactCount: 1),
            StoredContactList(id: "gone", name: "Weg", version: 1),
        ]

        let merged = ContactsSync.mergeLists([Make.list("l1", "Klanten", version: 3, count: 4), Make.list("l2", "Leveranciers", version: 1, count: 2)], into: existing)

        XCTAssertEqual(merged.map(\.id), ["l1", "l2"])
        XCTAssertEqual(merged[0].name, "Klanten")
        XCTAssertFalse(merged[0].isEnabled)
        XCTAssertEqual(merged[0].memberIds, ["a"])
        XCTAssertEqual(merged[0].version, 2, "the version is only updated by a download")
        XCTAssertTrue(merged[1].isEnabled, "a new list starts on")
    }

    func testNeedsSnapshot() {
        let list = StoredContactList(id: "l", name: "L", version: 3, etag: "\"3\"")

        XCTAssertFalse(ContactsSync.needsSnapshot(list, serverVersion: 3))
        XCTAssertTrue(ContactsSync.needsSnapshot(list, serverVersion: 4))
        XCTAssertTrue(ContactsSync.needsSnapshot(StoredContactList(id: "l", name: "L", version: 3), serverVersion: 3), "never downloaded")
        XCTAssertFalse(ContactsSync.needsSnapshot(StoredContactList(id: "l", name: "L", version: 0, isEnabled: false), serverVersion: 3), "a list that is off is not downloaded")
    }

    func testFetchMembersFollowsCursorsAndKeepsTheETag() async throws {
        let api = FakeContactsAPI()
        api.snapshots["l"] = [
            .success(.snapshot(Make.snapshot("l", version: 5, contactIds: ["a", "b"], nextCursor: "p2"), etag: "\"5\"")),
            .success(.snapshot(Make.snapshot("l", version: 5, contactIds: ["c"]), etag: nil)),
        ]

        let outcome = try await ContactsSync.fetchMembers(api: api, list: StoredContactList(id: "l", name: "L", version: 4, etag: "\"4\""))

        XCTAssertEqual(outcome, .members(ids: ["a", "b", "c"], version: 5, etag: "\"5\""))
        XCTAssertEqual(api.snapshotCalls.map(\.cursor), [nil, "p2"])
        XCTAssertEqual(api.snapshotCalls[0].etag, "\"4\"", "the stored ETag goes in If-None-Match")
        XCTAssertNil(api.snapshotCalls[1].etag, "not on a cursor page")
    }

    func testA304MeansTheStoredMembersAreStillRight() async throws {
        let api = FakeContactsAPI()
        api.snapshots["l"] = [.success(.notModified(etag: "\"4\""))]

        let outcome = try await ContactsSync.fetchMembers(api: api, list: StoredContactList(id: "l", name: "L", version: 4, etag: "\"4\"", memberIds: ["a"]))

        XCTAssertEqual(outcome, .notModified)
        XCTAssertEqual(api.snapshotCalls.count, 1, "nothing else was downloaded")
    }

    func testAVersionChangeHalfwayStartsOnceAgain() async throws {
        let api = FakeContactsAPI()
        api.snapshots["l"] = [
            .success(.snapshot(Make.snapshot("l", version: 5, contactIds: ["a"], nextCursor: "p2"), etag: "\"5\"")),
            .failure(APIError.stale(version: nil)),
            .success(.snapshot(Make.snapshot("l", version: 6, contactIds: ["a", "z"]), etag: "\"6\"")),
        ]

        let outcome = try await ContactsSync.fetchMembers(api: api, list: StoredContactList(id: "l", name: "L", version: 4))

        XCTAssertEqual(outcome, .members(ids: ["a", "z"], version: 6, etag: "\"6\""))
    }

    func testStaleTwiceGivesUp() async {
        let api = FakeContactsAPI()
        api.snapshots["l"] = [.failure(APIError.stale(version: nil)), .failure(APIError.stale(version: nil))]

        do {
            _ = try await ContactsSync.fetchMembers(api: api, list: StoredContactList(id: "l", name: "L", version: 4))
            XCTFail("expected a throw")
        } catch {
            XCTAssertEqual(error as? APIError, .stale(version: nil))
        }
    }
}
