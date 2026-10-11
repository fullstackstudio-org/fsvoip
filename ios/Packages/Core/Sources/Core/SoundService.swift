// SPDX-License-Identifier: AGPL-3.0-or-later
//
// What the "Geluiden" screen and the audio picker need from the API, per paired account. One implementation talks to the server,
// tests and the demo mode bring their own. Every method throws `APIError`; `SoundFailure.classify` turns that into words.

import Foundation

public protocol SoundServicing: Sendable {
    func sounds(for account: StoredAccount) async throws -> [Sound]
    /// Uploads `data` as a new sound; `progress` is the fraction sent (0...1). Cancelling the calling task cancels the request.
    func upload(for account: StoredAccount, name: String, fileName: String, data: Data, progress: (@Sendable (Double) -> Void)?) async throws -> String
    func rename(for account: StoredAccount, id: String, name: String) async throws
    func delete(for account: StoredAccount, id: String) async throws
    /// The audio of a sound to stream (`playable == true`).
    func source(for account: StoredAccount, id: String) throws -> AudioSource
}

public struct LiveSoundService: SoundServicing {
    private let api: FSVoipAPIClient

    public init(api: FSVoipAPIClient = FSVoipAPIClient()) {
        self.api = api
    }

    private func client(_ account: StoredAccount) -> FSVoipAPIClient {
        api.authenticated(with: account.deviceToken)
    }

    public func sounds(for account: StoredAccount) async throws -> [Sound] {
        try await client(account).sounds().sounds
    }

    public func upload(for account: StoredAccount, name: String, fileName: String, data: Data, progress: (@Sendable (Double) -> Void)?) async throws -> String {
        try await client(account).uploadSound(name: name, fileName: fileName, data: data, progress: progress).soundId
    }

    public func rename(for account: StoredAccount, id: String, name: String) async throws {
        try await client(account).renameSound(id: id, name: name)
    }

    public func delete(for account: StoredAccount, id: String) async throws {
        try await client(account).deleteSound(id: id)
    }

    public func source(for account: StoredAccount, id: String) throws -> AudioSource {
        .stream(try client(account).soundMedia(id: id))
    }
}

/// Why a sound could not be added, renamed, deleted or loaded, in the user's terms.
public enum SoundFailure: Equatable, Sendable {
    /// 400 `invalid_audio`: not WAV, MP3 or M4A.
    case invalidAudio
    /// 413 `too_large` (or a file above 20 MB before any request).
    case tooLarge
    /// 409 `too_many`: the limit of sounds per centrale is reached.
    case tooMany
    /// 409 `in_use`: the sound is still used here; nothing was deleted.
    case inUse(places: [APIErrorPlace])
    case rateLimited(retryAfterSeconds: Int?)
    /// 403: this pairing may not manage sounds (any more).
    case accessDenied
    /// 401: the pairing is gone.
    case revoked
    case readOnly
    case notFound
    case offline
    case unavailable
    case other

    public static func classify(_ error: Error) -> SoundFailure {
        guard let api = error as? APIError else {
            return .other
        }

        switch api {
        case .invalidAudio: return .invalidAudio
        case .tooLarge, .payloadTooLarge: return .tooLarge
        case .tooMany: return .tooMany
        case let .inUse(places): return .inUse(places: places)
        case let .rateLimited(seconds): return .rateLimited(retryAfterSeconds: seconds)
        case .forbidden: return .accessDenied
        case .unauthorized: return .revoked
        case .readOnly: return .readOnly
        case .conflict(let code) where code == "read_only": return .readOnly
        case .notFound: return .notFound
        case .transport: return .offline
        case .unavailable, .unexpectedStatus: return .unavailable
        default: return .other
        }
    }
}
