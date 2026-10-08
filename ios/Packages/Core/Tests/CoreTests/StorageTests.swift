// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import Core

final class StorageTests: XCTestCase {
    private func storedAccount(id: String = "3f0c2b1e-8a4d-4d6f-9b7a-1c2d3e4f5a6b", pairedAt: Date = Date(timeIntervalSince1970: 1_791_549_000)) throws -> StoredAccount {
        let response = try FSVoipJSON.decoder().decode(PairResponse.self, from: Fixtures.data("pair-response"))
        var account = StoredAccount(pairing: response, pairedAt: pairedAt)
        account.id = id

        return account
    }

    func testAccountStoreRoundTripKeepsSecrets() throws {
        let store = AccountStore(secrets: InMemorySecretStore())
        let account = try storedAccount()

        try store.save(account)

        let loaded = try XCTUnwrap(store.account(id: account.id))
        XCTAssertEqual(loaded, account)
        XCTAssertEqual(loaded.sip.password.reveal(), "fixture-not-a-real-password")
        XCTAssertTrue(loaded.deviceToken.reveal().hasPrefix("fss_vapp_"))
        XCTAssertEqual(loaded.installId, "a1b2c3d4e5f60718")
    }

    func testSeveralAccountsOldestFirstAndRemove() throws {
        let store = AccountStore(secrets: InMemorySecretStore())
        let second = try storedAccount(id: "11111111-1111-4111-8111-111111111111", pairedAt: Date(timeIntervalSince1970: 2_000))
        let first = try storedAccount(id: "22222222-2222-4222-8222-222222222222", pairedAt: Date(timeIntervalSince1970: 1_000))

        try store.save(second)
        try store.save(first)
        XCTAssertEqual(try store.accounts().map(\.id), [first.id, second.id])

        try store.remove(id: first.id)
        XCTAssertEqual(try store.accounts().map(\.id), [second.id])
        XCTAssertNil(try store.account(id: first.id))
    }

    func testCorruptItemIsSkipped() throws {
        let secrets = InMemorySecretStore()
        let store = AccountStore(secrets: secrets)

        try store.save(try storedAccount())
        try secrets.set(Data("garbage".utf8), for: "account.broken")

        XCTAssertEqual(try store.accounts().count, 1)
        XCTAssertThrowsError(try store.account(id: "broken")) { error in
            XCTAssertEqual(error as? SecretStoreError, .corrupt)
        }
    }

    func testKeychainStoreRoundTrip() throws {
        let store = KeychainSecretStore(service: "nl.fullstackstudio.fsvoip.tests.\(UUID().uuidString)")
        let key = "probe"

        do {
            try store.set(Data("one".utf8), for: key)
        } catch SecretStoreError.keychain(let status) where status == errSecMissingEntitlement || status == -34018 {
            throw XCTSkip("The Keychain is not available in this test host (status \(status)).")
        }

        defer { try? store.remove(key) }

        XCTAssertEqual(try store.get(key), Data("one".utf8))

        try store.set(Data("two".utf8), for: key)
        XCTAssertEqual(try store.get(key), Data("two".utf8))
        XCTAssertEqual(try store.allKeys(), [key])

        try store.remove(key)
        XCTAssertNil(try store.get(key))
        XCTAssertEqual(try store.allKeys(), [])
    }
}
