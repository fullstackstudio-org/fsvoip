// SPDX-License-Identifier: AGPL-3.0-or-later
import Combine
import Core
import Foundation

/// A sound that cannot be deleted because it is still used: what to tell the user.
struct SoundDeleteBlock: Identifiable, Equatable {
    let id = UUID()
    let soundName: String
    let places: [APIErrorPlace]
}

/// The progress of one upload.
struct SoundUploadState: Equatable {
    var name: String
    var fraction: Double
}

/// The sounds of the centrale of ONE paired account (an admin only): the list, listening, adding (a file or a recording), renaming
/// and deleting. It is shared by "Beheer › Geluiden" and the audio picker of the number screens, so a sound added in one place is
/// there in the other. The server enforces every rule; this keeps the screens honest.
@MainActor
final class SoundsModel: ObservableObject {
    let account: StoredAccount

    @Published private(set) var sounds: [Sound] = []
    @Published private(set) var isLoading = false
    @Published private(set) var hasLoaded = false
    @Published private(set) var failure: SoundFailure?
    /// Seconds per sound id, read from the audio when it was played (or from the file just added). Kept for the life of the model.
    @Published private(set) var durations: [String: TimeInterval] = [:]
    @Published private(set) var upload: SoundUploadState?
    @Published private(set) var playbackFailure: MediaFailure?
    @Published var banner: MediaBanner?
    @Published var deleteBlock: SoundDeleteBlock?
    /// The id of the sound that was added last (the picker selects it).
    @Published private(set) var lastAddedId: String?

    let player: AudioStreamer
    let recorder: AudioRecorderSession

    private let hub: MediaHub
    private let service: SoundServicing
    private let download: MediaServicing
    private var uploadTask: Task<String?, Never>?
    private var observers: Set<AnyCancellable> = []
    private var loadGeneration = 0
    static let localPreviewId = "local-recording"
    /// Leftovers of an earlier run (a crash while recording) are removed ONCE per process, before the first recorder exists. Doing it in
    /// every init would delete the recording another account's model is still holding.
    private static let purgeStaleRecordingsOnce: Void = AudioRecorderSession.purgeStaleFiles()

    init(account: StoredAccount, hub: MediaHub, recorderBackend: RecorderBackend? = nil) {
        self.account = account
        self.hub = hub
        service = hub.soundService
        download = hub.service
        player = hub.player

        let flag = { [weak hub] in hub?.isCallActive ?? false }
        recorder = AudioRecorderSession(backend: recorderBackend ?? AVRecorderBackend(), isCallActive: flag)
        _ = Self.purgeStaleRecordingsOnce

        player.$duration
            .sink { [weak self] value in self?.noteDuration(value) }
            .store(in: &observers)
        player.$state
            .removeDuplicates()
            .sink { [weak self] state in
                if case let .failed(failure) = state { self?.playbackFailure = failure } else { self?.playbackFailure = nil }
            }
            .store(in: &observers)
        // What `ObservableObject` children publish must reach the views that watch this model.
        recorder.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &observers)
    }

    // MARK: Reading

    func sound(_ id: String?) -> Sound? {
        guard let id else { return nil }

        return sounds.first { $0.id == id }
    }

    func name(of id: String?) -> String? {
        sound(id)?.name
    }

    func duration(of sound: Sound) -> TimeInterval? {
        durations[sound.id] ?? sound.durationSeconds
    }

    var isUploading: Bool { upload != nil }

    func load() async {
        loadGeneration += 1
        let mine = loadGeneration
        isLoading = true
        defer { if mine == loadGeneration { isLoading = false } }

        do {
            let list = try await service.sounds(for: account)

            guard mine == loadGeneration else { return }

            sounds = list.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            hasLoaded = true
            failure = nil
        } catch {
            guard mine == loadGeneration else { return }

            handle(error, whileLoading: true)
        }
    }

    // MARK: Listening

    func isPlaying(_ sound: Sound) -> Bool {
        player.currentId == sound.id && player.state == .playing
    }

    func isCurrent(_ sound: Sound) -> Bool {
        player.currentId == sound.id
    }

    func togglePlay(_ sound: Sound) {
        banner = nil
        hub.gate.touch()

        if player.currentId == sound.id, player.state != .idle, !isFailed(player.state) {
            player.togglePlayPause()

            return
        }

        play(sound)
    }

    func play(_ sound: Sound) {
        guard sound.playable else { return }

        do {
            let source = try service.source(for: account, id: sound.id)
            let download = download
            let account = account
            player.play(id: sound.id, source: source) { request in
                try await download.download(request, for: account)
            }
        } catch {
            handle(error, whileLoading: false)
        }
    }

    /// The preview of a recording that is not uploaded yet.
    func playLocal(_ url: URL) {
        if player.currentId == Self.localPreviewId, player.state != .idle, !isFailed(player.state) {
            player.togglePlayPause()

            return
        }

        player.play(id: Self.localPreviewId, source: .file(url)) { _ in throw APIError.notFound }
    }

    func stopPlaying() {
        player.stop()
    }

    func stopAll() {
        player.stop()
        recorder.cancel()
        uploadTask?.cancel()
    }

    /// A call started: what records stops, what plays pauses (the player does that itself).
    func callStarted() {
        recorder.callActivityChanged()
    }

    private func isFailed(_ state: AudioStreamer.State) -> Bool {
        if case .failed = state { return true }

        return false
    }

    private func noteDuration(_ value: TimeInterval?) {
        guard let value, let id = player.currentId, id != Self.localPreviewId, durations[id] != value else { return }

        durations[id] = value
    }

    // MARK: Adding

    /// Adds a file the user picked in Bestanden. The size is checked on disk first: a file above the limit is never read.
    func addFile(url: URL, name: String? = nil) async -> String? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        // An unreadable size is not "0 bytes": refuse instead of reading a file of unknown size.
        guard let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize else {
            show(.other)

            return nil
        }

        guard size <= SoundUpload.maxBytes else {
            show(.tooLarge)

            return nil
        }

        let data: Data

        do {
            data = try await Task.detached { try Data(contentsOf: url, options: .mappedIfSafe) }.value
        } catch {
            show(.other)

            return nil
        }

        let fileName = url.lastPathComponent
        let title = Self.cleanName(name ?? (fileName as NSString).deletingPathExtension)

        return await add(name: title, fileName: fileName, data: data, knownDuration: nil)
    }

    /// Adds the finished recording of the recorder. The temporary file stays until the upload worked (a failed upload can be tried again).
    func addRecording(name: String) async -> String? {
        guard let url = recorder.fileURL, case let .recorded(duration) = recorder.state else { return nil }

        guard let data = try? Data(contentsOf: url) else {
            show(.other)

            return nil
        }

        let id = await add(name: Self.cleanName(name), fileName: "opname.m4a", data: data, knownDuration: duration)

        if id != nil {
            stopPlaying()
            recorder.discard()
        }

        return id
    }

    private func add(name: String, fileName: String, data: Data, knownDuration: TimeInterval?) async -> String? {
        guard upload == nil else { return nil }

        banner = nil
        hub.gate.touch()
        upload = SoundUploadState(name: name, fraction: 0)
        let service = service
        let account = account

        let task = Task { [weak self] () -> String? in
            do {
                let id = try await service.upload(for: account, name: name, fileName: fileName, data: data) { fraction in
                    Task { @MainActor [weak self] in self?.upload?.fraction = fraction }
                }

                return id
            } catch {
                guard !Task.isCancelled else { return nil }

                self?.handle(error, whileLoading: false)

                return nil
            }
        }
        uploadTask = task
        let id = await task.value
        uploadTask = nil
        upload = nil

        guard let id else { return nil }

        if let knownDuration {
            durations[id] = knownDuration
        }

        lastAddedId = id
        await load()

        return id
    }

    func cancelUpload() {
        uploadTask?.cancel()
    }

    // MARK: Renaming and deleting

    func rename(_ sound: Sound, to newName: String) async -> Bool {
        let title = Self.cleanName(newName)

        guard !title.isEmpty, title != sound.name else { return true }

        hub.gate.touch()

        do {
            try await service.rename(for: account, id: sound.id, name: title)
            await load()

            return true
        } catch {
            handle(error, whileLoading: false)

            return false
        }
    }

    /// Deletes a sound. A sound that is still used stays (`deleteBlock` says where).
    func delete(_ sound: Sound) async {
        hub.gate.touch()

        do {
            try await service.delete(for: account, id: sound.id)

            if player.currentId == sound.id { player.stop() }

            sounds.removeAll { $0.id == sound.id }
            durations[sound.id] = nil
            await load()
        } catch let error as APIError {
            if case let .inUse(places) = error {
                deleteBlock = SoundDeleteBlock(soundName: sound.name, places: places)
            } else if case .notFound = error {
                sounds.removeAll { $0.id == sound.id }
            } else {
                handle(error, whileLoading: false)
            }
        } catch {
            handle(error, whileLoading: false)
        }
    }

    // MARK: Errors

    private func show(_ failure: SoundFailure) {
        banner = MediaBanner(text: failure.message, isError: true)
    }

    private func handle(_ error: Error, whileLoading: Bool) {
        // Cancelled by the screen or by SwiftUI: not a failure, never "no connection".
        if APIError.isCancellation(error) { return }

        let result = SoundFailure.classify(error)

        switch result {
        case .accessDenied:
            hub.soundsDenied(accountId: account.id)
        case .revoked:
            hub.onRevoked?()
        default:
            break
        }

        if whileLoading, !hasLoaded {
            failure = result
        } else if result != .accessDenied, result != .revoked {
            show(result)
        }
    }

    /// "Opname 9 okt 14:32" (a name for a recording the user does not rename).
    static func defaultRecordingName(now: Date = Date(), locale: Locale = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.setLocalizedDateFormatFromTemplate("d MMM HH:mm")

        return "\(L10n.string("sounds.record.defaultName")) \(formatter.string(from: now))"
    }

    /// Trimmed, one line, no more than the 100 characters we send (the server has its own limit).
    static func cleanName(_ value: String) -> String {
        let oneLine = value.components(separatedBy: .newlines).joined(separator: " ").trimmingCharacters(in: .whitespaces)

        return String(oneLine.prefix(100))
    }
}
