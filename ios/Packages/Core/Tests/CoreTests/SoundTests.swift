// SPDX-License-Identifier: AGPL-3.0-or-later
import XCTest
@testable import Core

/// The sounds service (upload fields, failure mapping) and the recorder state machine.
final class SoundServiceTests: XCTestCase {
    private func account() throws -> StoredAccount {
        StoredAccount(pairing: try FSVoipJSON.decoder().decode(PairResponse.self, from: Fixtures.data("pair-response")))
    }

    private func service(_ replies: [MockTransport.Reply]) -> (LiveSoundService, MockTransport) {
        let transport = MockTransport(replies)
        let api = FSVoipAPIClient(transport: transport, userAgent: "FSVoip/2.0 (test)", logger: FSLogger(category: "test", sink: MemoryLogSink()))

        return (LiveSoundService(api: api), transport)
    }

    func testUploadSendsExactlyTheNameAndFilePartsAndReturnsTheId() async throws {
        let (service, transport) = service([.init(status: 200, body: try Fixtures.data("sound-upload-response"))])
        let progress = ProgressCollector()

        let id = try await service.upload(for: account(), name: "Welkom", fileName: "opname.m4a", data: Data([0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70]), progress: { progress.add($0) })

        XCTAssertEqual(id, "a1b2c3d4-4444-4a2b-8c3d-4e5f6a7b8c9d")

        let request = try XCTUnwrap(transport.requests.first)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertTrue(request.url?.absoluteString.hasSuffix("/pbx/sounds") == true)

        let text = String(decoding: try XCTUnwrap(request.httpBody), as: UTF8.self)
        let boundary = String(try XCTUnwrap(request.value(forHTTPHeaderField: "Content-Type")).dropFirst("multipart/form-data; boundary=".count))
        XCTAssertEqual(text.components(separatedBy: "--\(boundary)\r\n").count - 1, 2)
        XCTAssertTrue(text.contains("name=\"name\"\r\n\r\nWelkom\r\n"))
        XCTAssertTrue(text.contains("name=\"file\"; filename=\"opname.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n"))
        XCTAssertEqual(progress.last, 1)
    }

    func testUploadFailuresBecomeTheirSoundFailure() async throws {
        let cases: [(Int, String, SoundFailure)] = [
            (400, "error-invalid-audio", .invalidAudio),
            (413, "error-too-large", .tooLarge),
            (409, "error-too-many", .tooMany),
        ]

        for (status, fixture, expected) in cases {
            let (service, _) = service([.init(status: status, body: try Fixtures.data(fixture))])

            do {
                _ = try await service.upload(for: account(), name: "x", fileName: "x.mp3", data: Data([1]), progress: nil)
                XCTFail("expected \(expected)")
            } catch {
                XCTAssertEqual(SoundFailure.classify(error), expected, fixture)
            }
        }
    }

    func testAFileAboveTheLimitIsTooLargeWithoutARequest() async throws {
        let (service, transport) = service([])

        do {
            _ = try await service.upload(for: account(), name: "x", fileName: "x.wav", data: Data(count: SoundUpload.maxBytes + 1), progress: nil)
            XCTFail("expected tooLarge")
        } catch {
            XCTAssertEqual(SoundFailure.classify(error), .tooLarge)
        }

        XCTAssertTrue(transport.requests.isEmpty)
    }

    func testDeletingASoundInUseCarriesThePlaces() async throws {
        let (service, _) = service([.init(status: 409, body: try Fixtures.data("error-in-use"))])

        do {
            try await service.delete(for: account(), id: "a1b2c3d4-1111-4a2b-8c3d-4e5f6a7b8c9d")
            XCTFail("expected inUse")
        } catch {
            guard case let .inUse(places) = SoundFailure.classify(error) else { return XCTFail("not inUse: \(error)") }

            XCTAssertFalse(places.isEmpty)
        }
    }

    func testOtherFailuresAreSortedIntoWhatTheUserCanDo() {
        XCTAssertEqual(SoundFailure.classify(APIError.forbidden), .accessDenied)
        XCTAssertEqual(SoundFailure.classify(APIError.unauthorized), .revoked)
        XCTAssertEqual(SoundFailure.classify(APIError.readOnly), .readOnly)
        XCTAssertEqual(SoundFailure.classify(APIError.notFound), .notFound)
        XCTAssertEqual(SoundFailure.classify(APIError.rateLimited(retryAfterSeconds: 30)), .rateLimited(retryAfterSeconds: 30))
        XCTAssertEqual(SoundFailure.classify(APIError.transport("offline")), .offline)
        XCTAssertEqual(SoundFailure.classify(APIError.payloadTooLarge), .tooLarge)
        XCTAssertEqual(SoundFailure.classify(CocoaError(.fileReadUnknown)), .other)
    }

    func testTheSoundRouteIsAStreamWithTheBearerToken() throws {
        let (service, _) = service([])
        let source = try service.source(for: account(), id: "a1b2c3d4-1111-4a2b-8c3d-4e5f6a7b8c9d")

        guard case let .stream(request) = source else { return XCTFail("expected a stream") }

        XCTAssertTrue(request.url.path.hasSuffix("/pbx/sounds/a1b2c3d4-1111-4a2b-8c3d-4e5f6a7b8c9d/audio"))
        XCTAssertFalse(request.url.absoluteString.contains("fss_vapp_"), "the token never sits in the URL")
    }
}

private final class ProgressCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [Double] = []

    func add(_ value: Double) { lock.withLock { values.append(value) } }
    var last: Double? { lock.withLock { values.last } }
}

@MainActor
private final class FakeRecorderBackend: RecorderBackend {
    var onInterrupted: (() -> Void)?
    var permission = true
    var failToStart = false
    var time: TimeInterval = 0
    private(set) var started: [URL] = []
    private(set) var stops = 0

    func requestPermission() async -> Bool { permission }

    func start(to url: URL) throws {
        if failToStart { throw CocoaError(.fileWriteUnknown) }

        FileManager.default.createFile(atPath: url.path, contents: Data([1, 2, 3]))
        started.append(url)
    }

    func currentTime() -> TimeInterval { time }

    func stop() -> TimeInterval {
        stops += 1

        return time
    }
}

@MainActor
final class AudioRecorderSessionTests: XCTestCase {
    private var backend: FakeRecorderBackend!
    private var directory: URL!
    private var callActive = false

    override func setUp() async throws {
        backend = FakeRecorderBackend()
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("fsvoip-rec-test-\(UUID().uuidString)", isDirectory: true)
        callActive = false
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func session() -> AudioRecorderSession {
        // A clock that never ticks by itself: the tests drive `tick()`.
        AudioRecorderSession(backend: backend, directory: directory, isCallActive: { [unowned self] in callActive }, sleep: { _ in try? await Task.sleep(nanoseconds: 60_000_000_000) })
    }

    func testStartRecordStopKeepsTheRecording() async throws {
        let recorder = session()

        await recorder.start()
        XCTAssertEqual(recorder.state, .recording)
        let file = try XCTUnwrap(recorder.fileURL)
        XCTAssertEqual(file.pathExtension, "m4a")
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))

        backend.time = 12.5
        recorder.tick()
        XCTAssertEqual(recorder.elapsed, 12.5)

        recorder.stop()
        XCTAssertEqual(recorder.state, .recorded(duration: 12.5))
        XCTAssertEqual(backend.stops, 1)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))

        XCTAssertEqual(recorder.takeFile(), file)
        XCTAssertEqual(recorder.state, .idle)
    }

    func testRecordingDuringACallIsRefusedAndNeverStartsTheMicrophone() async {
        callActive = true
        let recorder = session()

        await recorder.start()

        XCTAssertEqual(recorder.state, .failed(.callInProgress))
        XCTAssertTrue(backend.started.isEmpty)
    }

    func testACallThatStartsWhileRecordingStopsTheRecording() async {
        let recorder = session()
        await recorder.start()
        backend.time = 4

        callActive = true
        recorder.callActivityChanged()

        XCTAssertEqual(recorder.state, .recorded(duration: 4))
        XCTAssertEqual(backend.stops, 1)
    }

    func testAMicrophoneThatIsNotAllowedFailsCleanly() async {
        backend.permission = false
        let recorder = session()

        await recorder.start()

        XCTAssertEqual(recorder.state, .failed(.permissionDenied))
        XCTAssertTrue(backend.started.isEmpty)
        XCTAssertNil(recorder.fileURL)
    }

    func testTheLimitOfFiveMinutesStopsTheRecording() async {
        let recorder = session()
        await recorder.start()

        backend.time = AudioRecorderSession.maxDuration + 7
        recorder.tick()

        XCTAssertEqual(recorder.state, .recorded(duration: AudioRecorderSession.maxDuration))
        XCTAssertEqual(AudioRecorderSession.maxDuration, 300)
    }

    func testARecordingUnderASecondIsThrownAway() async throws {
        let recorder = session()
        await recorder.start()
        let file = try XCTUnwrap(recorder.fileURL)

        backend.time = 0.4
        recorder.stop()

        XCTAssertEqual(recorder.state, .failed(.tooShort))
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
    }

    func testCancelAndDiscardRemoveTheTemporaryFile() async throws {
        let recorder = session()
        await recorder.start()
        let file = try XCTUnwrap(recorder.fileURL)

        recorder.cancel()

        XCTAssertEqual(recorder.state, .idle)
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))

        await recorder.start()
        backend.time = 3
        recorder.stop()
        let second = try XCTUnwrap(recorder.fileURL)
        recorder.discard()
        XCTAssertFalse(FileManager.default.fileExists(atPath: second.path))
        XCTAssertEqual(recorder.state, .idle)
    }

    func testAStartThatFailsLeavesNothingBehind() async {
        backend.failToStart = true
        let recorder = session()

        await recorder.start()

        XCTAssertEqual(recorder.state, .failed(.couldNotStart))
        XCTAssertNil(recorder.fileURL)
    }

    func testTheSystemTakingTheMicrophoneStopsAndKeepsWhatWasRecorded() async {
        let recorder = session()
        await recorder.start()
        backend.time = 6

        backend.onInterrupted?()

        XCTAssertEqual(recorder.state, .recorded(duration: 6))
    }

    func testAnEarlierRecordingIsRemovedWhenANewOneStarts() async throws {
        let recorder = session()
        await recorder.start()
        backend.time = 3
        recorder.stop()
        let first = try XCTUnwrap(recorder.fileURL)

        await recorder.start()

        XCTAssertFalse(FileManager.default.fileExists(atPath: first.path))
        XCTAssertNotEqual(recorder.fileURL, first)
    }
}
