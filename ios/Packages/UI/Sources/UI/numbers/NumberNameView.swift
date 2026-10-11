// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// What a step form does with the answer of a save: close, show the fresh chain without losing the input, or say what is wrong.
@MainActor
enum ChainOutcomeHandler {
    /// `true` = the form can close.
    static func apply<Draft: ChainDraft>(
        _ outcome: ChainSaveOutcome,
        fresh: NumberChain?,
        draft: inout Draft,
        baseline: inout Draft,
        message: inout ChainEditorMessage?
    ) -> Bool {
        switch outcome {
        case .saved, .unchanged:
            message = nil
            return true
        case .stale:
            if let fresh {
                draft.rebase(onto: fresh, baseline: &baseline)
            }
            message = .stale
        case let .failed(failure):
            message = .failure(failure)
        case let .costRequired(cost):
            message = .failure(.costRequired(cost))
        }

        return false
    }
}

/// "Naam": what the number is called in the app, the portal and the call history.
struct NumberNameView: View {
    @ObservedObject var model: PbxSectionModel
    let numberId: String

    @State private var draft: NumberNameDraft
    @State private var baseline: NumberNameDraft
    @State private var message: ChainEditorMessage?
    @Environment(\.dismiss) private var dismiss

    init(model: PbxSectionModel, chain: NumberChain) {
        self.model = model
        numberId = chain.id
        _draft = State(initialValue: NumberNameDraft(chain))
        _baseline = State(initialValue: NumberNameDraft(chain))
    }

    var body: some View {
        ChainEditorScaffold(model: model, title: L10n.string("numbers.name.title"), isDirty: draft != baseline, message: message, onSave: save) {
            SettingsGroup(footer: L10n.string("numbers.name.footer")) {
                TextField(PbxVocabulary.formatNumber(model.chains[numberId]?.number ?? ""), text: $draft.name)
                    .textInputAutocapitalization(.sentences)
                    .submitLabel(.done)
                    .foregroundStyle(Theme.textPrimary)
                    .settingsRowChrome()
                    .accessibilityLabel(L10n.string("numbers.name.title"))
                    .accessibilityIdentifier("number-name-field")
            }
        }
    }

    private func save() {
        guard let chain = model.chains[numberId] else { return }

        Task {
            let outcome = await model.saveChainStep(numberId: numberId, draft.step(baseline: baseline, chain: chain))

            if ChainOutcomeHandler.apply(outcome, fresh: model.chains[numberId], draft: &draft, baseline: &baseline, message: &message) {
                dismiss()
            }
        }
    }
}
