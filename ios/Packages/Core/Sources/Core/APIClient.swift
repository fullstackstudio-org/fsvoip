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

    private func perform<Body: Encodable, Response: Decodable>(
        _ method: String,
        _ path: String,
        body: Body?,
        authenticated: Bool
    ) async throws -> Response {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = method
        request.timeoutInterval = 20
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")

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

        // Never log bodies or headers: the pair response carries the SIP password.
        logger.debug("\(method) /\(path)")

        let data: Data
        let response: HTTPURLResponse

        do {
            (data, response) = try await transport.send(request)
        } catch let error as APIError {
            throw error
        } catch {
            throw APIError.transport((error as NSError).localizedDescription)
        }

        logger.debug("\(method) /\(path) -> \(response.statusCode)")

        guard (200 ..< 300).contains(response.statusCode) else {
            throw Self.error(for: response, data: data)
        }

        do {
            return try FSVoipJSON.decoder().decode(Response.self, from: data)
        } catch {
            throw APIError.decoding("\(path): \(error)")
        }
    }

    static func error(for response: HTTPURLResponse, data: Data) -> APIError {
        let body = try? JSONDecoder().decode(APIErrorBody.self, from: data)
        let retryAfter = response.value(forHTTPHeaderField: "Retry-After").flatMap { Int($0) }

        switch response.statusCode {
        case 400:
            return .invalidRequest(message: body?.message)
        case 401:
            return .unauthorized
        case 404:
            return .notFound
        case 413:
            return .payloadTooLarge
        case 429:
            return .rateLimited(retryAfterSeconds: retryAfter)
        case 503:
            return .unavailable(retryable: body?.retryable ?? true, retryAfterSeconds: retryAfter)
        default:
            return .unexpectedStatus(response.statusCode)
        }
    }
}
