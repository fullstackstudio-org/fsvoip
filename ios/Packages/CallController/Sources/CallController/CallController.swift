// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Owner of the system call UI (CallKit) and the audio-session hooks (plan D16). The SIP stack's own CallKit
// integration is NOT used: CallKit and `AVAudioSession` belong to the app, so the behaviour is ours and the stack
// can be swapped. This is a skeleton: Task 5 (foreground calls) and Task 6 (PushKit wake-up) complete it.

import AVFoundation
import CallKit
import Foundation
import SipEngine

/// The app side of the call UI: what the user did on the system call screen.
public protocol CallControllerDelegate: AnyObject {
    func callController(_ controller: CallController, userStartedCall uuid: UUID, handle: String)
    func callController(_ controller: CallController, userAnswered uuid: UUID)
    func callController(_ controller: CallController, userEnded uuid: UUID)
    func callController(_ controller: CallController, userSetHeld uuid: UUID, held: Bool)
    func callController(_ controller: CallController, userSetMuted uuid: UUID, muted: Bool)
    func callController(_ controller: CallController, userSentDTMF uuid: UUID, digits: String)
    /// CallKit reset (rare): all calls are gone.
    func callControllerDidReset(_ controller: CallController)
}

public final class CallController: NSObject {
    public weak var delegate: CallControllerDelegate?

    private let provider: CXProvider
    private let callController = CXCallController()
    private let audio: SipAudioControl

    public init(audio: SipAudioControl) {
        self.audio = audio

        // The name shown on the call screen is the app's display name (FSVoip).
        let configuration = CXProviderConfiguration()
        configuration.supportsVideo = false
        configuration.maximumCallsPerCallGroup = 1
        configuration.maximumCallGroups = 2
        configuration.supportedHandleTypes = [.phoneNumber, .generic]
        configuration.includesCallsInRecents = false

        provider = CXProvider(configuration: configuration)

        super.init()

        provider.setDelegate(self, queue: nil)
    }

    // MARK: Incoming

    /// Report an incoming call to the system. 🚨 For a PushKit push this MUST be called inside the PushKit callback,
    /// before any network work (otherwise iOS ends the app and stops delivering VoIP pushes). `audio.configure()`
    /// runs first so the engine's audio is ready when the system activates the session.
    public func reportIncomingCall(
        uuid: UUID,
        handle: String,
        displayName: String,
        completion: @escaping (Error?) -> Void
    ) {
        audio.configure()

        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: handle.allSatisfy { $0.isNumber || $0 == "+" } ? .phoneNumber : .generic, value: handle)
        update.localizedCallerName = displayName
        update.hasVideo = false
        update.supportsHolding = true
        update.supportsDTMF = true
        update.supportsGrouping = false
        update.supportsUngrouping = false

        provider.reportNewIncomingCall(with: uuid, update: update) { error in
            completion(error)
        }
    }

    /// The caller name became known after the call was reported (lookup in the contacts).
    public func updateCaller(uuid: UUID, displayName: String) {
        let update = CXCallUpdate()
        update.localizedCallerName = displayName
        provider.reportCall(with: uuid, updated: update)
    }

    /// The call ended without the user doing anything on the call screen (remote hang-up, no INVITE in time).
    public func reportCallEnded(uuid: UUID, reason: CXCallEndedReason) {
        provider.reportCall(with: uuid, endedAt: Date(), reason: reason)
    }

    // MARK: Outgoing

    public func requestStartCall(uuid: UUID = UUID(), handle: String, displayName: String? = nil) {
        let handleType: CXHandle.HandleType = handle.allSatisfy { $0.isNumber || $0 == "+" } ? .phoneNumber : .generic
        let action = CXStartCallAction(call: uuid, handle: CXHandle(type: handleType, value: handle))
        action.contactIdentifier = displayName
        callController.request(CXTransaction(action: action)) { _ in }
    }

    public func reportOutgoingCallStartedConnecting(uuid: UUID) {
        provider.reportOutgoingCall(with: uuid, startedConnectingAt: Date())
    }

    public func reportOutgoingCallConnected(uuid: UUID) {
        provider.reportOutgoingCall(with: uuid, connectedAt: Date())
    }

    public func requestEndCall(uuid: UUID) {
        callController.request(CXTransaction(action: CXEndCallAction(call: uuid))) { _ in }
    }
}

extension CallController: CXProviderDelegate {
    public func providerDidReset(_ provider: CXProvider) {
        delegate?.callControllerDidReset(self)
    }

    public func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        delegate?.callController(self, userStartedCall: action.callUUID, handle: action.handle.value)
        action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        // The SIP answer is requested here, but the audio only starts in `didActivate` below
        // (the pattern linphone-iphone and baresip's CallKit notes follow).
        delegate?.callController(self, userAnswered: action.callUUID)
        action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        delegate?.callController(self, userEnded: action.callUUID)
        action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        delegate?.callController(self, userSetHeld: action.callUUID, held: action.isOnHold)
        action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        delegate?.callController(self, userSetMuted: action.callUUID, muted: action.isMuted)
        action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXPlayDTMFCallAction) {
        delegate?.callController(self, userSentDTMF: action.callUUID, digits: action.digits)
        action.fulfill()
    }

    public func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        audio.activate(true)
    }

    public func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        audio.activate(false)
    }
}
