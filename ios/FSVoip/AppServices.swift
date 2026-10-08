// SPDX-License-Identifier: AGPL-3.0-or-later
import CallController
import Core
import Foundation
import LinphoneEngine
import Pairing
import SipEngine
import UI
import UIKit

/// Composition root. The only place that knows which `SipEngine` implementation is used (plan D3): swapping the SIP
/// stack (the documented fallback is baresip) means changing the engine line and the package dependency, nothing else.
@MainActor
final class AppServices {
    let model: FSVoipAppModel

    init() {
        #if DEBUG
        if let demo = DemoMode.makeServices() {
            model = demo
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
                    sipInstanceId: "urn:uuid:\(identity.sipInstanceId)"
                )
            }
        )
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
