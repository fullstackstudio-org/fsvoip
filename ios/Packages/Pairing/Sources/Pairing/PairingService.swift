// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation

/// What the app tells the server about itself when pairing.
public struct DeviceDescriptor: Sendable, Equatable {
    public var model: String?
    public var osVersion: String?
    public var appVersion: String?
    public var installId: String
    public var sipInstanceId: String?
    public var pushToken: String?
    public var alertPushToken: String?
    public var pushEnvironment: PushEnvironment

    public init(
        model: String?,
        osVersion: String?,
        appVersion: String?,
        installId: String,
        sipInstanceId: String? = nil,
        pushToken: String? = nil,
        alertPushToken: String? = nil,
        pushEnvironment: PushEnvironment = .production
    ) {
        self.model = model
        self.osVersion = osVersion
        self.appVersion = appVersion
        self.installId = installId
        self.sipInstanceId = sipInstanceId
        self.pushToken = pushToken
        self.alertPushToken = alertPushToken
        self.pushEnvironment = pushEnvironment
    }

    var asDeviceInfo: DeviceInfo {
        DeviceInfo(
            platform: .ios,
            model: model,
            osVersion: osVersion,
            appVersion: appVersion,
            sipInstanceId: sipInstanceId,
            installId: installId,
            pushToken: pushToken,
            pushKind: pushToken == nil ? nil : .apnsVoip,
            pushEnv: pushToken == nil ? nil : pushEnvironment,
            alertPushToken: pushToken == nil ? nil : alertPushToken
        )
    }
}

/// Exchanges a pairing link for a stored account. NOT called by the app shell yet (Task 5 wires it to the UI);
/// it exists and is tested so the claim flow is one function.
public struct PairingService: Sendable {
    private let api: FSVoipAPIClient
    private let accounts: AccountStore
    private let logger: FSLogger

    public init(api: FSVoipAPIClient, accounts: AccountStore, logger: FSLogger = FSLogger(category: "pairing")) {
        self.api = api
        self.accounts = accounts
        self.logger = logger
    }

    /// `POST /pair`, then keep the result (SIP password and device token) in the Keychain only.
    @discardableResult
    public func pair(_ link: PairingLink, device: DeviceDescriptor, now: Date = Date()) async throws -> StoredAccount {
        let response = try await api.pair(PairRequest(token: link.token, device: device.asDeviceInfo))
        let account = StoredAccount(pairing: response, pairedAt: now)

        try accounts.save(account)
        logger.notice("Paired account \(account.id) (\(account.label))")

        return account
    }
}
