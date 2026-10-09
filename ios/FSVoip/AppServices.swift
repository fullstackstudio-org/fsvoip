// SPDX-License-Identifier: AGPL-3.0-or-later
import CallController
import Core
import Foundation
import LinphoneEngine
import Pairing
import SipEngine
import UI
import UIKit
import UserNotifications

/// Composition root. The only place that knows which `SipEngine` implementation is used (plan D3): swapping the SIP
/// stack (the documented fallback is baresip) means changing the engine line and the package dependency, nothing else.
///
/// Created once, in `application(_:didFinishLaunchingWithOptions:)` (`AppDelegate`), because a VoIP push that
/// launched the app must find the PushKit registry, the CallKit provider and the SIP engine ready.
@MainActor
final class AppServices {
    static let shared = AppServices()

    let model: FSVoipAppModel
    /// `nil` in the demo mode (no server, no push).
    let pushTokens: PushTokenReporter?
    let voipPush: VoipPushRegistry?

    private init() {
        #if DEBUG
        if let demo = DemoMode.makeServices() {
            model = demo
            pushTokens = nil
            voipPush = nil
            return
        }
        #endif

        let logger = FSLogger(category: "app")
        let secrets = KeychainSecretStore()
        let identity: InstallIdentity

        do {
            identity = try InstallIdentity.load(from: secrets)
        } catch {
            // The Keychain is unavailable (very early after a reboot before the first unlock). Use a fresh identity for
            // this run; pairing stores it again later.
            logger.error("Install identity could not be read: \(error)")
            identity = InstallIdentity(installId: InstallIdentity.secureRandomBytes(8).map { String(format: "%02x", $0) }.joined(), sipInstanceId: UUID().uuidString.lowercased())
        }

        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"
        let accounts = AccountStore(secrets: secrets)
        let preferences = UserDefaultsPreferencesStore()
        let engine = LinphoneSipEngine(appVersion: version, installId: identity.installId)
        let phone = PhoneController(engine: engine, system: CallKitSystem(), preferences: preferences)
        let api = FSVoipAPIClient(userAgent: "FSVoip/\(version) (iOS)")
        // One local check (Face ID / passcode, valid for five minutes) for the "Centrale" section and the recordings.
        let gate = LocalAccessGate(authenticator: SystemLocalAuth())
        let reporter = PushTokenReporter(api: api, accounts: accounts, ledger: UserDefaultsPushTokenLedger(), environment: Self.pushEnvironment)

        // Launched in the background by a VoIP push: the accounts stay un-registered until the push wakes the one
        // that is called (the others would only drain the battery).
        if UIApplication.shared.applicationState == .background {
            phone.enterBackground()
        }

        pushTokens = reporter
        voipPush = VoipPushRegistry()
        model = FSVoipAppModel(
            phone: phone,
            accountStore: accounts,
            service: AccountService(api: api, accounts: accounts),
            preferences: preferences,
            recentsStore: RecentCallsStore(),
            device: {
                DeviceDescriptor(
                    model: Self.hardwareModel(),
                    osVersion: UIDevice.current.systemVersion,
                    appVersion: "\(version) (\(build))",
                    installId: identity.installId,
                    sipInstanceId: "urn:uuid:\(identity.sipInstanceId)",
                    pushEnvironment: Self.pushEnvironment
                )
            },
            pushTokens: reporter,
            requestNotifications: { await Self.requestNotificationPermission() },
            pbx: PbxHub(service: LivePbxService(api: api), gate: gate),
            media: MediaHub(service: LiveMediaService(api: api), gate: gate),
            availability: AvailabilityHub(service: LiveAvailabilityService(api: api))
        )
    }

    /// Start PushKit (VoIP pushes for calls) and the regular APNs registration (notices such as "unpaired").
    func startPush(_ application: UIApplication) {
        guard let voipPush, let pushTokens else {
            return
        }

        let model = model

        voipPush.onPush = { payload in
            // Reports the CallKit call before returning (Apple requires it inside the push callback).
            model.phone.handleVoipPush(payload: payload)
        }
        voipPush.onTokenChange = { token in
            Task {
                await pushTokens.setVoipToken(token)
                await model.reportPushTokens()
            }
        }
        voipPush.register()

        if let token = voipPush.token {
            voipPush.onTokenChange?(token)
        }

        // The regular token needs no permission; showing the notice does (asked after the first pairing).
        application.registerForRemoteNotifications()
    }

    func didRegisterAlertToken(_ token: Data?) {
        guard let pushTokens else {
            return
        }

        let model = model

        Task {
            await pushTokens.setAlertToken(token)
            await model.reportPushTokens()
        }
    }

    /// `sandbox` for debug builds (development signing), `production` for TestFlight and the App Store. Set per
    /// configuration in `Config/*.xcconfig` (`FSVOIP_PUSH_ENV`) and read from the Info.plist.
    static var pushEnvironment: PushEnvironment {
        if let value = Bundle.main.object(forInfoDictionaryKey: "FSVoipPushEnvironment") as? String, let environment = PushEnvironment(rawValue: value) {
            return environment
        }

        #if DEBUG
        return .sandbox
        #else
        return .production
        #endif
    }

    static func requestNotificationPermission() async {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()

        guard settings.authorizationStatus == .notDetermined else {
            return
        }

        _ = try? await center.requestAuthorization(options: [.alert, .sound])
    }

    /// `iPhone17,1` and the like (not the user-chosen device name, which is personal).
    static func hardwareModel() -> String {
        var info = utsname()
        uname(&info)

        return withUnsafeBytes(of: &info.machine) { buffer in
            String(decoding: buffer.prefix { $0 != 0 }, as: UTF8.self)
        }
    }
}
