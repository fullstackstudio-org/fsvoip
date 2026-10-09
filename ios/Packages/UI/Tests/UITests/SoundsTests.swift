// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation
import XCTest
@testable import UI

private final class FakeSoundService: SoundServicing, @unchecked Sendable {
    private let lock = NSLock()
    private(set) var calls: [String] = []
    private(set) var uploads: [(name: String, fileName: String, bytes: Int)] = []
    var failures: [String: [Error]] = [:]
    var items: [Sound] = FakeSoundService.decode(
        #"{"sounds":[{"id":"s-2","name":"Welkom","playable":true,"uses":[{"kind":"menu_greeting","name":"Support"}],"sync":"ok"},{"id":"s-1","name":"Buiten kantoortijd","playable":true,"uses":[],"sync":"ok"}]}"#
    )
    var nextId = "s-new"

    static func decode(_ json: String) -> [Sound] {
        try! FSVoipJSON.decoder().decode(SoundsPage.self, from: Data(json.utf8)).sounds
    }

    private func enter(_ name: String) throws {
        try lock.withLock {
            calls.append(name)

            if var queue = failures[name], !queue.isEmpty {
                let error = queue.removeFirst()
                failures[name] = queue

                throw error
            }
        }
    }

    func count(_ name: String) -> Int { lock.withLock { calls.filter { $0 == name }.count } }

    func sounds(for account: StoredAccount) async throws -> [Sound] {
        try enter("sounds")

        return items
    }

    func upload(for account: StoredAccount, name: String, fileName: String, data: Data, progress: (@Sendable (Double) -> Void)?) async throws -> String {
        try enter("upload")
        lock.withLock { uploads.append((name, fileName, data.count)) }
        progress?(1)
        items.append(Self.decode(#"{"sounds":[{"id":"\#(nextId)","name":"\#(name)","playable":true,"uses":[],"sync":"pending"}]}"#)[0])

        return nextId
    }

    func rename(for account: StoredAccount, id: String, name: String) async throws {
        try enter("rename")

        if let index = items.firstIndex(where: { $0.id == id }) { items[index].name = name }
    }

    func delete(for account: StoredAccount, id: String) async throws {
        try enter("delete")
        items.removeAll { $0.id == id }
    }

    func source(for account: StoredAccount, id: String) throws -> AudioSource {
        try enter("source")

        return .stream(MediaRequest(url: URL(string: "https://example.invalid/api/voip-app/v1/pbx/sounds/\(id)/audio")!, deviceToken: Secret("fss_vapp_y"), userAgent: "FSVoip/1 (test)"))
    }
}

@MainActor
private final class StubRecorderBackend: RecorderBackend {
    var onInterrupted: (() -> Void)?
    func requestPermission() async -> Bool { true }
    func start(to url: URL) throws { FileManager.default.createFile(atPath: url.path, contents: Data([1, 2, 3])) }
    func currentTime() -> TimeInterval { 5 }
    func stop() -> TimeInterval { 5 }
}

@MainActor
final class SoundsTests: XCTestCase {
    private var sounds: FakeSoundService!
    private var hub: MediaHub!
    private var audio: FakeAudioBackend!
    private var cacheDirectory: URL!

    override func setUp() async throws {
        sounds = FakeSoundService()
        audio = FakeAudioBackend()
        cacheDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("fsvoip-ui-sounds-\(UUID().uuidString)", isDirectory: true)
        hub = MediaHub(
            service: FakeMediaService(),
            gate: LocalAccessGate(authenticator: FakeLocalAuth()),
            cache: MediaCache(directory: cacheDirectory),
            backend: audio,
            soundService: sounds
        )
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: cacheDirectory)
    }

    private func account(_ id: String = "admin-1") -> StoredAccount {
        StoredAccount(id: id, label: "Jan", pbxName: "Voorbeeld Bouw", extensionName: "Jan", extensionNumber: "102", customerName: "Voorbeeld", deviceToken: Secret("fss_vapp_x"), installId: "a1b2c3d4e5f60718", sip: SIPCredentials(username: "102", password: Secret("pw"), domain: "x.powervoip.nl", proxy: "sip.powervoip.nl", port: 5061, transport: .tls, srv: true), pairedAt: Date(timeIntervalSince1970: 1_790_000_000))
    }

    private func model() throws -> SoundsModel {
        hub.apply(me: PbxFixtures.decode("me-response-admin-v2", as: MeResponse.self), accountId: "admin-1")

        return try XCTUnwrap(hub.soundsModel(for: account()))
    }

    // MARK: Who sees it

    func testAPlainUserNeverGetsASoundsModelOrTheRight() {
        hub.apply(me: PbxFixtures.decode("me-response-user-v2", as: MeResponse.self), accountId: "user-1")

        XCTAssertFalse(hub.canManageSounds("user-1"))
        XCTAssertNil(hub.soundsModel(for: account("user-1")))
    }

    func testAnAdminGetsOneSharedModelAndLosesItWhenTheRightGoes() throws {
        let first = try model()

        XCTAssertTrue(hub.canManageSounds("admin-1"))
        XCTAssertTrue(first === hub.soundsModel(for: account()))

        hub.apply(me: PbxFixtures.decode("me-response-user-v2", as: MeResponse.self), accountId: "admin-1")

        XCTAssertFalse(hub.canManageSounds("admin-1"))
        XCTAssertNil(hub.soundsModel(for: account()))
    }

    func testA403OnTheSoundsRoutesTakesTheRightAway() async throws {
        let model = try model()
        sounds.failures["sounds"] = [APIError.forbidden]

        await model.load()

        XCTAssertFalse(hub.canManageSounds("admin-1"))
    }

    // MARK: Loading and the picker

    func testTheListIsSortedByName() async throws {
        let model = try model()

        await model.load()

        XCTAssertEqual(model.sounds.map(\.name), ["Buiten kantoortijd", "Welkom"])
        XCTAssertEqual(model.name(of: "s-2"), "Welkom")
    }

    func testApplyOnlyWhenTheChoiceChangedAndNothingIsBeingAdded() {
        XCTAssertTrue(SoundSelection.canApply(selection: "s-2", current: nil, allowsNone: false, isUploading: false))
        XCTAssertFalse(SoundSelection.canApply(selection: "s-2", current: "s-2", allowsNone: false, isUploading: false))
        XCTAssertFalse(SoundSelection.canApply(selection: nil, current: "s-2", allowsNone: false, isUploading: false), "no 'none' where it is not allowed")
        XCTAssertTrue(SoundSelection.canApply(selection: nil, current: "s-2", allowsNone: true, isUploading: false))
        XCTAssertFalse(SoundSelection.canApply(selection: "s-1", current: nil, allowsNone: true, isUploading: true))
    }

    // MARK: Adding

    func testAFileIsUploadedAndTheNewSoundIsSelectable() async throws {
        let model = try model()
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("Welkom nieuw.mp3")
        try Data([0x49, 0x44, 0x33, 4]).write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }

        let id = await model.addFile(url: file)

        XCTAssertEqual(id, "s-new")
        XCTAssertEqual(model.lastAddedId, "s-new")
        XCTAssertEqual(sounds.uploads.first?.name, "Welkom nieuw")
        XCTAssertEqual(sounds.uploads.first?.fileName, "Welkom nieuw.mp3")
        XCTAssertEqual(model.name(of: "s-new"), "Welkom nieuw", "the list was reloaded")
        XCTAssertNil(model.upload)
    }

    func testAFileAboveTheLimitIsNeverReadOrSent() async throws {
        let model = try model()
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("groot-\(UUID().uuidString).wav")
        FileManager.default.createFile(atPath: file.path, contents: nil)
        let handle = try FileHandle(forWritingTo: file)
        try handle.truncate(atOffset: UInt64(SoundUpload.maxBytes + 1))
        try handle.close()
        defer { try? FileManager.default.removeItem(at: file) }

        let id = await model.addFile(url: file)

        XCTAssertNil(id)
        XCTAssertEqual(sounds.count("upload"), 0)
        XCTAssertEqual(model.banner?.text, SoundFailure.tooLarge.message)
    }

    func testUploadFailuresShowTheirOwnWords() async throws {
        let cases: [(APIError, SoundFailure)] = [(.invalidAudio, .invalidAudio), (.tooLarge, .tooLarge), (.tooMany, .tooMany)]

        for (error, expected) in cases {
            let model = try model()
            let file = FileManager.default.temporaryDirectory.appendingPathComponent("x-\(UUID().uuidString).mp3")
            try Data([1, 2, 3]).write(to: file)
            defer { try? FileManager.default.removeItem(at: file) }
            sounds.failures["upload"] = [error]

            let id = await model.addFile(url: file)

            XCTAssertNil(id)
            XCTAssertEqual(model.banner?.text, expected.message)
            XCTAssertNil(model.upload, "the progress bar goes away")
            XCTAssertNil(model.lastAddedId)
        }
    }

    func testARecordingIsUploadedAsM4aAndTheTemporaryFileIsRemoved() async throws {
        hub.apply(me: PbxFixtures.decode("me-response-admin-v2", as: MeResponse.self), accountId: "admin-1")
        let model = SoundsModel(account: account(), hub: hub, recorderBackend: StubRecorderBackend())

        await model.recorder.start()
        model.recorder.stop()
        let file = try XCTUnwrap(model.recorder.fileURL)

        let id = await model.addRecording(name: "  Mijn bericht \n")

        XCTAssertEqual(id, "s-new")
        XCTAssertEqual(sounds.uploads.first?.name, "Mijn bericht")
        XCTAssertEqual(sounds.uploads.first?.fileName, "opname.m4a")
        XCTAssertEqual(model.duration(of: try XCTUnwrap(model.sound("s-new"))), 5)
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
        XCTAssertEqual(model.recorder.state, .idle)
    }

    func testAFailedRecordingUploadKeepsTheRecordingForANewTry() async throws {
        hub.apply(me: PbxFixtures.decode("me-response-admin-v2", as: MeResponse.self), accountId: "admin-1")
        let model = SoundsModel(account: account(), hub: hub, recorderBackend: StubRecorderBackend())
        await model.recorder.start()
        model.recorder.stop()
        let file = try XCTUnwrap(model.recorder.fileURL)
        sounds.failures["upload"] = [APIError.transport("offline")]

        let id = await model.addRecording(name: "Welkom")

        XCTAssertNil(id)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        XCTAssertEqual(model.banner?.text, SoundFailure.offline.message)

        let second = await model.addRecording(name: "Welkom")
        XCTAssertEqual(second, "s-new")
    }

    // MARK: Renaming and deleting

    func testDeletingASoundThatIsInUseExplainsWhere() async throws {
        let model = try model()
        await model.load()
        let welcome = try XCTUnwrap(model.sound("s-2"))
        sounds.failures["delete"] = [APIError.inUse(places: [APIErrorPlace(kind: "menu_greeting", name: "Support")])]

        await model.delete(welcome)

        XCTAssertEqual(model.deleteBlock?.places, [APIErrorPlace(kind: "menu_greeting", name: "Support")])
        XCTAssertEqual(model.deleteBlock?.soundName, "Welkom")
        XCTAssertNotNil(model.sound("s-2"), "nothing was deleted")
        XCTAssertTrue(SoundPlaces.inUseMessage(model.deleteBlock?.places ?? []).contains("Support"))
    }

    func testDeletingAnUnusedSoundRemovesItAndRenamingReloads() async throws {
        let model = try model()
        await model.load()

        let renamed = await model.rename(try XCTUnwrap(model.sound("s-1")), to: "  Gesloten  ")
        XCTAssertTrue(renamed)
        XCTAssertEqual(model.name(of: "s-1"), "Gesloten")

        await model.delete(try XCTUnwrap(model.sound("s-1")))
        XCTAssertNil(model.sound("s-1"))
        XCTAssertNil(model.deleteBlock)
    }

    // MARK: Listening

    func testListeningStreamsWithTheBearerAndCachesTheDuration() async throws {
        let model = try model()
        let backend = try XCTUnwrap(audio)
        await model.load()

        model.play(try XCTUnwrap(model.sound("s-2")))
        backend.emit(.ready(duration: 21))

        guard case let .stream(request)? = backend.loaded.first else { return XCTFail("expected a stream") }

        XCTAssertTrue(request.url.path.hasSuffix("/pbx/sounds/s-2/audio"))
        XCTAssertFalse(request.url.absoluteString.contains("fss_vapp_"))
        XCTAssertEqual(model.durations["s-2"], 21)
        XCTAssertEqual(model.duration(of: try XCTUnwrap(model.sound("s-2"))), 21)
    }

    func testASoundWithoutACopyIsNotPlayable() async throws {
        let model = try model()
        sounds.items = FakeSoundService.decode(#"{"sounds":[{"id":"old","name":"Oud","playable":false,"uses":[],"sync":"ok"}]}"#)
        await model.load()

        model.play(try XCTUnwrap(model.sound("old")))

        XCTAssertEqual(sounds.count("source"), 0)
    }
}
