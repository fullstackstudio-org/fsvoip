// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// "Toestellen": every toestel of the centrale with whether it is connected and what it does with a call.
struct DevicesView: View {
    @ObservedObject var model: PbxSectionModel

    @Environment(\.dismiss) private var dismiss
    @Environment(\.pbxClose) private var close

    var body: some View {
        SheetShell(title: L10n.string("pbx.devices.title"), back: { dismiss() }, onClose: { (close ?? { dismiss() })() }) {
            VStack(alignment: .leading, spacing: 0) {
                SectionNoticeCards(model: model)

                if let response = model.devices {
                    if response.devices.isEmpty {
                        EmptyState(symbol: "phone", title: L10n.string("pbx.devices.empty"))
                    } else {
                        SettingsGroup(footer: L10n.string("pbx.devices.footer")) {
                            ForEach(response.devices) { device in
                                NavigationLink {
                                    DeviceEditView(model: model, device: device)
                                } label: {
                                    DeviceRow(device: device, options: response.targets)
                                }
                                .buttonStyle(RowButtonStyle())
                                .accessibilityIdentifier("pbx-device-row")
                            }
                        }
                    }
                } else if model.isLoading {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(Theme.Spacing.xl)
                } else if model.loadFailure != nil || model.isOutdated {
                    EmptyState(symbol: "wifi.exclamationmark", title: L10n.string("pbx.devices.loadFailed"), actionTitle: L10n.string("action.retry")) {
                        Task { await model.refresh(.devices) }
                    }
                }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .refreshable { await model.refresh(.devices) }
        .task { await model.loadIfNeeded(.devices) }
        .accessibilityIdentifier("pbx-devices")
    }
}

private struct DeviceRow: View {
    let device: PbxDevice
    let options: [PbxTargetOption]

    private var connection: String? {
        guard let registration = device.registration else {
            return nil
        }

        return registration.connected ? L10n.string("pbx.device.connected") : L10n.string("pbx.device.disconnected")
    }

    var body: some View {
        HStack(spacing: Theme.Spacing.m) {
            InitialsAvatar(name: device.name, size: 40, dot: device.registration?.connected == true ? Theme.accent : nil)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: Theme.Spacing.s) {
                    Text(device.name)
                        .font(.body)
                        .foregroundStyle(Theme.textPrimary)
                    if let number = device.extensionNumber {
                        Text(number)
                            .font(.subheadline)
                            .foregroundStyle(Theme.textSecondary)
                    }
                }

                if let connection {
                    Text(connection)
                        .font(.footnote)
                        .foregroundStyle(Theme.textSecondary)
                }

                if let forward = device.forwardAlways {
                    Label(String(format: L10n.string("pbx.device.forwarding"), PbxVocabulary.describe(forward, options: options)), systemImage: "arrow.uturn.forward")
                        .font(.footnote)
                        .foregroundStyle(Theme.busy)
                }

                if device.dnd {
                    Label(L10n.string("pbx.flow.dnd"), systemImage: "moon.fill")
                        .font(.footnote)
                        .foregroundStyle(Theme.busy)
                }

                PbxSyncBadge(sync: device.sync)
            }

            Spacer(minLength: Theme.Spacing.s)

            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Theme.textTertiary)
                .accessibilityHidden(true)
        }
        .settingsRowChrome()
        .accessibilityElement(children: .combine)
    }
}

/// The settings of one toestel an admin may change from the app. The form keeps the version it was opened with.
struct DeviceEditView: View {
    @ObservedObject var model: PbxSectionModel
    @State private var original: PbxDevice
    @State private var draft: DeviceDraft
    @State private var failure: PbxFailure?
    @Environment(\.dismiss) private var dismiss

    init(model: PbxSectionModel, device: PbxDevice) {
        self.model = model
        _original = State(initialValue: device)
        _draft = State(initialValue: DeviceDraft(device))
    }

    private var options: [PbxTargetOption] {
        TargetChoice.excluding(original.id, from: model.targetOptions)
    }

    private var hasChanges: Bool {
        draft != DeviceDraft(original)
    }

    var body: some View {
        ChainEditorScaffold(model: model, title: original.name, isDirty: hasChanges, message: failure.map(ChainEditorMessage.failure), onSave: save) {
            VStack(alignment: .leading, spacing: 0) {
                SettingsGroup(title: original.name) {
                    ToggleRow(title: L10n.string("pbx.device.voicemail"), explanation: L10n.string("pbx.device.voicemail.footer"), isOn: $draft.voicemailEnabled)
                        .accessibilityIdentifier("pbx-voicemail-toggle")
                    ToggleRow(title: L10n.string("pbx.device.dnd"), explanation: L10n.string("pbx.device.dnd.footer"), isOn: $draft.dnd)
                        .accessibilityIdentifier("pbx-dnd-toggle")
                }

                SettingsGroup(footer: L10n.string("pbx.device.forward.footer")) {
                    TargetChoiceRow(title: L10n.string("pbx.device.forward.title"), target: $draft.forwardAlways, options: options, noneLabel: L10n.string("pbx.device.forward.off"), symbol: "arrow.uturn.forward")
                        .accessibilityIdentifier("pbx-forward-row")
                }

                SettingsGroup(title: L10n.string("pbx.device.noAnswer.title"), footer: L10n.string("pbx.device.noAnswer.footer")) {
                    Stepper(value: $draft.noAnswerSeconds, in: DeviceDraft.noAnswerRange, step: 5) {
                        Text(String(format: L10n.string("pbx.device.noAnswer.after"), draft.noAnswerSeconds))
                            .foregroundStyle(Theme.textPrimary)
                    }
                    .settingsRowChrome()

                    TargetChoiceRow(title: L10n.string("pbx.device.noAnswer.then"), target: $draft.noAnswerTarget, options: options, noneLabel: L10n.string(draft.voicemailEnabled ? "pbx.device.noAnswer.default.voicemail" : "pbx.device.noAnswer.default.ring"))
                }

                SettingsGroup(title: L10n.string("pbx.device.busy.title")) {
                    TargetChoiceRow(title: L10n.string("pbx.device.busy.then"), target: $draft.busyTarget, options: options, noneLabel: L10n.string("pbx.device.busy.default"))
                }

                SettingsGroup(title: L10n.string("pbx.device.offline.title")) {
                    TargetChoiceRow(title: L10n.string("pbx.device.offline.then"), target: $draft.notRegisteredTarget, options: options, noneLabel: L10n.string("pbx.device.offline.default"))
                }

                followMeGroup
            }
        }
        .accessibilityIdentifier("pbx-device-edit")
    }

    private var followMeGroup: some View {
        SettingsGroup(title: L10n.string("pbx.followMe.title"), footer: L10n.string("pbx.followMe.footer")) {
            ForEach(draft.followMe.indices, id: \.self) { index in
                NavigationLink {
                    FollowMeStepView(
                        step: stepBinding(index),
                        options: options,
                        canMoveUp: index > 0,
                        canMoveDown: index < draft.followMe.count - 1,
                        onMove: { move(index, by: $0) },
                        onDelete: { remove(index) }
                    )
                } label: {
                    SettingsRow(title: PbxVocabulary.describe(draft.followMe[index].target, options: options), subtitle: String(format: L10n.string("pbx.followMe.timing"), draft.followMe[index].delaySeconds, draft.followMe[index].timeoutSeconds))
                }
                .buttonStyle(RowButtonStyle())
                .accessibilityIdentifier("pbx-followme-row")
            }

            if draft.followMe.count < DeviceDraft.followMeLimit {
                Button {
                    draft.followMe.append(FollowMeStep(target: .external(""), delaySeconds: draft.followMe.isEmpty ? 0 : 10))
                } label: {
                    SettingsRow(symbol: "plus.circle", title: L10n.string("pbx.followMe.add"), showsChevron: false)
                }
                .buttonStyle(RowButtonStyle())
                .accessibilityIdentifier("pbx-followme-add")
            }
        }
    }

    /// A binding that survives the step being deleted while its page is still on screen.
    private func stepBinding(_ index: Int) -> Binding<FollowMeStep> {
        let fallback = FollowMeStep(target: .external(""))

        return Binding(
            get: { draft.followMe.indices.contains(index) ? draft.followMe[index] : fallback },
            set: { if draft.followMe.indices.contains(index) { draft.followMe[index] = $0 } }
        )
    }

    private func move(_ index: Int, by offset: Int) {
        let target = index + offset

        guard draft.followMe.indices.contains(index), draft.followMe.indices.contains(target) else { return }

        draft.followMe.swapAt(index, target)
    }

    private func remove(_ index: Int) {
        guard draft.followMe.indices.contains(index) else { return }

        draft.followMe.remove(at: index)
    }

    private func save() {
        // A step without a destination is a half-filled form, not something the server should judge.
        guard !draft.followMe.contains(where: { $0.target.type == .external && ($0.target.number ?? "").trimmingCharacters(in: .whitespaces).isEmpty }) else {
            failure = .invalid
            return
        }

        Task {
            let outcome = await model.saveDevice(draft, original: original)

            if outcome.closesForm {
                dismiss()
            } else if case let .failed(reason) = outcome {
                failure = reason
            }
        }
    }
}

/// One step of "volg mij": who is called next, after how long, and for how long.
struct FollowMeStepView: View {
    @Binding var step: FollowMeStep
    let options: [PbxTargetOption]
    var canMoveUp = false
    var canMoveDown = false
    var onMove: (Int) -> Void = { _ in }
    var onDelete: () -> Void = {}

    @Environment(\.dismiss) private var dismiss
    @Environment(\.pbxClose) private var close

    private var target: Binding<PbxTarget?> {
        Binding(get: { step.target }, set: { if let value = $0 { step.target = value } })
    }

    var body: some View {
        SheetShell(title: L10n.string("pbx.followMe.step"), back: { dismiss() }, onClose: { (close ?? { dismiss() })() }) {
            VStack(alignment: .leading, spacing: 0) {
                SettingsGroup {
                    TargetChoiceRow(title: L10n.string("pbx.followMe.who"), target: target, options: options, allowsNone: false)
                }

                SettingsGroup(footer: L10n.string("pbx.followMe.step.footer")) {
                    Stepper(value: $step.delaySeconds, in: 0 ... 120, step: 5) {
                        Text(String(format: L10n.string("pbx.followMe.delay"), step.delaySeconds)).foregroundStyle(Theme.textPrimary)
                    }
                    .settingsRowChrome()

                    Stepper(value: $step.timeoutSeconds, in: 5 ... 120, step: 5) {
                        Text(String(format: L10n.string("pbx.followMe.timeout"), step.timeoutSeconds)).foregroundStyle(Theme.textPrimary)
                    }
                    .settingsRowChrome()
                }

                SettingsGroup {
                    if canMoveUp {
                        Button { onMove(-1) } label: {
                            SettingsRow(symbol: "arrow.up", title: L10n.string("pbx.followMe.moveUp"), showsChevron: false)
                        }
                        .buttonStyle(RowButtonStyle())
                    }

                    if canMoveDown {
                        Button { onMove(1) } label: {
                            SettingsRow(symbol: "arrow.down", title: L10n.string("pbx.followMe.moveDown"), showsChevron: false)
                        }
                        .buttonStyle(RowButtonStyle())
                    }

                    Button {
                        dismiss()
                        // After the page has gone: its binding must not read a step that no longer exists.
                        DispatchQueue.main.async { onDelete() }
                    } label: {
                        SettingsRow(symbol: "trash", title: L10n.string("pbx.followMe.remove"), showsChevron: false, isDestructive: true)
                    }
                    .buttonStyle(RowButtonStyle())
                    .accessibilityIdentifier("pbx-followme-remove")
                }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
    }
}
