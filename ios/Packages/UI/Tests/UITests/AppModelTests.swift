// SPDX-License-Identifier: AGPL-3.0-or-later
import CallController
import Core
import Pairing
import SipEngine
import XCTest
@testable import UI

/// Scripted stand-in for the FullStack Studio API.
private final class FakeAccountService: AccountServicing, @unchecked Sendable {
    let store: AccountStore
    var pairResult: Result<StoredAccount, Error>?
    var refreshRevokes: Set<String> = []
    var unpairError: Error?
    private(set) var pairedLinks: [String] = []
    private(set) var sentDevices: [DeviceDescriptor] = []

    init(store: AccountStore) {
        self.store = store
    }

    func pair(_ link: PairingLink, device: DeviceDescriptor) async throws -> StoredAccount {
        pairedLinks.append(link.token)
        sentDevices.append(device)

        switch pairResult {
        case let .success(account)?:
            try store.save(account)
            return account
        case let .failure(error)?:
            throw error
        case nil:
            throw APIError.notFound
        }
    }

    func refresh(_ account: StoredAccount) async throws -> AccountRefreshResult {
        if refreshRevokes.contains(account.id) {
            try store.remove(id: account.id)
            return .revoked
        }

        return .updated(account, internalContacts: [InternalContact(number: "103", name: "Werkplaats")])
    }

    func rename(_ account: StoredAccount, alias: String?) async throws -> StoredAccount {
        var updated = account
        updated.labelOverride = AccountService.cleanAlias(alias)
        try store.save(updated)
        return updated
    }

    func unpair(_ account: StoredAccount) async throws {
        if let unpairError {
            throw unpairError
        }

        try store.remove(id: account.id)
    }

    func forget(_ account: StoredAccount) throws {
        try store.remove(id: account.id)
    }
}

@MainActor
final class AppModelTests: XCTestCase {
    private let token = "fss_vpair_" + String(repeating: "aB3-_", count: 8) + "xyz"
    private var store: AccountStore!
    private var service: FakeAccountService!
    private var engine: NullSipEngine!
    private var preferences: InMemoryPreferencesStore!
    private var recentsDefaults: UserDefaults!
    private var micRequests = 0

    override func setUp() async throws {
        store = AccountStore(secrets: InMemorySecretStore())
        service = FakeAccountService(store: store)
        engine = NullSipEngine()
        preferences = InMemoryPreferencesStore()
        recentsDefaults = UserDefaults(suiteName: "fsvoip.uitests.\(UUID().uuidString)")
        micRequests = 0
    }

    private func makeModel() -> FSVoipAppModel {
        let phone = PhoneController(engine: engine, system: ImmediateCallSystem(), audioRouting: MemoryAudioRouting(), preferences: preferences, endedLinger: 0)

        return FSVoipAppModel(
            phone: phone,
            accountStore: store,
            service: service,
            preferences: preferences,
            recentsStore: RecentCallsStore(defaults: recentsDefaults),
            device: { DeviceDescriptor(model: "iPhone17,1", osVersion: "26.0", appVersion: "0.1.0 (1)", installId: "a1b2c3d4e5f60718") },
            requestMicrophone: { [unowned self] in micRequests += 1; return true }
        )
    }

    private func account(_ id: String, label: String = "Voorbeeld · Jan") -> StoredAccount {
        StoredAccount(id: id, label: label, pbxName: "Voorbeeld", extensionName: "Jan", extensionNumber: "102", customerName: "Voorbeeld B.V.", deviceToken: Secret("fss_vapp_x"), installId: "a1b2c3d4e5f60718", sip: SIPCredentials(username: "102", password: Secret("pw"), domain: "voorbeeld.powervoip.nl", proxy: "sip.powervoip.nl", port: 5061, transport: .tls, srv: true), pairedAt: Date(timeIntervalSince1970: TimeInterval(id.hashValue & 0xffff)))
    }

    // MARK: Pairing state machine

    func testStartsIdleWithoutAccounts() {
        let model = makeModel()

        XCTAssertEqual(model.pairing, .idle)
        XCTAssertTrue(model.accounts.isEmpty)
    }

    func testUniversalAndCustomSchemeLinksOpenTheConfirmStep() throws {
        let model = makeModel()

        model.handleIncoming(url: URL(string: "https://fullstackstudio.nl/fsvoip/pair?t=\(token)")!)
        XCTAssertEqual(model.pairing, .linkReceived(try PairingLink(token: token)))

        model.closePairing()
        model.handleIncoming(url: URL(string: "fsvoip://pair?t=\(token)")!)
        XCTAssertEqual(model.pairing, .linkReceived(try PairingLink(token: token)))
        XCTAssertTrue(service.pairedLinks.isEmpty, "nothing is claimed before the user confirms")
    }

    func testForeignLinkShowsANoticeAndStaysIdle() {
        let model = makeModel()

        model.handleIncoming(url: URL(string: "https://evil.example/fsvoip/pair?t=\(token)")!)

        XCTAssertEqual(model.pairing, .idle)
        XCTAssertNotNil(model.notice)
    }

    func testScannedLinkClosesTheScannerAndABadScanKeepsItOpen() throws {
        let model = makeModel()
        model.isScannerPresented = true

        XCTAssertFalse(model.handleScanned("hello"))
        XCTAssertTrue(model.isScannerPresented)
        XCTAssertNotNil(model.scannerError)

        XCTAssertTrue(model.handleScanned("https://fullstackstudio.nl/fsvoip/pair?t=\(token)"))
        XCTAssertFalse(model.isScannerPresented)
        XCTAssertNil(model.scannerError)
        XCTAssertEqual(model.pairing, .linkReceived(try PairingLink(token: token)))
    }

    func testSuccessfulPairingStoresRegistersAndAsksForTheMicrophone() async throws {
        let model = makeModel()
        let paired = account("acc-1")
        service.pairResult = .success(paired)
        model.handleIncoming(url: URL(string: "fsvoip://pair?t=\(token)")!)

        await model.confirmPairing()

        XCTAssertEqual(model.pairing, .paired(paired))
        XCTAssertEqual(model.accounts.map(\.id), ["acc-1"])
        XCTAssertEqual(model.phone.registrationState(for: "acc-1"), .registering, "the account goes to the SIP engine right away")
        XCTAssertEqual(service.sentDevices.first?.installId, "a1b2c3d4e5f60718")
        XCTAssertEqual(micRequests, 1)

        model.closePairing()
        XCTAssertEqual(model.pairing, .idle)
        XCTAssertEqual(model.selectedTab, .dialer)
    }

    func testExpiredCodeIsFinalAndOffersANewScan() async throws {
        let model = makeModel()
        service.pairResult = .failure(APIError.notFound)
        model.handleIncoming(url: URL(string: "fsvoip://pair?t=\(token)")!)

        await model.confirmPairing()

        XCTAssertEqual(model.pairing, .failed(nil, .codeExpiredOrUsed))

        // Confirming again does nothing: the code is gone.
        await model.confirmPairing()
        XCTAssertEqual(service.pairedLinks.count, 1)

        model.restartScan()
        XCTAssertEqual(model.pairing, .idle)
        XCTAssertTrue(model.isScannerPresented)
    }

    func testTemporaryFailureKeepsTheCodeForARetry() async throws {
        let model = makeModel()
        service.pairResult = .failure(APIError.unavailable(retryable: true, retryAfterSeconds: nil))
        model.handleIncoming(url: URL(string: "fsvoip://pair?t=\(token)")!)

        await model.confirmPairing()
        XCTAssertEqual(model.pairing, .failed(try PairingLink(token: token), .temporarilyUnavailable))

        service.pairResult = .success(account("acc-2"))
        await model.confirmPairing()

        XCTAssertEqual(service.pairedLinks, [token, token])
        if case .paired = model.pairing {} else { XCTFail("expected paired, got \(model.pairing)") }
    }

    // MARK: Accounts

    func testRefreshRemovesRevokedAccountsAndLearnsInternalContacts() async throws {
        try store.save(account("keep"))
        try store.save(account("gone", label: "Oud toestel"))
        preferences.setPreferences(AccountPreferences(showCalledAccount: true), for: "gone")
        service.refreshRevokes = ["gone"]
        let model = makeModel()

        await model.refreshAccounts()

        XCTAssertEqual(model.accounts.map(\.id), ["keep"])
        XCTAssertNil(preferences.preferences(for: "gone").showCalledAccount, "settings of a revoked account are removed")
        XCTAssertEqual(model.name(forNumber: "103"), "Werkplaats")
        XCTAssertTrue(model.notice?.isError ?? false)
    }

    func testUnpairOfflineOffersForgetting() async throws {
        try store.save(account("a"))
        service.unpairError = APIError.transport("offline")
        let model = makeModel()

        let result = await model.unpair(accountId: "a")

        XCTAssertEqual(result, .failed(.network))
        XCTAssertEqual(model.accounts.count, 1)

        model.forget(accountId: "a")
        XCTAssertTrue(model.accounts.isEmpty)
    }

    func testRenameUpdatesTheAccount() async throws {
        try store.save(account("a"))
        let model = makeModel()

        let ok = await model.rename(accountId: "a", alias: " Balie ")

        XCTAssertTrue(ok)
        XCTAssertEqual(model.account(id: "a")?.displayLabel, "Balie")
    }

    func testShowCalledAccountSettingAndDefaultLine() throws {
        try store.save(account("a"))
        let model = makeModel()

        XCTAssertFalse(model.showsCalledAccount("a"), "off by default with one account")
        model.setShowsCalledAccount("a", true)
        XCTAssertTrue(model.showsCalledAccount("a"))

        XCTAssertEqual(model.defaultOutgoingAccountId, "a")
        model.setDefaultOutgoing("missing")
        XCTAssertEqual(model.defaultOutgoingAccountId, "a", "an unknown default falls back to the first account")
    }

    func testCallWithoutRegistrationExplainsWhy() throws {
        try store.save(account("a"))
        let model = makeModel()

        XCTAssertFalse(model.call("0612345678", from: nil))
        XCTAssertEqual(model.notice?.message, L10n.string("call.error.notConnected"))

        XCTAssertFalse(model.call("abc", from: "a"))
        XCTAssertEqual(model.notice?.message, L10n.string("call.error.invalidNumber"))
    }

    // MARK: Strings

    func testStringsExistInBothLanguages() throws {
        // Every key must be translated: a missing key shows the key itself.
        let keys = try String(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Sources/UI/Resources/nl.lproj/Localizable.strings"))
            .split(separator: "\n")
            .compactMap { line -> String? in
                guard line.hasPrefix("\"") else { return nil }
                return line.split(separator: "\"").first.map(String.init)
            }

        XCTAssertGreaterThan(keys.count, 100)

        for language in ["nl", "en"] {
            let path = try XCTUnwrap(L10n.bundle.path(forResource: "Localizable", ofType: "strings", inDirectory: nil, forLocalization: language), "no \(language) strings")
            let table = try XCTUnwrap(NSDictionary(contentsOfFile: path) as? [String: String])

            for key in keys {
                XCTAssertFalse((table[key] ?? "").isEmpty, "\(key) missing in \(language)")
            }

            XCTAssertEqual(Set(table.keys), Set(keys), "\(language) has different keys than nl")
        }
    }

    func testEveryKeyUsedInTheSourcesExists() throws {
        let sources = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Sources/UI")
        let table = try XCTUnwrap(NSDictionary(contentsOfFile: try XCTUnwrap(L10n.bundle.path(forResource: "Localizable", ofType: "strings", inDirectory: nil, forLocalization: "nl"))) as? [String: String])
        let pattern = try NSRegularExpression(pattern: #"(?:L10n\.(?:string|text)\(|(?:titleKey|textKey|bodyKey): )"([a-zA-Z.]+)""#)
        var used = Set<String>()

        for case let file as URL in FileManager.default.enumerator(at: sources, includingPropertiesForKeys: nil)! where file.pathExtension == "swift" {
            let text = try String(contentsOf: file)
            for match in pattern.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
                used.insert(String(text[Range(match.range(at: 1), in: text)!]))
            }
        }

        XCTAssertGreaterThan(used.count, 50)

        for key in used {
            XCTAssertNotNil(table[key], "\(key) is used but not translated")
        }
    }
}

final class DialerInputTests: XCTestCase {
    func testTyping() {
        var input = DialerInput()
        input.longPressZero()
        for key in "31612" { input.press(key) }
        input.press("+")
        input.press("x")

        XCTAssertEqual(input.number, "+31612")

        input.deleteLast()
        XCTAssertEqual(input.number, "+3161")
        XCTAssertTrue(input.canCall)

        input.clear()
        XCTAssertTrue(input.isEmpty)
        XCTAssertFalse(input.canCall)
    }

    func testLongPressZeroAfterDigitsIsAZero() {
        var input = DialerInput(number: "06")
        input.longPressZero()

        XCTAssertEqual(input.number, "060")
    }

    func testPasteCleansTheNumber() {
        var input = DialerInput()
        input.paste("Bel me: +31 (0)70 - 123 45 67")

        XCTAssertEqual(input.number, "+31701234567")
    }

    func testMaximumLength() {
        var input = DialerInput()
        for _ in 0 ..< 40 { input.press("1") }

        XCTAssertEqual(input.number.count, 32)
    }
}

final class RecentRowFormatTests: XCTestCase {
    func testDuration() {
        XCTAssertEqual(RecentRow.duration(42), "0:42")
        XCTAssertEqual(RecentRow.duration(312), "5:12")
        XCTAssertEqual(RecentRow.duration(3723), "1:02:03")
    }
}
