// SPDX-License-Identifier: AGPL-3.0-or-later
import CallController
import Core
import Pairing
import SipEngine
import SwiftUI
import UIKit

/// The settings sheet, opened from the gear and the avatar on every tab. One navigation stack; the pages that are built on the
/// design system hide the system bar and draw a `SheetShell`, the older "Centrale" and "Opnames" screens keep their own bar.
struct SettingsSheet: View {
    @ObservedObject var model: FSVoipAppModel
    @ObservedObject var phone: PhoneController

    @State private var path: [SettingsPage]
    @State private var selectedAccountId: String?
    @State private var confirmsUnpair = false
    @State private var isUnpairing = false
    @State private var offersForget = false

    init(model: FSVoipAppModel) {
        self.model = model
        phone = model.phone
        _path = State(initialValue: SettingsOutline.pages(for: model.settingsStart))
        _selectedAccountId = State(initialValue: model.defaultOutgoingAccountId ?? model.accounts.first?.id)
    }

    private var account: StoredAccount? {
        selectedAccountId.flatMap { model.account(id: $0) } ?? model.accounts.first
    }

    private func close() {
        model.isSettingsPresented = false
    }

    var body: some View {
        NavigationStack(path: $path) {
            root
                .toolbar(.hidden, for: .navigationBar)
                .navigationDestination(for: SettingsPage.self) { page in
                    destination(page)
                }
        }
        .environment(\.soundsModel, account.flatMap { model.media?.soundsModel(for: $0) })
        .tint(Theme.accentText)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .onChange(of: model.accounts) { accounts in
            if accounts.isEmpty {
                close()
            } else if selectedAccountId.map({ id in accounts.contains { $0.id == id } }) != true {
                selectedAccountId = accounts.first?.id
            }
        }
    }

    // MARK: Root

    private var root: some View {
        let outline = SettingsOutline(model: model, accountId: account?.id)

        return SheetShell(title: L10n.string("settings.title"), back: nil, onClose: close) {
            VStack(alignment: .leading, spacing: 0) {
                accountsGroup(outline)

                if outline.showsAvailability, let account {
                    AvailabilityGroup(model: model, account: account)
                }

                if let account {
                    SettingsGroup(title: L10n.string("settings.myAccount")) {
                        link(.profile, symbol: "person.crop.circle", title: L10n.string("settings.profile"), value: account.displayLabel)
                        link(.callPreferences, symbol: "phone.arrow.down.left", title: L10n.string("settings.callPreferences"))
                        link(.link, symbol: "link", title: L10n.string("settings.link"), value: model.registration(for: account.id).label)
                    }
                }

                if outline.showsAdmin {
                    adminGroup(outline)
                }

                SettingsGroup(title: L10n.string("settings.app")) {
                    link(.appearance, symbol: "circle.lefthalf.filled", title: L10n.string("settings.appearance"))
                    link(.notifications, symbol: "bell", title: L10n.string("settings.notifications"))
                    link(.about, symbol: "info.circle", title: L10n.string("settings.aboutRow"))
                }

                SettingsGroup {
                    Link(destination: Self.feedbackURL) {
                        SettingsRow(symbol: "envelope", title: L10n.string("settings.feedback"), showsChevron: false)
                    }
                    .buttonStyle(RowButtonStyle())
                    .accessibilityIdentifier("feedback-link")

                    if let account {
                        Button {
                            confirmsUnpair = true
                        } label: {
                            SettingsRow(symbol: "link.badge.plus", title: String(format: L10n.string("settings.unpair"), account.displayLabel), showsChevron: false, isDestructive: true)
                        }
                        .buttonStyle(RowButtonStyle())
                        .disabled(isUnpairing)
                        .accessibilityIdentifier("unpair-button")
                    }
                }

                Text(String(format: L10n.string("settings.versionLine"), Self.version))
                    .font(.footnote)
                    .foregroundStyle(Theme.textTertiary)
                    .frame(maxWidth: .infinity)
                    .padding(.top, Theme.Spacing.s)
                    .accessibilityIdentifier("settings-version")
            }
        }
        .confirmationDialog(
            String(format: L10n.string("account.unpair.title"), account?.displayLabel ?? ""),
            isPresented: $confirmsUnpair,
            titleVisibility: .visible
        ) {
            Button(L10n.string("account.unpair.confirm"), role: .destructive) { unpair() }
        } message: {
            L10n.text("account.unpair.message")
        }
        .alert(L10n.string("account.forget.title"), isPresented: $offersForget) {
            Button(L10n.string("account.forget.confirm"), role: .destructive) {
                if let id = account?.id { model.forget(accountId: id) }
            }
            Button(L10n.string("action.cancel"), role: .cancel) {}
        } message: {
            L10n.text("account.forget.message")
        }
        .accessibilityIdentifier("settings-sheet")
    }

    private func link(_ page: SettingsPage, symbol: String, title: String, subtitle: String? = nil, value: String? = nil) -> some View {
        NavigationLink(value: page) {
            SettingsRow(symbol: symbol, title: title, subtitle: subtitle, value: value)
        }
        .buttonStyle(RowButtonStyle())
        .accessibilityIdentifier("settings-row-\(String(describing: page))")
    }

    private func accountsGroup(_ outline: SettingsOutline) -> some View {
        SettingsGroup(title: outline.showsAccountSwitcher ? L10n.string("settings.lines") : nil) {
            if outline.showsAccountSwitcher {
                ForEach(model.accounts) { item in
                    ChoiceRow(
                        title: item.displayLabel,
                        subtitle: model.registration(for: item.id).label,
                        isSelected: item.id == account?.id
                    ) {
                        selectedAccountId = item.id
                    }
                    .accessibilityIdentifier("account-row")
                }
            }

            Button {
                model.isScannerPresented = true
                close()
            } label: {
                SettingsRow(symbol: "plus", title: L10n.string("settings.addLine"), showsChevron: false)
            }
            .buttonStyle(RowButtonStyle())
            .accessibilityIdentifier("add-line-button")
        }
    }

    private func adminGroup(_ outline: SettingsOutline) -> some View {
        SettingsGroup(title: L10n.string("settings.admin")) {
            ForEach(outline.admin, id: \.self) { item in
                switch item {
                case .numbers: link(.centrale(.numbers), symbol: "number", title: L10n.string("settings.admin.numbers"))
                case .devices: link(.centrale(.devices), symbol: "phone.fill", title: L10n.string("pbx.devices.title"))
                case .ringGroups: link(.centrale(.ringGroups), symbol: "person.3.fill", title: L10n.string("pbx.ringGroups.title"))
                case .overview: link(.centrale(.overview), symbol: "arrow.triangle.branch", title: L10n.string("pbx.title"))
                case .hours: link(.centrale(.hours), symbol: "clock", title: L10n.string("pbx.hours.title"))
                case .sounds: link(.sounds, symbol: "speaker.wave.2", title: L10n.string("settings.admin.sounds"))
                case .invite: link(.invite, symbol: "person.badge.plus", title: L10n.string("settings.admin.invite"))
                case .recordings: link(.recordings, symbol: "waveform", title: L10n.string("media.recordings.title"))
                }
            }
        }
    }

    // MARK: Destinations

    @ViewBuilder
    private func destination(_ page: SettingsPage) -> some View {
        switch page {
        case .profile:
            if let account { ProfilePage(model: model, account: account, back: pop) }
        case .callPreferences:
            if let account { CallPreferencesPage(model: model, account: account, back: pop, close: close) }
        case .link:
            if let account { LinkPage(model: model, account: account, back: pop, close: close) }
        case .centrale(let part):
            if let hub = model.pbx, let account {
                PbxSectionView(hub: hub, account: account, part: part, close: close)
            }
        case .sounds:
            if let hub = model.media, let account {
                MediaGateView(
                    hub: hub,
                    account: account,
                    title: L10n.string("settings.admin.sounds"),
                    reason: L10n.string("sounds.lock.reason"),
                    message: L10n.string("sounds.lock.message"),
                    requirement: .sounds
                ) {
                    if let sounds = hub.soundsModel(for: account) {
                        PageScaffold(title: L10n.string("settings.admin.sounds"), back: pop, close: close) {
                            SoundsView(model: sounds)
                        }
                    }
                }
            }
        case .invite:
            ComingSoonPage(title: L10n.string("settings.admin.invite"), symbol: "person.badge.plus", back: pop, close: close)
        case .recordings:
            if let hub = model.media, let account {
                MediaGateView(
                    hub: hub,
                    account: account,
                    title: L10n.string("media.recordings.title"),
                    reason: L10n.string("media.lock.reason.recordings"),
                    message: L10n.string("media.lock.message.recordings")
                ) {
                    RecordingsView(hub: hub, account: account)
                }
            }
        case .appearance:
            AppearancePage(back: pop, close: close)
        case .notifications:
            NotificationsPage(back: pop, close: close)
        case .about:
            AboutPage(back: pop, close: close, openLicenses: { path.append(.licenses) })
        case .licenses:
            LicensesView()
        }
    }

    private func pop() {
        if !path.isEmpty { path.removeLast() }
    }

    // MARK: Actions

    private func unpair() {
        guard let id = account?.id else { return }

        isUnpairing = true

        Task {
            let result = await model.unpair(accountId: id)
            isUnpairing = false

            if case .failed = result {
                offersForget = true
            }
        }
    }

    static let feedbackURL = URL(string: "mailto:info@fullstackstudio.nl?subject=FSVoip%20feedback")!

    static var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "–"
        let build = info?["CFBundleVersion"] as? String ?? "–"

        return "\(short) (\(build))"
    }
}

// MARK: - Beschikbaar

/// "Beschikbaar": the switch behind the dot on the avatar. Off = do not disturb on the own extension.
private struct AvailabilityGroup: View {
    @ObservedObject var model: FSVoipAppModel
    let account: StoredAccount

    var body: some View {
        if let hub = model.availability {
            AvailabilityGroupBody(hub: hub, account: account)
        }
    }
}

private struct AvailabilityGroupBody: View {
    @ObservedObject var hub: AvailabilityHub
    let account: StoredAccount

    var body: some View {
        SettingsGroup {
            ToggleRow(
                title: L10n.string("settings.available"),
                explanation: L10n.string(hub.isAvailable(account.id) == false ? "settings.available.off" : "settings.available.on"),
                isOn: Binding(
                    get: { hub.isAvailable(account.id) ?? true },
                    set: { value in Task { await hub.setAvailable(value, account: account) } }
                ),
                isEnabled: hub.state(for: account.id) != nil && !hub.saving.contains(account.id)
            )
            .accessibilityIdentifier("available-toggle")
        }
        .task(id: account.id) { await hub.load(account) }
    }
}

// MARK: - Pages

/// A page of the sheet on the design system: back, title, close, a flat body.
private struct PageScaffold<Content: View>: View {
    let title: String
    let back: () -> Void
    let close: () -> Void
    var footer: SheetFooter?
    var isSaving = false
    var isDirty = false
    @ViewBuilder let content: () -> Content

    var body: some View {
        SheetShell(title: title, back: back, onClose: close, footer: footer, isSaving: isSaving, isDirty: isDirty, content: content)
            .toolbar(.hidden, for: .navigationBar)
    }
}

private struct ProfilePage: View {
    @ObservedObject var model: FSVoipAppModel
    let account: StoredAccount
    let back: () -> Void

    @State private var alias = ""
    @State private var isSaving = false

    private var isDirty: Bool {
        AccountService.cleanAlias(alias) != AccountService.cleanAlias(account.labelOverride)
    }

    var body: some View {
        PageScaffold(
            title: L10n.string("settings.profile"),
            back: back,
            close: { model.isSettingsPresented = false },
            footer: SheetFooter(canSave: isDirty, onCancel: back, onSave: save),
            isSaving: isSaving,
            isDirty: isDirty
        ) {
            VStack(alignment: .leading, spacing: Theme.Spacing.l) {
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
            }
        }
        .onAppear { alias = account.labelOverride ?? "" }
    }

    private func save() {
        guard isDirty, !isSaving else { return }

        isSaving = true

        Task {
            let saved = await model.rename(accountId: account.id, alias: alias)
            isSaving = false

            if saved { back() }
        }
    }
}

private struct CallPreferencesPage: View {
    @ObservedObject var model: FSVoipAppModel
    let account: StoredAccount
    let back: () -> Void
    let close: () -> Void

    var body: some View {
        PageScaffold(title: L10n.string("settings.callPreferences"), back: back, close: close) {
            VStack(alignment: .leading, spacing: 0) {
                SettingsGroup(footer: String(format: L10n.string("account.showCalled.footer"), account.displayLabel)) {
                    ToggleRow(
                        title: L10n.string("account.showCalled"),
                        isOn: Binding(
                            get: { _ = model.settingsRevision; return model.showsCalledAccount(account.id) },
                            set: { model.setShowsCalledAccount(account.id, $0) }
                        )
                    )
                    .accessibilityIdentifier("show-called-toggle")
                }

                if model.accounts.count > 1 {
                    SettingsGroup(title: L10n.string("settings.defaultLine"), footer: L10n.string("settings.defaultLine.footer")) {
                        ForEach(model.accounts) { item in
                            ChoiceRow(title: item.displayLabel, isSelected: model.defaultOutgoingAccountId == item.id) {
                                model.setDefaultOutgoing(item.id)
                            }
                        }
                    }
                }
            }
        }
    }
}

private struct LinkPage: View {
    @ObservedObject var model: FSVoipAppModel
    let account: StoredAccount
    let back: () -> Void
    let close: () -> Void

    var body: some View {
        let state = model.registration(for: account.id)

        PageScaffold(title: L10n.string("settings.link"), back: back, close: close) {
            VStack(alignment: .leading, spacing: 0) {
                SettingsGroup {
                    HStack(spacing: Theme.Spacing.m) {
                        StatusLight(state: state, size: 10)
                        Text(state.label).font(.body).foregroundStyle(Theme.textPrimary)
                        Spacer(minLength: 0)
                    }
                    .settingsRowChrome()
                    .accessibilityElement(children: .combine)

                    if case .failed = state {
                        Button {
                            model.phone.refreshRegistrations()
                        } label: {
                            SettingsRow(symbol: "arrow.clockwise", title: L10n.string("account.reconnect"), showsChevron: false)
                        }
                        .buttonStyle(RowButtonStyle())
                    }
                }

                SettingsGroup(title: L10n.string("account.details")) {
                    detail("account.pbx", account.pbxName)
                    detail("account.device", [account.extensionName, account.extensionNumber].compactMap { $0 }.joined(separator: " · "))
                    detail("account.customer", account.customerName)
                    detail("account.connection", account.sip.transport == .tls ? L10n.string("account.connection.tls") : L10n.string("account.connection.plain"))
                    detail("account.domain", account.sip.domain)
                    detail("account.paired", account.pairedAt.formatted(date: .abbreviated, time: .omitted))
                }

                Text(L10n.string("account.unpair.footer"))
                    .font(.footnote)
                    .foregroundStyle(Theme.textTertiary)
                    .padding(.horizontal, Theme.Spacing.l)
            }
        }
    }

    private func detail(_ key: String, _ value: String) -> some View {
        SettingsRow(title: L10n.string(key), value: value, showsChevron: false)
    }
}

private struct AppearancePage: View {
    let back: () -> Void
    let close: () -> Void

    @AppStorage(AppearancePreference.storageKey) private var raw = AppearancePreference.default.rawValue

    var body: some View {
        let current = AppearancePreference(rawValue: raw) ?? .default

        PageScaffold(title: L10n.string("settings.appearance"), back: back, close: close) {
            SettingsGroup(footer: L10n.string("settings.appearance.footer")) {
                ForEach(AppearancePreference.allCases) { option in
                    ChoiceRow(title: L10n.string(option.titleKey), isSelected: option == current) {
                        raw = option.rawValue
                    }
                }
            }
        }
    }
}

private struct NotificationsPage: View {
    let back: () -> Void
    let close: () -> Void

    var body: some View {
        PageScaffold(title: L10n.string("settings.notifications"), back: back, close: close) {
            SettingsGroup(footer: L10n.string("settings.notifications.footer")) {
                Button {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                } label: {
                    SettingsRow(symbol: "gearshape", title: L10n.string("settings.notifications.open"), showsChevron: false)
                }
                .buttonStyle(RowButtonStyle())
            }
        }
    }
}

private struct AboutPage: View {
    let back: () -> Void
    let close: () -> Void
    let openLicenses: () -> Void

    var body: some View {
        PageScaffold(title: L10n.string("settings.aboutRow"), back: back, close: close) {
            SettingsGroup(footer: L10n.string("settings.about.footer")) {
                SettingsRow(title: L10n.string("settings.version"), value: SettingsSheet.version, showsChevron: false)
                Button(action: openLicenses) {
                    SettingsRow(title: L10n.string("settings.licenses"))
                }
                .buttonStyle(RowButtonStyle())
            }
        }
    }
}

/// Pages whose content arrives in a later task (geluiden, uitnodigen).
private struct ComingSoonPage: View {
    let title: String
    let symbol: String
    let back: () -> Void
    let close: () -> Void

    var body: some View {
        PageScaffold(title: title, back: back, close: close) {
            EmptyState(symbol: symbol, title: L10n.string("settings.soon.title"), message: L10n.string("settings.soon.message"))
                .frame(minHeight: 320)
        }
    }
}
