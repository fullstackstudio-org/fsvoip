// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

struct RingGroupsView: View {
    @ObservedObject var model: PbxSectionModel
    @State private var creating = false

    var body: some View {
        List {
            PbxNoticesSection(model: model)

            if let response = model.ringGroups {
                if response.ringGroups.isEmpty {
                    Section {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(L10n.string("pbx.ringGroups.empty.title"))
                                .font(.headline)
                            Text(L10n.string("pbx.ringGroups.empty.message"))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 6)
                        .accessibilityElement(children: .combine)
                    }
                } else {
                    Section {
                        ForEach(response.ringGroups) { group in
                            NavigationLink {
                                RingGroupEditView(model: model, group: group)
                            } label: {
                                VStack(alignment: .leading, spacing: 3) {
                                    HStack {
                                        Text(group.name)
                                            .font(.body.weight(.medium))
                                        if let number = group.extensionNumber {
                                            Text(number)
                                                .font(.subheadline)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                    Text("\(PbxVocabulary.strategy(group.strategy)) · \(String(format: L10n.string("pbx.count.members"), group.members.count))")
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)
                                    PbxSyncBadge(sync: group.sync)
                                }
                                .accessibilityElement(children: .combine)
                            }
                            .accessibilityIdentifier("pbx-ringgroup-row")
                        }
                    }
                }
            } else if model.isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle(L10n.string("pbx.ringGroups.title"))
        .refreshable { await model.refresh(.ringGroups) }
        .task { await model.loadIfNeeded(.ringGroups) }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    creating = true
                } label: {
                    Label(L10n.string("pbx.ringGroups.add"), systemImage: "plus")
                }
                .disabled(model.isReadOnly || model.ringGroups == nil)
                .accessibilityIdentifier("pbx-ringgroup-add")
            }
        }
        .navigationDestination(isPresented: $creating) {
            RingGroupEditView(model: model, group: nil)
        }
        .accessibilityIdentifier("pbx-ringgroups")
    }
}

/// Make a new belgroep or change one: name, how the phones ring, who is in it, and what happens when nobody picks up.
struct RingGroupEditView: View {
    @ObservedObject var model: PbxSectionModel
    @State private var original: PbxRingGroup?
    @State private var draft: RingGroupDraft
    @State private var failure: PbxFailure?
    @Environment(\.dismiss) private var dismiss

    init(model: PbxSectionModel, group: PbxRingGroup?) {
        self.model = model
        _original = State(initialValue: group)
        _draft = State(initialValue: group.map(RingGroupDraft.init) ?? RingGroupDraft())
    }

    private var devices: [PbxDeviceRef] {
        model.ringGroups?.devices ?? []
    }

    private var options: [PbxTargetOption] {
        TargetChoice.excluding(original?.id, from: model.ringGroups?.targets ?? [])
    }

    private var hasChanges: Bool {
        if let original {
            return draft.patch(from: original) != nil
        }

        return draft.isFilledIn
    }

    private func device(_ id: String) -> PbxDeviceRef? {
        devices.first { $0.id == id }
    }

    var body: some View {
        Form {
            PbxNoticesSection(model: model)

            PbxFormError(failure: failure)

            Group {
                Section {
                    TextField(L10n.string("pbx.ringGroup.name"), text: $draft.name)
                        .textInputAutocapitalization(.sentences)
                        .accessibilityIdentifier("pbx-ringgroup-name")
                } header: {
                    Text(L10n.string("pbx.ringGroup.name"))
                }

                Section {
                    Picker(L10n.string("pbx.ringGroup.strategy"), selection: $draft.strategy) {
                        ForEach([RingStrategy.all, .sequence, .round], id: \.self) { strategy in
                            Text(PbxVocabulary.strategy(strategy)).tag(strategy)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                } header: {
                    Text(L10n.string("pbx.ringGroup.strategy"))
                } footer: {
                    Text(PbxVocabulary.strategyHint(draft.strategy))
                }

                Section {
                    ForEach(devices) { device in
                        Button {
                            toggle(device)
                        } label: {
                            HStack {
                                Text([device.name, device.extensionNumber].compactMap { $0 }.joined(separator: " · "))
                                    .foregroundStyle(.primary)
                                Spacer()
                                if isMember(device.id) {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundStyle(Brand.ink)
                                        .accessibilityHidden(true)
                                } else {
                                    Image(systemName: "circle")
                                        .foregroundStyle(.tertiary)
                                        .accessibilityHidden(true)
                                }
                            }
                        }
                        .accessibilityAddTraits(isMember(device.id) ? .isSelected : [])
                    }
                } header: {
                    Text(L10n.string("pbx.ringGroup.members"))
                } footer: {
                    if draft.members.isEmpty {
                        Text(L10n.string("pbx.ringGroup.members.empty"))
                    }
                }

                if !draft.members.isEmpty {
                    Section {
                        ForEach(draft.members.indices, id: \.self) { index in
                            MemberTimingRow(
                                name: device(draft.members[index].extensionId)?.name ?? L10n.string("pbx.target.gone"),
                                member: $draft.members[index],
                                showsDelay: draft.strategy != .all
                            )
                        }
                        .onMove { draft.members.move(fromOffsets: $0, toOffset: $1) }
                    } header: {
                        Text(L10n.string(draft.strategy == .all ? "pbx.ringGroup.times" : "pbx.ringGroup.order"))
                    } footer: {
                        if draft.strategy != .all {
                            Text(L10n.string("pbx.ringGroup.order.footer"))
                        }
                    }
                }

                Section {
                    TargetRow(title: L10n.string("pbx.ringGroup.nobody.then"), target: $draft.timeoutTarget, options: options)
                } header: {
                    Text(L10n.string("pbx.ringGroup.nobody"))
                } footer: {
                    Text(L10n.string("pbx.ringGroup.nobody.footer"))
                }
            }
            .disabled(model.isReadOnly)
        }
        .navigationTitle(original?.name ?? L10n.string("pbx.ringGroup.new"))
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.loadIfNeeded(.ringGroups) }
        .toolbar {
            if draft.strategy != .all, draft.members.count > 1 {
                ToolbarItem(placement: .navigationBarTrailing) { EditButton() }
            }

            ToolbarItem(placement: .confirmationAction) {
                PbxSaveButton(isSaving: model.isSaving, isEnabled: hasChanges && !model.isReadOnly, action: save)
            }
        }
        .accessibilityIdentifier("pbx-ringgroup-edit")
    }

    private func isMember(_ id: String) -> Bool {
        draft.members.contains { $0.extensionId == id }
    }

    private func toggle(_ device: PbxDeviceRef) {
        if let index = draft.members.firstIndex(where: { $0.extensionId == device.id }) {
            draft.members.remove(at: index)
        } else if draft.members.count < RingGroupDraft.memberLimit {
            draft.members.append(RingGroupMember(extensionId: device.id))
        }
    }

    private func save() {
        Task {
            let outcome: PbxSaveOutcome

            if let original {
                outcome = await model.saveRingGroup(draft, original: original)
            } else {
                outcome = await model.createRingGroup(draft)
            }

            if outcome.closesForm {
                dismiss()
            } else if case let .failed(reason) = outcome {
                failure = reason
            }
        }
    }
}

private struct MemberTimingRow: View {
    let name: String
    @Binding var member: RingGroupMember
    let showsDelay: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(name)
                .font(.body.weight(.medium))

            if showsDelay {
                Stepper(value: $member.delaySeconds, in: 0 ... 120, step: 5) {
                    Text(String(format: L10n.string("pbx.ringGroup.delay"), member.delaySeconds))
                        .font(.subheadline)
                }
            }

            Stepper(value: $member.timeoutSeconds, in: 5 ... 120, step: 5) {
                Text(String(format: L10n.string("pbx.ringGroup.rings"), member.timeoutSeconds))
                    .font(.subheadline)
            }
        }
        .padding(.vertical, 2)
    }
}
