// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation

/// Why a "Centrale" call did not work, as the screens need to tell it. The server decides what is allowed; this only
/// sorts the answer into what the user can do about it.
enum PbxFailure: Equatable {
    /// 403: the role was taken away. The whole section disappears.
    case accessLost
    /// 401: the pairing is gone. The app removes the account.
    case revoked
    /// 409 `read_only`: the centrale is frozen or still being set up.
    case readOnly
    /// 409 `stale`: someone else changed it in the meantime.
    case stale
    /// 409 `in_use`: names of what still uses it.
    case inUse([String])
    /// 422: a block list stops these external numbers.
    case blockedDestination([String])
    case rateLimited(retryAfterSeconds: Int?)
    case unavailable
    case offline
    /// 400 and other "this cannot be saved like this" answers.
    case invalid
    case conflict(String)
    /// The Face ID / passcode check was cancelled or failed: nothing was sent.
    case authentication
    /// No passcode on this phone.
    case notAvailable
    /// 409 `advanced`: the number is set up in more detail than the app can show; only name and recording here.
    case advanced
    /// A welcome message or menu needs a sound first.
    case greetingRequired
    /// Switching on call recording costs money: show the price and ask first.
    case costRequired(RecordingCost?)
    case other

    static func classify(_ error: Error) -> PbxFailure {
        guard let api = error as? APIError else {
            return .other
        }

        switch api {
        case .forbidden:
            return .accessLost
        case .unauthorized:
            return .revoked
        case .readOnly:
            return .readOnly
        case .stale, .staleChain:
            return .stale
        case let .inUse(places):
            return .inUse(places.map(\.name))
        case let .blockedDestination(numbers):
            return .blockedDestination(numbers)
        case let .rateLimited(seconds):
            return .rateLimited(retryAfterSeconds: seconds)
        case .unavailable, .unexpectedStatus:
            return .unavailable
        case .transport:
            return .offline
        case .greetingRequired:
            return .greetingRequired
        case .invalid, .invalidRequest, .payloadTooLarge, .resync, .invalidAudio, .tooLarge:
            return .invalid
        case let .conflict(code):
            return .conflict(code)
        case .advanced:
            return .advanced
        case let .costNotAccepted(cost):
            return .costRequired(cost)
        case .tooMany:
            return .conflict("too_many")
        case .noFreeSlot:
            return .conflict("no_free_slot")
        case .parkUnavailable:
            return .conflict("park_unavailable")
        case .parkUncertain:
            return .unavailable
        case .notFound, .gone, .callNotFound:
            // The object is gone: reload and see.
            return .stale
        case .missingDeviceToken, .decoding:
            return .other
        }
    }

    /// The sentence for the user. No server text, no telecom words.
    var message: String {
        switch self {
        case .accessLost:
            return L10n.string("pbx.error.accessLost")
        case .revoked:
            return L10n.string("pbx.error.revoked")
        case .readOnly:
            return L10n.string("pbx.error.readOnly")
        case .stale:
            return L10n.string("pbx.error.stale")
        case let .inUse(places):
            let list = places.isEmpty ? L10n.string("pbx.error.inUse.somewhere") : places.joined(separator: ", ")

            return String(format: L10n.string("pbx.error.inUse"), list)
        case let .blockedDestination(numbers):
            let list = numbers.joined(separator: ", ")

            return String(format: L10n.string("pbx.error.blocked"), list)
        case let .rateLimited(seconds):
            if let seconds, seconds > 0 {
                return String(format: L10n.string("pbx.error.rateLimited.seconds"), seconds)
            }

            return L10n.string("pbx.error.rateLimited")
        case .unavailable:
            return L10n.string("pbx.error.unavailable")
        case .offline:
            return L10n.string("pbx.error.offline")
        case .invalid:
            return L10n.string("pbx.error.invalid")
        case let .conflict(code):
            switch code {
            case "busy": return L10n.string("pbx.error.busy")
            default: return L10n.string("pbx.error.conflict")
            }
        case .authentication:
            return L10n.string("pbx.error.authentication")
        case .notAvailable:
            return L10n.string("pbx.lock.noPasscode")
        case .advanced:
            return L10n.string("pbx.error.advanced")
        case .greetingRequired:
            return L10n.string("pbx.error.greetingRequired")
        case .costRequired:
            return L10n.string("pbx.error.costRequired")
        case .other:
            return L10n.string("error.generic")
        }
    }
}

/// What a save did.
enum PbxSaveOutcome: Equatable {
    case saved
    /// Nothing differed from what the server has: nothing was sent.
    case unchanged
    /// Changed in the meantime: the newest data has been loaded, the form should close.
    case stale
    case failed(PbxFailure)

    /// The form can close (it saved, had nothing to save, or its data is out of date anyway).
    var closesForm: Bool {
        switch self {
        case .saved, .unchanged, .stale: return true
        case .failed: return false
        }
    }
}

/// What a step of a number's chain did.
enum ChainSaveOutcome: Equatable {
    case saved
    /// Nothing differed: nothing was sent.
    case unchanged
    /// Changed in the meantime: the fresh chain is in the model; the form stays open and keeps what was typed.
    case stale
    /// Switching recording on costs money: show the price (this one, or the one of the chain) and ask.
    case costRequired(RecordingCost?)
    case failed(PbxFailure)

    var closesForm: Bool {
        switch self {
        case .saved, .unchanged: return true
        case .stale, .costRequired, .failed: return false
        }
    }
}
