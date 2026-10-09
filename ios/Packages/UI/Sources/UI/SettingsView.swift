// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SipEngine
import SwiftUI

struct SettingsView: View {
    @ObservedObject var model: FSVoipAppModel
    /// Demo mode only (`-FSVoipDemoScreen pbx`): open the first account, and from there the "Centrale" section, without tapping.
    @State private var opensDemoAccount = Self.demoScreen == "pbx"

    private static var demoScreen: String? {
        #if DEBUG
        UserDefaults.standard.string(forKey: "FSVoipDemoScreen")
        #else
        nil
        #endif
    }

    var body: some View {
        List {
            Section {
                ForEach(model.accounts) { account in
                    NavigationLink {
                        AccountDetailView(model: model, accountId: account.id)
                    } label: {
                        AccountRow(account: account, state: model.registration(for: account.id))
                    }
                    .accessibilityIdentifier("account-row")
                }

                Button {
                    model.isScannerPresented = true
                } label: {
                    Label(L10n.string("settings.addLine"), systemImage: "plus")
                }
                .accessibilityIdentifier("add-line-button")
            } header: {
                L10n.text("settings.lines")
            } footer: {
                L10n.text("settings.lines.footer")
            }

            if model.accounts.count > 1 {
                Section {
                    Picker(L10n.string("settings.defaultLine"), selection: Binding(
                        get: { model.defaultOutgoingAccountId ?? "" },
                        set: { model.setDefaultOutgoing($0) }
                    )) {
                        ForEach(model.accounts) { account in
                            Text(account.displayLabel).tag(account.id)
                        }
                    }
                } header: {
                    L10n.text("settings.calling")
                } footer: {
                    L10n.text("settings.defaultLine.footer")
                }
            }

            Section {
                LabeledContent(L10n.string("settings.version"), value: Self.version)
                NavigationLink {
                    LicensesView()
                } label: {
                    L10n.text("settings.licenses")
                }
            } header: {
                L10n.text("settings.about")
            } footer: {
                L10n.text("settings.about.footer")
            }
        }
        .navigationTitle(L10n.string("tab.settings"))
        .navigationDestination(isPresented: $opensDemoAccount) {
            if let first = model.accounts.first {
                AccountDetailView(model: model, accountId: first.id)
            }
        }
    }

    static var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "–"
        let build = info?["CFBundleVersion"] as? String ?? "–"

        return "\(short) (\(build))"
    }
}

struct AccountRow: View {
    let account: StoredAccount
    let state: RegistrationState

    var body: some View {
        HStack(spacing: 12) {
            StatusLight(state: state, size: 10)

            VStack(alignment: .leading, spacing: 3) {
                Text(account.displayLabel)
                    .font(.body.weight(.medium))
                    .lineLimit(1)
                Text([state.label, account.extensionNumber.map { String(format: L10n.string("account.extension"), $0) }].compactMap { $0 }.joined(separator: " · "))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .padding(.vertical, 2)
    }
}
