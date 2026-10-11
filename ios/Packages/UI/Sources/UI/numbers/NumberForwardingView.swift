// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// "Doorschakeling": the phones that ring (Standaard), or a menu with keys (Keuzemenu).
struct NumberForwardingView: View {
    @ObservedObject var model: PbxSectionModel
    let numberId: String

    @State private var draft: NumberForwardingDraft
    @State private var baseline: NumberForwardingDraft
    @State private var message: ChainEditorMessage?
    @Environment(\.dismiss) private var dismiss

    init(model: PbxSectionModel, chain: NumberChain) {
        self.model = model
        numberId = chain.id
        _draft = State(initialValue: NumberForwardingDraft(chain))
        _baseline = State(initialValue: NumberForwardingDraft(chain))
    }

    private var chain: NumberChain? { model.chains[numberId] }
    private var options: ChainOptions { chain?.options ?? ChainOptions() }

    private var shared: [String] {
        switch chain?.forwarding {
        case let .standard(value): return value.sharedWith
        case let .menu(value): return value.sharedWith
        default: return []
        }
    }

    var body: some View {
        ChainEditorScaffold(model: model, title: L10n.string("numbers.forwarding.title"), isDirty: draft != baseline, canSave: draft.canSave, message: message, onSave: save) {
            VStack(alignment: .leading, spacing: Theme.Spacing.l) {
                Text(L10n.string("numbers.forwarding.intro"))
                    .font(.subheadline)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                SegmentedBar(options: [
                    .init(value: NumberForwardingDraft.Kind.standard, title: L10n.string("numbers.forwarding.standard")),
                    .init(value: NumberForwardingDraft.Kind.menu, title: L10n.string("numbers.forwarding.menu")),
                ], selection: $draft.kind)
                .accessibilityIdentifier("forwarding-kind")

                if draft.kind != baseline.kind {
                    Text(L10n.string(draft.kind == .menu ? "numbers.forwarding.switch.toMenu" : "numbers.forwarding.switch.toStandard"))
                        .font(.footnote)
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                SharedNote(names: draft.kind == baseline.kind ? shared : [])

                switch draft.kind {
                case .standard: standard
                case .menu: NumberMenuView(draft: $draft, options: options)
                }
            }
        }
    }

    // MARK: Standard

    private var standard: some View {
        VStack(alignment: .leading, spacing: 0) {
            SettingsGroup(title: L10n.string("numbers.forwarding.members"), footer: draft.members.isEmpty ? L10n.string("numbers.forwarding.members.none") : nil) {
                ForEach(options.devices) { device in
                    ToggleRow(
                        title: device.name,
                        explanation: device.extensionNumber.map { String(format: L10n.string("account.extension"), $0) },
                        isOn: Binding(get: { draft.isMember(device.id) }, set: { draft.setMember(device.id, on: $0) })
                    )
                    .accessibilityIdentifier("member-\(device.id)")
                }

                ForEach(draft.members(notIn: Set(options.devices.map(\.id))), id: \.deviceId) { member in
                    SettingsRow(
                        symbol: "phone.fill",
                        title: L10n.string("numbers.forwarding.members.other"),
                        subtitle: String(format: L10n.string("numbers.forwarding.members.other.timing"), member.delaySeconds, member.timeoutSeconds),
                        showsChevron: false
                    )
                    .accessibilityIdentifier("member-other-\(member.deviceId)")
                }
            }

            SettingsGroup(title: L10n.string("numbers.forwarding.settings")) {
                NavigationLink {
                    NumberLogicPage(draft: $draft)
                } label: {
                    SettingsRow(symbol: "arrow.triangle.branch", title: L10n.string("numbers.forwarding.logic"), value: logicValue)
                }
                .buttonStyle(RowButtonStyle())
                .accessibilityIdentifier("forwarding-logic")

                NumberFallbackRow(title: L10n.string("numbers.forwarding.unanswered"), symbol: "phone.down", fallback: $draft.unanswered, options: options)
            }
        }
    }

    private var logicValue: String {
        let strategy = PbxVocabulary.strategy(draft.strategy)

        guard let seconds = draft.ringSeconds else {
            return strategy
        }

        return "\(strategy) · \(String(format: L10n.string("numbers.forwarding.seconds"), seconds))"
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

/// "Logica": all at once, one after the other, or take turns, and how long the phones ring.
private struct NumberLogicPage: View {
    @Binding var draft: NumberForwardingDraft

    var body: some View {
        ChainSubPage(title: L10n.string("numbers.forwarding.logic")) {
            VStack(alignment: .leading, spacing: 0) {
                SettingsGroup(title: L10n.string("numbers.forwarding.strategy")) {
                    ForEach([RingStrategy.all, .sequence, .round], id: \.self) { strategy in
                        ChoiceRow(title: PbxVocabulary.strategy(strategy), subtitle: PbxVocabulary.strategyHint(strategy), isSelected: draft.strategy == strategy) {
                            draft.strategy = strategy
                        }
                    }
                }

                SettingsGroup(title: L10n.string("numbers.forwarding.ringTime"), footer: draft.ringSeconds == nil && !draft.members.isEmpty ? L10n.string("numbers.forwarding.ringTime.mixed") : nil) {
                    Stepper(value: Binding(get: { draft.ringSeconds ?? 25 }, set: { draft.setRingSeconds($0) }), in: NumberForwardingDraft.ringSecondsRange, step: 5) {
                        Text(String(format: L10n.string("numbers.forwarding.seconds"), draft.ringSeconds ?? 25))
                            .foregroundStyle(Theme.textPrimary)
                    }
                    .disabled(draft.members.isEmpty)
                    .settingsRowChrome()
                    .accessibilityIdentifier("forwarding-ringtime")
                }
            }
        }
    }
}
