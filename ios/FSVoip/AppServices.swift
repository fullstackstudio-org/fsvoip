// SPDX-License-Identifier: AGPL-3.0-or-later
import CallController
import Foundation
import LinphoneEngine
import SipEngine

/// The only place that knows which `SipEngine` implementation is used (plan D3): swapping the SIP stack
/// (the documented fallback is baresip) means changing this line and the package dependency, nothing else.
final class AppServices {
    let sipEngine: SipEngine
    let callController: CallController

    init() {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        let engine = LinphoneSipEngine(appVersion: version)

        sipEngine = engine
        callController = CallController(audio: engine.audio)
    }
}
