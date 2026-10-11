// SPDX-License-Identifier: AGPL-3.0-or-later
import AVFoundation
import Core
import Foundation
import XCTest
@testable import UI

private final class TestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var value = Date(timeIntervalSince1970: 1_790_000_000)

    var now: Date { lock.withLock { value } }
    func advance(_ seconds: TimeInterval) { lock.withLock { value = value.addingTimeInterval(seconds) } }
}

@MainActor
final class MediaTests: XCTestCase {
    private var service: FakeMediaService!
    private var auth: FakeLocalAuth!
    private var gate: LocalAccessGate!
    private var backend: FakeAudioBackend!
    private var cacheDirectory: URL!
    private var hub: MediaHub!
    private var clock: TestClock!

    override func setUp() async throws {
        service = FakeMediaService()
        auth = FakeLocalAuth()
        let clock = TestClock()
        self.clock = clock
        gate = LocalAccessGate(authenticator: auth, now: { clock.now })
        backend = FakeAudioBackend()
        cacheDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("fsvoip-ui-media-\(UUID().uuidString)", isDirectory: true)
        hub = MediaHub(service: service, gate: gate, cache: MediaCache(directory: cacheDirectory), backend: backend)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: cacheDirectory)
    }

    private func account(_ id: String = "admin-1", number: String? = "102") -> StoredAccount {
        StoredAccount(id: id, label: "Jan", pbxName: "Voorbeeld Bouw", extensionName: "Jan", extensionNumber: number, customerName: "Voorbeeld", deviceToken: Secret("fss_vapp_x"), installId: "a1b2c3d4e5f60718", sip: SIPCredentials(username: "102", password: Secret("pw"), domain: "x.powervoip.nl", proxy: "sip.powervoip.nl", port: 5061, transport: .tls, srv: true), pairedAt: Date(timeIntervalSince1970: 1_790_000_000))
    }

    private func admin(_ id: String = "admin-1") {
        hub.apply(me: PbxFixtures.me, accountId: id)
    }

    private func user(_ id: String = "user-1") {
        hub.apply(me: PbxFixtures.meUser, accountId: id)
    }

    private func settle() async {
        for _ in 0 ..< 10 { await Task.yield() }
        try? await Task.sleep(nanoseconds: 20_000_000)
    }

    // MARK: Rights from /me

    func testAnAdminGetsVoicemailAndRecordings() {
        admin()

        XCTAssertEqual(hub.access(for: "admin-1"), MediaHub.Access(voicemail: .all, recordings: true, readOnly: false))
        XCTAssertTrue(hub.hasVoicemail("admin-1"))
        XCTAssertTrue(hub.hasRecordings("admin-1"))
    }

    func testAUserGetsOnlyTheOwnVoicemailAndNoRecordings() {
        user()

        XCTAssertEqual(hub.access(for: "user-1")?.voicemail, .own)
        XCTAssertTrue(hub.hasVoicemail("user-1"))
        XCTAssertFalse(hub.hasRecordings("user-1"), "recordings are admin only")
    }

    func testAFrozenPhoneSystemIsReadOnly() {
        hub.apply(me: PbxFixtures.meFrozen, accountId: "a")

        XCTAssertEqual(hub.access(for: "a")?.readOnly, true)
    }

    func testWithoutCapabilitiesNothingIsOffered() {
        var me = PbxFixtures.me
        me.capabilities = nil
        hub.apply(me: me, accountId: "old")

        XCTAssertFalse(hub.isAvailable("old"), "fail closed: an older server gives no section")
    }

    func testARoleThatNoLongerHasRecordingsKeepsVoicemailAndSaysSo() {
        admin()
        var me = PbxFixtures.me
        me.capabilities?.recordings = false
        hub.apply(me: me, accountId: "admin-1")

        XCTAssertFalse(hub.hasRecordings("admin-1"))
        XCTAssertTrue(hub.hasVoicemail("admin-1"))
        XCTAssertEqual(hub.lostAccessFor, "admin-1")
    }

    func testA403OnTheMeCallHidesEverythingAndLocks() async {
        admin()
        _ = await gate.ensureUnlocked(reason: "x")
        hub.accessDenied(accountId: "admin-1")

        XCTAssertFalse(hub.isAvailable("admin-1"))
        XCTAssertFalse(gate.isUnlocked)
    }

    // MARK: Voicemail, user

    func testAUserSeesAllMessagesWithoutAnyCheck() async {
        user()
        service.voicemailPage.boxes = [service.voicemailPage.boxes[1]]
        service.voicemailPage.messages = service.voicemailPage.messages.filter { $0.boxId == MediaFixtures.ownBox }
        let model = hub.makeVoicemailModel(account: account("user-1"))
        await model.load()

        XCTAssertEqual(model.messages.count, 2)
        XCTAssertFalse(model.canChooseBox)
        XCTAssertFalse(model.needsUnlock)
        XCTAssertEqual(auth.evaluations, 0, "the own voicemail never asks for Face ID")
        XCTAssertEqual(service.requestedBoxes.count, 1)
        XCTAssertNil(service.requestedBoxes[0], "no box: the server decides what a user sees")
    }

    func testPlayingAMessageGivesTheTokenInTheHeadersOnly() async throws {
        user()
        service.voicemailPage.messages = service.voicemailPage.messages.filter { $0.boxId == MediaFixtures.ownBox }
        let model = hub.makeVoicemailModel(account: account("user-1"))
        await model.load()
        let message = try XCTUnwrap(model.messages.first)

        model.play(message)

        XCTAssertEqual(hub.player.currentId, message.id)
        XCTAssertEqual(model.nowPlaying?.id, message.id)

        guard case let .stream(request)? = backend.loaded.last else { return XCTFail("streams first") }

        XCTAssertFalse(request.url.absoluteString.contains("fss_vapp_"))
        XCTAssertEqual(request.headers()["Authorization"], "Bearer \(service.tokenForSources.reveal())")
        XCTAssertEqual(AVPlayerBackend.assetOptions(for: .stream(request))?[MediaRequest.assetHeaderFieldsKey] as? [String: String], request.headers())
        XCTAssertNil(AVPlayerBackend.assetOptions(for: .file(URL(fileURLWithPath: "/tmp/x.wav"))))
        XCTAssertFalse(AVPlayerBackend.makeAsset(for: .stream(request)).url.absoluteString.contains("fss_vapp_"))
    }

    // MARK: Voicemail, admin

    func testAnAdminStartsOnTheOwnBoxAndNeverSeesOthersWhileLocked() async {
        admin()
        let model = hub.makeVoicemailModel(account: account())
        await model.load()

        XCTAssertEqual(model.ownBoxId, MediaFixtures.ownBox)
        XCTAssertEqual(model.scope, .own)
        XCTAssertEqual(Set(model.messages.map(\.boxId)), [MediaFixtures.ownBox])
        XCTAssertEqual(model.messages.count, 2)
        XCTAssertEqual(auth.evaluations, 0)
        XCTAssertTrue(model.canChooseBox)
    }

    func testAskingForAllOpensAfterTheLocalCheck() async {
        admin()
        let model = hub.makeVoicemailModel(account: account())
        await model.load()

        await model.select(.all)

        XCTAssertEqual(auth.evaluations, 1)
        XCTAssertEqual(model.scope, .all)
        XCTAssertEqual(model.messages.count, 4)

        await model.select(.box(MediaFixtures.colleagueBox))
        XCTAssertEqual(auth.evaluations, 1, "inside the five minutes nobody is asked again")
        XCTAssertEqual(model.messages.map(\.boxId), [MediaFixtures.colleagueBox])
    }

    func testACancelledCheckKeepsTheOwnBox() async {
        admin()
        auth.results = [.cancelled]
        let model = hub.makeVoicemailModel(account: account())
        await model.load()

        await model.select(.all)

        XCTAssertEqual(model.scope, .own)
        XCTAssertEqual(model.messages.count, 2)
        XCTAssertFalse(model.needsUnlock)
    }

    func testAPhoneWithoutPasscodeCannotOpenOthers() async {
        admin()
        auth.availabilityValue = .noPasscode
        let model = hub.makeVoicemailModel(account: account())
        await model.load()

        await model.select(.all)

        XCTAssertEqual(model.scope, .own)
        XCTAssertTrue(model.noPasscode)
    }

    func testWhenTheFiveMinutesAreOverTheScreenFallsBackAndStopsAColleaguesMessage() async throws {
        admin()
        let model = hub.makeVoicemailModel(account: account())
        await model.load()
        await model.select(.all)
        let colleague = try XCTUnwrap(model.messages.first { $0.boxId == MediaFixtures.colleagueBox })
        model.play(colleague)
        XCTAssertNotNil(model.nowPlaying)

        clock.advance(LocalAccessGate.validity + 1)
        model.refreshLock()

        XCTAssertEqual(model.scope, .own)
        XCTAssertEqual(model.messages.count, 2)
        XCTAssertNil(hub.player.currentId, "the colleague's message stopped")
        XCTAssertNil(model.nowPlaying)
    }

    func testAnAdminWithoutAnOwnBoxSeesALockUntilUnlocked() async {
        admin()
        let model = hub.makeVoicemailModel(account: account(number: nil))
        await model.load()

        XCTAssertEqual(model.scope, .all)
        XCTAssertTrue(model.needsUnlock)
        XCTAssertTrue(model.messages.isEmpty)

        await model.unlock()

        XCTAssertFalse(model.needsUnlock)
        XCTAssertEqual(model.messages.count, 4)
    }

    // MARK: Deleting

    func testDeletingInTheOwnBoxNeedsNoCheck() async throws {
        user()
        service.voicemailPage.messages = service.voicemailPage.messages.filter { $0.boxId == MediaFixtures.ownBox }
        let model = hub.makeVoicemailModel(account: account("user-1"))
        await model.load()
        let message = try XCTUnwrap(model.messages.first)
        model.play(message)

        await model.delete(message)

        XCTAssertEqual(service.deleted.map(\.ref), [message.ref])
        XCTAssertEqual(model.messages.count, 1)
        XCTAssertNil(hub.player.currentId, "what is being deleted stops first")
        XCTAssertEqual(auth.evaluations, 0)
    }

    func testDeletingAColleaguesMessageNeedsTheOpenCheck() async throws {
        admin()
        let model = hub.makeVoicemailModel(account: account())
        await model.load()
        await model.select(.all)
        let colleague = try XCTUnwrap(model.messages.first { $0.boxId == MediaFixtures.colleagueBox })

        clock.advance(LocalAccessGate.validity + 1)
        await model.delete(colleague)

        XCTAssertTrue(service.deleted.isEmpty, "nothing was deleted without a fresh check")
        XCTAssertEqual(auth.evaluations, 2, "it asked again instead")
    }

    func testAFrozenPhoneSystemBlocksDeleting() async throws {
        hub.apply(me: PbxFixtures.meFrozen, accountId: "admin-1")
        let model = hub.makeVoicemailModel(account: account())
        await model.load()
        let message = try XCTUnwrap(model.messages.first)

        XCTAssertTrue(model.isReadOnly)

        await model.delete(message)

        XCTAssertTrue(service.deleted.isEmpty)
        XCTAssertEqual(model.banner?.text, L10n.string("media.delete.readOnly"))
    }

    func testA409ReadOnlyOnDeleteBlocksFurtherDeleting() async throws {
        user()
        service.voicemailPage.messages = service.voicemailPage.messages.filter { $0.boxId == MediaFixtures.ownBox }
        service.failures["deleteVoicemail"] = [APIError.readOnly]
        let model = hub.makeVoicemailModel(account: account("user-1"))
        await model.load()
        let message = try XCTUnwrap(model.messages.first)

        await model.delete(message)

        XCTAssertEqual(model.messages.count, 2, "the message stays")
        XCTAssertTrue(model.isReadOnly)
        XCTAssertEqual(model.banner?.isError, true)
    }

    func testDeletingSomethingAlreadyGoneJustRemovesItFromTheList() async throws {
        user()
        service.voicemailPage.messages = service.voicemailPage.messages.filter { $0.boxId == MediaFixtures.ownBox }
        service.failures["deleteVoicemail"] = [APIError.notFound]
        let model = hub.makeVoicemailModel(account: account("user-1"))
        await model.load()
        let message = try XCTUnwrap(model.messages.first)

        await model.delete(message)

        XCTAssertEqual(model.messages.count, 1)
        XCTAssertNil(model.banner)
    }

    // MARK: Errors

    func testA403OnLoadingTakesVoicemailAway() async {
        admin()
        service.failures["voicemail"] = [APIError.forbidden]
        let model = hub.makeVoicemailModel(account: account())
        await model.load()

        XCTAssertFalse(hub.hasVoicemail("admin-1"))
        XCTAssertTrue(hub.hasRecordings("admin-1"), "just that part goes")
        XCTAssertEqual(model.failure, .accessDenied)
    }

    func testOfflineKeepsTheListAndSaysSo() async {
        admin()
        let model = hub.makeVoicemailModel(account: account())
        await model.load()
        service.failures["voicemail"] = [APIError.transport("offline")]
        await model.reload()

        XCTAssertEqual(model.messages.count, 2)
        XCTAssertEqual(model.banner?.text, MediaFailure.offline.message(for: .voicemail))
    }

    func testTheFirstLoadFailingShowsTheFailure() async {
        user()
        service.failures["voicemail"] = [APIError.rateLimited(retryAfterSeconds: 30)]
        let model = hub.makeVoicemailModel(account: account("user-1"))
        await model.load()

        XCTAssertNil(model.page)
        XCTAssertEqual(model.failure, .rateLimited(retryAfterSeconds: 30))
        XCTAssertTrue(model.failure!.message(for: .voicemail).contains("30"))
    }

    func testAPbxThatDoesNotAnswerIsAnoticeNotAnEmptyList() async {
        user()
        service.voicemailPage = MediaFixtures.decode(#"{"boxes": [], "boxId": null, "messages": [], "available": false}"#)
        let model = hub.makeVoicemailModel(account: account("user-1"))
        await model.load()

        XCTAssertTrue(model.isUnavailable)
    }

    func testAMessageThatTurnsOutToBeGoneIsMarkedAndNotPlayableAgain() async throws {
        user()
        service.voicemailPage.messages = service.voicemailPage.messages.filter { $0.boxId == MediaFixtures.ownBox }
        let model = hub.makeVoicemailModel(account: account("user-1"))
        await model.load()
        let message = try XCTUnwrap(model.messages.first)
        model.play(message)

        // The stream fails, the download says 410.
        service.failures["download"] = [APIError.gone]
        backend.emit(.failed)
        await settle()

        XCTAssertEqual(hub.player.state, .failed(.gone))
        XCTAssertTrue(model.isGone(message))

        let loads = backend.loaded.count
        model.play(message)
        XCTAssertEqual(backend.loaded.count, loads, "a gone message is not requested again")
    }

    // MARK: Recordings

    func testRecordingsListsOnlyRecordedCallsOfTheMonth() async {
        admin()
        let model = hub.makeRecordingsModel(account: account())
        await model.load()

        XCTAssertEqual(model.rows.count, 2)
        XCTAssertTrue(model.rows.allSatisfy(\.hasRecording))
        XCTAssertEqual(model.months, ["2026-10", "2026-09"])
        XCTAssertEqual(service.requestedMonths.count, 1)
        XCTAssertNil(service.requestedMonths[0], "no month = the current one")
    }

    func testChoosingAnotherMonthLoadsItAndShowsExpiredRecordingsAsGone() async {
        admin()
        let model = hub.makeRecordingsModel(account: account())
        await model.load()

        await model.select(month: "2026-09")

        XCTAssertEqual(service.requestedMonths.last ?? nil, "2026-09")
        XCTAssertEqual(model.rows.count, 1)
        XCTAssertTrue(model.isGone(model.rows[0]), "past retention: 'Niet meer beschikbaar'")

        let loads = backend.loaded.count
        model.play(model.rows[0])
        XCTAssertEqual(backend.loaded.count, loads)
    }

    func testPlayingARecordingStreamsWithTheTokenInTheHeaders() async throws {
        admin()
        let model = hub.makeRecordingsModel(account: account())
        await model.load()
        let call = try XCTUnwrap(model.rows.first)

        model.play(call)

        guard case let .stream(request)? = backend.loaded.last else { return XCTFail() }

        XCTAssertTrue(request.url.path.hasSuffix("/recording"))
        XCTAssertFalse(request.url.absoluteString.contains("fss_vapp_"))
        XCTAssertNotNil(request.headers()["Authorization"])
        XCTAssertEqual(model.nowPlaying?.id, call.id)
    }

    func testA410WhilePlayingARecordingMarksItGone() async throws {
        admin()
        let model = hub.makeRecordingsModel(account: account())
        await model.load()
        let call = try XCTUnwrap(model.rows.first)
        service.failures["download"] = [APIError.gone]

        model.play(call)
        backend.emit(.failed)
        await settle()

        XCTAssertTrue(model.isGone(call))
        XCTAssertEqual(hub.player.state, .failed(.gone))
        XCTAssertEqual(MediaFailure.gone.message(for: .recording), L10n.string("media.error.gone.recording"))
    }

    func testA403OnTheRecordingTakesRecordingsAwayButNotVoicemail() async throws {
        admin()
        let model = hub.makeRecordingsModel(account: account())
        await model.load()
        let call = try XCTUnwrap(model.rows.first)
        service.failures["download"] = [APIError.forbidden]

        model.play(call)
        backend.emit(.failed)
        await settle()

        XCTAssertFalse(hub.hasRecordings("admin-1"))
        XCTAssertTrue(hub.hasVoicemail("admin-1"))
    }

    func testA429WhilePlayingExplainsAndOffersRetry() async throws {
        admin()
        let model = hub.makeRecordingsModel(account: account())
        await model.load()
        let call = try XCTUnwrap(model.rows.first)
        service.failures["download"] = [APIError.rateLimited(retryAfterSeconds: 20)]

        model.play(call)
        backend.emit(.failed)
        await settle()

        guard case let .failed(failure) = hub.player.state else { return XCTFail() }

        XCTAssertEqual(failure, .rateLimited(retryAfterSeconds: 20))
        XCTAssertTrue(failure.isRetryable)
        XCTAssertTrue(failure.message(for: .recording).contains("20"))
    }

    // MARK: Calls

    func testNothingPlaysWhileACallIsGoingAndACallPausesWhatPlays() async throws {
        admin()
        let model = hub.makeRecordingsModel(account: account())
        await model.load()
        let call = try XCTUnwrap(model.rows.first)

        model.play(call)
        backend.emit(.ready(duration: 30))
        XCTAssertEqual(hub.player.state, .playing)

        hub.callStateChanged(isActive: true)
        XCTAssertEqual(hub.player.state, .paused)

        hub.player.resume()
        XCTAssertEqual(hub.player.state, .failed(.callInProgress))

        let loads = backend.loaded.count
        model.play(call)
        XCTAssertEqual(backend.loaded.count, loads, "not even requested during a call")
    }

    // MARK: Words

    func testClockAndSpeedFormats() {
        XCTAssertEqual(MediaFormat.clock(0), "0:00")
        XCTAssertEqual(MediaFormat.clock(83.9), "1:23")
        XCTAssertEqual(MediaFormat.clock(3725), "1:02:05")
        XCTAssertEqual(MediaFormat.speed(.fast, locale: Locale(identifier: "nl_NL")), "1,5×")
        XCTAssertEqual(MediaFormat.speed(.fast, locale: Locale(identifier: "en_US")), "1.5×")
        XCTAssertEqual(MediaFormat.speed(.normal, locale: Locale(identifier: "nl_NL")), "1×")
        XCTAssertEqual(MediaFormat.month("2026-10", locale: Locale(identifier: "nl_NL")).lowercased(), "oktober 2026")
    }

    func testDaysLeftWords() {
        XCTAssertEqual(MediaFormat.daysLeft(29), "Nog 29 dagen")
        XCTAssertEqual(MediaFormat.daysLeft(1), "Nog 1 dag")
        XCTAssertEqual(MediaFormat.daysLeft(0), "Laatste dag")
        XCTAssertNil(MediaFormat.daysLeft(nil))
    }
}

final class MediaLocalizationTests: XCTestCase {
    private static func strings(_ language: String) -> [String: String] {
        let path = Bundle.module.path(forResource: "Localizable", ofType: "strings", inDirectory: nil, forLocalization: language)!
        let dictionary = NSDictionary(contentsOfFile: path) as! [String: String]

        return dictionary.filter { $0.key.hasPrefix("media.") }
    }

    private func placeholders(_ text: String) -> [String] {
        let regex = try! NSRegularExpression(pattern: "%(?:\\d+\\$)?(?:ld|@|d|f)")
        let range = NSRange(text.startIndex..., in: text)

        return regex.matches(in: text, range: range).map { String(text[Range($0.range, in: text)!]) }.sorted()
    }

    func testBothLanguagesHaveTheSameKeysAndPlaceholders() {
        let nl = Self.strings("nl")
        let en = Self.strings("en")

        XCTAssertGreaterThan(nl.count, 60)
        XCTAssertEqual(Set(nl.keys), Set(en.keys))

        for (key, text) in nl {
            XCTAssertEqual(placeholders(text), placeholders(en[key] ?? ""), key)
            XCTAssertFalse(text.isEmpty, key)
        }
    }

    func testNoTelecomJargon() {
        for (key, text) in Self.strings("nl") {
            for word in ["extensie", "ivr", "trunk", "gateway", "sip", "dialplan", "pbx"] {
                XCTAssertNil(text.lowercased().range(of: "\\b\(word)\\b", options: .regularExpression), "\(key): \(text)")
            }
        }
    }

    func testEveryFailureHasAWordInBothContexts() {
        let failures: [MediaFailure] = [.accessDenied, .revoked, .gone, .notFound, .rateLimited(retryAfterSeconds: nil), .rateLimited(retryAfterSeconds: 5), .offline, .unavailable, .readOnly, .callInProgress, .unplayable, .other]

        for failure in failures {
            for kind in [MediaKind.recording, .voicemail] {
                let text = failure.message(for: kind)
                XCTAssertFalse(text.isEmpty)
                XCTAssertFalse(text.hasPrefix("media."), "missing translation: \(text)")
            }
        }
    }
}
