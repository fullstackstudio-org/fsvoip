// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// A number that is set up in more detail than the chain can show (a queue, a sub menu, ...): what it does in words, the flow
/// when the overview has it, and the way to the portal. Name and recording stay editable in the app (D1).
struct NumberAdvancedView: View {
    @ObservedObject var model: PbxSectionModel
    let chain: NumberChain

    static let portalURL = URL(string: "https://fullstackstudio.nl/portal/voip")!

    /// The flow behind this number, when the overview of the centrale has been read.
    private var flow: FlowNode? {
        model.overview?.numbers.first { $0.id == chain.id }?.flow
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            NoticeCard(symbol: "slider.horizontal.3", tint: Theme.textSecondary, title: L10n.string("numbers.advanced.title"), message: L10n.string("numbers.advanced.message"))
                .accessibilityIdentifier("number-advanced")

            SettingsGroup(title: L10n.string("numbers.advanced.summary")) {
                ForEach(Array((chain.advancedSummary ?? []).enumerated()), id: \.offset) { _, line in
                    Text(line)
                        .foregroundStyle(Theme.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                        .settingsRowChrome()
                }

                if (chain.advancedSummary ?? []).isEmpty {
                    Text(L10n.string("numbers.advanced.noSummary"))
                        .foregroundStyle(Theme.textSecondary)
                        .settingsRowChrome()
                }
            }

            if let flow {
                SettingsGroup(title: L10n.string("numbers.advanced.flow")) {
                    FlowView(node: flow)
                        .padding(Theme.Spacing.l)
                }
            }

            SettingsGroup {
                Link(destination: Self.portalURL) {
                    SettingsRow(symbol: "safari", title: L10n.string("numbers.advanced.portal"), subtitle: L10n.string("numbers.advanced.portal.hint"))
                }
                .buttonStyle(RowButtonStyle())
                .accessibilityIdentifier("number-portal-link")
            }
        }
    }
}
