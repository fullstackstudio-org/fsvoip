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
}

public protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
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
}

/// Client of `/api/voip-app/v1`. One instance per paired account (the device token is part of it).
public struct FSVoipAPIClient: Sendable {
    public static let productionBaseURL = URL(string: "https://fullstackstudio.nl/api/voip-app/v1")!

    private let baseURL: URL
    private let deviceToken: Secret?
    private let transport: HTTPTransport
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

        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try FSVoipJSON.encoder().encode(body)
        }

        // Never log bodies, queries or headers: the pair response carries the SIP password and contact queries carry a sync cursor.
        logger.debug("\(method) /\(path.split(separator: "/").prefix(1).joined(separator: "/"))")

        let data: Data
        let response: HTTPURLResponse

        do {
            (data, response) = try await transport.send(request)
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

        switch response.statusCode {
        case 400:
            if body?.code == "resync" {
                return .resync
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
