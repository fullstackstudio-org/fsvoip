// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation

/// A request for call-recording or voicemail audio. The server accepts the device token ONLY as `Authorization: Bearer` (no
/// token in the URL), supports `Range` (206/416) and answers HEAD like GET without a body.
///
/// Core does not import AVFoundation. The player builds the asset from this value:
/// `AVURLAsset(url: media.url, options: ["AVURLAssetHTTPHeaderFieldsKey": media.headers()])` (that key is
/// `AVURLAssetHTTPHeaderFieldsKey`; AVFoundation then issues the Range requests itself), or sends `urlRequest()` through
/// `URLSession` for a download. 🚨 Never log the URL together with the headers, and never put the headers in a log at all.
public struct MediaRequest: Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    public let url: URL
    private let authorization: Secret
    private let userAgent: String

    init(url: URL, deviceToken: Secret, userAgent: String) {
        self.url = url
        authorization = Secret("Bearer \(deviceToken.reveal())")
        self.userAgent = userAgent
    }

    /// The headers every request for this media needs (contains the bearer token).
    public func headers() -> [String: String] {
        ["Authorization": authorization.reveal(), "User-Agent": userAgent]
    }

    /// A ready `URLRequest` (GET, no cache). Add a `Range` header yourself if you need one.
    public func urlRequest(method: String = "GET") -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = 30

        for (name, value) in headers() {
            request.setValue(value, forHTTPHeaderField: name)
        }

        return request
    }

    public var description: String {
        "MediaRequest(\(url.path))"
    }

    public var debugDescription: String {
        description
    }
}
