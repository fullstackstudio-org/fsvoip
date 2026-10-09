// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// "Nummers": every number of the centrale with a one-line summary of its chain.
struct NumbersListView: View {
    @ObservedObject var model: PbxSectionModel

    @Environment(\.dismiss) private var dismiss
    @Environment(\.pbxClose) private var close
    #if DEBUG
    /// Demo mode only (`-FSVoipDemoNumber open|<step>`): open the first number without tapping, for screenshots.
    @State private var demoNumberId: String?
    @State private var demoOpens = false
    #endif

    var body: some View {
        SheetShell(title: L10n.string("numbers.title"), back: { dismiss() }, onClose: { (close ?? { dismiss() })() }) {
            VStack(alignment: .leading, spacing: 0) {
                SectionNoticeCards(model: model)

                if let page = model.numbers {
                    if page.numbers.isEmpty {
                        EmptyState(symbol: "number", title: L10n.string("numbers.empty.title"), message: L10n.string("numbers.empty.message"))
                    } else {
                        SettingsGroup(footer: L10n.string("numbers.list.footer")) {
                            ForEach(page.numbers) { entry in
                                NavigationLink {
                                    NumberView(model: model, numberId: entry.id, placeholderTitle: title(entry))
                                } label: {
                                    SettingsRow(symbol: entry.mode == .advanced ? "slider.horizontal.3" : "number", title: title(entry), subtitle: NumberSummary.listLine(entry), value: entry.name?.isEmpty == false ? PbxVocabulary.formatNumber(entry.number) : nil)
                                }
                                .buttonStyle(RowButtonStyle())
                                .accessibilityIdentifier("number-entry")
                            }
                        }
                    }
                } else if model.isLoading {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(Theme.Spacing.xl)
                } else if model.loadFailure != nil || model.isOutdated {
                    EmptyState(symbol: "wifi.exclamationmark", title: L10n.string("numbers.loadFailed"), actionTitle: L10n.string("action.retry")) {
                        Task { await model.refresh(.numbers) }
                    }
                }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .refreshable { await model.refresh(.numbers) }
        .task { await model.loadIfNeeded(.numbers) }
        .onDisappear { model.stopPolling() }
        .accessibilityIdentifier("numbers-list")
        #if DEBUG
        .navigationDestination(isPresented: $demoOpens) {
            if let id = demoNumberId {
                NumberView(model: model, numberId: id)
            }
        }
        .onAppear { openDemoNumber(model.numbers?.numbers.first?.id) }
        .onChange(of: model.numbers?.numbers.first?.id) { openDemoNumber($0) }
        #endif
    }

    #if DEBUG
    private func openDemoNumber(_ id: String?) {
        guard let id, UserDefaults.standard.string(forKey: "FSVoipDemoNumber") != nil, !demoOpens else { return }

        demoNumberId = id
        demoOpens = true
    }
    #endif

    private func title(_ entry: PbxNumberEntry) -> String {
        let name = entry.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        return name.isEmpty ? PbxVocabulary.formatNumber(entry.number) : name
    }
}
