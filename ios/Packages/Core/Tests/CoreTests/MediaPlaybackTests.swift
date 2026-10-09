// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import Core

@MainActor
private final class FakeBackend: AudioBackend {
    var onEvent: ((AudioBackendEvent) -> Void)?
    var isCallActive: () -> Bool = { false }
    private(set) var loaded: [AudioSource] = []
    private(set) var played: [Double] = []
    private(set) var rates: [Double] = []
    private(set) var seeks: [TimeInterval] = []
    private(set) var pauses = 0
    private(set) var stops = 0

    func load(_ source: AudioSource) { loaded.append(source) }
    func play(rate: Double) { played.append(rate) }
    func pause() { pauses += 1 }
    func seek(to seconds: TimeInterval) { seeks.append(seconds) }
    func setRate(_ rate: Double) { rates.append(rate) }
    func stop() { stops += 1 }

    func emit(_ event: AudioBackendEvent) { onEvent?(event) }

    var lastFileURL: URL? {
        if case let .file(url)? = loaded.last { return url }

        return nil
    }

    var lastWasStream: Bool {
        if case .stream? = loaded.last { return true }

        return false
    }
}

@MainActor
final class AudioStreamerTests: XCTestCase {
    private var directory: URL!
    private var cache: MediaCache!
    private var backend: FakeBackend!
    private var callActive = false

    override func setUp() async throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("fsvoip-media-\(UUID().uuidString)", isDirectory: true)
        cache = MediaCache(directory: directory, maxBytes: 1_000)
        backend = FakeBackend()
        callActive = false
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func streamer() -> AudioStreamer {
        AudioStreamer(backend: backend, cache: cache, isCallActive: { [unowned self] in callActive })
    }

    private func request() -> MediaRequest {
        MediaRequest(url: URL(string: "https://example.invalid/api/voip-app/v1/voicemail/b/r/audio")!, deviceToken: Secret("fss_vapp_secret"), userAgent: "FSVoip/1 (test)")
    }

    private nonisolated static func wav() -> MediaDownload {
        MediaDownload(data: Data([0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x41, 0x56, 0x45]), contentType: "audio/wav")
    }

    private func settle() async {
        for _ in 0 ..< 20 { await Task.yield() }
        try? await Task.sleep(nanoseconds: 30_000_000)
    }

    // MARK: Loading and playing

    func testLoadsThenPlaysWhenTheBackendIsReady() {
        let player = streamer()
        player.play(id: "a", source: .stream(request()), fetch: { _ in Self.wav() })

        XCTAssertEqual(player.state, .loading)
        XCTAssertEqual(player.currentId, "a")
        XCTAssertTrue(backend.lastWasStream)

        backend.emit(.ready(duration: 21))

        XCTAssertEqual(player.state, .playing)
        XCTAssertEqual(player.duration, 21)
        XCTAssertEqual(backend.played, [1])

        backend.emit(.progress(5.5))
        XCTAssertEqual(player.position, 5.5)

        backend.emit(.ended)
        XCTAssertEqual(player.state, .ended)
        XCTAssertEqual(player.position, 21)
    }

    func testPauseResumeSeekAndSpeed() {
        let player = streamer()
        player.play(id: "a", source: .stream(request()), fetch: { _ in Self.wav() })
        backend.emit(.ready(duration: 60))

        player.cycleSpeed()
        XCTAssertEqual(player.speed, .fast)
        XCTAssertEqual(backend.rates, [1.5])

        player.pause()
        XCTAssertEqual(player.state, .paused)
        XCTAssertEqual(backend.pauses, 1)

        player.seek(to: 100)
        XCTAssertEqual(player.position, 60, "clamped to the length")
        XCTAssertEqual(backend.seeks, [60])

        player.seek(to: -5)
        XCTAssertEqual(player.position, 0)

        player.resume()
        XCTAssertEqual(player.state, .playing)
        XCTAssertEqual(backend.played.last, 1.5, "a new play uses the chosen speed")

        player.cycleSpeed()
        player.cycleSpeed()
        XCTAssertEqual(player.speed, .normal, "1x, 1.5x, 2x and around")
    }

    func testTogglePlayPauseRestartsAnEndedItem() {
        let player = streamer()
        player.play(id: "a", source: .stream(request()), fetch: { _ in Self.wav() })
        backend.emit(.ready(duration: 10))
        backend.emit(.ended)

        player.togglePlayPause()

        XCTAssertEqual(player.state, .playing)
        XCTAssertEqual(backend.seeks.last, 0)
    }

    func testStopForgetsEverything() {
        let player = streamer()
        player.play(id: "a", source: .stream(request()), fetch: { _ in Self.wav() })
        backend.emit(.ready(duration: 10))
        player.stop()

        XCTAssertEqual(player.state, .idle)
        XCTAssertNil(player.currentId)
        XCTAssertGreaterThan(backend.stops, 0)

        backend.emit(.progress(3))
        XCTAssertEqual(player.position, 0, "events of a stopped item are ignored")
    }

    func testAnInterruptionPauses() {
        let player = streamer()
        player.play(id: "a", source: .stream(request()), fetch: { _ in Self.wav() })
        backend.emit(.ready(duration: 10))
        backend.emit(.interrupted)

        XCTAssertEqual(player.state, .paused)
    }

    // MARK: Calls

    func testNothingPlaysDuringACall() {
        callActive = true
        let player = streamer()
        player.play(id: "a", source: .stream(request()), fetch: { _ in Self.wav() })

        XCTAssertEqual(player.state, .failed(.callInProgress))
        XCTAssertTrue(backend.loaded.isEmpty, "the audio is not even requested")
    }

    func testACallThatStartsPausesWhatPlays() {
        let player = streamer()
        player.play(id: "a", source: .stream(request()), fetch: { _ in Self.wav() })
        backend.emit(.ready(duration: 10))

        callActive = true
        player.callActivityChanged()

        XCTAssertEqual(player.state, .paused)

        player.resume()
        XCTAssertEqual(player.state, .failed(.callInProgress), "resuming over a call is refused")
    }

    func testACallThatStartsWhileLoadingStopsTheLoad() {
        let player = streamer()
        player.play(id: "a", source: .stream(request()), fetch: { _ in Self.wav() })

        callActive = true
        player.callActivityChanged()

        XCTAssertEqual(player.state, .failed(.callInProgress))
        backend.emit(.ready(duration: 10))
        XCTAssertEqual(player.state, .failed(.callInProgress), "a late 'ready' does not start the audio")
        XCTAssertTrue(backend.played.isEmpty)
    }

    // MARK: Fallback

    func testAFailedStreamIsRetriedAsADownloadAndThenPlays() async {
        let player = streamer()
        player.play(id: "box/ref", source: .stream(request()), fetch: { _ in Self.wav() })
        backend.emit(.failed)

        XCTAssertEqual(player.state, .loading)
        XCTAssertTrue(player.usesDownload)

        await settle()

        let file = try? XCTUnwrap(backend.lastFileURL)
        XCTAssertNotNil(file)
        XCTAssertEqual(file?.pathExtension, "wav")
        XCTAssertTrue(FileManager.default.fileExists(atPath: file?.path ?? ""))

        backend.emit(.ready(duration: 12))
        XCTAssertEqual(player.state, .playing)
    }

    func testTheDownloadSaysWhyItFailed() async {
        let cases: [(APIError, MediaFailure)] = [
            (.gone, .gone),
            (.forbidden, .accessDenied),
            (.rateLimited(retryAfterSeconds: 12), .rateLimited(retryAfterSeconds: 12)),
            (.transport("offline"), .offline),
            (.notFound, .notFound),
            (.unavailable(retryable: true, retryAfterSeconds: nil), .unavailable),
        ]

        for (error, expected) in cases {
            let player = streamer()
            player.play(id: "x-\(expected)", source: .stream(request()), fetch: { _ in throw error })
            backend.emit(.failed)
            await settle()

            XCTAssertEqual(player.state, .failed(expected), "\(error)")
        }
    }

    func testAFileThatCannotBePlayedIsUnplayableAndNotRetriedForever() async {
        let player = streamer()
        player.play(id: "a", source: .stream(request()), fetch: { _ in Self.wav() })
        backend.emit(.failed)
        await settle()
        backend.emit(.failed)

        XCTAssertEqual(player.state, .failed(.unplayable))
        XCTAssertEqual(backend.loaded.count, 2, "one stream and one file, no loop")
    }

    func testALocalFileNeverAsksTheServer() {
        let player = streamer()
        player.play(id: "a", source: .file(URL(fileURLWithPath: "/tmp/x.wav")), fetch: { _ in XCTFail("no download"); return Self.wav() })
        backend.emit(.failed)

        XCTAssertEqual(player.state, .failed(.unplayable))
    }

    func testAnItemInTheCacheIsPlayedWithoutStreaming() async {
        let player = streamer()
        player.play(id: "k", source: .stream(request()), fetch: { _ in Self.wav() })
        backend.emit(.failed)
        await settle()

        player.play(id: "k", source: .stream(request()), fetch: { _ in XCTFail("cached"); return Self.wav() })

        XCTAssertNotNil(backend.lastFileURL)
        XCTAssertEqual(backend.loaded.count, 3)
    }

    func testAnOlderLoadDoesNotOverwriteANewerOne() async {
        let player = streamer()
        player.play(id: "slow", source: .stream(request()), fetch: { _ in
            try await Task.sleep(nanoseconds: 80_000_000)
            return Self.wav()
        })
        backend.emit(.failed)
        player.play(id: "next", source: .file(URL(fileURLWithPath: "/tmp/next.wav")), fetch: { _ in Self.wav() })
        try? await Task.sleep(nanoseconds: 200_000_000)

        XCTAssertEqual(player.currentId, "next")
        XCTAssertEqual(backend.loaded.count, 2, "the slow download never reaches the backend")
    }
}

final class MediaCacheTests: XCTestCase {
    private var directory: URL!

    override func setUp() {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("fsvoip-cache-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: directory)
    }

    private func blob(_ size: Int, type: String = "audio/mpeg") -> MediaDownload {
        MediaDownload(data: Data(repeating: 1, count: size), contentType: type)
    }

    func testStoresUnderAHashedNameWithTheRightExtension() throws {
        let cache = MediaCache(directory: directory, maxBytes: 1_000)
        let url = try cache.store(blob(10), key: "caller-box/ref")

        XCTAssertEqual(url.pathExtension, "mp3")
        XCTAssertFalse(url.lastPathComponent.contains("box"), "nothing readable in the name")
        XCTAssertEqual(cache.cachedURL(key: "caller-box/ref"), url)
        XCTAssertNil(cache.cachedURL(key: "other"))
    }

    func testTheLeastRecentlyUsedGoFirst() throws {
        let cache = MediaCache(directory: directory, maxBytes: 250)
        let first = try cache.store(blob(100), key: "1")
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSinceNow: -300)], ofItemAtPath: first.path)
        let second = try cache.store(blob(100), key: "2")
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSinceNow: -200)], ofItemAtPath: second.path)

        // Hearing the first again makes it the freshest.
        XCTAssertNotNil(cache.cachedURL(key: "1"))

        _ = try cache.store(blob(100), key: "3")

        XCTAssertNotNil(cache.cachedURL(key: "1"))
        XCTAssertNil(cache.cachedURL(key: "2"), "the least recently used one went")
        XCTAssertNotNil(cache.cachedURL(key: "3"))
        XCTAssertLessThanOrEqual(cache.totalBytes(), 250)
    }

    func testTheFileJustStoredAlwaysStaysEvenWhenItAloneIsTooBig() throws {
        let cache = MediaCache(directory: directory, maxBytes: 50)
        _ = try cache.store(blob(40), key: "old")
        _ = try cache.store(blob(200), key: "big")

        XCTAssertNotNil(cache.cachedURL(key: "big"))
        XCTAssertNil(cache.cachedURL(key: "old"))
    }

    func testClearRemovesEverything() throws {
        let cache = MediaCache(directory: directory)
        _ = try cache.store(blob(10), key: "a")
        cache.clear()

        XCTAssertEqual(cache.totalBytes(), 0)
        XCTAssertNil(cache.cachedURL(key: "a"))
    }

    func testFileTypeFromContentTypeOrFirstBytes() {
        XCTAssertEqual(AudioFileType.detect(contentType: "audio/x-wav", data: Data()), .wav)
        XCTAssertEqual(AudioFileType.detect(contentType: "audio/mpeg", data: Data()), .mp3)
        XCTAssertEqual(AudioFileType.detect(contentType: "audio/mp4", data: Data()), .m4a)
        XCTAssertEqual(AudioFileType.detect(contentType: "application/octet-stream", data: Data([0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 0, 0, 0, 0])), .mp3)
        XCTAssertEqual(AudioFileType.detect(contentType: nil, data: Data([0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70, 0, 0, 0, 0])), .m4a)
        XCTAssertEqual(AudioFileType.detect(contentType: nil, data: Data([0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x41, 0x56, 0x45])), .wav)
    }
}

final class MediaRequestSecurityTests: XCTestCase {
    private let token = Secret("fss_vapp_" + String(repeating: "x", count: 43))

    private func api(_ replies: [MockTransport.Reply]) -> (FSVoipAPIClient, MockTransport) {
        let transport = MockTransport(replies)
        let api = FSVoipAPIClient(deviceToken: token, transport: transport, userAgent: "FSVoip/1.0 (test)", logger: FSLogger(category: "test", sink: MemoryLogSink()))

        return (api, transport)
    }

    func testTheBearerIsInTheAssetHeadersAndNeverInTheURL() throws {
        let (client, _) = api([])
        let media = try client.voicemailMedia(boxId: "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57", ref: "v1.Zm9vYmFy")
        let options = media.assetOptions()

        XCTAssertEqual(MediaRequest.assetHeaderFieldsKey, "AVURLAssetHTTPHeaderFieldsKey")

        let headers = try XCTUnwrap(options[MediaRequest.assetHeaderFieldsKey] as? [String: String])
        XCTAssertEqual(headers["Authorization"], "Bearer \(token.reveal())")
        XCTAssertFalse(media.url.absoluteString.contains("fss_vapp_"))
        XCTAssertFalse(media.url.absoluteString.lowercased().contains("token"))
        XCTAssertNil(URLComponents(url: media.url, resolvingAgainstBaseURL: false)?.query, "no query string at all")
        XCTAssertEqual(options.count, 1)
    }

    func testDownloadMapsTheStatusAndKeepsTheContentType() async throws {
        let (client, transport) = api([
            .init(status: 200, body: Data([1, 2, 3]), headers: ["Content-Type": "audio/wav"]),
            .init(status: 410, body: Data(#"{"error":"gone"}"#.utf8)),
            .init(status: 429, body: Data(#"{"error":"rate_limited"}"#.utf8), headers: ["Retry-After": "7"]),
            .init(status: 403, body: Data(#"{"error":"forbidden"}"#.utf8)),
        ])
        let media = try client.recordingMedia(callId: "11111111-aaaa-4bbb-8ccc-000000000001")

        let download = try await client.downloadMedia(media)
        XCTAssertEqual(download.data, Data([1, 2, 3]))
        XCTAssertEqual(download.contentType, "audio/wav")
        XCTAssertEqual(transport.requests.first?.value(forHTTPHeaderField: "Authorization"), "Bearer \(token.reveal())")

        do { _ = try await client.downloadMedia(media); XCTFail() } catch { XCTAssertEqual(error as? APIError, .gone) }
        do { _ = try await client.downloadMedia(media); XCTFail() } catch { XCTAssertEqual(error as? APIError, .rateLimited(retryAfterSeconds: 7)) }
        do { _ = try await client.downloadMedia(media); XCTFail() } catch { XCTAssertEqual(error as? APIError, .forbidden) }
    }

    func testFailureClassification() {
        XCTAssertEqual(MediaFailure.classify(APIError.forbidden), .accessDenied)
        XCTAssertEqual(MediaFailure.classify(APIError.gone), .gone)
        XCTAssertEqual(MediaFailure.classify(APIError.readOnly), .readOnly)
        XCTAssertEqual(MediaFailure.classify(APIError.conflict(code: "read_only")), .readOnly)
        XCTAssertEqual(MediaFailure.classify(APIError.transport("x")), .offline)
        XCTAssertEqual(MediaFailure.classify(APIError.unauthorized), .revoked)
        XCTAssertEqual(MediaFailure.classify(CancellationError()), .other)
    }
}
