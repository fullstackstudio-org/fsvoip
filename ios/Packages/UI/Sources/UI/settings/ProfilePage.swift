// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Pairing
import SwiftUI

/// "Profiel": who this phone is on the centrale. The name in the app is local; the e-mail address for voicemail is the own
/// extension's (`PATCH /me/extension`). The device name and number are the portal's to change.
struct ProfilePage: View {
    @ObservedObject var model: FSVoipAppModel
    @ObservedObject var hub: SelfExtensionHub
    let account: StoredAccount
    let back: () -> Void
    let close: () -> Void
    let onUnpair: () -> Void

    @State private var alias = ""
    @State private var form = SelfExtensionForm()
    @State private var isSaving = false

    private var offersServerFields: Bool {
        model.capabilities(for: account.id)?.selfExtension == true && form.isLoaded
    }

    private var isAliasDirty: Bool {
        AccountService.cleanAlias(alias) != AccountService.cleanAlias(account.labelOverride)
    }

    private var isDirty: Bool {
        isAliasDirty || (offersServerFields && form.isDirty)
    }

    var body: some View {
        PageScaffold(
            title: L10n.string("settings.profile"),
            back: back,
            close: close,
            footer: SheetFooter(canSave: isDirty, onCancel: back, onSave: save),
            isSaving: isSaving,
            isDirty: isDirty
        ) {
            VStack(alignment: .leading, spacing: Theme.Spacing.l) {
                SelfExtensionNotice(message: form.message)

                HStack(spacing: Theme.Spacing.m) {
                    InitialsAvatar(name: account.extensionName, size: 56)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(account.extensionName).font(.title3.weight(.semibold)).foregroundStyle(Theme.textPrimary)
                        Text([account.pbxName, account.extensionNumber.map { String(format: L10n.string("account.extension"), $0) }].compactMap { $0 }.joined(separator: " · "))
                            .font(.footnote)
                            .foregroundStyle(Theme.textSecondary)
                    }
                }
                .accessibilityElement(children: .combine)

                SettingsGroup(title: L10n.string("account.alias"), footer: String(format: L10n.string("account.alias.footer"), "\(account.pbxName) · \(account.extensionName)")) {
                    TextField(account.label, text: $alias)
                        .textInputAutocapitalization(.sentences)
                        .submitLabel(.done)
                        .foregroundStyle(Theme.textPrimary)
                        .settingsRowChrome()
                        .accessibilityIdentifier("alias-field")
                }

                SettingsGroup(title: L10n.string("profile.device"), footer: L10n.string("profile.device.footer")) {
                    SettingsRow(title: L10n.string("profile.device.name"), value: account.extensionName, showsChevron: false)
                    if let number = account.extensionNumber {
                        SettingsRow(title: L10n.string("profile.device.number"), value: number, showsChevron: false)
                    }
                }

                if offersServerFields {
                    SettingsGroup(title: L10n.string("profile.email"), footer: L10n.string("profile.email.footer")) {
                        TextField(L10n.string("profile.email.placeholder"), text: $form.draft.email)
                            .keyboardType(.emailAddress)
                            .textContentType(.emailAddress)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .submitLabel(.done)
                            .foregroundStyle(Theme.textPrimary)
                            .settingsRowChrome()
                            .accessibilityLabel(L10n.string("profile.email"))
                            .accessibilityIdentifier("profile-email-field")
                    }
                }

                SettingsGroup(title: L10n.string("settings.link")) {
                    NavigationLink(value: SettingsPage.link) {
                        let state = model.registration(for: account.id)

                        HStack(spacing: Theme.Spacing.m) {
                            StatusLight(state: state, size: 10)
                            Text(state.label).font(.body).foregroundStyle(Theme.textPrimary)
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Theme.textTertiary).accessibilityHidden(true)
                        }
                        .settingsRowChrome()
                        .accessibilityElement(children: .combine)
                    }
                    .buttonStyle(RowButtonStyle())
                    .accessibilityIdentifier("profile-link-status")

                    Button {
                        model.isScannerPresented = true
                        close()
                    } label: {
                        SettingsRow(symbol: "qrcode.viewfinder", title: L10n.string("profile.relink"), showsChevron: false)
                    }
                    .buttonStyle(RowButtonStyle())
                    .accessibilityIdentifier("profile-relink")

                    Button(action: onUnpair) {
                        SettingsRow(symbol: "link.badge.plus", title: L10n.string("account.unpair"), showsChevron: false, isDestructive: true)
                    }
                    .buttonStyle(RowButtonStyle())
                    .accessibilityIdentifier("profile-unpair")
                }
            }
        }
        .onAppear { alias = account.labelOverride ?? "" }
        .task(id: "\(account.id)/\(model.capabilities(for: account.id)?.selfExtension == true)") {
            guard model.capabilities(for: account.id)?.selfExtension == true else { return }

            await hub.load(account)
            form.adopt(hub.state(for: account.id))
        }
    }

    private func save() {
        guard isDirty, !isSaving else { return }

        isSaving = true

        Task {
            defer { isSaving = false }

            if offersServerFields, form.isDirty, let baseline = form.baseline {
                let outcome = await hub.save(form.draft, original: baseline, account: account)

                guard form.finish(outcome) else { return }

                await model.availability?.load(account)
            }

            if isAliasDirty {
                guard await model.rename(accountId: account.id, alias: alias) else { return }
            }

            back()
        }
    }
}
