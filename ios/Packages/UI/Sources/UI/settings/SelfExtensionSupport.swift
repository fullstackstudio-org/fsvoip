// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// What a page of the own extension says above its fields after a save that did not go through.
enum SelfExtensionMessage: Equatable {
    /// Changed somewhere else in the meantime: the newest state is in, what the user typed is still there.
    case stale
    case failure(SelfExtensionFailure)
}

struct SelfExtensionNotice: View {
    let message: SelfExtensionMessage?

    var body: some View {
        switch message {
        case .stale:
            NoticeCard(symbol: "arrow.clockwise.circle.fill", tint: Theme.busy, title: L10n.string("self.stale.title"), message: L10n.string("self.stale.message"))
                .accessibilityIdentifier("self-stale")
        case let .failure(failure):
            NoticeCard(symbol: "exclamationmark.circle.fill", tint: Theme.danger, title: failure.message)
                .accessibilityIdentifier("self-error")
        case .none:
            EmptyView()
        }
    }
}

/// The shared bookkeeping of a page that edits the own extension: the draft, the state it started from, and the save.
@MainActor
struct SelfExtensionForm {
    var draft = SelfExtensionDraft()
    var baseline: SelfExtensionDraft?
    var message: SelfExtensionMessage?

    var isLoaded: Bool { baseline != nil }

    var isDirty: Bool {
        guard let baseline else { return false }

        return draft.patch(from: baseline, version: 0) != nil
    }

    /// Takes the state in: the first time as the start, later (a refresh) only the fields the user did not touch.
    mutating func adopt(_ state: SelfExtension?) {
        guard let state else { return }

        if var existing = baseline {
            draft.rebase(onto: state, baseline: &existing)
            baseline = existing
        } else {
            draft = SelfExtensionDraft(state)
            baseline = draft
        }
    }

    /// Saves through the hub. `true` = nothing left to do (saved or nothing to save).
    mutating func finish(_ outcome: SelfExtensionHub.SaveOutcome) -> Bool {
        switch outcome {
        case let .saved(fresh):
            message = nil
            // What was saved is now the start; a field the server answers differently follows the server.
            if let fresh { draft = SelfExtensionDraft(fresh); baseline = draft }

            return true
        case .unchanged:
            message = nil

            return true
        case let .stale(fresh):
            if var existing = baseline {
                draft.rebase(onto: fresh, baseline: &existing)
                baseline = existing
            }
            message = .stale

            return false
        case let .failed(failure):
            message = .failure(failure)

            return false
        }
    }
}
