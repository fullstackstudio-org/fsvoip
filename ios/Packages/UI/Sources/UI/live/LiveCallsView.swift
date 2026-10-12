// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// "Live": the calls that are running on the PBX right now, for the whole team. A ringing call can be taken over to the own phone, a call
/// the own extension answered can be transferred (an `admin` transfers every answered call). The list is polled every few seconds while
/// this section is visible; there is no push for this.
struct LiveCallsContent: View {
    @ObservedObject var app: FSVoipAppModel
    @ObservedObject var live: LiveModel
    let account: StoredAccount

    @State private var transferring: LiveCall?
    @Environment(\.scenePhase) private var scenePhase

    /// The tab view keeps this view alive while another tab is showing (the section switch removes it by itself): poll only when the
    /// "On hold" tab is the visible tab and the app is active.
    static func shouldPoll(phase: ScenePhase, selectedTab: FSVoipAppModel.Tab) -> Bool {
        phase == .active && selectedTab == .onHold
    }

    var body: some View {
        content
            .task(id: account.id) {
                if Self.shouldPoll(phase: scenePhase, selectedTab: app.selectedTab) {
                    live.startPolling(account)
                }
            }
            .onDisappear { live.stopPolling() }
            .onChange(of: scenePhase) { phase in
                if Self.shouldPoll(phase: phase, selectedTab: app.selectedTab) {
                    live.startPolling(account)
                    Task { await live.refresh(account) }
                } else {
                    live.stopPolling()
                }
            }
            .onChange(of: app.selectedTab) { tab in
                if Self.shouldPoll(phase: scenePhase, selectedTab: tab) {
                    live.startPolling(account)
                } else {
                    live.stopPolling()
                }
            }
            .sheet(item: $transferring) { call in
                LiveTransferSheet(call: call, targets: live.targets) { target in
                    transferring = nil
                    Task { await transfer(call, to: target) }
                }
            }
            .accessibilityIdentifier("live-list")
    }

    @ViewBuilder
    private var content: some View {
        if live.enabled == false {
            scrolling {
                EmptyState(symbol: "phone.badge.waveform", title: L10n.string("live.disabled.title"), message: L10n.string("live.disabled.message"))
                    .accessibilityIdentifier("live-disabled")
            }
        } else if live.enabled == nil {
            if let failure = live.failure {
                scrolling {
                    EmptyState(symbol: "exclamationmark.circle", title: L10n.string("live.error.title"), message: failure.message, actionTitle: L10n.string("action.retry")) {
                        Task { await live.refresh(account) }
                    }
                }
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityLabel(L10n.string("live.loading"))
            }
        } else if live.calls.isEmpty {
            VStack(spacing: 0) {
                banners

                scrolling {
                    EmptyState(symbol: "phone", title: L10n.string("live.empty.title"), message: L10n.string("live.empty.message"))
                        .accessibilityIdentifier("live-empty")
                }
            }
        } else {
            VStack(spacing: 0) {
                banners

                List {
                    section("live.section.ringing", live.ringing)
                    section("live.section.running", live.running)
                    section("live.section.parked", live.parked)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .detachedRefreshable { await live.refresh(account) }
            }
        }
    }

    @ViewBuilder
    private var banners: some View {
        VStack(spacing: 0) {
            if live.failure != nil {
                banner(L10n.string("live.stale"), symbol: "wifi.exclamationmark", id: "live-stale")
            } else if !live.fresh {
                banner(L10n.string("live.notFresh"), symbol: "clock.arrow.circlepath", id: "live-notfresh")
            }

            if live.readOnly {
                banner(L10n.string("live.readOnly"), symbol: "lock", id: "live-readonly")
            }
        }
    }

    private func banner(_ text: String, symbol: String, id: String) -> some View {
        Label(text, systemImage: symbol)
            .font(.footnote)
            .foregroundStyle(Theme.textSecondary)
            .padding(.horizontal, Theme.Spacing.l)
            .padding(.vertical, Theme.Spacing.s)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier(id)
    }

    @ViewBuilder
    private func section(_ titleKey: String, _ calls: [LiveCall]) -> some View {
        if !calls.isEmpty {
            Section {
                ForEach(calls) { call in
                    LiveRow(
                        call: call,
                        isBusy: live.busyIds.contains(call.id),
                        canTransfer: call.canTransfer && !live.targets.isEmpty,
                        pickUp: { Task { await pickUp(call) } },
                        transfer: { transferring = call }
                    )
                    .listRowBackground(Color.clear)
                    .listRowSeparatorTint(Theme.separator)
                    .listRowInsets(EdgeInsets(top: 0, leading: Theme.Spacing.l, bottom: 0, trailing: Theme.Spacing.l))
                }
            } header: {
                Text(L10n.string(titleKey))
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Theme.textSecondary)
            }
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
            .detachedRefreshable { await live.refresh(account) }
        }
    }

    // MARK: Actions

    private func pickUp(_ call: LiveCall) async {
        switch await live.pickup(call, account: account) {
        case .pickedUp:
            app.notice = FSVoipAppModel.Notice(message: L10n.string("live.notice.pickedUp"), isError: false)
        case let .failed(failure):
            app.notice = FSVoipAppModel.Notice(message: failure.message, isError: true)
        case .ignored:
            break
        }
    }

    private func transfer(_ call: LiveCall, to target: LiveTarget) async {
        switch await live.transfer(call, to: target, account: account) {
        case .transferred:
            app.notice = FSVoipAppModel.Notice(message: String(format: L10n.string("live.notice.transferred"), target.name), isError: false)
        case let .failed(failure):
            app.notice = FSVoipAppModel.Notice(message: failure.message, isError: true)
        case .ignored:
            break
        }
    }
}

// MARK: - Row

private struct LiveRow: View {
    let call: LiveCall
    let isBusy: Bool
    let canTransfer: Bool
    let pickUp: () -> Void
    let transfer: () -> Void

    var body: some View {
        // Once a second: the running time.
        TimelineView(.periodic(from: .now, by: 1)) { context in
            row(now: context.date)
        }
    }

    private func row(now: Date) -> some View {
        let title = LiveFormat.title(for: call)
        let detail = LiveFormat.detail(for: call)
        let elapsed = LiveFormat.elapsed(from: call.clockStart, now: now)

        return VStack(alignment: .leading, spacing: Theme.Spacing.s) {
            HStack(spacing: Theme.Spacing.m) {
                Image(systemName: symbol)
                    .font(.title3)
                    .foregroundStyle(call.isRinging ? Theme.busy : call.isParked ? Theme.textTertiary : Theme.accentText)
                    .frame(width: 32)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: Theme.Spacing.s) {
                        Text(title)
                            .font(.body.weight(.semibold))
                            .foregroundStyle(Theme.textPrimary)
                            .adaptiveLineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)

                        if call.mine {
                            Text(L10n.string("live.mine"))
                                .font(.caption2.weight(.semibold))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Theme.accent.opacity(0.25), in: Capsule())
                                .foregroundStyle(Theme.textPrimary)
                        }
                    }

                    if call.callerName?.isEmpty == false, let number = call.callerNumber {
                        Text(number)
                            .font(.footnote.monospacedDigit())
                            .foregroundStyle(Theme.textSecondary)
                    }

                    Text(detail)
                        .font(.footnote)
                        .foregroundStyle(Theme.textSecondary)
                        .adaptiveLineLimit(3)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: Theme.Spacing.s)

                Text(elapsed)
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
                    .accessibilityLabel(String(format: L10n.string("live.elapsed"), elapsed))
            }

            if call.canPickup || canTransfer {
                HStack(spacing: Theme.Spacing.m) {
                    if call.canPickup {
                        Button(action: pickUp) {
                            Label(L10n.string("live.pickUp"), systemImage: "phone.arrow.down.left")
                        }
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("live-pickup")
                    }

                    if canTransfer {
                        Button(action: transfer) {
                            Label(L10n.string("live.transfer"), systemImage: "arrowshape.turn.up.right")
                        }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("live-transfer")
                    }

                    if isBusy { ProgressView() }
                }
                .controlSize(.small)
                .tint(Theme.accentText)
                .disabled(isBusy)
                .padding(.leading, 32 + Theme.Spacing.m)
            }
        }
        .padding(.vertical, Theme.Spacing.s)
        .frame(minHeight: Theme.minimumTarget)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("live-row")
    }

    private var symbol: String {
        if call.isParked { return "pause.circle" }

        switch call.direction {
        case .outbound: return "phone.arrow.up.right"
        case .internal: return "phone.connection"
        default: return call.isRinging ? "phone.badge.waveform" : "phone.arrow.down.left"
        }
    }
}

// MARK: - Transfer

/// Where to transfer a call: only inside the own PBX (extensions, ring groups, queues, menus, opening hours, voicemail).
struct LiveTransferSheet: View {
    let call: LiveCall
    let targets: [LiveTarget]
    let pick: (LiveTarget) -> Void

    @State private var search = ""
    @State private var confirming: LiveTarget?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                let groups = LiveFormat.grouped(targets, search: search)

                if groups.isEmpty {
                    EmptyState(symbol: "arrowshape.turn.up.right", title: L10n.string(targets.isEmpty ? "live.transfer.none" : "live.transfer.nothing"))
                        .listRowBackground(Color.clear)
                }

                ForEach(groups, id: \.type) { group in
                    Section(L10n.string("live.target.\(group.type.rawValue)")) {
                        ForEach(group.items) { target in
                            Button {
                                confirming = target
                            } label: {
                                HStack {
                                    Text(target.name).foregroundStyle(Theme.textPrimary)
                                    Spacer()
                                    Text(target.extension)
                                        .font(.body.monospacedDigit())
                                        .foregroundStyle(Theme.textSecondary)
                                }
                                .frame(minHeight: Theme.minimumTarget)
                                .contentShape(Rectangle())
                            }
                            .accessibilityIdentifier("live-target")
                        }
                    }
                }

                Section {} footer: {
                    Text(L10n.string("live.transfer.footer"))
                }
            }
            .searchable(text: $search, prompt: L10n.string("live.transfer.search"))
            .navigationTitle(L10n.string("live.transfer.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.string("action.cancel")) { dismiss() }
                }
            }
            .confirmationDialog(
                String(format: L10n.string("live.transfer.confirm.title"), confirming?.name ?? ""),
                isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
                titleVisibility: .visible,
                presenting: confirming
            ) { target in
                Button(L10n.string("live.transfer")) { pick(target) }
            } message: { _ in
                Text(String(format: L10n.string("live.transfer.confirm.message"), LiveFormat.title(for: call)))
            }
        }
        .presentationDetents([.medium, .large])
        .accessibilityIdentifier("live-transfer-sheet")
    }
}
