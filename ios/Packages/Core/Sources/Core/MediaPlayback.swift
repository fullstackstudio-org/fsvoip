// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Playing recordings and voicemail. Core owns the rules (what a failure means to the user, the cache of the download fallback,
// the state machine of the player); the AVFoundation player lives in the UI package behind `AudioBackend`, so this runs in the
// tests on a Mac with a fake backend.

import CryptoKit
import Foundation

// MARK: - What goes wrong, in the user's terms

/// Why a recording or voicemail did not play (or could not be deleted). The server decides what is allowed; this sorts the answer
/// into what the user can do about it.
public enum MediaFailure: Equatable, Sendable {
    /// 403: this pairing may not hear/delete this. The screens hide the part.
    case accessDenied
    /// 401: the pairing is gone.
    case revoked
    /// 410: past its retention period (recordings 90 days, voicemail 30).
    case gone
    /// 404: it is already gone (deleted somewhere else).
    case notFound
    case rateLimited(retryAfterSeconds: Int?)
    case offline
    case unavailable
    /// 409 `read_only`: the phone system does not accept changes now.
    case readOnly
    /// A call is going on: audio does not play over a call.
    case callInProgress
    /// The audio arrived but cannot be played.
    case unplayable
    case other

    public static func classify(_ error: Error) -> MediaFailure {
        guard let api = error as? APIError else {
            return .other
        }

        switch api {
        case .forbidden: return .accessDenied
        case .unauthorized: return .revoked
        case .gone: return .gone
        case .notFound: return .notFound
        case let .rateLimited(seconds): return .rateLimited(retryAfterSeconds: seconds)
        case .transport: return .offline
        case .unavailable, .unexpectedStatus: return .unavailable
        case .readOnly: return .readOnly
        case .conflict(let code) where code == "read_only": return .readOnly
        default: return .other
        }
    }
}

// MARK: - Audio files

public enum AudioFileType: String, Sendable {
    case wav
    case mp3
    case m4a

    /// From the `Content-Type` of the answer, else from the first bytes (a file in the cache needs the right extension for AVFoundation).
    public static func detect(contentType: String?, data: Data) -> AudioFileType {
        let type = contentType?.lowercased() ?? ""

        if type.contains("wav") { return .wav }
        if type.contains("mpeg") || type.contains("mp3") { return .mp3 }
        if type.contains("mp4") || type.contains("m4a") || type.contains("aac") { return .m4a }

        let head = [UInt8](data.prefix(12))

        if head.count >= 12, head[0 ..< 4] == [0x52, 0x49, 0x46, 0x46] { return .wav }
        if head.count >= 8, head[4 ..< 8] == [0x66, 0x74, 0x79, 0x70] { return .m4a }
        if head.count >= 3, head[0 ..< 3] == [0x49, 0x44, 0x33] { return .mp3 }
        if head.count >= 2, head[0] == 0xFF, head[1] & 0xE0 == 0xE0 { return .mp3 }

        return .m4a
    }
}

public struct MediaDownload: Sendable {
    public var data: Data
    public var contentType: String?

    public init(data: Data, contentType: String?) {
        self.data = data
        self.contentType = contentType
    }
}

/// Where the audio comes from.
public enum AudioSource: Sendable {
    /// Streamed from the server by AVFoundation (it issues the `Range` requests itself).
    case stream(MediaRequest)
    /// A file on this phone (the download fallback, and the demo mode).
    case file(URL)
}

// MARK: - Cache of the download fallback

/// The files of the download fallback: in the temporary directory, protected, out of the backups, at most `maxBytes` in total
/// (the least recently used go first). A file is named after a hash of its key, so nothing in the name tells what it is.
public final class MediaCache: @unchecked Sendable {
    public static let defaultMaxBytes = 50 * 1024 * 1024

    public let directory: URL
    public let maxBytes: Int
    private let fileManager: FileManager
    private let lock = NSLock()

    public init(directory: URL? = nil, maxBytes: Int = MediaCache.defaultMaxBytes, fileManager: FileManager = .default) {
        self.directory = directory ?? fileManager.temporaryDirectory.appendingPathComponent("FSVoipMedia", isDirectory: true)
        self.maxBytes = maxBytes
        self.fileManager = fileManager
    }

    private func name(for key: String, type: AudioFileType) -> String {
        let digest = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()

        return "\(digest.prefix(32)).\(type.rawValue)"
    }

    private func prepareDirectory() throws {
        if !fileManager.fileExists(atPath: directory.path) {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        }

        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = directory
        try? url.setResourceValues(values)
    }

    /// The file for this key if it is there (and counts as used just now).
    public func cachedURL(key: String) -> URL? {
        lock.lock()
        defer { lock.unlock() }

        for type in [AudioFileType.wav, .mp3, .m4a] {
            let url = directory.appendingPathComponent(name(for: key, type: type))

            if fileManager.fileExists(atPath: url.path) {
                try? fileManager.setAttributes([.modificationDate: Date()], ofItemAtPath: url.path)

                return url
            }
        }

        return nil
    }

    @discardableResult
    public func store(_ download: MediaDownload, key: String) throws -> URL {
        lock.lock()
        defer { lock.unlock() }

        try prepareDirectory()

        let type = AudioFileType.detect(contentType: download.contentType, data: download.data)
        let url = directory.appendingPathComponent(name(for: key, type: type))
        #if os(iOS)
        try download.data.write(to: url, options: [.atomic, .completeFileProtection])
        #else
        try download.data.write(to: url, options: .atomic)
        #endif
        trim(keeping: url)

        return url
    }

    /// Removes the oldest files until the total fits; the file just stored always stays.
    private func trim(keeping keep: URL) {
        let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey]
        let urls = (try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: keys)) ?? []
        var entries: [(url: URL, date: Date, size: Int)] = urls.compactMap { url in
            guard let values = try? url.resourceValues(forKeys: Set(keys)) else { return nil }

            return (url, values.contentModificationDate ?? .distantPast, values.fileSize ?? 0)
        }
        var total = entries.reduce(0) { $0 + $1.size }

        entries.sort { $0.date < $1.date }

        for entry in entries where total > maxBytes && entry.url.lastPathComponent != keep.lastPathComponent {
            try? fileManager.removeItem(at: entry.url)
            total -= entry.size
        }
    }

    public func totalBytes() -> Int {
        lock.lock()
        defer { lock.unlock() }

        let urls = (try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])) ?? []

        return urls.reduce(0) { $0 + ((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
    }

    public func clear() {
        lock.lock()
        defer { lock.unlock() }

        try? fileManager.removeItem(at: directory)
    }
}

// MARK: - The player's state machine

public enum PlaybackSpeed: Double, CaseIterable, Sendable {
    case normal = 1
    case fast = 1.5
    case faster = 2

    /// 1× → 1,5× → 2× → 1×.
    public var next: PlaybackSpeed {
        switch self {
        case .normal: return .fast
        case .fast: return .faster
        case .faster: return .normal
        }
    }
}

public enum AudioBackendEvent: Equatable, Sendable {
    /// The item can play; `duration` in seconds when known.
    case ready(duration: TimeInterval?)
    case progress(TimeInterval)
    case ended
    case failed
    /// Something outside the app took the audio (a phone call, Siri, an alarm).
    case interrupted
}

/// What plays the sound. The UI package implements it with `AVPlayer`; tests use a fake. Events arrive on the main actor.
@MainActor
public protocol AudioBackend: AnyObject {
    var onEvent: ((AudioBackendEvent) -> Void)? { get set }
    /// Set by the owner: a call is in progress, so the audio session is not the backend's to change.
    var isCallActive: () -> Bool { get set }
    func load(_ source: AudioSource)
    func play(rate: Double)
    func pause()
    func seek(to seconds: TimeInterval)
    func setRate(_ rate: Double)
    /// Stops and lets go of the item and the audio session.
    func stop()
}

/// One item at a time: the state of what is being played and the rules around it.
///
/// - A stream that fails to load is retried once as a download (`fetch`, into the `MediaCache`); that is also how the real reason
///   (410, 403, 429, offline) comes out, because AVFoundation reports every HTTP failure as the same error.
/// - Nothing plays during a call, and a call that starts pauses what plays (`callActivityChanged`).
@MainActor
public final class AudioStreamer: ObservableObject {
    public enum State: Equatable {
        case idle
        case loading
        case paused
        case playing
        case ended
        case failed(MediaFailure)
    }

    @Published public private(set) var state: State = .idle
    /// The id the caller gave `play(id:...)`; `nil` = nothing selected.
    @Published public private(set) var currentId: String?
    @Published public private(set) var position: TimeInterval = 0
    @Published public private(set) var duration: TimeInterval?
    @Published public private(set) var speed: PlaybackSpeed = .normal
    /// The current item plays from the download fallback.
    @Published public private(set) var usesDownload = false

    private let backend: AudioBackend
    private let cache: MediaCache
    private let isCallActive: () -> Bool
    private var generation = 0
    private var wantsToPlay = false
    private var streamRequest: MediaRequest?
    private var fetchFallback: (@Sendable (MediaRequest) async throws -> MediaDownload)?
    private var fallbackTask: Task<Void, Never>?

    public init(backend: AudioBackend, cache: MediaCache = MediaCache(), isCallActive: @escaping () -> Bool = { false }) {
        self.backend = backend
        self.cache = cache
        self.isCallActive = isCallActive
        backend.onEvent = { [weak self] event in self?.handle(event) }
    }

    public var isActive: Bool {
        currentId != nil
    }

    /// Load this item and start playing it. `fetch` is the download used when streaming fails.
    public func play(id: String, source: AudioSource, fetch: @escaping @Sendable (MediaRequest) async throws -> MediaDownload) {
        reset()
        generation += 1
        currentId = id
        fetchFallback = fetch

        if case let .stream(request) = source {
            streamRequest = request
        }

        guard !isCallActive() else {
            state = .failed(.callInProgress)

            return
        }

        state = .loading
        wantsToPlay = true

        if case .stream = source, let cached = cache.cachedURL(key: id) {
            // Heard before and still in the cache: no need to ask the server again (and it counts against the media limit).
            usesDownload = true
            backend.load(.file(cached))
        } else {
            backend.load(source)
        }
    }

    public func togglePlayPause() {
        switch state {
        case .playing: pause()
        case .paused: resume()
        case .ended:
            seek(to: 0)
            resume()
        default: break
        }
    }

    public func pause() {
        guard state == .playing else { return }

        wantsToPlay = false
        backend.pause()
        state = .paused
    }

    public func resume() {
        guard state == .paused || state == .ended else { return }

        guard !isCallActive() else {
            state = .failed(.callInProgress)

            return
        }

        wantsToPlay = true
        backend.play(rate: speed.rawValue)
        state = .playing
    }

    public func seek(to seconds: TimeInterval) {
        guard currentId != nil, state != .loading else { return }

        let upper = duration ?? max(seconds, position)
        let target = min(max(0, seconds), upper)
        position = target
        backend.seek(to: target)

        if state == .ended {
            state = .paused
        }
    }

    public func skip(by seconds: TimeInterval) {
        seek(to: position + seconds)
    }

    public func setSpeed(_ value: PlaybackSpeed) {
        speed = value

        if state == .playing {
            backend.setRate(value.rawValue)
        }
    }

    public func cycleSpeed() {
        setSpeed(speed.next)
    }

    /// The user left the screen or closed the player.
    public func stop() {
        reset()
        generation += 1
        state = .idle
        currentId = nil
    }

    /// Call this when the phone's call state changes: a call that starts pauses (or blocks) the audio.
    public func callActivityChanged() {
        guard isCallActive() else { return }

        switch state {
        case .playing:
            pause()
        case .loading:
            // Let go of the stream: it must not start by itself in the middle of a call.
            let id = currentId
            reset()
            generation += 1
            currentId = id
            state = .failed(.callInProgress)
        default:
            break
        }
    }

    // MARK: Events of the backend

    private func handle(_ event: AudioBackendEvent) {
        guard currentId != nil else { return }

        switch event {
        case let .ready(length):
            guard state == .loading else { return }

            duration = length.flatMap { $0.isFinite && $0 > 0 ? $0 : nil }

            if wantsToPlay, !isCallActive() {
                backend.play(rate: speed.rawValue)
                state = .playing
            } else {
                state = .paused
            }
        case let .progress(seconds):
            if state == .playing || state == .paused {
                position = seconds
            }
        case .ended:
            guard state == .playing else { return }

            if let duration { position = duration }

            wantsToPlay = false
            state = .ended
        case .interrupted:
            if state == .playing {
                wantsToPlay = false
                state = .paused
            }
        case .failed:
            failed()
        }
    }

    private func failed() {
        guard state == .loading || state == .playing || state == .paused else { return }

        if !usesDownload, let request = streamRequest, let fetch = fetchFallback {
            // The stream failed: fetch the whole file. Its answer says why (410, 403, 429, offline) and it plays when it works.
            usesDownload = true
            state = .loading
            backend.stop()
            download(request, key: currentId ?? "", fetch: fetch)

            return
        }

        wantsToPlay = false
        state = .failed(.unplayable)
    }

    private func download(_ request: MediaRequest, key: String, fetch: @escaping @Sendable (MediaRequest) async throws -> MediaDownload) {
        let token = generation
        let cache = cache

        fallbackTask = Task { [weak self] in
            do {
                let download = try await fetch(request)
                let url = try await Task.detached { try cache.store(download, key: key) }.value

                guard let self, !Task.isCancelled, token == self.generation else { return }

                self.backend.load(.file(url))
            } catch is CancellationError {
                return
            } catch {
                guard let self, !Task.isCancelled, token == self.generation else { return }

                self.wantsToPlay = false
                self.state = .failed(MediaFailure.classify(error))
            }
        }
    }

    private func reset() {
        fallbackTask?.cancel()
        fallbackTask = nil
        backend.stop()
        position = 0
        duration = nil
        wantsToPlay = false
        usesDownload = false
        streamRequest = nil
        fetchFallback = nil
    }
}
