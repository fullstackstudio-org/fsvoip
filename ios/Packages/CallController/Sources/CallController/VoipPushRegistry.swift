// SPDX-License-Identifier: AGPL-3.0-or-later
//
// PushKit (VoIP pushes). Thin on purpose: the decision what to do with a push lives in `PhoneController` and
// `IncomingPushPolicy`, which are tested without PushKit (PushKit delivers nothing on the simulator).
//
// 🚨 Create this registry in `application(_:didFinishLaunchingWithOptions:)`: a push that launched the app is only
// delivered once a registry with a delegate exists, and iOS terminates an app (and stops sending it VoIP pushes) that
// does not report a CallKit call inside the push callback.

import Foundation
import PushKit

@MainActor
public final class VoipPushRegistry: NSObject {
    /// The VoIP token changed (`nil` = invalidated by the system).
    public var onTokenChange: ((Data?) -> Void)?
    /// A VoIP push. MUST report a CallKit call before it returns (see `PhoneController.handleVoipPush`).
    public var onPush: (([AnyHashable: Any]) -> Void)?

    private let registry: PKPushRegistry

    /// The current token, if PushKit gave one.
    public var token: Data? {
        registry.pushToken(for: .voIP)
    }

    public override init() {
        // nil = the main queue: pushes arrive on the main thread, where `PhoneController` and CallKit live.
        registry = PKPushRegistry(queue: nil)
        super.init()
        registry.delegate = self
    }

    /// Ask for a VoIP token. Called at launch; the token arrives in `onTokenChange`.
    public func register() {
        registry.desiredPushTypes = [.voIP]
    }
}

extension VoipPushRegistry: PKPushRegistryDelegate {
    public nonisolated func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        guard type == .voIP else {
            return
        }

        let token = pushCredentials.token
        MainActor.assumeIsolated { onTokenChange?(token) }
    }

    public nonisolated func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
        guard type == .voIP else {
            return
        }

        MainActor.assumeIsolated { onTokenChange?(nil) }
    }

    public nonisolated func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType, completion: @escaping () -> Void) {
        let dictionary = payload.dictionaryPayload

        MainActor.assumeIsolated {
            if type == .voIP {
                // Reports the CallKit call synchronously, before `completion`.
                onPush?(dictionary)
            }
        }

        completion()
    }
}
