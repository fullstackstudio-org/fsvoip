// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// "Belgroepen": the groups of toestellen that ring together or one after the other.
struct RingGroupsView: View {
    @ObservedObject var model: PbxSectionModel
    @State private var creating = false

    @Environment(\.dismiss) private var dismiss
    @Environment(\.pbxClose) private var close

    var body: some View {
        SheetShell(title: L10n.string("pbx.ringGroups.title"), back: { dismiss() }, onClose: { (close ?? { dismiss() })() }) {
            VStack(alignment: .leading, spacing: 0) {
                SectionNoticeCards(model: model)

                if let response = model.ringGroups {
                    if response.ringGroups.isEmpty {
                        EmptyState(symbol: "person.3", title: L10n.string("pbx.ringGroups.empty.title"), message: L10n.string("pbx.ringGroups.empty.message"))
                            .frame(minHeight: 220)
                    } else {
                        SettingsGroup {
                            ForEach(response.ringGroups) { group in
                                NavigationLink {
                                    RingGroupEditView(model: model, group: group)
                                } label: {
                                    SettingsRow(
                                        symbol: "person.3.fill",
                                        title: [group.name, group.extensionNumber].compactMap { $0 }.joined(separator: " · "),
                                        subtitle: "\(PbxVocabulary.strategy(group.strategy)) · \(String(format: L10n.string("pbx.count.members"), group.members.count))"
                                    )
                                }
                                .buttonStyle(RowButtonStyle())
                                .accessibilityIdentifier("pbx-ringgroup-row")
                            }
                        }
                    }

                    Button {
                        creating = true
                    } label: {
                        SettingsRow(symbol: "plus", title: L10n.string("pbx.ringGroups.add"), showsChevron: false)
                    }
                    .buttonStyle(RowButtonStyle())
                    .background(Theme.raised, in: Theme.card())
                    .disabled(model.isReadOnly)
                    .opacity(model.isReadOnly ? 0.5 : 1)
                    .accessibilityIdentifier("pbx-ringgroup-add")
                } else if model.isLoading {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(Theme.Spacing.xl)
                } else if model.loadFailure != nil || model.isOutdated {
                    EmptyState(symbol: "wifi.exclamationmark", title: L10n.string("pbx.ringGroups.loadFailed"), actionTitle: L10n.string("action.retry")) {
                        Task { await model.refresh(.ringGroups) }
                    }
                }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .refreshable { await model.refresh(.ringGroups) }
        .task { await model.loadIfNeeded(.ringGroups) }
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
        ChainEditorScaffold(model: model, title: original?.name ?? L10n.string("pbx.ringGroup.new"), isDirty: hasChanges, canSave: original != nil || draft.isFilledIn, message: failure.map(ChainEditorMessage.failure), onSave: save) {
            VStack(alignment: .leading, spacing: 0) {
                SettingsGroup(title: L10n.string("pbx.ringGroup.name")) {
                    TextField(L10n.string("pbx.ringGroup.name"), text: $draft.name)
                        .textInputAutocapitalization(.sentences)
                        .submitLabel(.done)
                        .foregroundStyle(Theme.textPrimary)
                        .settingsRowChrome()
                        .accessibilityLabel(L10n.string("pbx.ringGroup.name"))
                        .accessibilityIdentifier("pbx-ringgroup-name")
                }

                SettingsGroup(title: L10n.string("pbx.ringGroup.strategy"), footer: PbxVocabulary.strategyHint(draft.strategy)) {
                    ForEach([RingStrategy.all, .sequence, .round], id: \.self) { strategy in
                        ChoiceRow(title: PbxVocabulary.strategy(strategy), isSelected: draft.strategy == strategy) {
                            draft.strategy = strategy
                        }
                    }
                }

                SettingsGroup(title: L10n.string("pbx.ringGroup.members"), footer: draft.members.isEmpty ? L10n.string("pbx.ringGroup.members.empty") : nil) {
                    ForEach(devices) { device in
                        Button {
                            toggle(device)
                        } label: {
                            HStack(spacing: Theme.Spacing.m) {
                                InitialsAvatar(name: device.name, size: 36)
                                ChoiceRowLabel(title: device.name, subtitle: device.extensionNumber.map { String(format: L10n.string("account.extension"), $0) }, isSelected: isMember(device.id))
                            }
                            .settingsRowChrome()
                        }
                        .buttonStyle(RowButtonStyle())
                        .accessibilityAddTraits(isMember(device.id) ? .isSelected : [])
                        .accessibilityIdentifier("pbx-ringgroup-member-row")
                    }
                }

                if !draft.members.isEmpty {
                    SettingsGroup(title: L10n.string(draft.strategy == .all ? "pbx.ringGroup.times" : "pbx.ringGroup.order"), footer: draft.strategy == .all ? nil : L10n.string("pbx.ringGroup.order.footer")) {
                        ForEach(draft.members.indices, id: \.self) { index in
                            MemberTimingRow(
                                name: device(draft.members[index].extensionId)?.name ?? L10n.string("pbx.target.gone"),
                                member: $draft.members[index],
                                showsDelay: draft.strategy != .all,
                                showsOrder: draft.strategy != .all && draft.members.count > 1,
                                canMoveUp: index > 0,
                                canMoveDown: index < draft.members.count - 1,
                                onMove: { move(index, by: $0) }
                            )
                        }
                    }
                }

                SettingsGroup(title: L10n.string("pbx.ringGroup.nobody"), footer: L10n.string("pbx.ringGroup.nobody.footer")) {
                    TargetChoiceRow(title: L10n.string("pbx.ringGroup.nobody.then"), target: $draft.timeoutTarget, options: options)
                }
            }
        }
        .task { await model.loadIfNeeded(.ringGroups) }
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

    private func move(_ index: Int, by offset: Int) {
        let target = index + offset

        guard draft.members.indices.contains(index), draft.members.indices.contains(target) else { return }

        draft.members.swapAt(index, target)
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
    let showsOrder: Bool
    let canMoveUp: Bool
    let canMoveDown: Bool
    let onMove: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.xs) {
            HStack {
                Text(name)
                    .font(.body.weight(.medium))
                    .foregroundStyle(Theme.textPrimary)

                Spacer(minLength: Theme.Spacing.s)

                if showsOrder {
                    orderButton("chevron.up", label: "pbx.followMe.moveUp", enabled: canMoveUp, offset: -1)
                    orderButton("chevron.down", label: "pbx.followMe.moveDown", enabled: canMoveDown, offset: 1)
                }
            }

            if showsDelay {
                Stepper(value: $member.delaySeconds, in: 0 ... 120, step: 5) {
                    Text(String(format: L10n.string("pbx.ringGroup.delay"), member.delaySeconds))
                        .font(.subheadline)
                        .foregroundStyle(Theme.textSecondary)
                }
            }

            Stepper(value: $member.timeoutSeconds, in: 5 ... 120, step: 5) {
                Text(String(format: L10n.string("pbx.ringGroup.rings"), member.timeoutSeconds))
                    .font(.subheadline)
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .settingsRowChrome()
    }

    private func orderButton(_ symbol: String, label: String, enabled: Bool, offset: Int) -> some View {
        Button {
            onMove(offset)
        } label: {
            Image(systemName: symbol)
                .font(.body.weight(.semibold))
                .foregroundStyle(enabled ? Theme.textPrimary : Theme.textTertiary)
                .frame(width: Theme.minimumTarget, height: Theme.minimumTarget)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(L10n.string(label))
    }
}
