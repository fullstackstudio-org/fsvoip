// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import Core

final class ContactsStoreTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("fsvoip-contacts-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func sample() -> AccountContactsData {
        AccountContactsData(
            serverTime: "2026-10-09T10:00:00.000Z",
            lastSyncedAt: Date(timeIntervalSince1970: 1_791_549_000),
            contacts: [
                StoredContact(id: "c1", name: "Pieter de Groot", firstName: "Pieter", lastName: "de Groot", company: nil, email: "p@example.nl", phones: [StoredContactPhone(number: "+31612345678", label: "mobile", isPrimary: true)], tags: ["klant"], updatedAt: "2026-10-09T09:59:59.123Z"),
            ],
            lists: [StoredContactList(id: "l1", name: "Klanten", version: 3, etag: "\"3\"", isEnabled: true, memberIds: ["c1"], contactCount: 1)],
            canRead: true,
            canWrite: true,
            canDelete: true
        )
    }

    func testRoundTripKeepsEverythingAndTheOpaqueTimestamps() throws {
        let store = ContactsFileStore(directory: directory)

        try store.save(sample(), accountId: "acc-1")

        XCTAssertEqual(store.load(accountId: "acc-1"), sample())
        XCTAssertEqual(store.load(accountId: "acc-1")?.contacts.first?.updatedAt, "2026-10-09T09:59:59.123Z")
    }

    func testAccountsAreSeparateAndRemoveDeletesOnlyThatFile() throws {
        let store = ContactsFileStore(directory: directory)
        try store.save(sample(), accountId: "acc-1")
        try store.save(AccountContactsData(), accountId: "acc-2")

        XCTAssertEqual(Set(store.storedAccountIds()), ["acc-1", "acc-2"])

        store.remove(accountId: "acc-1")

        XCTAssertNil(store.load(accountId: "acc-1"))
        XCTAssertNotNil(store.load(accountId: "acc-2"))
    }

    func testUnsafeAccountIdsCannotEscapeTheDirectory() throws {
        let store = ContactsFileStore(directory: directory)

        try store.save(sample(), accountId: "../../etc/passwd")

        XCTAssertEqual(store.storedAccountIds().count, 1)
        XCTAssertFalse(store.storedAccountIds()[0].contains("/"))
        XCTAssertNotNil(store.load(accountId: "../../etc/passwd"))
        XCTAssertEqual(ContactsFileStore.fileNameStem(for: "../../etc/passwd"), store.storedAccountIds()[0])
    }

    func testAnUnreadableFileIsTreatedAsAbsent() throws {
        let store = ContactsFileStore(directory: directory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data("not json".utf8).write(to: directory.appendingPathComponent("acc-1.json"))

        XCTAssertNil(store.load(accountId: "acc-1"))
    }

    func testAnOlderFileWithFewerFieldsStillLoads() throws {
        let store = ContactsFileStore(directory: directory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data(#"{"contacts":[{"id":"c1","name":"A","phones":[],"tags":[],"updatedAt":"x"}]}"#.utf8).write(to: directory.appendingPathComponent("acc-1.json"))

        let loaded = try XCTUnwrap(store.load(accountId: "acc-1"))
        XCTAssertEqual(loaded.contacts.map(\.id), ["c1"])
        XCTAssertTrue(loaded.isEnabled)
        XCTAssertTrue(loaded.canWrite)
        XCTAssertFalse(loaded.canDelete, "deleting is the restricted right: a missing value means no")
    }

    func testTheFileIsExcludedFromBackupAndProtected() throws {
        let store = ContactsFileStore(directory: directory)
        try store.save(sample(), accountId: "acc-1")

        let file = directory.appendingPathComponent("acc-1.json")
        let values = try file.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(values.isExcludedFromBackup, true)
        let folder = try directory.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(folder.isExcludedFromBackup, true)
    }

    func testAnUnknownPhoneLabelSurvivesTheStore() throws {
        let contact = StoredContactPhone(number: "+31612345678", label: "pager")

        XCTAssertEqual(contact.phoneLabel, .other)

        let data = try FSVoipJSON.encoder().encode(contact)
        XCTAssertEqual(try FSVoipJSON.decoder().decode(StoredContactPhone.self, from: data).label, "pager")
    }
}
