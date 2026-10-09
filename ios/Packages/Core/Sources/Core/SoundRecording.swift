// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Recording a sound with the microphone. Core owns the rules (the state machine, the five-minute limit, no recording during a call,
// the temporary file that is always cleaned up); the AVFoundation recorder lives in the UI package behind `RecorderBackend`, so
// this runs in the tests on a Mac with a fake backend.

import Foundation

public enum RecorderFailure: Equatable, Sendable {
    /// The microphone is not allowed (Settings > FSVoip).
    case permissionDenied
    /// A call is going on: the microphone belongs to the call.
    case callInProgress
    /// Shorter than a second: nothing worth keeping.
    case tooShort
    case couldNotStart
}

/// What records. The UI package implements it with `AVAudioRecorder`; tests use a fake.
@MainActor
public protocol RecorderBackend: AnyObject {
    /// Called when the system takes the microphone away (a call comes in, Siri): the session stops what it has.
    var onInterrupted: (() -> Void)? { get set }
    func requestPermission() async -> Bool
    /// Starts recording AAC (`.m4a`) to `url`. Throws when the recorder cannot start.
    func start(to url: URL) throws
    /// Seconds recorded so far.
    func currentTime() -> TimeInterval
    /// Stops and returns the seconds recorded; lets go of the audio session.
    func stop() -> TimeInterval
}

/// One recording: idle → (permission) → recording → recorded, or failed. The file lives in the temporary directory and is removed
/// by `discard()`, `cancel()`, a new `start()` and `deinit`; the caller that keeps it (`takeFile()`) owns it from then on.
@MainActor
public final class AudioRecorderSession: ObservableObject {
    public enum State: Equatable, Sendable {
        case idle
        case requestingPermission
        case recording
        case recorded(duration: TimeInterval)
        case failed(RecorderFailure)
    }

    /// Five minutes.
    public static let maxDuration: TimeInterval = 300
    public static let minDuration: TimeInterval = 1

    @Published public private(set) var state: State = .idle
    @Published public private(set) var elapsed: TimeInterval = 0

    public private(set) var fileURL: URL?

    private let backend: RecorderBackend
    private let directory: URL
    private let isCallActive: () -> Bool
    private let sleep: @Sendable (TimeInterval) async -> Void
    private var ticker: Task<Void, Never>?
    private var generation = 0

    public init(
        backend: RecorderBackend,
        directory: URL = FileManager.default.temporaryDirectory.appendingPathComponent("FSVoipRecordings", isDirectory: true),
        isCallActive: @escaping () -> Bool = { false },
        sleep: @escaping @Sendable (TimeInterval) async -> Void = { seconds in try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)) }
    ) {
        self.backend = backend
        self.directory = directory
        self.isCallActive = isCallActive
        self.sleep = sleep
        backend.onInterrupted = { [weak self] in self?.interrupted() }
    }

    deinit {
        ticker?.cancel()

        if let fileURL {
            try? FileManager.default.removeItem(at: fileURL)
        }
    }

    public var isRecording: Bool {
        state == .recording
    }

    /// Removes recordings an earlier run left behind (a crash while recording).
    public static func purgeStaleFiles(in directory: URL = FileManager.default.temporaryDirectory.appendingPathComponent("FSVoipRecordings", isDirectory: true)) {
        try? FileManager.default.removeItem(at: directory)
    }

    // MARK: Control

    public func start() async {
        removeFile()
        elapsed = 0

        guard !isCallActive() else {
            state = .failed(.callInProgress)

            return
        }

        generation += 1
        let mine = generation
        state = .requestingPermission

        guard await backend.requestPermission() else {
            if mine == generation { state = .failed(.permissionDenied) }

            return
        }

        // Cancelled while the permission question was open, or a call started in the meantime.
        guard mine == generation, state == .requestingPermission else { return }

        guard !isCallActive() else {
            state = .failed(.callInProgress)

            return
        }

        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: directory.path)
            let url = directory.appendingPathComponent("rec-\(UUID().uuidString).m4a")
            fileURL = url
            try backend.start(to: url)
            state = .recording
            startTicker(generation: mine)
        } catch {
            removeFile()
            state = .failed(.couldNotStart)
        }
    }

    /// Stops recording and keeps the result (at least a second).
    public func stop() {
        guard state == .recording else { return }

        finish()
    }

    /// Throws the recording away and goes back to the start.
    public func cancel() {
        generation += 1
        ticker?.cancel()
        ticker = nil

        if state == .recording {
            _ = backend.stop()
        }

        removeFile()
        elapsed = 0
        state = .idle
    }

    /// Throws away a finished recording ("Opnieuw").
    public func discard() {
        cancel()
    }

    /// Hands the file to the caller (which removes it after the upload). The session is idle afterwards.
    public func takeFile() -> URL? {
        guard case .recorded = state, let url = fileURL else { return nil }

        fileURL = nil
        elapsed = 0
        state = .idle

        return url
    }

    /// Called by the owner when a call starts: what is recording stops (the call needs the microphone).
    public func callActivityChanged() {
        if state == .recording, isCallActive() {
            finish()
        }
    }

    /// One step of the clock: the limit of five minutes. Public so the tests drive it without waiting.
    public func tick() {
        guard state == .recording else { return }

        elapsed = min(backend.currentTime(), Self.maxDuration)

        if elapsed >= Self.maxDuration {
            finish()
        }
    }

    // MARK: Private

    private func interrupted() {
        if state == .recording {
            finish()
        }
    }

    private func finish() {
        ticker?.cancel()
        ticker = nil
        let seconds = min(backend.stop(), Self.maxDuration)
        elapsed = seconds

        if seconds < Self.minDuration {
            removeFile()
            state = .failed(.tooShort)
        } else {
            state = .recorded(duration: seconds)
        }
    }

    private func startTicker(generation mine: Int) {
        ticker?.cancel()
        let sleep = sleep
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                await sleep(0.25)

                guard !Task.isCancelled else { return }

                await MainActor.run { [weak self] in
                    guard let self, self.generation == mine else { return }

                    self.tick()
                }
            }
        }
    }

    private func removeFile() {
        if let fileURL {
            try? FileManager.default.removeItem(at: fileURL)
        }

        fileURL = nil
    }
}
