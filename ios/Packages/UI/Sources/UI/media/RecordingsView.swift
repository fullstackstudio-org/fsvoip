// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// "Opnames" (admins): the calls of one month that were recorded; tap one to listen.
struct RecordingsView: View {
    @ObservedObject var hub: MediaHub
    @StateObject private var model: RecordingsModel

    @Environment(\.dismiss) private var dismiss
    @Environment(\.pbxClose) private var close

    init(hub: MediaHub, account: StoredAccount) {
        self.hub = hub
        _model = StateObject(wrappedValue: hub.makeRecordingsModel(account: account))
    }

    var body: some View {
        SheetShell(title: L10n.string("media.recordings.title"), back: { dismiss() }, onClose: { (close ?? { dismiss() })() }) {
            VStack(alignment: .leading, spacing: 0) {
                if let banner = model.banner {
                    NoticeCard(symbol: banner.isError ? "exclamationmark.circle.fill" : "info.circle.fill", tint: banner.isError ? Theme.danger : Theme.textSecondary, title: banner.text)
                        .accessibilityIdentifier("media-banner")
                }

                if model.page != nil {
                    monthGroup
                }

                content
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if let nowPlaying = model.nowPlaying {
                AudioPlayerBar(player: hub.player, nowPlaying: nowPlaying, kind: .recording, onClose: model.closePlayer, onRetry: model.retryPlaying)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .motionAnimation(.easeOut(duration: 0.22), value: model.nowPlaying)
        .detachedRefreshable { await model.load() }
        .task {
            await model.load()
            startDemo()
        }
        .onDisappear { hub.player.stop() }
        .accessibilityIdentifier("recordings-list")
    }

    private var monthGroup: some View {
        SettingsGroup(title: L10n.string("media.recordings.month")) {
            Menu {
                ForEach(model.months, id: \.self) { month in
                    Button {
                        Task { await model.select(month: month) }
                    } label: {
                        if month == model.page?.month { Label(MediaFormat.month(month).capitalized, systemImage: "checkmark") } else { Text(MediaFormat.month(month).capitalized) }
                    }
                }
            } label: {
                SettingsRow(symbol: "calendar", title: model.monthTitle.capitalized, showsChevron: false)
                    .overlay(alignment: .trailing) {
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.footnote)
                            .foregroundStyle(Theme.textTertiary)
                            .padding(.trailing, Theme.Spacing.l)
                            .accessibilityHidden(true)
                    }
            }
            .buttonStyle(RowButtonStyle())
            .accessibilityLabel(L10n.string("media.recordings.month"))
            .accessibilityValue(model.monthTitle)
            .accessibilityIdentifier("recordings-month")
        }
    }

    @ViewBuilder
    private var content: some View {
        if model.page == nil {
            if model.isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .padding(Theme.Spacing.xl)
            } else if let failure = model.failure {
                NoticeCard(symbol: "exclamationmark.circle.fill", tint: Theme.danger, title: failure.message(for: .recording))

                if failure.isRetryable {
                    Button(L10n.string("action.retry")) { Task { await model.load() } }
                        .buttonStyle(SecondaryButtonStyle())
                }
            }
        } else if model.isLoading {
            ProgressView()
                .frame(maxWidth: .infinity)
                .padding(Theme.Spacing.xl)
        } else if model.rows.isEmpty {
            EmptyState(symbol: "waveform", title: L10n.string("media.recordings.empty.title"), message: L10n.string("media.recordings.empty.message"))
                .frame(minHeight: 260)
        } else {
            SettingsGroup(footer: footerText) {
                ForEach(model.rows) { call in
                    RecordingRow(
                        title: model.title(for: call),
                        subtitle: model.subtitle(for: call),
                        symbol: symbol(call),
                        isGone: model.isGone(call),
                        isPlaying: model.nowPlayingId == call.id
                    ) {
                        model.play(call)
                    }
                }
            }
        }
    }

    private var footerText: String {
        var text = L10n.string("media.recordings.footer")

        if model.isTruncated {
            text += "\n" + L10n.string("media.recordings.truncated")
        }

        return text
    }

    private func symbol(_ call: CallItem) -> String {
        switch call.direction {
        case .inbound: return "arrow.down.left"
        case .outbound: return "arrow.up.right"
        case .internal: return "arrow.left.arrow.right"
        case .unknown: return "phone"
        }
    }

    private func startDemo() {
        #if DEBUG
        if MediaDemo.opensRecordings, let first = model.rows.first(where: { $0.hasRecording }) {
            model.play(first)
        }
        #endif
    }
}

private struct RecordingRow: View {
    let title: String
    let subtitle: String
    let symbol: String
    let isGone: Bool
    let isPlaying: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: Theme.Spacing.m) {
                Image(systemName: isPlaying ? "waveform" : "play.circle")
                    .font(.title3)
                    .foregroundStyle(isPlaying ? Theme.accentText : (isGone ? Theme.textTertiary : Theme.textPrimary))
                    .frame(width: 28, height: 28)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(isGone ? Theme.textSecondary : Theme.textPrimary)
                        .adaptiveLineLimit(2)

                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(Theme.textSecondary)
                        .adaptiveLineLimit(2)

                    if isGone {
                        Text(L10n.string("media.unavailable.row"))
                            .font(.caption.weight(.medium))
                            .foregroundStyle(Theme.textSecondary)
                    }
                }

                Spacer(minLength: 0)
            }
            .settingsRowChrome()
        }
        .buttonStyle(RowButtonStyle())
        .disabled(isGone)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue([subtitle, isGone ? L10n.string("media.unavailable.row") : nil].compactMap { $0 }.joined(separator: ", "))
        .accessibilityHint(isGone ? "" : L10n.string("media.row.hint"))
        .accessibilityAddTraits(isPlaying ? [.isSelected] : [])
        .accessibilityIdentifier("media-row")
    }
}
