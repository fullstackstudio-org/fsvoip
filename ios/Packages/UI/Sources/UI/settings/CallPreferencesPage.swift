// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// "Oproepvoorkeuren": what happens to a call for the own extension (always forward, when nobody picks up, voicemail), plus the
/// two settings that only live on this phone. The number a call goes out with is chosen per call, on the dial screen, not here.
struct CallPreferencesPage: View {
    @ObservedObject var model: FSVoipAppModel
    @ObservedObject var hub: SelfExtensionHub
    let account: StoredAccount
    let back: () -> Void
    let close: () -> Void

    @State private var form = SelfExtensionForm()
    @State private var isSaving = false

    private var state: SelfExtension? {
        model.capabilities(for: account.id)?.selfExtension == true ? hub.state(for: account.id) : nil
    }

    private var footer: SheetFooter? {
        form.isLoaded ? SheetFooter(canSave: form.isDirty, onCancel: back, onSave: save) : nil
    }

    var body: some View {
        PageScaffold(title: L10n.string("settings.callPreferences"), back: back, close: close, footer: footer, isSaving: isSaving, isDirty: form.isDirty) {
            VStack(alignment: .leading, spacing: 0) {
                SelfExtensionNotice(message: form.message)

                if let state, form.isLoaded {
                    serverGroups(state)
                }

                SettingsGroup(title: L10n.string("prefs.phone"), footer: String(format: L10n.string("account.showCalled.footer"), account.displayLabel)) {
                    ToggleRow(
                        title: L10n.string("account.showCalled"),
                        isOn: Binding(
                            get: { _ = model.settingsRevision; return model.showsCalledAccount(account.id) },
                            set: { model.setShowsCalledAccount(account.id, $0) }
                        )
                    )
                    .accessibilityIdentifier("show-called-toggle")
                }

                if model.accounts.count > 1 {
                    SettingsGroup(title: L10n.string("settings.defaultLine"), footer: L10n.string("settings.defaultLine.footer")) {
                        ForEach(model.accounts) { item in
                            ChoiceRow(title: item.displayLabel, isSelected: model.defaultOutgoingAccountId == item.id) {
                                model.setDefaultOutgoing(item.id)
                            }
                        }
                    }
                }
            }
        }
        .task(id: "\(account.id)/\(model.capabilities(for: account.id)?.selfExtension == true)") {
            guard model.capabilities(for: account.id)?.selfExtension == true else { return }

            await hub.load(account)
            form.adopt(hub.state(for: account.id))
        }
    }

    @ViewBuilder
    private func serverGroups(_ state: SelfExtension) -> some View {
        SettingsGroup(title: L10n.string("prefs.forward"), footer: L10n.string("prefs.forward.footer")) {
            TargetChoiceRow(
                title: L10n.string("pbx.device.forward.title"),
                target: $form.draft.forwardAlways,
                options: state.targets,
                noneLabel: L10n.string("pbx.device.forward.off"),
                allowsHangup: false,
                symbol: "arrow.uturn.forward"
            )
        }

        SettingsGroup(title: L10n.string("pbx.device.noAnswer.title"), footer: L10n.string("pbx.device.noAnswer.footer")) {
            Stepper(value: $form.draft.noAnswerSeconds, in: SelfExtensionDraft.noAnswerRange, step: 5) {
                Text(String(format: L10n.string("pbx.device.noAnswer.after"), form.draft.noAnswerSeconds))
                    .foregroundStyle(Theme.textPrimary)
            }
            .settingsRowChrome()
            .accessibilityIdentifier("prefs-noanswer-seconds")

            TargetChoiceRow(
                title: L10n.string("pbx.device.noAnswer.then"),
                target: $form.draft.noAnswerTarget,
                options: state.targets,
                noneLabel: L10n.string(form.draft.voicemailEnabled ? "pbx.device.noAnswer.default.voicemail" : "pbx.device.noAnswer.default.ring"),
                allowsHangup: false
            )
        }

        SettingsGroup(title: L10n.string("prefs.voicemail"), footer: voicemailFooter) {
            ToggleRow(title: L10n.string("pbx.device.voicemail"), explanation: L10n.string("pbx.device.voicemail.footer"), isOn: $form.draft.voicemailEnabled)
                .accessibilityIdentifier("prefs-voicemail-toggle")

            ToggleRow(title: L10n.string("prefs.voicemail.email"), isOn: $form.draft.voicemailToEmail, isEnabled: form.draft.voicemailEnabled && !form.draft.cleanEmail.isEmpty)
                .accessibilityIdentifier("prefs-voicemail-email-toggle")
        }
    }

    private var voicemailFooter: String {
        form.draft.cleanEmail.isEmpty ? L10n.string("prefs.voicemail.email.needsAddress") : String(format: L10n.string("prefs.voicemail.email.to"), form.draft.cleanEmail)
    }

    private func save() {
        guard let baseline = form.baseline, !isSaving else { return }

        isSaving = true

        Task {
            defer { isSaving = false }

            let outcome = await hub.save(form.draft, original: baseline, account: account)

            if form.finish(outcome) {
                await model.availability?.load(account)
                back()
            }
        }
    }
}
