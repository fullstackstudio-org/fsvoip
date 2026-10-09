// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The sounds routes (`/pbx/sounds/**`, admin only) and the invitation link for a colleague (`/pbx/devices/{id}/app-pairing`).

import Foundation

private struct NoBody: Encodable {}

extension FSVoipAPIClient {
    public func sounds() async throws -> SoundsPage {
        try await get("pbx/sounds")
    }

    /// `POST /pbx/sounds` as `multipart/form-data` with exactly the parts `name` and `file`. `progress` reports the fraction sent
    /// (0...1). A file above `SoundUpload.maxBytes` is `APIError.tooLarge` without any request. Other failures:
    /// `.invalidAudio` (not WAV/MP3/M4A), `.tooMany`, `.rateLimited` (20 uploads per hour).
    public func uploadSound(name: String, fileName: String, data: Data, progress: (@Sendable (Double) -> Void)? = nil) async throws -> SoundUploadResponse {
        guard data.count <= SoundUpload.maxBytes else {
            throw APIError.tooLarge
        }

        let form = MultipartForm()
        let body = form.body(parts: [
            .text(name: "name", value: name),
            .file(name: "file", fileName: fileName, contentType: SoundUpload.contentType(forFileName: fileName), data: data),
        ])
        let (responseData, _) = try await sendUpload("POST", "pbx/sounds", body: body, contentType: form.contentType, authenticated: true, progress: progress)

        do {
            return try FSVoipJSON.decoder().decode(SoundUploadResponse.self, from: responseData)
        } catch {
            throw APIError.decoding("pbx/sounds: \(error)")
        }
    }

    @discardableResult
    public func renameSound(id: String, name: String) async throws -> OkResponse {
        try await perform("PATCH", "pbx/sounds/\(id)", body: SoundRenameRequest(name: name), authenticated: true)
    }

    /// `409 in_use` = `APIError.inUse(places:)` while the sound is still used: nothing is deleted.
    @discardableResult
    public func deleteSound(id: String) async throws -> OkResponse {
        try await perform("DELETE", "pbx/sounds/\(id)", body: Optional<NoBody>.none, authenticated: true)
    }

    /// The audio of a sound (`playable == true`). See `MediaRequest`.
    public func soundMedia(id: String) throws -> MediaRequest {
        guard let token = deviceTokenValue else {
            throw APIError.missingDeviceToken
        }

        return MediaRequest(url: url("pbx/sounds/\(id)/audio"), deviceToken: token, userAgent: userAgentValue)
    }

    /// `POST /pbx/devices/{id}/app-pairing`: an invitation link for the extension `deviceId` (role `user`, valid 10 minutes, once).
    /// Not for the own extension (`APIError.conflict`). 🚨 The link is a credential.
    public func createAppPairing(deviceId: String) async throws -> AppPairingResponse {
        try await perform("POST", "pbx/devices/\(deviceId)/app-pairing", body: Optional<NoBody>.none, authenticated: true)
    }
}

/// A `multipart/form-data` body. The server wants exactly the parts it names; the boundary is random per form.
struct MultipartForm {
    enum Part {
        case text(name: String, value: String)
        case file(name: String, fileName: String, contentType: String, data: Data)
    }

    let boundary: String

    init(boundary: String = "fsvoip-" + UUID().uuidString) {
        self.boundary = boundary
    }

    var contentType: String { "multipart/form-data; boundary=\(boundary)" }

    func body(parts: [Part]) -> Data {
        var data = Data()

        for part in parts {
            data.append("--\(boundary)\r\n")

            switch part {
            case let .text(name, value):
                data.append("Content-Disposition: form-data; name=\"\(Self.header(name))\"\r\n\r\n")
                data.append(value)
                data.append("\r\n")
            case let .file(name, fileName, contentType, bytes):
                data.append("Content-Disposition: form-data; name=\"\(Self.header(name))\"; filename=\"\(Self.header(fileName))\"\r\n")
                data.append("Content-Type: \(Self.header(contentType))\r\n\r\n")
                data.append(bytes)
                data.append("\r\n")
            }
        }

        data.append("--\(boundary)--\r\n")

        return data
    }

    /// Keeps a value out of trouble inside a header: no quotes, backslashes or line breaks.
    static func header(_ value: String) -> String {
        String(value.unicodeScalars.filter { $0 != "\"" && $0 != "\\" && $0 != "\r" && $0 != "\n" && !$0.properties.isDefaultIgnorableCodePoint })
    }
}

private extension Data {
    mutating func append(_ string: String) {
        append(Data(string.utf8))
    }
}
