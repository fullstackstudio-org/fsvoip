// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// Where the callers of one number go. "Begin van de belstroom" = the standard start of the centrale.
struct NumberRoutingView: View {
    @ObservedObject var model: PbxSectionModel
    @State private var number: PbxNumber
    @State private var target: PbxTarget?
    @State private var failure: PbxFailure?
    @Environment(\.dismiss) private var dismiss

    init(model: PbxSectionModel, number: PbxNumber) {
        self.model = model
        _number = State(initialValue: number)
        _target = State(initialValue: number.routing)
    }

    var body: some View {
        Form {
            PbxNoticesSection(model: model)

            PbxFormError(failure: failure)

            Section {
                TargetRow(
                    title: L10n.string("pbx.routing.callers"),
                    target: $target,
                    options: model.targetOptions,
                    noneLabel: L10n.string("pbx.routing.standard"),
                    footer: L10n.string("pbx.routing.standard.footer")
                )
                .accessibilityIdentifier("pbx-routing-target")
            } header: {
                Text(PbxVocabulary.formatNumber(number.number))
            } footer: {
                Text(L10n.string("pbx.routing.footer"))
            }
            .disabled(model.isReadOnly)
        }
        .navigationTitle(L10n.string("pbx.routing.title"))
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.loadIfNeeded(.devices) }
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                PbxSaveButton(isSaving: model.isSaving, isEnabled: target != number.routing && !model.isReadOnly, action: save)
            }
        }
    }

    private func save() {
        Task {
            let outcome = await model.saveRouting(of: number, to: target)

            if outcome.closesForm {
                dismiss()
            } else if case let .failed(reason) = outcome {
                failure = reason
            }
        }
    }
}
