// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// The door of "Gebruiker uitnodigen": Face ID / Touch ID / passcode first (the same five minutes as "Beheer"), then the page.
struct InviteGateView: View {
    @ObservedObject var model: FSVoipAppModel
    let pbx: PbxHub
    let account: StoredAccount
    let back: () -> Void
    let close: () -> Void

    @Environment(\.scenePhase) private var scenePhase

    private enum Lock {
        case checking
        case locked
        case open
        case noPasscode
    }

    @State private var lock = Lock.checking

    var body: some View {
        ZStack {
            switch lock {
            case .open:
                InviteUserPage(model: model, hub: model.selfExtension, section: pbx.section(for: account), account: account, back: back, close: close)
            case .checking:
                gateShell { ProgressView(L10n.string("pbx.lock.checking")).frame(maxWidth: .infinity, minHeight: 240) }
            case .locked:
                gateShell {
                    MediaLockCard(symbol: "lock.fill", title: L10n.string("media.lock.title"), message: L10n.string("invite.lock.message"), buttonTitle: L10n.string("pbx.lock.unlock")) {
                        Task { await unlock() }
                    }
                }
            case .noPasscode:
                gateShell {
                    MediaLockCard(symbol: "lock.slash.fill", title: L10n.string("pbx.lock.noPasscode.title"), message: L10n.string("media.lock.noPasscode"), buttonTitle: nil, action: {})
                }
            }
        }
        .task(id: model.canInvite(account.id)) { await unlock() }
        .onChange(of: scenePhase) { phase in
            if phase == .active, lock == .open, !pbx.gate.isUnlocked {
                lock = .checking
                Task { await unlock() }
            }
        }
        .onChange(of: model.canInvite(account.id)) { allowed in
            if !allowed { back() }
        }
    }

    private func gateShell<Content: View>(@ViewBuilder _ content: @escaping () -> Content) -> some View {
        PageScaffold(title: L10n.string("settings.admin.invite"), back: back, close: close, content: content)
    }

    private func unlock() async {
        guard model.canInvite(account.id) else { return }

        switch await pbx.gate.ensureUnlocked(reason: L10n.string("invite.lock.reason")) {
        case .unlocked: lock = .open
        case .cancelled, .failed: lock = .locked
        case .unavailable: lock = .noPasscode
        }
    }
}

struct InviteUserPage: View {
    @ObservedObject var model: FSVoipAppModel
    @ObservedObject var hub: SelfExtensionHub
    @ObservedObject var section: PbxSectionModel
    let account: StoredAccount
    let back: () -> Void
    let close: () -> Void

    @StateObject private var invite: InviteModel

    init(model: FSVoipAppModel, hub: SelfExtensionHub, section: PbxSectionModel, account: StoredAccount, back: @escaping () -> Void, close: @escaping () -> Void) {
        self.model = model
        self.hub = hub
        self.section = section
        self.account = account
        self.back = back
        self.close = close
        _invite = StateObject(wrappedValue: InviteModel(hub: hub, account: account))
    }

    var body: some View {
        // A ZStack and not a Group: the modifiers below belong to the page, not to each branch (a branch change must not reset the link).
        ZStack {
            switch invite.phase {
            case let .shown(invitation):
                shown(invitation)
            case .choosing, .creating, .failed:
                choosing
            }
        }
        .task {
            await hub.load(account)
            await section.loadIfNeeded(.devices)
        }
        .onDisappear { invite.reset() }
    }

    // MARK: Choosing

    private var devices: [PbxDevice] {
        invite.candidates(section.devices?.devices ?? [])
    }

    private var choosing: some View {
        PageScaffold(
            title: L10n.string("settings.admin.invite"),
            back: back,
            close: close,
            footer: SheetFooter(saveTitle: L10n.string("invite.create"), canSave: invite.selectedId != nil && !section.isReadOnly, onCancel: back) {
                Task { await invite.create(deviceName: name(of:)) }
            },
            isSaving: invite.phase == .creating
        ) {
            VStack(alignment: .leading, spacing: 0) {
                if case let .failed(failure) = invite.phase {
                    NoticeCard(symbol: "exclamationmark.circle.fill", tint: Theme.danger, title: failure.message)
                        .accessibilityIdentifier("invite-error")
                }

                if section.isReadOnly {
                    NoticeCard(symbol: "lock.fill", tint: Theme.textSecondary, title: L10n.string("pbx.readOnly.title"), message: L10n.string("pbx.readOnly.message"))
                }

                if section.devices == nil {
                    if section.isLoading {
                        ProgressView().frame(maxWidth: .infinity).padding(Theme.Spacing.xl)
                    } else {
                        EmptyState(symbol: "wifi.exclamationmark", title: L10n.string("invite.loadFailed"), actionTitle: L10n.string("action.retry")) {
                            Task { await section.refresh(.devices) }
                        }
                    }
                } else if devices.isEmpty {
                    EmptyState(symbol: "person.2", title: L10n.string("invite.empty.title"), message: L10n.string("invite.empty.message"))
                } else {
                    Text(L10n.string("invite.intro"))
                        .font(.callout)
                        .foregroundStyle(Theme.textSecondary)
                        .padding(.horizontal, Theme.Spacing.l)
                        .padding(.bottom, Theme.Spacing.l)

                    SettingsGroup(title: L10n.string("invite.pick"), footer: L10n.string("invite.pick.footer")) {
                        ForEach(devices) { device in
                            Button {
                                invite.selectedId = device.id
                            } label: {
                                HStack(spacing: Theme.Spacing.m) {
                                    InitialsAvatar(name: device.name, size: 36)
                                    ChoiceRowLabel(title: device.name, subtitle: device.extensionNumber.map { String(format: L10n.string("account.extension"), $0) }, isSelected: invite.selectedId == device.id)
                                }
                                .settingsRowChrome()
                            }
                            .buttonStyle(RowButtonStyle())
                            .accessibilityAddTraits(invite.selectedId == device.id ? .isSelected : [])
                            .accessibilityIdentifier("invite-device-row")
                        }
                    }
                }
            }
        }
    }

    private func name(of id: String) -> String {
        section.devices?.devices.first { $0.id == id }?.name ?? ""
    }

    // MARK: The link

    private func shown(_ invitation: InviteModel.Invitation) -> some View {
        PageScaffold(
            title: L10n.string("settings.admin.invite"),
            back: { invite.reset() },
            close: close,
            footer: SheetFooter(saveTitle: L10n.string("action.done"), cancelTitle: L10n.string("invite.another"), onCancel: { invite.reset() }, onSave: back)
        ) {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                InviteLinkCard(invitation: invitation, remaining: invite.remaining(of: invitation, at: context.date))
            }
        }
    }
}

/// The QR code, the share button and the countdown. Once the time is up the link is no longer shown.
private struct InviteLinkCard: View {
    let invitation: InviteModel.Invitation
    let remaining: TimeInterval

    private var link: URL? {
        URL(string: invitation.url.reveal())
    }

    var body: some View {
        VStack(spacing: Theme.Spacing.l) {
            if remaining > 0 {
                Text(String(format: L10n.string("invite.shown.title"), invitation.deviceName))
                    .font(.headline)
                    .foregroundStyle(Theme.textPrimary)
                    .multilineTextAlignment(.center)

                if let image = QRCodeImage.make(invitation.url.reveal()) {
                    Image(uiImage: image)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: 240)
                        .padding(Theme.Spacing.m)
                        .background(Color.white, in: Theme.card(Theme.Radius.s))
                        .accessibilityLabel(String(format: L10n.string("invite.qr.label"), invitation.deviceName))
                        .accessibilityIdentifier("invite-qr")
                }

                Text(L10n.string("invite.shown.how"))
                    .font(.callout)
                    .foregroundStyle(Theme.textSecondary)
                    .multilineTextAlignment(.center)

                if let link {
                    ShareLink(item: link, subject: Text(L10n.string("invite.share.subject")), message: Text(String(format: L10n.string("invite.share.message"), invitation.deviceName))) {
                        Label(L10n.string("invite.share"), systemImage: "square.and.arrow.up")
                    }
                    .buttonStyle(SecondaryButtonStyle())
                    .accessibilityIdentifier("invite-share")
                }

                Label(String(format: L10n.string("invite.countdown"), InviteCountdown.format(remaining)), systemImage: "timer")
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(remaining < 60 ? Theme.busy : Theme.textSecondary)
                    .accessibilityIdentifier("invite-countdown")

                Text(L10n.string("invite.shown.once"))
                    .font(.footnote)
                    .foregroundStyle(Theme.textTertiary)
                    .multilineTextAlignment(.center)
            } else {
                EmptyState(symbol: "clock.badge.xmark", title: L10n.string("invite.expired.title"), message: L10n.string("invite.expired.message"))
                    .frame(minHeight: 280)
                    .accessibilityIdentifier("invite-expired")
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, Theme.Spacing.m)
    }
}
