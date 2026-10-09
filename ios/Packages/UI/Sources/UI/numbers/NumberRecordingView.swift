// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// "Gespreksopname": record the calls of this number (incoming and outgoing), optionally with a short message that says so.
/// Switching it on costs a monthly amount: the price is shown and agreed to first, unless it is already being billed (D11).
struct NumberRecordingView: View {
    @ObservedObject var model: PbxSectionModel
    let numberId: String

    @State private var draft: NumberRecordingDraft
    @State private var baseline: NumberRecordingDraft
    @State private var message: ChainEditorMessage?
    /// The price the server asked consent for (a 422 `cost_not_accepted`), when it differs from the chain's.
    @State private var serverCost: RecordingCost?
    @Environment(\.dismiss) private var dismiss

    init(model: PbxSectionModel, chain: NumberChain) {
        self.model = model
        numberId = chain.id
        _draft = State(initialValue: NumberRecordingDraft(chain))
        _baseline = State(initialValue: NumberRecordingDraft(chain))
    }

    private var chain: NumberChain? { model.chains[numberId] }
    private var options: ChainOptions { chain?.options ?? ChainOptions() }
    private var recording: ChainRecording? { chain?.recording }

    private var cost: RecordingCost? { serverCost ?? recording?.cost }

    private var needsConsent: Bool {
        guard let recording else { return false }

        // The server asked (a 422 with a price) or this save starts the billing.
        return draft.enabled && serverCost != nil || draft.needsConsent(recording)
    }

    private var canSave: Bool {
        !needsConsent || draft.costAccepted
    }

    var body: some View {
        ChainEditorScaffold(model: model, title: L10n.string("numbers.recording.title"), isDirty: draft != baseline, canSave: canSave, message: message, onSave: save) {
            if recording?.available == false && !(recording?.enabled ?? false) {
                EmptyState(symbol: "mic.slash", title: L10n.string("numbers.recording.unavailable.title"), message: L10n.string("numbers.recording.unavailable.message"))
                    .accessibilityIdentifier("recording-unavailable")
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    SettingsGroup(footer: L10n.string("numbers.recording.legal")) {
                        ToggleRow(title: L10n.string("numbers.recording.toggle"), explanation: L10n.string("numbers.recording.explanation"), isOn: $draft.enabled)
                            .accessibilityIdentifier("recording-toggle")
                    }

                    if draft.enabled {
                        if needsConsent, let cost {
                            SettingsGroup(title: L10n.string("numbers.recording.cost")) {
                                Text(RecordingPrice.text(cost))
                                    .foregroundStyle(Theme.textPrimary)
                                    .settingsRowChrome()
                                    .accessibilityIdentifier("recording-price")
                                ToggleRow(title: L10n.string("numbers.recording.consent"), explanation: L10n.string("numbers.recording.consent.explanation"), isOn: $draft.costAccepted)
                                    .accessibilityIdentifier("recording-consent")
                            }
                        }

                        SettingsGroup {
                            ToggleRow(title: L10n.string("numbers.recording.announce"), explanation: L10n.string("numbers.recording.announce.explanation"), isOn: Binding(
                                get: { draft.announcementSoundId != nil },
                                set: { on in draft.announcementSoundId = on ? (draft.announcementSoundId ?? options.sounds.first?.id) : nil }
                            ))
                            .disabled(options.sounds.isEmpty && draft.announcementSoundId == nil)
                            .accessibilityIdentifier("recording-announce")

                            if draft.announcementSoundId != nil {
                                ChainSoundRow(title: L10n.string("numbers.recording.announce.sound"), soundId: $draft.announcementSoundId, options: options, allowsNone: true)
                            }
                        }

                        if options.sounds.isEmpty {
                            Text(L10n.string("numbers.sound.empty.message"))
                                .font(.footnote)
                                .foregroundStyle(Theme.textTertiary)
                                .padding(.horizontal, Theme.Spacing.l)
                        }
                    }
                }
            }
        }
    }

    private func save() {
        guard let chain else { return }

        Task {
            let outcome = await model.saveRecording(numberId: numberId, draft.patch(baseline: baseline, chain: chain))

            if case let .costRequired(cost) = outcome {
                // The server wants consent this app did not ask for (a price came in): show it and let the user agree.
                serverCost = cost ?? chain.recording.cost
                draft.costAccepted = false
                message = nil

                return
            }

            if ChainOutcomeHandler.apply(outcome, fresh: model.chains[numberId], draft: &draft, baseline: &baseline, message: &message) {
                dismiss()
            }
        }
    }
}
