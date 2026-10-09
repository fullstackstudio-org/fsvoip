// SPDX-License-Identifier: AGPL-3.0-or-later
//
// What the "Voicemail" and "Opnames" screens need from the API, per paired account. One implementation talks to the server, tests
// and the demo mode bring their own. Every method throws `APIError`; `MediaFailure.classify` turns that into words.

import Foundation

public protocol MediaServicing: Sendable {
    func calls(for account: StoredAccount, month: String?) async throws -> CallsPage
    func voicemail(for account: StoredAccount, box: String?) async throws -> VoicemailPage
    func deleteVoicemail(for account: StoredAccount, boxId: String, ref: String) async throws
    func recordingSource(for account: StoredAccount, callId: String) throws -> AudioSource
    func voicemailSource(for account: StoredAccount, boxId: String, ref: String) throws -> AudioSource
    /// The download fallback of the player: the whole audio in one request.
    func download(_ media: MediaRequest, for account: StoredAccount) async throws -> MediaDownload
}

public struct LiveMediaService: MediaServicing {
    private let api: FSVoipAPIClient
    private let locale: @Sendable () -> String

    public init(api: FSVoipAPIClient = FSVoipAPIClient(), locale: @escaping @Sendable () -> String = { LiveMediaService.currentLanguage() }) {
        self.api = api
        self.locale = locale
    }

    /// `nl` or `en`: the languages the server formats its labels in.
    public static func currentLanguage() -> String {
        Locale.preferredLanguages.first?.lowercased().hasPrefix("nl") == true ? "nl" : "en"
    }

    private func client(_ account: StoredAccount) -> FSVoipAPIClient {
        api.authenticated(with: account.deviceToken)
    }

    public func calls(for account: StoredAccount, month: String?) async throws -> CallsPage {
        try await client(account).calls(month: month, locale: locale())
    }

    public func voicemail(for account: StoredAccount, box: String?) async throws -> VoicemailPage {
        try await client(account).voicemail(box: box, locale: locale())
    }

    public func deleteVoicemail(for account: StoredAccount, boxId: String, ref: String) async throws {
        try await client(account).deleteVoicemail(boxId: boxId, ref: ref)
    }

    public func recordingSource(for account: StoredAccount, callId: String) throws -> AudioSource {
        .stream(try client(account).recordingMedia(callId: callId))
    }

    public func voicemailSource(for account: StoredAccount, boxId: String, ref: String) throws -> AudioSource {
        .stream(try client(account).voicemailMedia(boxId: boxId, ref: ref))
    }

    public func download(_ media: MediaRequest, for account: StoredAccount) async throws -> MediaDownload {
        try await client(account).downloadMedia(media)
    }
}

extension FSVoipAPIClient {
    /// Audio is a few MB at most (a recording of an hour is the extreme); more than this is not audio we want in memory.
    static let maxDownloadBytes = 64 * 1024 * 1024

    /// The whole audio in one request (the fallback when streaming fails). Errors map like every other route, so a `410` is
    /// `APIError.gone` and a `429` carries its `Retry-After`.
    public func downloadMedia(_ media: MediaRequest) async throws -> MediaDownload {
        let data: Data
        let response: HTTPURLResponse

        do {
            (data, response) = try await transport.send(media.urlRequest())
        } catch let error as APIError {
            throw error
        } catch {
            throw APIError.transport((error as NSError).localizedDescription)
        }

        guard (200 ..< 300).contains(response.statusCode) else {
            throw Self.error(for: response, data: data)
        }

        guard data.count <= Self.maxDownloadBytes else {
            throw APIError.payloadTooLarge
        }

        return MediaDownload(data: data, contentType: response.value(forHTTPHeaderField: "Content-Type"))
    }
}
