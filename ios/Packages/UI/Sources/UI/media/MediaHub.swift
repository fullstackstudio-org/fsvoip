// SPDX-License-Identifier: AGPL-3.0-or-later
import Combine
import Core
import Foundation
import SwiftUI

/// Who may open "Voicemail" and "Opnames", per paired account, and the one player they share.
///
/// Like the "Centrale" section, the rights come from the app's `GET /me` (`apply(me:accountId:)`) and are never stored on the
/// phone: after a restart the sections stay hidden until the server has said so again (fail closed). A 403 from the server hides
/// the part at once. The server enforces every route; this keeps the screens honest.
@MainActor
public final class MediaHub: ObservableObject {
    public struct Access: Equatable {
        /// `.own`: only the own box. `.all`: every box (an admin). `.noAccess`: no voicemail section.
        public var voicemail: VoicemailAccess
        public var recordings: Bool
        /// The phone system does not accept changes now (frozen, being set up): no deleting.
        public var readOnly: Bool

        public var hasVoicemail: Bool { voicemail == .own || voicemail == .all }
        public var hasAnything: Bool { hasVoicemail || recordings }
    }

    @Published public private(set) var access: [String: Access] = [:]
    /// Set when a part was taken away while it was open, so the app can say so.
    @Published public var lostAccessFor: String?

    public let gate: LocalAccessGate
    public let player: AudioStreamer

    /// Asks the app to re-read its accounts (a 401).
    public var onRevoked: (() -> Void)?
    /// Name of a phone number from the customer's contacts (for the rows).
    public var nameLookup: (String) -> String? = { _ in nil }

    let service: MediaServicing
    let soundService: SoundServicing
    private let cache: MediaCache
    /// Who may manage sounds, from `GET /me` (admin + `capabilities.sounds == manage`). Fail closed.
    @Published public private(set) var soundsAllowed: Set<String> = []
    private var soundsModels: [String: SoundsModel] = [:]
    private let callFlag = CallActivityFlag()

    public init(service: MediaServicing, gate: LocalAccessGate, cache: MediaCache = MediaCache(), backend: AudioBackend? = nil, soundService: SoundServicing = LiveSoundService()) {
        let flag = callFlag
        let backend = backend ?? AVPlayerBackend()
        self.service = service
        self.soundService = soundService
        self.gate = gate
        self.cache = cache
        backend.isCallActive = { flag.active }
        player = AudioStreamer(backend: backend, cache: cache, isCallActive: { flag.active })
    }

    // MARK: Access

    public func access(for accountId: String) -> Access? {
        access[accountId]
    }

    public func hasVoicemail(_ accountId: String) -> Bool {
        access[accountId]?.hasVoicemail == true
    }

    public func hasRecordings(_ accountId: String) -> Bool {
        access[accountId]?.recordings == true
    }

    public func isAvailable(_ accountId: String) -> Bool {
        access[accountId]?.hasAnything == true
    }

    /// The answer of `GET /me`.
    public func apply(me: MeResponse, accountId: String) {
        applySounds(me: me, accountId: accountId)

        // Without `capabilities` (an older server) nothing is offered: fail closed.
        guard let capabilities = me.capabilities else {
            set(nil, for: accountId)

            return
        }

        let voicemail: VoicemailAccess

        switch capabilities.voicemail {
        case .all where me.effectiveRole == .admin:
            voicemail = .all
        case .all, .own:
            voicemail = .own
        default:
            voicemail = .noAccess
        }

        let value = Access(
            voicemail: voicemail,
            recordings: me.effectiveRole == .admin && capabilities.recordings,
            readOnly: me.pbx?.readOnly ?? false
        )

        set(value.hasAnything ? value : nil, for: accountId)
    }

    /// `GET /me` said 403: this pairing has no rights any more.
    public func accessDenied(accountId: String) {
        set(nil, for: accountId)
        setSoundsAllowed(false, accountId: accountId)
    }

    // MARK: Sounds

    private func applySounds(me: MeResponse, accountId: String) {
        setSoundsAllowed(me.effectiveRole == .admin && me.capabilities?.sounds == .manage, accountId: accountId)
    }

    private func setSoundsAllowed(_ allowed: Bool, accountId: String) {
        if allowed {
            soundsAllowed.insert(accountId)

            return
        }

        guard soundsAllowed.remove(accountId) != nil else { return }

        soundsModels[accountId]?.stopAll()
        soundsModels[accountId] = nil
    }

    /// A sounds call said 403: this pairing may not manage sounds (any more).
    func soundsDenied(accountId: String) {
        setSoundsAllowed(false, accountId: accountId)
    }

    public func canManageSounds(_ accountId: String) -> Bool {
        soundsAllowed.contains(accountId)
    }

    /// The model of the sounds of this account (made on first use, dropped when the right goes). `nil` for anyone who may not
    /// manage sounds: a plain user never gets one.
    func soundsModel(for account: StoredAccount) -> SoundsModel? {
        guard soundsAllowed.contains(account.id) else { return nil }

        if let existing = soundsModels[account.id] {
            return existing
        }

        let model = SoundsModel(account: account, hub: self)
        soundsModels[account.id] = model

        return model
    }

    /// A call is going on (the microphone and the audio session belong to it).
    var isCallActive: Bool { callFlag.active }

    /// One part got a 403 (the role changed in between): take just that part away.
    func partDenied(_ part: MediaPart, accountId: String) {
        guard var value = access[accountId] else { return }

        switch part {
        case .voicemail: value.voicemail = .noAccess
        case .recordings: value.recordings = false
        }

        set(value.hasAnything ? value : nil, for: accountId)
    }

    private func set(_ value: Access?, for accountId: String) {
        let previous = access[accountId]

        guard previous != value else { return }

        access[accountId] = value

        guard let previous else { return }

        let lostVoicemail = previous.hasVoicemail && !(value?.hasVoicemail ?? false)
        let lostRecordings = previous.recordings && !(value?.recordings ?? false)

        // What was taken away closes: stop the sound, lock again and say so.
        if lostVoicemail || lostRecordings {
            lostAccessFor = accountId
            player.stop()
            gate.lock()
        }
    }

    // MARK: Calls

    /// The phone has a call (or ringing call): the sound pauses and does not start.
    public func callStateChanged(isActive: Bool) {
        callFlag.active = isActive

        if isActive {
            player.callActivityChanged()

            for model in soundsModels.values {
                model.callStarted()
            }
        }
    }

    // MARK: Models

    func makeVoicemailModel(account: StoredAccount) -> VoicemailModel {
        VoicemailModel(
            account: account,
            hub: self
        )
    }

    func makeRecordingsModel(account: StoredAccount) -> RecordingsModel {
        RecordingsModel(account: account, hub: self)
    }

    /// The account is gone from this phone.
    public func forget(accountId: String) {
        access[accountId] = nil
        setSoundsAllowed(false, accountId: accountId)
        player.stop()
        cache.clear()
    }
}

/// Whether a call is in progress, readable from the player without a reference to the hub.
final class CallActivityFlag {
    var active = false
}

enum MediaPart {
    case voicemail
    case recordings
}
