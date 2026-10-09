// SPDX-License-Identifier: AGPL-3.0-or-later
import CallController
import Core
import SwiftUI

public struct RootView: View {
    @ObservedObject private var model: FSVoipAppModel
    @ObservedObject private var phone: PhoneController
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage(AppearancePreference.storageKey) private var appearanceRaw = AppearancePreference.default.rawValue

    private var appearance: AppearancePreference {
        AppearancePreference(rawValue: appearanceRaw) ?? .default
    }

    public init(model: FSVoipAppModel) {
        self.model = model
        phone = model.phone
    }

    private var callOnScreen: CallSession? {
        phone.activeSession ?? phone.lastEnded
    }

    public var body: some View {
        ZStack {
            Group {
                if model.accounts.isEmpty {
                    OnboardingView(model: model)
                } else {
                    MainTabView(model: model)
                }
            }
            .sheet(isPresented: pairingSheetPresented) {
                PairingSheet(model: model)
            }

            if let session = callOnScreen {
                InCallView(model: model, phone: phone, session: session)
                    .transition(.move(edge: .bottom))
                    .zIndex(1)
            }
        }
        .animation(reduceMotion ? nil : .spring(response: 0.38, dampingFraction: 0.9), value: callOnScreen?.id)
        .overlay(alignment: .top) {
            if let notice = model.notice {
                NoticeBanner(notice: notice) { model.notice = nil }
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .task(id: notice.id) {
                        try? await Task.sleep(nanoseconds: 5_000_000_000)

                        if model.notice?.id == notice.id {
                            model.notice = nil
                        }
                    }
            }
        }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.25), value: model.notice)
        .preferredColorScheme(appearance.colorScheme)
        .onChange(of: phone.activeSession?.id) { id in
            // A call takes the screen: put the scanner away.
            if id != nil, model.isScannerPresented, !model.pairing.isActive {
                model.isScannerPresented = false
            }
        }
    }

    private var pairingSheetPresented: Binding<Bool> {
        Binding(
            get: { model.isScannerPresented || model.pairing.isActive },
            set: { presented in
                if !presented {
                    model.isScannerPresented = false
                    model.closePairing()
                }
            }
        )
    }
}

struct MainTabView: View {
    @ObservedObject var model: FSVoipAppModel

    var body: some View {
        TabView(selection: $model.selectedTab) {
            NavigationStack {
                DialerView(model: model)
                    .shellToolbar(model: model)
            }
            .tabItem { Label(L10n.string("tab.dialer"), systemImage: "circle.grid.3x3.fill") }
            .tag(FSVoipAppModel.Tab.dialer)
            .accessibilityIdentifier("tab-dialer")

            NavigationStack {
                OnHoldTab(model: model)
                    .shellToolbar(model: model)
            }
            .tabItem { Label(L10n.string("tab.onHold"), systemImage: "pause.circle.fill") }
            .tag(FSVoipAppModel.Tab.onHold)
            .accessibilityIdentifier("tab-onhold")

            NavigationStack {
                RecentsView(model: model)
                    .shellToolbar(model: model)
            }
            .tabItem { Label(L10n.string("tab.recents"), systemImage: "clock.fill") }
            .tag(FSVoipAppModel.Tab.recents)
            .accessibilityIdentifier("tab-recents")

            NavigationStack {
                VoicemailTab(model: model)
                    .shellToolbar(model: model)
            }
            .tabItem { Label(L10n.string("tab.voicemail"), systemImage: "voicemail") }
            .tag(FSVoipAppModel.Tab.voicemail)
            .accessibilityIdentifier("tab-voicemail")

            NavigationStack {
                ContactsView(model: model)
                    .shellToolbar(model: model)
            }
            .tabItem { Label(L10n.string("tab.contacts"), systemImage: "person.2.fill") }
            .tag(FSVoipAppModel.Tab.contacts)
            .accessibilityIdentifier("tab-contacts")
        }
        .tint(Theme.accentText)
        .sheet(isPresented: $model.isSettingsPresented) {
            SettingsSheet(model: model)
        }
    }
}

// MARK: - Top bar

extension View {
    /// The top bar of every tab: the gear and the avatar with the availability dot. Both open the settings sheet.
    func shellToolbar(model: FSVoipAppModel) -> some View {
        toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                ShellBarItems(model: model, hub: model.availability ?? AvailabilityHub(service: NoAvailabilityService()))
            }
        }
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct ShellBarItems: View {
    @ObservedObject var model: FSVoipAppModel
    @ObservedObject var hub: AvailabilityHub

    private var account: StoredAccount? {
        model.defaultOutgoingAccountId.flatMap { model.account(id: $0) } ?? model.accounts.first
    }

    private var dotKind: AvailabilityDot.Kind {
        guard let account else { return .offline }

        let registration = model.registration(for: account.id)

        return AvailabilityDot.kind(
            registered: registration == .registered,
            connecting: registration == .registering,
            doNotDisturb: hub.state(for: account.id)?.doNotDisturb ?? false
        )
    }

    private var dotColor: Color {
        switch dotKind {
        case .available: return Theme.accent
        case .doNotDisturb: return Theme.danger
        case .connecting: return Theme.busy
        case .offline: return Theme.textTertiary
        }
    }

    private var dotLabel: String {
        switch dotKind {
        case .available: return L10n.string("settings.dot.available")
        case .doNotDisturb: return L10n.string("settings.dot.dnd")
        case .connecting: return L10n.string("settings.dot.connecting")
        case .offline: return L10n.string("settings.dot.offline")
        }
    }

    var body: some View {
        HStack(spacing: Theme.Spacing.s) {
            Button {
                model.openSettings()
            } label: {
                Image(systemName: "gearshape")
                    .font(.body)
                    .frame(minWidth: Theme.minimumTarget, minHeight: Theme.minimumTarget)
            }
            .accessibilityLabel(L10n.string("settings.title"))
            .accessibilityIdentifier("settings-gear")

            Button {
                model.openSettings()
            } label: {
                InitialsAvatar(name: account?.extensionName, size: 30, dot: dotColor)
                    .frame(minWidth: Theme.minimumTarget, minHeight: Theme.minimumTarget)
            }
            .accessibilityLabel(String(format: L10n.string("settings.avatar.label"), account?.extensionName ?? ""))
            .accessibilityValue(dotLabel)
            .accessibilityIdentifier("settings-avatar")
        }
        .task(id: account?.id) {
            if let account { await hub.load(account) }
        }
    }
}

// MARK: - Tabs without a screen of their own yet

/// "On hold": parked calls arrive in a later task; until then an honest empty state.
struct OnHoldTab: View {
    @ObservedObject var model: FSVoipAppModel

    var body: some View {
        EmptyState(
            symbol: "pause.circle",
            title: L10n.string("onHold.empty.title"),
            message: L10n.string(model.canPark ? "onHold.empty.message" : "onHold.unavailable.message")
        )
        .background(Theme.background)
        .navigationTitle(L10n.string("tab.onHold"))
    }
}

/// The voicemail of the default account that has a box.
struct VoicemailTab: View {
    @ObservedObject var model: FSVoipAppModel

    private var account: StoredAccount? {
        guard let hub = model.media else { return nil }

        let preferred = model.defaultOutgoingAccountId.flatMap { model.account(id: $0) }

        if let preferred, hub.hasVoicemail(preferred.id) { return preferred }

        return model.accounts.first { hub.hasVoicemail($0.id) }
    }

    var body: some View {
        if let hub = model.media, let account {
            VoicemailView(hub: hub, account: account)
        } else {
            EmptyState(
                symbol: "voicemail",
                title: L10n.string("voicemail.empty.title"),
                message: L10n.string("voicemail.empty.message")
            )
            .background(Theme.background)
            .navigationTitle(L10n.string("media.voicemail.title"))
        }
    }
}

/// Stands in when the app has no availability service, so the toolbar can observe a hub unconditionally.
private struct NoAvailabilityService: AvailabilityServicing {
    func load(for account: StoredAccount) async throws -> AvailabilityHub.State { AvailabilityHub.State(doNotDisturb: false, version: 0) }
    func setDoNotDisturb(_ dnd: Bool, version: Int, for account: StoredAccount) async throws -> AvailabilityHub.State { AvailabilityHub.State(doNotDisturb: dnd, version: version) }
}
