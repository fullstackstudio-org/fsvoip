// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import XCTest
@testable import UI

private func account(_ id: String = "a") -> StoredAccount {
    StoredAccount(id: id, label: "Jan", pbxName: "Voorbeeld Bouw", extensionName: "Jan de Vries", extensionNumber: "102", customerName: "Voorbeeld", deviceToken: Secret("fss_vapp_x"), installId: "a1b2c3d4e5f60718", sip: SIPCredentials(username: "102", password: Secret("pw"), domain: "x.powervoip.nl", proxy: "sip.powervoip.nl", port: 5061, transport: .tls, srv: true), pairedAt: Date(timeIntervalSince1970: 1_790_000_000))
}

private func state(version: Int = 4, edit: (inout [String: Any]) -> Void = { _ in }) -> SelfExtension {
    var json = try! JSONSerialization.jsonObject(with: PbxFixtures.data("self-extension")) as! [String: Any]
    json["version"] = version
    edit(&json)

    return try! FSVoipJSON.decoder().decode(SelfExtension.self, from: JSONSerialization.data(withJSONObject: json))
}

private final class FakeSelfService: SelfExtensionServicing, InviteServicing, @unchecked Sendable {
    private let lock = NSLock()
    private(set) var patches: [SelfExtensionPatch] = []
    private(set) var loads = 0
    private(set) var invitedDevices: [String] = []
    /// What `load` returns, in order (the last one repeats).
    var loadStates: [SelfExtension] = [state()]
    /// Errors for the next patches, in order.
    var patchErrors: [Error] = []
    /// `nil` = the server could not read the fresh state.
    var patchAnswer: (SelfExtensionPatch) -> SelfExtension? = { patch in
        state(version: patch.version + 1)
    }
    var inviteResult: Result<AppPairingResponse, Error> = .success(PbxFixtures.decode("app-pairing-response"))

    func load(for account: StoredAccount) async throws -> SelfExtension {
        lock.lock()
        defer { lock.unlock() }
        loads += 1

        return loadStates[min(loads - 1, loadStates.count - 1)]
    }

    func patch(_ patch: SelfExtensionPatch, for account: StoredAccount) async throws -> SelfExtension? {
        lock.lock()
        defer { lock.unlock() }
        patches.append(patch)

        if !patchErrors.isEmpty {
            throw patchErrors.removeFirst()
        }

        return patchAnswer(patch)
    }

    func invite(deviceId: String, for account: StoredAccount) async throws -> AppPairingResponse {
        invitedDevices.append(deviceId)

        return try inviteResult.get()
    }
}

private func body(_ patch: SelfExtensionPatch) throws -> String {
    String(decoding: try FSVoipJSON.encoder().encode(patch), as: UTF8.self)
}

final class SelfExtensionDraftTests: XCTestCase {
    func testOnlyChangedFieldsAndTheVersionGoToTheServer() throws {
        let original = SelfExtensionDraft(state())
        var draft = original
        draft.email = "nieuw@voorbeeld-bouw.example"

        XCTAssertEqual(try body(try XCTUnwrap(draft.patch(from: original, version: 4))), #"{"email":"nieuw@voorbeeld-bouw.example","version":4}"#)

        draft = original
        draft.voicemailToEmail = false
        draft.noAnswerSeconds = 40
        draft.forwardAlways = .external("+31612345678")

        XCTAssertEqual(try body(try XCTUnwrap(draft.patch(from: original, version: 7))), #"{"forwardAlways":{"number":"+31612345678","type":"external"},"noAnswerSeconds":40,"version":7,"voicemailToEmail":false}"#)
    }

    func testNothingChangedSendsNothing() {
        let original = SelfExtensionDraft(state())

        XCTAssertNil(original.patch(from: original, version: 4))

        var spaced = original
        spaced.email = " " + original.email + "  "

        XCTAssertNil(spaced.patch(from: original, version: 4), "trimming is not a change")
    }

    func testClearingSendsExplicitNullAndNeverDoNotDisturb() throws {
        let original = SelfExtensionDraft(state { $0["forwardAlways"] = ["type": "external", "number": "+31612345678"] })
        var draft = original
        draft.forwardAlways = nil
        draft.noAnswerTarget = nil
        draft.email = "  "

        let text = try body(try XCTUnwrap(draft.patch(from: original, version: 4)))

        XCTAssertEqual(text, #"{"email":null,"forwardAlways":null,"noAnswerTarget":null,"version":4}"#)
        XCTAssertFalse(text.contains("dnd"))
    }

    func testRebaseFollowsTheServerOnlyWhereTheUserDidNotTouch() {
        let original = SelfExtensionDraft(state())
        var draft = original
        draft.email = "typed@voorbeeld-bouw.example"
        var baseline = original
        let fresh = state(version: 9) {
            $0["noAnswerSeconds"] = 60
            $0["email"] = "elders@voorbeeld-bouw.example"
        }

        draft.rebase(onto: fresh, baseline: &baseline)

        XCTAssertEqual(draft.email, "typed@voorbeeld-bouw.example", "what the user typed stays")
        XCTAssertEqual(draft.noAnswerSeconds, 60, "untouched fields follow the server")
        XCTAssertEqual(baseline.email, "elders@voorbeeld-bouw.example")
    }
}

@MainActor
final class SelfExtensionHubTests: XCTestCase {
    private func loaded(_ service: FakeSelfService) async -> SelfExtensionHub {
        let hub = SelfExtensionHub(service: service, invites: service)
        await hub.load(account())

        return hub
    }

    func testSavingSendsOnlyTheChangeWithTheLoadedVersion() async throws {
        let service = FakeSelfService()
        let hub = await loaded(service)
        let original = SelfExtensionDraft(try XCTUnwrap(hub.state(for: "a")))
        var draft = original
        draft.voicemailEnabled = false

        let outcome = await hub.save(draft, original: original, account: account())

        guard case let .saved(fresh) = outcome else { return XCTFail("\(outcome)") }
        XCTAssertEqual(fresh?.version, 5)
        XCTAssertEqual(service.patches.count, 1)
        XCTAssertEqual(try body(service.patches[0]), #"{"version":4,"voicemailEnabled":false}"#)
        XCTAssertEqual(hub.state(for: "a")?.version, 5)
    }

    func testNothingToSaveSendsNothing() async throws {
        let service = FakeSelfService()
        let hub = await loaded(service)
        let original = SelfExtensionDraft(try XCTUnwrap(hub.state(for: "a")))

        let unchanged = await hub.save(original, original: original, account: account())

        XCTAssertEqual(unchanged, .unchanged)
        XCTAssertTrue(service.patches.isEmpty)
    }

    func testStaleGetsOneRetryOnTheFreshVersionWithOnlyTheTouchedFields() async throws {
        let service = FakeSelfService()
        service.loadStates = [state(version: 4), state(version: 8) { $0["noAnswerSeconds"] = 60 }]
        service.patchErrors = [APIError.stale(version: 8)]
        let hub = await loaded(service)
        let original = SelfExtensionDraft(try XCTUnwrap(hub.state(for: "a")))
        var draft = original
        draft.email = "typed@voorbeeld-bouw.example"

        let outcome = await hub.save(draft, original: original, account: account())

        guard case .saved = outcome else { return XCTFail("\(outcome)") }
        XCTAssertEqual(service.patches.map(\.version), [4, 8])
        XCTAssertEqual(try body(service.patches[1]), #"{"email":"typed@voorbeeld-bouw.example","version":8}"#, "the change of the other phone (noAnswerSeconds) is not overwritten")
    }

    func testStaleTwiceShowsTheFreshStateAndKeepsTheInput() async throws {
        let service = FakeSelfService()
        service.loadStates = [state(version: 4), state(version: 8), state(version: 9) { $0["noAnswerSeconds"] = 60 }]
        service.patchErrors = [APIError.stale(version: 8), APIError.stale(version: 9)]
        let hub = await loaded(service)
        let original = SelfExtensionDraft(try XCTUnwrap(hub.state(for: "a")))
        var form = SelfExtensionForm()
        form.adopt(hub.state(for: "a"))
        form.draft.email = "typed@voorbeeld-bouw.example"

        let outcome = await hub.save(form.draft, original: original, account: account())

        guard case let .stale(fresh) = outcome else { return XCTFail("\(outcome)") }
        XCTAssertEqual(fresh.version, 9)
        XCTAssertEqual(service.patches.count, 2, "one retry, not more")

        XCTAssertFalse(form.finish(outcome))
        XCTAssertEqual(form.message, .stale)
        XCTAssertEqual(form.draft.email, "typed@voorbeeld-bouw.example", "nothing typed is lost")
        XCTAssertEqual(form.draft.noAnswerSeconds, 60, "the rest follows the server")
        XCTAssertTrue(form.isDirty)
    }

    func testAFieldTheUserMayNotChangeIsRefusedAndTheStateStays() async throws {
        let service = FakeSelfService()
        service.patchErrors = [APIError.forbidden]
        let hub = await loaded(service)
        let original = SelfExtensionDraft(try XCTUnwrap(hub.state(for: "a")))
        var draft = original
        draft.voicemailToEmail = false

        let outcome = await hub.save(draft, original: original, account: account())

        XCTAssertEqual(outcome, .failed(.accessDenied))
        XCTAssertEqual(hub.state(for: "a")?.version, 4)
        XCTAssertFalse(SelfExtensionFailure.accessDenied.message.isEmpty)
    }

    func testFailuresAreSortedIntoTheUsersTerms() {
        XCTAssertEqual(SelfExtensionFailure.classify(APIError.unauthorized), .revoked)
        XCTAssertEqual(SelfExtensionFailure.classify(APIError.invalid(code: "invalid_request", field: "email")), .invalid)
        XCTAssertEqual(SelfExtensionFailure.classify(APIError.rateLimited(retryAfterSeconds: 120)), .rateLimited(retryAfterSeconds: 120))
        XCTAssertEqual(SelfExtensionFailure.classify(APIError.transport("x")), .offline)
        XCTAssertEqual(SelfExtensionFailure.classify(APIError.conflict(code: "conflict")), .conflict)
        XCTAssertEqual(SelfExtensionFailure.classify(APIError.conflict(code: "conflict"), forInvite: true), .ownDevice)
        XCTAssertEqual(SelfExtensionFailure.classify(APIError.conflict(code: "read_only")), .readOnly)
    }

    func testAnUnreadableAnswerAsksForTheFreshState() async throws {
        let service = FakeSelfService()
        service.loadStates = [state(version: 4), state(version: 5) { $0["voicemailEnabled"] = false }]
        service.patchAnswer = { _ in nil }
        let hub = await loaded(service)
        let original = SelfExtensionDraft(try XCTUnwrap(hub.state(for: "a")))
        var draft = original
        draft.voicemailEnabled = false

        let outcome = await hub.save(draft, original: original, account: account())

        guard case let .saved(fresh) = outcome else { return XCTFail("\(outcome)") }
        XCTAssertEqual(fresh?.version, 5)
        XCTAssertEqual(hub.state(for: "a")?.voicemailEnabled, false)
    }
}

@MainActor
final class InviteTests: XCTestCase {
    private let own = "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57"

    private func devices() -> [PbxDevice] {
        PbxFixtures.decode("pbx-devices", as: PbxDevicesResponse.self).devices
    }

    func testTheOwnExtensionIsNotOffered() async {
        let service = FakeSelfService()
        let hub = SelfExtensionHub(service: service, invites: service)
        await hub.load(account())
        let model = InviteModel(hub: hub, account: account())

        XCTAssertTrue(devices().contains { $0.id == own })
        XCTAssertFalse(model.candidates(devices()).contains { $0.id == own })
        XCTAssertFalse(model.candidates(devices()).isEmpty)
    }

    func testCreatingShowsTheLinkUntilItExpiresAndTheUrlIsNotInTheDescription() async throws {
        let service = FakeSelfService()
        let hub = SelfExtensionHub(service: service, invites: service)
        let created = Date(timeIntervalSince1970: 1_790_000_000)
        var clock = created
        let expires = try XCTUnwrap(PbxFixtures.decode("app-pairing-response", as: AppPairingResponse.self).expiresAt as Date?)
        let model = InviteModel(hub: hub, account: account(), now: { clock })
        let colleague = try XCTUnwrap(devices().first { $0.id != own })
        model.selectedId = colleague.id

        await model.create(deviceName: { _ in colleague.name })

        XCTAssertEqual(service.invitedDevices, [colleague.id])
        guard case let .shown(invitation) = model.phase else { return XCTFail("\(model.phase)") }
        XCTAssertEqual(invitation.deviceName, colleague.name)
        XCTAssertFalse(String(describing: model.phase).contains("fss_vpair"), "the link must not appear in a description or log")
        XCTAssertFalse(String(reflecting: invitation).contains("fss_vpair"))

        clock = expires.addingTimeInterval(-90)
        XCTAssertEqual(model.remaining(of: invitation), 90)
        XCTAssertFalse(model.isExpired(invitation))

        clock = expires.addingTimeInterval(1)
        XCTAssertTrue(model.isExpired(invitation))
        XCTAssertEqual(model.remaining(of: invitation), 0)

        model.reset()
        XCTAssertEqual(model.phase, .choosing)
    }

    func testInvitingYourselfIsTheServersConflictAndShowsAClearMessage() async {
        let service = FakeSelfService()
        service.inviteResult = .failure(APIError.conflict(code: "conflict"))
        let hub = SelfExtensionHub(service: service, invites: service)
        let model = InviteModel(hub: hub, account: account())
        model.selectedId = own

        await model.create(deviceName: { _ in "" })

        XCTAssertEqual(model.phase, .failed(.ownDevice))
        XCTAssertEqual(SelfExtensionFailure.ownDevice.message, L10n.string("invite.error.ownDevice"))
    }

    func testNothingIsSentWithoutAChoice() async {
        let service = FakeSelfService()
        let hub = SelfExtensionHub(service: service, invites: service)
        let model = InviteModel(hub: hub, account: account())

        await model.create(deviceName: { _ in "" })

        XCTAssertTrue(service.invitedDevices.isEmpty)
        XCTAssertEqual(model.phase, .choosing)
    }

    func testRateLimitedAndOtherFailuresReturnToTheList() async {
        let service = FakeSelfService()
        service.inviteResult = .failure(APIError.rateLimited(retryAfterSeconds: 600))
        let hub = SelfExtensionHub(service: service, invites: service)
        let model = InviteModel(hub: hub, account: account())
        model.selectedId = "x"

        await model.create(deviceName: { _ in "" })

        XCTAssertEqual(model.phase, .failed(.rateLimited(retryAfterSeconds: 600)))
        XCTAssertTrue(SelfExtensionFailure.rateLimited(retryAfterSeconds: 600).message.contains("10"))
    }

    func testCountdownFormat() {
        XCTAssertEqual(InviteCountdown.format(582), "9:42")
        XCTAssertEqual(InviteCountdown.format(59.2), "1:00")
        XCTAssertEqual(InviteCountdown.format(0), "0:00")
        XCTAssertEqual(InviteCountdown.format(-5), "0:00")
    }

    func testQRCodeIsDrawn() throws {
        let image = try XCTUnwrap(QRCodeImage.make("https://fullstackstudio.nl/fsvoip/pair?t=fss_vpair_x"))

        XCTAssertGreaterThan(image.size.width, 100)
        XCTAssertEqual(image.size.width, image.size.height)
        XCTAssertNil(QRCodeImage.make(""))
    }

    /// The link lives in the model while the page is open and nowhere else: not in a preference, and the code never logs it.
    func testTheLinkIsNeverStoredOrLogged() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Sources/UI")
        let files = ["settings/InviteModel.swift", "settings/InviteUserPage.swift", "SelfExtensionHub.swift"]

        for file in files {
            let text = try String(contentsOf: root.appendingPathComponent(file))

            for forbidden in ["UserDefaults", "@AppStorage", "@SceneStorage", "print(", "NSLog", "logger.", "FSLogger", "Logger("] {
                XCTAssertFalse(text.contains(forbidden), "\(file) uses \(forbidden)")
            }
        }
    }
}

final class SettingsSurfaceTests: XCTestCase {
    private var root: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Sources/UI")
    }

    /// The pages of Task 11 have no "calling out via" or "anonymous" row either (the dialler owns them).
    func testThePagesHaveNoCallerChoiceOrAnonymousRow() throws {
        let banned = ["uitbellen", "anoniem", "anonymous", "outgoingvia", "callerchoice", "callerid"]
        let files = ["settings/ProfilePage.swift", "settings/CallPreferencesPage.swift", "settings/InviteUserPage.swift", "SettingsSheet.swift"]

        for file in files {
            let text = try String(contentsOf: root.appendingPathComponent(file)).lowercased()

            for word in banned {
                XCTAssertFalse(text.contains(word), "\(file) mentions \(word)")
            }
        }
    }

    func testTheProfileAndPreferencePagesOnlyOfferWhatTheServerAllows() throws {
        let profile = try String(contentsOf: root.appendingPathComponent("settings/ProfilePage.swift"))
        let preferences = try String(contentsOf: root.appendingPathComponent("settings/CallPreferencesPage.swift"))

        XCTAssertTrue(profile.contains("capabilities(for: account.id)?.selfExtension == true"))
        XCTAssertTrue(preferences.contains("capabilities(for: account.id)?.selfExtension == true"))
        XCTAssertTrue(preferences.contains("allowsHangup: false"), "a user cannot forward to 'hang up'")
        // "Beheer" stays behind the same Face ID gate; the invitation too.
        let sheet = try String(contentsOf: root.appendingPathComponent("SettingsSheet.swift"))
        XCTAssertTrue(sheet.contains("InviteGateView"))
        XCTAssertTrue(try String(contentsOf: root.appendingPathComponent("settings/InviteUserPage.swift")).contains("ensureUnlocked"))
    }

    func testTheOutlineHidesBeheerFromAUser() async throws {
        // Covered with models in PbxHubTests/AppModelTests; here only that Beheer's rows are built from the outline.
        XCTAssertTrue(SettingsOutline.Admin.allCases.contains(.invite))
        XCTAssertEqual(SettingsOutline.pages(for: .invite), [.invite])
        XCTAssertEqual(SettingsOutline.pages(for: .profile), [.profile])
        XCTAssertEqual(SettingsOutline.pages(for: .callPreferences), [.callPreferences])
    }
}
