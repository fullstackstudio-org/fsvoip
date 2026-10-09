// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

struct DevicesView: View {
    @ObservedObject var model: PbxSectionModel

    var body: some View {
        List {
            PbxNoticesSection(model: model)

            if let response = model.devices {
                Section {
                    ForEach(response.devices) { device in
                        NavigationLink {
                            DeviceEditView(model: model, device: device)
                        } label: {
                            DeviceRow(device: device, options: response.targets)
                        }
                        .accessibilityIdentifier("pbx-device-row")
                    }
                } footer: {
                    Text(L10n.string("pbx.devices.footer"))
                }
            } else if model.isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle(L10n.string("pbx.devices.title"))
        .refreshable { await model.refresh(.devices) }
        .task { await model.loadIfNeeded(.devices) }
        .accessibilityIdentifier("pbx-devices")
    }
}

private struct DeviceRow: View {
    let device: PbxDevice
    let options: [PbxTargetOption]

    private var connection: (text: String, tint: Color)? {
        guard let registration = device.registration else {
            return nil
        }

        return registration.connected
            ? (L10n.string("pbx.device.connected"), Color.green)
            : (L10n.string("pbx.device.disconnected"), Color(.systemGray))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(device.name)
                    .font(.body.weight(.medium))
                if let number = device.extensionNumber {
                    Text(number)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }

            if let connection {
                HStack(spacing: 6) {
                    Circle().fill(connection.tint).frame(width: 8, height: 8)
                        .accessibilityHidden(true)
                    Text(connection.text)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            if let forward = device.forwardAlways {
                Label(String(format: L10n.string("pbx.device.forwarding"), PbxVocabulary.describe(forward, options: options)), systemImage: "arrow.uturn.forward")
                    .font(.footnote)
                    .foregroundStyle(Brand.amber)
            }

            if device.dnd {
                Label(L10n.string("pbx.flow.dnd"), systemImage: "moon.fill")
                    .font(.footnote)
                    .foregroundStyle(Brand.amber)
            }

            PbxSyncBadge(sync: device.sync)
        }
        .padding(.vertical, 2)
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
        Form {
            PbxNoticesSection(model: model)

            PbxFormError(failure: failure)

            Group {
                Section {
                    Toggle(L10n.string("pbx.device.voicemail"), isOn: $draft.voicemailEnabled)
                        .accessibilityIdentifier("pbx-voicemail-toggle")
                } header: {
                    Text(original.name)
                } footer: {
                    Text(L10n.string("pbx.device.voicemail.footer"))
                }

                Section {
                    Toggle(L10n.string("pbx.device.dnd"), isOn: $draft.dnd)
                        .accessibilityIdentifier("pbx-dnd-toggle")
                } footer: {
                    Text(L10n.string("pbx.device.dnd.footer"))
                }

                Section {
                    TargetRow(title: L10n.string("pbx.device.forward.title"), target: $draft.forwardAlways, options: options, noneLabel: L10n.string("pbx.device.forward.off"))
                        .accessibilityIdentifier("pbx-forward-row")
                } footer: {
                    Text(L10n.string("pbx.device.forward.footer"))
                }

                Section {
                    Stepper(value: $draft.noAnswerSeconds, in: DeviceDraft.noAnswerRange, step: 5) {
                        Text(String(format: L10n.string("pbx.device.noAnswer.after"), draft.noAnswerSeconds))
                    }
                    TargetRow(title: L10n.string("pbx.device.noAnswer.then"), target: $draft.noAnswerTarget, options: options, noneLabel: L10n.string(draft.voicemailEnabled ? "pbx.device.noAnswer.default.voicemail" : "pbx.device.noAnswer.default.ring"))
                } header: {
                    Text(L10n.string("pbx.device.noAnswer.title"))
                } footer: {
                    Text(L10n.string("pbx.device.noAnswer.footer"))
                }

                Section {
                    TargetRow(title: L10n.string("pbx.device.busy.then"), target: $draft.busyTarget, options: options, noneLabel: L10n.string("pbx.device.busy.default"))
                } header: {
                    Text(L10n.string("pbx.device.busy.title"))
                }

                Section {
                    TargetRow(title: L10n.string("pbx.device.offline.then"), target: $draft.notRegisteredTarget, options: options, noneLabel: L10n.string("pbx.device.offline.default"))
                } header: {
                    Text(L10n.string("pbx.device.offline.title"))
                }

                followMeSection
            }
            .disabled(model.isReadOnly)
        }
        .navigationTitle(original.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                PbxSaveButton(isSaving: model.isSaving, isEnabled: hasChanges && !model.isReadOnly, action: save)
            }
        }
        .accessibilityIdentifier("pbx-device-edit")
    }

    private var followMeSection: some View {
        Section {
            ForEach(draft.followMe.indices, id: \.self) { index in
                NavigationLink {
                    FollowMeStepView(step: $draft.followMe[index], options: options)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(PbxVocabulary.describe(draft.followMe[index].target, options: options))
                        Text(String(format: L10n.string("pbx.followMe.timing"), draft.followMe[index].delaySeconds, draft.followMe[index].timeoutSeconds))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
            .onDelete { draft.followMe.remove(atOffsets: $0) }
            .onMove { draft.followMe.move(fromOffsets: $0, toOffset: $1) }

            if draft.followMe.count < DeviceDraft.followMeLimit {
                Button {
                    draft.followMe.append(FollowMeStep(target: .external(""), delaySeconds: draft.followMe.isEmpty ? 0 : 10))
                } label: {
                    Label(L10n.string("pbx.followMe.add"), systemImage: "plus.circle")
                }
            }
        } header: {
            Text(L10n.string("pbx.followMe.title"))
        } footer: {
            Text(L10n.string("pbx.followMe.footer"))
        }
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

    private var target: Binding<PbxTarget?> {
        Binding(get: { step.target }, set: { if let value = $0 { step.target = value } })
    }

    var body: some View {
        Form {
            Section {
                TargetRow(title: L10n.string("pbx.followMe.who"), target: target, options: options, allowsNone: false)
            }

            Section {
                Stepper(value: $step.delaySeconds, in: 0 ... 120, step: 5) {
                    Text(String(format: L10n.string("pbx.followMe.delay"), step.delaySeconds))
                }
                Stepper(value: $step.timeoutSeconds, in: 5 ... 120, step: 5) {
                    Text(String(format: L10n.string("pbx.followMe.timeout"), step.timeoutSeconds))
                }
            } footer: {
                Text(L10n.string("pbx.followMe.step.footer"))
            }
        }
        .navigationTitle(L10n.string("pbx.followMe.step"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
