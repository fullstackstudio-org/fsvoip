// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// "On hold": the calls parked on the PBX, for the whole team. Tap a call to pick it up (it is dialled as a normal call), `···` hangs
/// it up. The list refreshes when the tab opens, on pull to refresh, when the app comes to the front and every few seconds while the
/// tab is visible.
struct OnHoldTab: View {
    @ObservedObject var model: FSVoipAppModel

    var body: some View {
        Group {
            if let park = model.park, let account = model.parkAccount {
                OnHoldContent(app: model, park: park, account: account)
                    // Another account is another list: start over.
                    .id(account.id)
            } else {
                unavailable
            }
        }
        .background(Theme.background)
        .navigationTitle(L10n.string("tab.onHold"))
    }

    private var unavailable: some View {
        EmptyState(
            symbol: "pause.circle",
            title: L10n.string("onHold.unavailable.title"),
            message: L10n.string("onHold.unavailable.message")
        )
        .accessibilityIdentifier("onhold-unavailable")
    }
}

struct OnHoldContent: View {
    @ObservedObject var app: FSVoipAppModel
    @ObservedObject var park: ParkModel
    let account: StoredAccount

    @State private var scope: ParkModel.Scope = .all
    @State private var pendingHangUp: ParkedCall?
    @Environment(\.scenePhase) private var scenePhase

    /// The tab view keeps this view alive while another tab is showing: poll only when On hold is the visible tab and the app is active.
    static func shouldPoll(phase: ScenePhase, selectedTab: FSVoipAppModel.Tab) -> Bool {
        phase == .active && selectedTab == .onHold
    }

    private var rows: [ParkedCall] {
        park.calls(in: scope)
    }

    var body: some View {
        VStack(spacing: 0) {
            controls

            content
        }
        .task(id: account.id) {
            if Self.shouldPoll(phase: scenePhase, selectedTab: app.selectedTab) {
                park.startPolling(account)
            }
        }
        .onDisappear { park.stopPolling() }
        .onChange(of: scenePhase) { phase in
            if Self.shouldPoll(phase: phase, selectedTab: app.selectedTab) {
                park.startPolling(account)
                Task { await park.refresh(account) }
            } else {
                park.stopPolling()
            }
        }
        .onChange(of: app.selectedTab) { tab in
            if Self.shouldPoll(phase: scenePhase, selectedTab: tab) {
                park.startPolling(account)
            } else {
                park.stopPolling()
            }
        }
        .confirmationDialog(
            L10n.string("onHold.hangUp.title"),
            isPresented: Binding(get: { pendingHangUp != nil }, set: { if !$0 { pendingHangUp = nil } }),
            titleVisibility: .visible,
            presenting: pendingHangUp
        ) { call in
            Button(L10n.string("onHold.hangUp.confirm"), role: .destructive) {
                Task { await hangUp(call) }
            }
        } message: { call in
            Text(String(format: L10n.string("onHold.hangUp.message"), Self.title(for: call)))
        }
        .accessibilityIdentifier("onhold-list")
    }

    // MARK: Parts

    private var controls: some View {
        HStack(spacing: Theme.Spacing.m) {
            SegmentedBar(
                options: [
                    .init(value: ParkModel.Scope.all, title: L10n.string("onHold.scope.all")),
                    .init(value: ParkModel.Scope.mine, title: String(format: L10n.string("onHold.scope.mine"), park.mineCount)),
                ],
                selection: $scope
            )
            .accessibilityIdentifier("onhold-segments")

            if app.parkAccounts.count > 1 {
                accountMenu
            }
        }
        .padding(.horizontal, Theme.Spacing.l)
        .padding(.vertical, Theme.Spacing.s)
    }

    private var accountMenu: some View {
        Menu {
            ForEach(app.parkAccounts) { candidate in
                Button {
                    app.chooseParkAccount(candidate.id)
                } label: {
                    if candidate.id == account.id {
                        Label(candidate.displayLabel, systemImage: "checkmark")
                    } else {
                        Text(candidate.displayLabel)
                    }
                }
            }
        } label: {
            Image(systemName: "person.crop.circle")
                .font(.title3)
                .foregroundStyle(Theme.accentText)
                .frame(minWidth: Theme.minimumTarget, minHeight: Theme.minimumTarget)
        }
        .accessibilityLabel(L10n.string("onHold.account"))
        .accessibilityValue(account.displayLabel)
    }

    @ViewBuilder
    private var content: some View {
        if park.available == false {
            scrolling {
                EmptyState(
                    symbol: "pause.circle",
                    title: L10n.string("onHold.unavailable.title"),
                    message: L10n.string("onHold.unavailable.message")
                )
                .accessibilityIdentifier("onhold-unavailable")
            }
        } else if rows.isEmpty {
            if park.available == nil, park.failure == nil {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityLabel(L10n.string("onHold.loading"))
            } else if let failure = park.failure, park.available == nil {
                scrolling {
                    EmptyState(
                        symbol: "exclamationmark.circle",
                        title: L10n.string("onHold.error.title"),
                        message: failure.message,
                        actionTitle: L10n.string("action.retry")
                    ) {
                        Task { await park.refresh(account) }
                    }
                }
            } else {
                scrolling {
                    EmptyState(
                        symbol: "cup.and.saucer",
                        title: L10n.string("onHold.empty.title"),
                        message: L10n.string("onHold.empty.message")
                    )
                    .accessibilityIdentifier("onhold-empty")
                }
            }
        } else {
            List {
                ForEach(rows) { call in
                    ParkedRow(
                        call: call,
                        isBusy: park.busyIds.contains(call.id),
                        canHangUp: park.canHangUp(call, accountId: account.id),
                        pickUp: { Task { await pickUp(call) } },
                        hangUp: { pendingHangUp = call }
                    )
                    .listRowBackground(Color.clear)
                    .listRowSeparatorTint(Theme.separator)
                    .listRowInsets(EdgeInsets(top: 0, leading: Theme.Spacing.l, bottom: 0, trailing: Theme.Spacing.s))
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .refreshable { await park.refresh(account) }
        }
    }

    /// An empty state that can still be pulled down to refresh.
    private func scrolling<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        let built = content()

        return GeometryReader { geometry in
            ScrollView {
                built
                    .frame(minHeight: geometry.size.height)
            }
            .refreshable { await park.refresh(account) }
        }
    }

    // MARK: Actions

    private func pickUp(_ call: ParkedCall) async {
        switch await park.retrieve(call, account: account) {
        case .dialing:
            break
        case .gone:
            app.notice = FSVoipAppModel.Notice(message: L10n.string("onHold.notice.gone"), isError: true)
        case .dialFailed:
            // `app.call` has already shown its own, specific notice.
            break
        case let .failed(failure):
            app.notice = FSVoipAppModel.Notice(message: failure.message, isError: true)
        }
    }

    private func hangUp(_ call: ParkedCall) async {
        switch await park.hangUp(call, account: account) {
        case .done:
            break
        case .gone:
            app.notice = FSVoipAppModel.Notice(message: L10n.string("onHold.notice.gone"), isError: true)
        case let .failed(failure):
            app.notice = FSVoipAppModel.Notice(message: failure.message, isError: true)
        }
    }

    static func title(for call: ParkedCall) -> String {
        if let name = call.callerName, !name.isEmpty { return name }
        if let number = call.callerNumber, !number.isEmpty { return number }

        return L10n.string("call.anonymous")
    }
}

// MARK: - Row

private struct ParkedRow: View {
    let call: ParkedCall
    let isBusy: Bool
    let canHangUp: Bool
    let pickUp: () -> Void
    let hangUp: () -> Void

    @ScaledMetric(relativeTo: .body) private var menuSide: CGFloat = 44

    var body: some View {
        // Once a second: the "since" and the "rings back in" run.
        TimelineView(.periodic(from: .now, by: 1)) { context in
            row(now: context.date)
        }
    }

    private func row(now: Date) -> some View {
        let title = OnHoldContent.title(for: call)
        let since = ParkFormat.elapsed(since: call.parkedAt, now: now)
        let remaining = call.mine ? ParkFormat.remaining(until: call.expiresAt, now: now) : nil
        let parker = call.parkedBy?.name

        var parts = [String(format: L10n.string("onHold.slot"), ParkFormat.slot(call.slot))]

        if let since {
            parts.append(String(format: L10n.string("onHold.since"), since))
        }

        let subtitle = parts.joined(separator: " · ")

        return HStack(spacing: Theme.Spacing.s) {
            Button(action: pickUp) {
                HStack(spacing: Theme.Spacing.m) {
                    InitialsAvatar(name: parker, size: 40)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.body.weight(.semibold))
                            .foregroundStyle(Theme.textPrimary)
                            .adaptiveLineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)

                        Text(subtitle)
                            .font(.footnote.monospacedDigit())
                            .foregroundStyle(Theme.textSecondary)
                            .adaptiveLineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)

                        if let remaining {
                            Text(String(format: L10n.string("onHold.rings"), remaining))
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(Theme.busy)
                        }
                    }

                    Spacer(minLength: Theme.Spacing.s)

                    if isBusy {
                        ProgressView()
                    } else {
                        Image(systemName: "phone.arrow.down.left")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(Theme.accentText)
                            .accessibilityHidden(true)
                    }
                }
                .padding(.vertical, Theme.Spacing.s)
                .frame(minHeight: Theme.minimumTarget)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(isBusy)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(title)
            .accessibilityValue([subtitle, remaining.map { String(format: L10n.string("onHold.rings"), $0) }, parker.map { String(format: L10n.string("onHold.parkedBy"), $0) }].compactMap { $0 }.joined(separator: ", "))
            .accessibilityHint(L10n.string("onHold.row.hint"))
            .accessibilityActions {
                if canHangUp {
                    Button(L10n.string("onHold.hangUp"), role: .destructive, action: hangUp)
                }
            }

            Menu {
                Button(action: pickUp) {
                    Label(L10n.string("onHold.pickUp"), systemImage: "phone.arrow.down.left")
                }

                if canHangUp {
                    Button(role: .destructive, action: hangUp) {
                        Label(L10n.string("onHold.hangUp"), systemImage: "phone.down")
                    }
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(minWidth: menuSide, minHeight: menuSide)
            }
            .accessibilityLabel(String(format: L10n.string("onHold.more"), title))
            .accessibilityIdentifier("onhold-more")
        }
    }
}
