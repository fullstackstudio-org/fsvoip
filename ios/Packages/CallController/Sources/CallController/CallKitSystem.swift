// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The system call UI on iOS (plan D16). CallKit and the audio session belong to the app, not to the SIP stack:
// the stack is only told when the system activates the audio session.

import AVFoundation
import CallKit
import Foundation

@MainActor
public final class CallKitSystem: NSObject, CallSystem {
    public weak var handler: CallSystemActionHandler?

    private let provider: CXProvider
    private let controller = CXCallController()

    /// - Parameter iconTemplateImageData: PNG of a monochrome glyph shown on the system call screen (optional).
    public init(iconTemplateImageData: Data? = nil) {
        // The name on the call screen is the app's display name (FSVoip).
        let configuration = CXProviderConfiguration()
        configuration.supportsVideo = false
        configuration.maximumCallsPerCallGroup = 1
        configuration.maximumCallGroups = 1
        configuration.supportedHandleTypes = [.phoneNumber, .generic]
        // Our own recents list keeps the history (the phone's Recents would call back with the system dialler).
        configuration.includesCallsInRecents = false
        configuration.iconTemplateImageData = iconTemplateImageData

        provider = CXProvider(configuration: configuration)

        super.init()

        // nil = the main queue: CallKit callbacks run on the main thread, like the SIP engine.
        provider.setDelegate(self, queue: nil)
    }

    // MARK: Reports

    public func reportIncomingCall(uuid: UUID, handle: String, displayName: String, completion: @escaping (Error?) -> Void) {
        let update = CXCallUpdate()
        update.remoteHandle = Self.handle(handle)
        update.localizedCallerName = displayName
        update.hasVideo = false
        update.supportsHolding = true
        update.supportsDTMF = true
        update.supportsGrouping = false
        update.supportsUngrouping = false

        provider.reportNewIncomingCall(with: uuid, update: update) { error in
            DispatchQueue.main.async { completion(error) }
        }
    }

    public func reportCallUpdated(uuid: UUID, displayName: String) {
        let update = CXCallUpdate()
        update.localizedCallerName = displayName
        provider.reportCall(with: uuid, updated: update)
    }

    public func reportCallEnded(uuid: UUID, reason: CallSystemEndReason) {
        provider.reportCall(with: uuid, endedAt: Date(), reason: Self.reason(reason))
    }

    public func reportOutgoingCallStartedConnecting(uuid: UUID) {
        provider.reportOutgoingCall(with: uuid, startedConnectingAt: Date())
    }

    public func reportOutgoingCallConnected(uuid: UUID) {
        provider.reportOutgoingCall(with: uuid, connectedAt: Date())
    }

    // MARK: Requests

    public func requestStartCall(uuid: UUID, handle: String, displayName: String?, completion: @escaping (Error?) -> Void) {
        let action = CXStartCallAction(call: uuid, handle: Self.handle(handle))
        action.contactIdentifier = displayName
        action.isVideo = false
        request(action, completion: completion)
    }

    public func requestAnswerCall(uuid: UUID, completion: @escaping (Error?) -> Void) {
        request(CXAnswerCallAction(call: uuid), completion: completion)
    }

    public func requestEndCall(uuid: UUID, completion: @escaping (Error?) -> Void) {
        request(CXEndCallAction(call: uuid), completion: completion)
    }

    public func requestSetHeld(uuid: UUID, onHold: Bool, completion: @escaping (Error?) -> Void) {
        request(CXSetHeldCallAction(call: uuid, onHold: onHold), completion: completion)
    }

    public func requestSetMuted(uuid: UUID, muted: Bool, completion: @escaping (Error?) -> Void) {
        request(CXSetMutedCallAction(call: uuid, muted: muted), completion: completion)
    }

    public func requestPlayDTMF(uuid: UUID, digits: String, completion: @escaping (Error?) -> Void) {
        request(CXPlayDTMFCallAction(call: uuid, digits: digits, type: .singleTone), completion: completion)
    }

    private func request(_ action: CXCallAction, completion: @escaping (Error?) -> Void) {
        controller.request(CXTransaction(action: action)) { error in
            DispatchQueue.main.async { completion(error) }
        }
    }

    // MARK: Helpers

    static func handle(_ value: String) -> CXHandle {
        CXHandle(type: DialNumber.isDialable(value) ? .phoneNumber : .generic, value: value)
    }

    static func reason(_ reason: CallSystemEndReason) -> CXCallEndedReason {
        switch reason {
        case .failed: return .failed
        case .remoteEnded: return .remoteEnded
        case .unanswered: return .unanswered
        case .answeredElsewhere: return .answeredElsewhere
        case .declinedElsewhere: return .declinedElsewhere
        }
    }
}

extension CallKitSystem: CXProviderDelegate {
    // CallKit calls these on the main queue (`setDelegate(_, queue: nil)`).

    public nonisolated func providerDidReset(_ provider: CXProvider) {
        MainActor.assumeIsolated { handler?.systemDidReset() }
    }

    public nonisolated func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        MainActor.assumeIsolated {
            complete(action, handler?.performStartCall(uuid: action.callUUID, handle: action.handle.value) ?? false)
        }
    }

    public nonisolated func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        // The SIP answer goes out now; the audio only starts in `didActivate` below (the pattern linphone-iphone
        // and baresip's CallKit notes follow).
        MainActor.assumeIsolated {
            complete(action, handler?.performAnswerCall(uuid: action.callUUID) ?? false)
        }
    }

    public nonisolated func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        MainActor.assumeIsolated {
            complete(action, handler?.performEndCall(uuid: action.callUUID) ?? false)
        }
    }

    public nonisolated func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        MainActor.assumeIsolated {
            complete(action, handler?.performSetHeld(uuid: action.callUUID, onHold: action.isOnHold) ?? false)
        }
    }

    public nonisolated func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        MainActor.assumeIsolated {
            complete(action, handler?.performSetMuted(uuid: action.callUUID, muted: action.isMuted) ?? false)
        }
    }

    public nonisolated func provider(_ provider: CXProvider, perform action: CXPlayDTMFCallAction) {
        MainActor.assumeIsolated {
            complete(action, handler?.performPlayDTMF(uuid: action.callUUID, digits: action.digits) ?? false)
        }
    }

    public nonisolated func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        MainActor.assumeIsolated { handler?.audioSessionActivated(true) }
    }

    public nonisolated func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        MainActor.assumeIsolated { handler?.audioSessionActivated(false) }
    }

    private func complete(_ action: CXAction, _ ok: Bool) {
        if ok {
            action.fulfill()
        } else {
            action.fail()
        }
    }
}
