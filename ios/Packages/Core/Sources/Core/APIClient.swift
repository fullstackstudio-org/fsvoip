// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation

public enum APIError: Error, Equatable, Sendable {
    /// 404 on `/pair`: unknown, expired, used or malformed pairing token (one neutral answer).
    case notFound
    /// 401: the device token is unknown or revoked. The app must remove the account.
    case unauthorized
    case rateLimited(retryAfterSeconds: Int?)
    /// 400: the app sent something the server does not accept (a bug in the app).
    case invalidRequest(message: String?)
    case payloadTooLarge
    /// 503 (or other temporary trouble). `retryable` = the same request may work shortly.
    case unavailable(retryable: Bool, retryAfterSeconds: Int?)
    case missingDeviceToken
    case transport(String)
    case decoding(String)
    case unexpectedStatus(Int)
    /// 403: this pairing may not do that (the role is `user`, or the role was taken away). The app hides the section.
    case forbidden
    /// 409 `read_only`: the PBX is frozen or still being set up; nothing can be changed now.
    case readOnly
    /// 409 `stale`: the object changed in the meantime. Reload it (`version` = the current version, when the server tells).
    case stale(version: Int?)
    /// 409 `in_use`: something still uses it (`places` = names the customer chose).
    case inUse(places: [APIErrorPlace])
    /// 409 any other (`busy`, `not_ready`, `range_full`, `limit_reached`, ...): the code the server gave.
    case conflict(code: String)
    /// 422 `blocked_destination`: a block list stops these external numbers.
    case blockedDestination([String])
    /// 410: gone for good (a recording past its retention period).
    case gone
    /// 400 `invalid_request` with `code: "resync"`: `since` is too old; do a full contacts sync.
    case resync
    /// 400 `invalid_request` with a `code` (and the `field` that is wrong), e.g. `invalid_phone`.
    case invalid(code: String, field: String?)
    /// 409 `stale` of a chain step or the recording, with the fresh chain: keep what the user typed and show the new state.
    case staleChain(NumberChain)
    /// 409 `advanced`: the flow behind the number is more than the chain can show; only the name can be changed.
    case advanced
    /// 400 `invalid_request` with `code: "greeting_required"`: a welcome message needs a sound.
    case greetingRequired
    /// `cost_not_accepted` (422 on a recording): switching it on costs money; show `cost` and repeat the request with `costAccepted: true`.
    case costNotAccepted(cost: RecordingCost?)
    /// 400 `invalid_audio`: not a supported audio file (WAV, MP3 or M4A).
    case invalidAudio
    /// 413 `too_large`: the sound file is above the limit.
    case tooLarge
    /// 409 `too_many`: the PBX has the maximum number of sounds.
    case tooMany
    /// 404 `call_not_found`: no running call of this extension has that Call-ID.
    case callNotFound
    /// 409 `no_free_slot`: every park slot is taken.
    case noFreeSlot
    /// 409 `park_unavailable`: the PBX cannot park right now.
    case parkUnavailable
    /// 503 `park_uncertain`: the PBX failed and the call MAY be parked. Never park again: refresh the parked list.
    case parkUncertain
}

public protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)

    /// Sends `request` with `body` as its body and reports the fraction sent (0...1) while it goes. The default sends the body in one
    /// piece without progress (a test transport needs nothing more); `URLSessionTransport` reports the real progress.
    func upload(_ request: URLRequest, body: Data, progress: (@Sendable (Double) -> Void)?) async throws -> (Data, HTTPURLResponse)
}

extension HTTPTransport {
    public func upload(_ request: URLRequest, body: Data, progress: (@Sendable (Double) -> Void)?) async throws -> (Data, HTTPURLResponse) {
        var request = request
        request.httpBody = body
        let result = try await send(request)
        progress?(1)

        return result
    }
}

/// Forwards `didSendBodyData` of one upload task to a closure.
private final class UploadProgressDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let handler: @Sendable (Double) -> Void

    init(_ handler: @escaping @Sendable (Double) -> Void) {
        self.handler = handler
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        guard totalBytesExpectedToSend > 0 else {
            return
        }

        handler(min(1, max(0, Double(totalBytesSent) / Double(totalBytesExpectedToSend))))
    }
}

/// `URLSession` without a cache, cookies or credentials storage: this API is stateless and `no-store`.
public struct URLSessionTransport: HTTPTransport {
    private let session: URLSession

    public init(session: URLSession? = nil) {
        if let session {
            self.session = session
        } else {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
            configuration.httpCookieStorage = nil
            configuration.httpShouldSetCookies = false
            configuration.urlCredentialStorage = nil
            configuration.waitsForConnectivity = false
            self.session = URLSession(configuration: configuration)
        }
    }

    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)

        guard let http = response as? HTTPURLResponse else {
            throw APIError.transport("Not an HTTP response")
        }

        return (data, http)
    }

    public func upload(_ request: URLRequest, body: Data, progress: (@Sendable (Double) -> Void)?) async throws -> (Data, HTTPURLResponse) {
        let delegate = progress.map { UploadProgressDelegate($0) }
        let (data, response) = try await session.upload(for: request, from: body, delegate: delegate)

        guard let http = response as? HTTPURLResponse else {
            throw APIError.transport("Not an HTTP response")
        }

        return (data, http)
    }
}

/// Client of `/api/voip-app/v1`. One instance per paired account (the device token is part of it).
public struct FSVoipAPIClient: Sendable {
    public static let productionBaseURL = URL(string: "https://fullstackstudio.nl/api/voip-app/v1")!

    private let baseURL: URL
    private let deviceToken: Secret?
    let transport: HTTPTransport
    private let userAgent: String
    private let logger: FSLogger

    public init(
        baseURL: URL = FSVoipAPIClient.productionBaseURL,
        deviceToken: Secret? = nil,
        transport: HTTPTransport = URLSessionTransport(),
        userAgent: String = "FSVoip",
        logger: FSLogger = FSLogger(category: "api")
    ) {
        self.baseURL = baseURL
        self.deviceToken = deviceToken
        self.transport = transport
        self.userAgent = userAgent
        self.logger = logger
    }

    /// The same client, authenticated with a device token.
    public func authenticated(with token: Secret) -> FSVoipAPIClient {
        FSVoipAPIClient(baseURL: baseURL, deviceToken: token, transport: transport, userAgent: userAgent, logger: logger)
    }

    // MARK: Routes

    /// `POST /pair`. 🚨 The response holds the SIP password: store it in the Keychain, never log it.
    public func pair(_ request: PairRequest) async throws -> PairResponse {
        try await perform("POST", "pair", body: request, authenticated: false)
    }

    public func me() async throws -> MeResponse {
        try await perform("GET", "me", body: Optional<PairRequest>.none, authenticated: true)
    }

    public func setLabelOverride(_ label: String?) async throws -> MePatchResponse {
        try await perform("PATCH", "me", body: MePatchRequest(labelOverride: label), authenticated: true)
    }

    @discardableResult
    public func updatePushToken(_ update: PushTokenUpdate) async throws -> OkResponse {
        try await perform("PUT", "push-token", body: update, authenticated: true)
    }

    @discardableResult
    public func unpair() async throws -> OkResponse {
        try await perform("POST", "unpair", body: Optional<PairRequest>.none, authenticated: true)
    }

    // MARK: Plumbing

    func perform<Body: Encodable, Response: Decodable>(
        _ method: String,
        _ path: String,
        query: [URLQueryItem] = [],
        body: Body?,
        headers: [String: String] = [:],
        authenticated: Bool
    ) async throws -> Response {
        let (data, _) = try await send(method, path, query: query, body: body, headers: headers, authenticated: authenticated, acceptNotModified: false)

        do {
            return try FSVoipJSON.decoder().decode(Response.self, from: data)
        } catch {
            throw APIError.decoding("\(path): \(error)")
        }
    }

    /// Like `perform` but without a body.
    func get<Response: Decodable>(_ path: String, query: [URLQueryItem] = [], headers: [String: String] = [:]) async throws -> Response {
        try await perform("GET", path, query: query, body: Optional<PairRequest>.none, headers: headers, authenticated: true)
    }

    /// The raw variant: returns the data and the response (headers such as `ETag`). `acceptNotModified`: a `304` is an answer,
    /// not an error (conditional GET); the data is then empty.
    func send<Body: Encodable>(
        _ method: String,
        _ path: String,
        query: [URLQueryItem] = [],
        body: Body?,
        headers: [String: String] = [:],
        authenticated: Bool,
        acceptNotModified: Bool
    ) async throws -> (Data, HTTPURLResponse) {
        var request = try makeRequest(method, path, query: query, headers: headers, authenticated: authenticated)

        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try FSVoipJSON.encoder().encode(body)
        }

        return try await execute(request, method: method, path: path, acceptNotModified: acceptNotModified) { try await transport.send($0) }
    }

    /// A `multipart/form-data` (or other raw body) request, with upload progress (0...1). Used for the sounds upload.
    func sendUpload(
        _ method: String,
        _ path: String,
        body: Data,
        contentType: String,
        authenticated: Bool,
        progress: (@Sendable (Double) -> Void)?
    ) async throws -> (Data, HTTPURLResponse) {
        var request = try makeRequest(method, path, query: [], headers: [:], authenticated: authenticated)
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 120

        return try await execute(request, method: method, path: path, acceptNotModified: false) { try await transport.upload($0, body: body, progress: progress) }
    }

    private func makeRequest(_ method: String, _ path: String, query: [URLQueryItem], headers: [String: String], authenticated: Bool) throws -> URLRequest {
        var request = URLRequest(url: url(path, query: query))
        request.httpMethod = method
        request.timeoutInterval = 20
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")

        for (name, value) in headers {
            request.setValue(value, forHTTPHeaderField: name)
        }

        if authenticated {
            guard let deviceToken else {
                throw APIError.missingDeviceToken
            }

            request.setValue("Bearer \(deviceToken.reveal())", forHTTPHeaderField: "Authorization")
        }

        return request
    }

    private func execute(
        _ request: URLRequest,
        method: String,
        path: String,
        acceptNotModified: Bool,
        transport run: (URLRequest) async throws -> (Data, HTTPURLResponse)
    ) async throws -> (Data, HTTPURLResponse) {
        // Never log bodies, queries or headers: the pair response carries the SIP password and contact queries carry a sync cursor.
        logger.debug("\(method) /\(path.split(separator: "/").prefix(1).joined(separator: "/"))")

        let data: Data
        let response: HTTPURLResponse

        do {
            (data, response) = try await run(request)
        } catch let error as APIError {
            throw error
        } catch {
            throw APIError.transport((error as NSError).localizedDescription)
        }

        logger.debug("\(method) /\(path.split(separator: "/").prefix(1).joined(separator: "/")) -> \(response.statusCode)")

        if acceptNotModified, response.statusCode == 304 {
            return (data, response)
        }

        guard (200 ..< 300).contains(response.statusCode) else {
            throw Self.error(for: response, data: data)
        }

        return (data, response)
    }

    /// `base/<segments>?query`, every segment percent-encoded on its own (a sealed voicemail reference is opaque text).
    func url(_ path: String, query: [URLQueryItem] = []) -> URL {
        var url = baseURL

        for segment in path.split(separator: "/", omittingEmptySubsequences: true) {
            url.appendPathComponent(String(segment))
        }

        guard !query.isEmpty, var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return url
        }

        components.queryItems = query

        return components.url ?? url
    }

    var userAgentValue: String { userAgent }
    var deviceTokenValue: Secret? { deviceToken }

    static func error(for response: HTTPURLResponse, data: Data) -> APIError {
        let body = try? JSONDecoder().decode(APIErrorBody.self, from: data)
        let retryAfter = response.value(forHTTPHeaderField: "Retry-After").flatMap { Int($0) }

        // The same error names can come with another status on another route (`cost_not_accepted` is 422 on a recording and 409 on a
        // new extension), so the ones that need no detail are recognised on the name first.
        switch body?.error {
        case "cost_not_accepted":
            return .costNotAccepted(cost: body?.cost)
        case "advanced":
            return .advanced
        case "park_uncertain":
            return .parkUncertain
        case "park_unavailable":
            return .parkUnavailable
        case "no_free_slot":
            return .noFreeSlot
        case "call_not_found":
            return .callNotFound
        case "invalid_audio":
            return .invalidAudio
        case "too_large":
            return .tooLarge
        case "too_many":
            return .tooMany
        default:
            break
        }

        switch response.statusCode {
        case 400:
            if body?.code == "resync" {
                return .resync
            }

            if body?.code == "greeting_required" {
                return .greetingRequired
            }

            if let code = body?.code {
                return .invalid(code: code, field: body?.field)
            }

            return .invalidRequest(message: body?.message)
        case 401:
            return .unauthorized
        case 403:
            return .forbidden
        case 404:
            return .notFound
        case 409:
            switch body?.error {
            case "read_only":
                return .readOnly
            case "stale":
                if let chain = body?.chain {
                    return .staleChain(chain)
                }

                return .stale(version: body?.version)
            case "in_use":
                return .inUse(places: body?.places ?? [])
            default:
                return .conflict(code: body?.code ?? body?.error ?? "conflict")
            }
        case 410:
            return .gone
        case 413:
            return .payloadTooLarge
        case 422 where body?.error == "blocked_destination":
            return .blockedDestination(body?.blocked ?? [])
        case 429:
            return .rateLimited(retryAfterSeconds: retryAfter)
        case 503:
            return .unavailable(retryable: body?.retryable ?? true, retryAfterSeconds: retryAfter)
        default:
            return .unexpectedStatus(response.statusCode)
        }
    }
}
