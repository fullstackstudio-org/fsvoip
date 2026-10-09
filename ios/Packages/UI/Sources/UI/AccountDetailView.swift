// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Pairing
import SipEngine
import SwiftUI

struct AccountDetailView: View {
    @ObservedObject var model: FSVoipAppModel
    let accountId: String
    @Environment(\.dismiss) private var dismiss

    @State private var alias = ""
    @State private var isSavingAlias = false
    @State private var confirmsUnpair = false
    @State private var isUnpairing = false
    @State private var offersForget = false

    var body: some View {
        Group {
            if let account = model.account(id: accountId) {
                content(account)
            } else {
                Color.clear.onAppear { dismiss() }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
    }

    private func content(_ account: StoredAccount) -> some View {
        let state = model.registration(for: account.id)

        return List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text(account.displayLabel)
                        .font(.title2.bold())
                    HStack(spacing: 8) {
                        StatusLight(state: state, size: 10)
                        Text(state.label)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .accessibilityElement(children: .combine)
                }
                .padding(.vertical, 6)

                if case .failed = state {
                    Button(L10n.string("account.reconnect")) {
                        model.phone.refreshRegistrations()
                    }
                }
            }

            if let pbx = model.pbx {
                PbxAccountSection(hub: pbx, account: account)
            }

            Section {
                TextField(account.label, text: $alias)
                    .textInputAutocapitalization(.sentences)
                    .submitLabel(.done)
                    .onSubmit { saveAlias(account) }
                    .accessibilityIdentifier("alias-field")

                if aliasChanged(account) {
                    Button {
                        saveAlias(account)
                    } label: {
                        HStack {
                            L10n.text("action.save")
                            if isSavingAlias {
                                Spacer()
                                ProgressView()
                            }
                        }
                    }
                    .disabled(isSavingAlias)
                }
            } header: {
                L10n.text("account.alias")
            } footer: {
                Text(String(format: L10n.string("account.alias.footer"), "\(account.pbxName) · \(account.extensionName)"))
            }

            Section {
                Toggle(isOn: Binding(
                    get: { _ = model.settingsRevision; return model.showsCalledAccount(account.id) },
                    set: { model.setShowsCalledAccount(account.id, $0) }
                )) {
                    L10n.text("account.showCalled")
                }
                .tint(Brand.ink)
                .accessibilityIdentifier("show-called-toggle")

            } header: {
                L10n.text("account.calls")
            } footer: {
                Text(String(format: L10n.string("account.showCalled.footer"), account.displayLabel))
            }

            if model.accounts.count > 1 {
                Section {
                    Button {
                        model.setDefaultOutgoing(account.id)
                    } label: {
                        HStack {
                            L10n.text("account.defaultLine")
                                .foregroundStyle(.primary)
                            Spacer()
                            if model.defaultOutgoingAccountId == account.id {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(.primary)
                            }
                        }
                    }
                } header: {
                    L10n.text("settings.calling")
                }
            }

            Section {
                LabeledContent(L10n.string("account.pbx"), value: account.pbxName)
                LabeledContent(L10n.string("account.device"), value: [account.extensionName, account.extensionNumber].compactMap { $0 }.joined(separator: " · "))
                LabeledContent(L10n.string("account.customer"), value: account.customerName)
                LabeledContent(L10n.string("account.connection"), value: connectionText(account))
                LabeledContent(L10n.string("account.domain"), value: account.sip.domain)
                LabeledContent(L10n.string("account.paired"), value: account.pairedAt.formatted(date: .abbreviated, time: .omitted))
            } header: {
                L10n.text("account.details")
            }

            Section {
                Button(role: .destructive) {
                    confirmsUnpair = true
                } label: {
                    HStack {
                        L10n.text("account.unpair")
                        if isUnpairing {
                            Spacer()
                            ProgressView()
                        }
                    }
                }
                .disabled(isUnpairing)
                .accessibilityIdentifier("unpair-button")
            } footer: {
                L10n.text("account.unpair.footer")
            }
        }
        .onAppear { alias = account.labelOverride ?? "" }
        .confirmationDialog(String(format: L10n.string("account.unpair.title"), account.displayLabel), isPresented: $confirmsUnpair, titleVisibility: .visible) {
            Button(L10n.string("account.unpair.confirm"), role: .destructive) { unpair(account) }
        } message: {
            L10n.text("account.unpair.message")
        }
        .alert(L10n.string("account.forget.title"), isPresented: $offersForget) {
            Button(L10n.string("account.forget.confirm"), role: .destructive) { model.forget(accountId: account.id) }
            Button(L10n.string("action.cancel"), role: .cancel) {}
        } message: {
            L10n.text("account.forget.message")
        }
    }

    private func aliasChanged(_ account: StoredAccount) -> Bool {
        AccountService.cleanAlias(alias) != AccountService.cleanAlias(account.labelOverride)
    }

    private func saveAlias(_ account: StoredAccount) {
        guard aliasChanged(account), !isSavingAlias else {
            return
        }

        isSavingAlias = true

        Task {
            if await model.rename(accountId: account.id, alias: alias) {
                alias = model.account(id: account.id)?.labelOverride ?? ""
            }

            isSavingAlias = false
        }
    }

    private func unpair(_ account: StoredAccount) {
        isUnpairing = true

        Task {
            let result = await model.unpair(accountId: account.id)
            isUnpairing = false

            if case .failed = result {
                offersForget = true
            }
        }
    }

    private func connectionText(_ account: StoredAccount) -> String {
        switch account.sip.transport {
        case .tls:
            return L10n.string("account.connection.tls")
        case .tcp, .udp:
            return L10n.string("account.connection.plain")
        }
    }
}
