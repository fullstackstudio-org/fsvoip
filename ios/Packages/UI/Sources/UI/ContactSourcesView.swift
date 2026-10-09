// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import FSContacts
import SwiftUI
import UIKit

/// Which contacts the app uses: the phone's own, and per account the address book and its lists.
struct ContactSourcesView: View {
    @ObservedObject var model: FSVoipAppModel
    @ObservedObject private var hub: ContactsHub
    @Environment(\.dismiss) private var dismiss

    init(model: FSVoipAppModel) {
        self.model = model
        hub = model.contacts
    }

    var body: some View {
        NavigationStack {
            List {
                deviceSection

                ForEach(model.accounts) { account in
                    if let state = hub.accountStates[account.id] {
                        accountSection(account, state: state)
                    }
                }
            }
            .navigationTitle(L10n.string("contacts.sources.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.string("action.done")) { dismiss() }
                }
            }
        }
    }

    private var deviceSection: some View {
        Section {
            Toggle(isOn: Binding(
                get: { hub.usesDeviceContacts },
                set: { enabled in Task { await hub.setDeviceContactsEnabled(enabled) } }
            )) {
                L10n.text("contacts.sources.device")
            }
            .tint(Brand.ink)
            .disabled(hub.deviceAccess == .denied)
            .accessibilityIdentifier("contacts-device-toggle")

            if hub.deviceAccess == .denied {
                L10n.text("contacts.sources.device.denied")
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                if let url = URL(string: UIApplication.openSettingsURLString) {
                    Link(L10n.string("contacts.sources.device.openSettings"), destination: url)
                }
            }
        } footer: {
            L10n.text("contacts.sources.device.footer")
        }
    }

    private func accountSection(_ account: StoredAccount, state: ContactsAccountState) -> some View {
        Section {
            Toggle(isOn: Binding(
                get: { state.isEnabled },
                set: { enabled in Task { await hub.setAddressBookEnabled(enabled, accountId: account.id) } }
            )) {
                VStack(alignment: .leading, spacing: 2) {
                    L10n.text("contacts.sources.book")
                    Text(String(format: L10n.string("contacts.sources.book.footer"), ContactFailure.countText(state.contactCount), updatedText(state)))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .tint(Brand.ink)

            if state.isEnabled {
                ForEach(state.lists) { list in
                    Toggle(isOn: Binding(
                        get: { list.isEnabled },
                        set: { enabled in Task { await hub.setListEnabled(enabled, listId: list.id, accountId: account.id) } }
                    )) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(list.name)
                            Text(ContactFailure.countText(list.contactCount))
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .tint(Brand.ink)
                }
            }
        } header: {
            Text(account.displayLabel)
        } footer: {
            if state.isEnabled, !state.lists.isEmpty {
                L10n.text("contacts.sources.lists.footer")
            }
        }
    }

    private func updatedText(_ state: ContactsAccountState) -> String {
        guard let date = state.lastSyncedAt else {
            return L10n.string("contacts.sources.neverSynced")
        }

        return date.formatted(.relative(presentation: .named))
    }
}
