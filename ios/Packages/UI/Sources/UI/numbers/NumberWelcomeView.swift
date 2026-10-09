// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// "Welkomstbericht": a message callers hear first, before the phones ring or the menu starts.
struct NumberWelcomeView: View {
    @ObservedObject var model: PbxSectionModel
    let numberId: String

    @State private var draft: NumberWelcomeDraft
    @State private var baseline: NumberWelcomeDraft
    @State private var message: ChainEditorMessage?
    @Environment(\.dismiss) private var dismiss

    init(model: PbxSectionModel, chain: NumberChain) {
        self.model = model
        numberId = chain.id
        _draft = State(initialValue: NumberWelcomeDraft(chain))
        _baseline = State(initialValue: NumberWelcomeDraft(chain))
    }

    private var chain: NumberChain? { model.chains[numberId] }
    private var options: ChainOptions { chain?.options ?? ChainOptions() }

    var body: some View {
        ChainEditorScaffold(model: model, title: L10n.string("numbers.welcome.title"), isDirty: draft != baseline, canSave: draft.canSave, message: message, onSave: save) {
            VStack(alignment: .leading, spacing: 0) {
                SharedNote(names: chain?.welcome?.sharedWith ?? [])

                SettingsGroup {
                    ToggleRow(title: L10n.string("numbers.welcome.toggle"), explanation: L10n.string("numbers.welcome.explanation"), isOn: $draft.enabled)
                        .accessibilityIdentifier("welcome-toggle")
                }

                if draft.enabled {
                    SettingsGroup(title: L10n.string("numbers.welcome.sound"), footer: draft.soundId == nil ? L10n.string("numbers.welcome.soundNeeded") : nil) {
                        ChainSoundRow(title: L10n.string("numbers.welcome.sound"), soundId: $draft.soundId, options: options)
                    }
                }
            }
        }
    }

    private func save() {
        guard let chain else { return }

        Task {
            let outcome = await model.saveChainStep(numberId: numberId, draft.step(baseline: baseline, chain: chain))

            if ChainOutcomeHandler.apply(outcome, fresh: model.chains[numberId], draft: &draft, baseline: &baseline, message: &message) {
                dismiss()
            }
        }
    }
}
