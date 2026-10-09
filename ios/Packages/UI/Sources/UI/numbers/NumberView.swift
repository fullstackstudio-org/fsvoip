// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// One number as a chain: Naam, Openingstijden, Welkomstbericht, Doorschakeling and Gespreksopname, each opening its own sheet.
/// An `advanced` number shows what it does in words; only name and recording can change here.
struct NumberView: View {
    enum Step: String, Identifiable {
        case name
        case hours
        case welcome
        case forwarding
        case recording

        var id: String { rawValue }
    }

    @ObservedObject var model: PbxSectionModel
    let numberId: String
    /// What the list knew, for the title while the chain loads.
    var placeholderTitle: String?

    @State private var editing: Step?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.pbxClose) private var close

    private var chain: NumberChain? { model.chains[numberId] }

    var body: some View {
        SheetShell(title: chain?.displayName ?? placeholderTitle ?? "", back: { dismiss() }, onClose: { (close ?? { dismiss() })() }) {
            VStack(alignment: .leading, spacing: 0) {
                SectionNoticeCards(model: model)

                if let chain {
                    header(chain)

                    if chain.isEditable {
                        editable(chain)
                    } else {
                        SettingsGroup {
                            nameRow(chain)
                            recordingRow(chain)
                        }

                        NumberAdvancedView(model: model, chain: chain)
                    }
                } else if let failure = model.chainFailures[numberId] {
                    EmptyState(symbol: "exclamationmark.circle", title: failure.message, actionTitle: L10n.string("action.retry")) {
                        Task { await model.loadChain(numberId) }
                    }
                } else {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(Theme.Spacing.xl)
                }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .refreshable { await model.loadChain(numberId) }
        .task { await model.loadChainIfNeeded(numberId) }
        .sheet(item: $editing) { step in
            if let chain {
                NavigationStack {
                    editor(step, chain: chain)
                }
                .tint(Theme.accentText)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
            }
        }
        .accessibilityIdentifier("number-view")
        #if DEBUG
        .onAppear { openDemoStep() }
        .onChange(of: chain != nil) { _ in openDemoStep() }
        #endif
    }

    #if DEBUG
    /// Demo mode only: open one step sheet directly (`-FSVoipDemoNumber name|hours|welcome|forwarding|recording`).
    private func openDemoStep() {
        guard chain != nil, editing == nil, let raw = UserDefaults.standard.string(forKey: "FSVoipDemoNumber"), let step = Step(rawValue: raw) else { return }

        editing = step
    }
    #endif

    private func header(_ chain: NumberChain) -> some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.xs) {
            Text(PbxVocabulary.formatNumber(chain.number))
                .font(Theme.digits(28, weight: .regular))
                .foregroundStyle(Theme.textPrimary)
                .accessibilityLabel(chain.number.map { String($0) }.joined(separator: " "))

            PbxSyncBadge(sync: chain.sync)
        }
        .padding(.horizontal, Theme.Spacing.l)
        .padding(.bottom, Theme.Spacing.l)
    }

    @ViewBuilder
    private func editor(_ step: Step, chain: NumberChain) -> some View {
        switch step {
        case .name: NumberNameView(model: model, chain: chain)
        case .hours: NumberHoursView(model: model, chain: chain)
        case .welcome: NumberWelcomeView(model: model, chain: chain)
        case .forwarding: NumberForwardingView(model: model, chain: chain)
        case .recording: NumberRecordingView(model: model, chain: chain)
        }
    }

    private func editable(_ chain: NumberChain) -> some View {
        SettingsGroup {
            nameRow(chain)

            row(.hours, symbol: "calendar", title: L10n.string("numbers.hours.title"), value: NumberSummary.hours(chain), subtitle: NumberSummary.shared(chain.hours?.sharedWith))
            row(.welcome, symbol: "speaker.wave.2", title: L10n.string("numbers.welcome.title"), value: NumberSummary.welcome(chain), subtitle: NumberSummary.shared(chain.welcome?.sharedWith))
            row(.forwarding, symbol: "phone.arrow.right", title: L10n.string("numbers.forwarding.title"), value: NumberSummary.forwarding(chain), subtitle: NumberSummary.forwardingDetail(chain))

            recordingRow(chain)
        }
    }

    private func nameRow(_ chain: NumberChain) -> some View {
        row(.name, symbol: "tag", title: L10n.string("numbers.name.title"), value: chain.name?.isEmpty == false ? chain.name : L10n.string("numbers.name.none"), subtitle: nil)
    }

    private func recordingRow(_ chain: NumberChain) -> some View {
        row(.recording, symbol: "mic", title: L10n.string("numbers.recording.title"), value: NumberSummary.recording(chain), subtitle: nil)
    }

    private func row(_ step: Step, symbol: String, title: String, value: String?, subtitle: String?) -> some View {
        Button {
            editing = step
        } label: {
            SettingsRow(symbol: symbol, title: title, subtitle: subtitle, value: value)
        }
        .buttonStyle(RowButtonStyle())
        .accessibilityIdentifier("number-row-\(step.rawValue)")
    }
}

/// The values on the rows of a number, in words.
enum NumberSummary {
    static func hours(_ chain: NumberChain) -> String {
        guard let hours = chain.hours else {
            return L10n.string("numbers.off")
        }

        return PbxVocabulary.hoursSummary(hours.week)
    }

    static func welcome(_ chain: NumberChain) -> String {
        guard let welcome = chain.welcome else {
            return L10n.string("numbers.off")
        }

        return ChainWords.soundName(welcome.soundId, chain.options) ?? L10n.string("numbers.on")
    }

    static func forwarding(_ chain: NumberChain) -> String {
        switch chain.forwarding {
        case .standard: return L10n.string("numbers.forwarding.standard")
        case .menu: return L10n.string("numbers.forwarding.menu")
        case .unknown, .none: return L10n.string("numbers.portalOnly")
        }
    }

    static func forwardingDetail(_ chain: NumberChain) -> String? {
        switch chain.forwarding {
        case let .standard(standard):
            let line = "\(String(format: L10n.string("numbers.forwarding.phones"), standard.members.count)) · \(PbxVocabulary.strategy(standard.strategy))"

            return [line, shared(standard.sharedWith)].compactMap { $0 }.joined(separator: "\n")
        case let .menu(menu):
            let line = String(format: L10n.string("numbers.menu.keyCount"), menu.keys.count)

            return [line, shared(menu.sharedWith)].compactMap { $0 }.joined(separator: "\n")
        case .unknown, .none:
            return nil
        }
    }

    static func recording(_ chain: NumberChain) -> String {
        if chain.recording.enabled {
            return L10n.string("numbers.on")
        }

        return chain.recording.available ? L10n.string("numbers.off") : L10n.string("numbers.recording.notAvailable")
    }

    /// "Ook voor Servicenummer".
    static func shared(_ names: [String]?) -> String? {
        guard let names, !names.isEmpty else {
            return nil
        }

        return String(format: L10n.string("numbers.alsoFor"), names.joined(separator: ", "))
    }

    /// The one line of a number in the list: "ma–vr 09:00–17:00 · Welkom · Standaard".
    static func listLine(_ entry: PbxNumberEntry) -> String {
        var parts = [entry.summary.hours.isEmpty ? L10n.string("numbers.hours.alwaysOpen") : entry.summary.hours]

        if entry.summary.welcome {
            parts.append(L10n.string("numbers.welcome.short"))
        }

        switch entry.summary.forwarding {
        case .standard: parts.append(L10n.string("numbers.forwarding.standard"))
        case .menu: parts.append(L10n.string("numbers.forwarding.menu"))
        case .advanced, .unknown: parts.append(L10n.string("numbers.advanced.short"))
        }

        if entry.summary.recording {
            parts.append(L10n.string("numbers.recording.short"))
        }

        if entry.sync == .pending {
            parts.append(L10n.string("pbx.sync.pending"))
        } else if entry.sync == .error {
            parts.append(L10n.string("pbx.sync.error"))
        }

        return parts.joined(separator: " · ")
    }
}
