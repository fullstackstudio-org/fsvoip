// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import Core

final class InstallIdentityTests: XCTestCase {
    func testCreatedOnceThenStable() throws {
        let store = InMemorySecretStore()
        let first = try InstallIdentity.load(from: store, random: { [0xde, 0xad, 0xbe, 0xef, 0x00, 0x01, 0x02, 0x03] })
        let second = try InstallIdentity.load(from: store, random: { [UInt8](repeating: 0xff, count: 8) })

        XCTAssertEqual(first.installId, "deadbeef00010203")
        XCTAssertEqual(first, second, "the identity is created once per installation")
        XCTAssertNotNil(UUID(uuidString: first.sipInstanceId))
    }

    func testMalformedStoredValueIsReplaced() throws {
        let store = InMemorySecretStore()
        try store.set(Data("not-hex!".utf8), for: "install.id")

        let identity = try InstallIdentity.load(from: store, random: { [1, 2, 3, 4, 5, 6, 7, 8] })

        XCTAssertEqual(identity.installId, "0102030405060708")
        XCTAssertTrue(InstallIdentity.isValidInstallId(identity.installId))
    }

    func testInstallIdRules() {
        XCTAssertTrue(InstallIdentity.isValidInstallId("a1b2c3d4"))
        XCTAssertTrue(InstallIdentity.isValidInstallId(String(repeating: "f", count: 32)))
        XCTAssertFalse(InstallIdentity.isValidInstallId("a1b2c3d"))
        XCTAssertFalse(InstallIdentity.isValidInstallId(String(repeating: "f", count: 33)))
        XCTAssertFalse(InstallIdentity.isValidInstallId("a1b2c3dz"))
    }

    func testRandomBytesAreRandom() {
        XCTAssertNotEqual(InstallIdentity.secureRandomBytes(8), InstallIdentity.secureRandomBytes(8))
    }
}

final class PreferencesTests: XCTestCase {
    func testShowCalledAccountDefaultsToSeveralAccounts() {
        XCTAssertFalse(AccountPreferences().effectiveShowCalledAccount(accountCount: 1))
        XCTAssertTrue(AccountPreferences().effectiveShowCalledAccount(accountCount: 2))
        XCTAssertTrue(AccountPreferences(showCalledAccount: true).effectiveShowCalledAccount(accountCount: 1))
        XCTAssertFalse(AccountPreferences(showCalledAccount: false).effectiveShowCalledAccount(accountCount: 3), "an explicit choice wins")
    }

    func testUserDefaultsStoreRoundTripAndRemove() throws {
        let suite = "fsvoip.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = UserDefaultsPreferencesStore(defaults: defaults)

        store.setPreferences(AccountPreferences(showCalledAccount: true), for: "a")
        store.defaultOutgoingAccountId = "a"

        XCTAssertEqual(store.preferences(for: "a").showCalledAccount, true)
        XCTAssertNil(store.preferences(for: "b").showCalledAccount)

        store.removePreferences(for: "a")

        XCTAssertNil(store.preferences(for: "a").showCalledAccount)
        XCTAssertNil(store.defaultOutgoingAccountId, "removing the default account clears the default")
    }
}

final class RecentCallsTests: XCTestCase {
    private func call(_ number: String, account: String = "a") -> RecentCall {
        RecentCall(number: number, name: nil, accountId: account, accountLabel: "A", direction: .outgoing, outcome: .answered, startedAt: Date(timeIntervalSince1970: 1_791_400_000), duration: 12)
    }

    func testNewestFirstCappedAndPerAccountRemoval() throws {
        let suite = "fsvoip.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = RecentCallsStore(defaults: defaults, limit: 3)

        store.add(call("1"))
        store.add(call("2", account: "b"))
        store.add(call("3"))
        store.add(call("4"))

        XCTAssertEqual(store.all().map(\.number), ["4", "3", "2"])

        store.remove(accountId: "b")
        XCTAssertEqual(store.all().map(\.number), ["4", "3"])

        store.clear()
        XCTAssertTrue(store.all().isEmpty)
    }
}

final class StoredAccountUpdateTests: XCTestCase {
    func testMeUpdatesLabelsAndServerButKeepsCredentials() throws {
        let pair = try FSVoipJSON.decoder().decode(PairResponse.self, from: Fixtures.data("pair-response-tls"))
        let me = try FSVoipJSON.decoder().decode(MeResponse.self, from: Fixtures.data("me-response"))
        let account = StoredAccount(pairing: pair, pairedAt: Date(timeIntervalSince1970: 1))

        let updated = account.updated(with: me)

        XCTAssertEqual(updated.displayLabel, "Jan (balie)")
        XCTAssertEqual(updated.labelOverride, "Jan (balie)")
        XCTAssertEqual(updated.extensionNumber, "102")
        XCTAssertEqual(updated.sip.transport, .tcp)
        XCTAssertEqual(updated.sip.port, 5060)
        XCTAssertEqual(updated.sip.username, account.sip.username)
        XCTAssertEqual(updated.sip.password, account.sip.password)
        XCTAssertEqual(updated.deviceToken, account.deviceToken)
        XCTAssertEqual(updated.installId, account.installId)
    }

    func testMeWithoutSipKeepsTheStoredServer() throws {
        let pair = try FSVoipJSON.decoder().decode(PairResponse.self, from: Fixtures.data("pair-response-tls"))
        let me = try FSVoipJSON.decoder().decode(MeResponse.self, from: Fixtures.data("me-response-no-sip"))
        let account = StoredAccount(pairing: pair, pairedAt: Date(timeIntervalSince1970: 1))

        XCTAssertEqual(account.updated(with: me).sip, account.sip)
    }

    func testDisplayLabelPrefersAlias() throws {
        let pair = try FSVoipJSON.decoder().decode(PairResponse.self, from: Fixtures.data("pair-response"))
        var account = StoredAccount(pairing: pair)

        XCTAssertEqual(account.displayLabel, account.label)
        account.labelOverride = ""
        XCTAssertEqual(account.displayLabel, account.label)
        account.labelOverride = "Balie"
        XCTAssertEqual(account.displayLabel, "Balie")
    }
}
