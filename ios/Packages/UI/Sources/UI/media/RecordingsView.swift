// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

/// "Opnames" (admins): the calls of one month that were recorded; tap one to listen.
struct RecordingsView: View {
    @ObservedObject var hub: MediaHub
    @StateObject private var model: RecordingsModel

    @Environment(\.dismiss) private var dismiss

    init(hub: MediaHub, account: StoredAccount) {
        self.hub = hub
        _model = StateObject(wrappedValue: hub.makeRecordingsModel(account: account))
    }

    var body: some View {
        List {
            if let banner = model.banner {
                Section {
                    MediaNotice(symbol: banner.isError ? "exclamationmark.circle.fill" : "info.circle.fill", tint: banner.isError ? Brand.hangUp : .secondary, text: banner.text)
                        .accessibilityIdentifier("media-banner")
                }
            }

            if model.page != nil {
                monthSection
            }

            content
        }
        .navigationTitle(L10n.string("media.recordings.title"))
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if let nowPlaying = model.nowPlaying {
                AudioPlayerBar(player: hub.player, nowPlaying: nowPlaying, kind: .recording, onClose: model.closePlayer, onRetry: model.retryPlaying)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.22), value: model.nowPlaying)
        .refreshable { await model.load() }
        .task {
            await model.load()
            startDemo()
        }
        .onDisappear { hub.player.stop() }
        .accessibilityIdentifier("recordings-list")
    }

    private var monthSection: some View {
        Section {
            Menu {
                ForEach(model.months, id: \.self) { month in
                    Button {
                        Task { await model.select(month: month) }
                    } label: {
                        if month == model.page?.month { Label(MediaFormat.month(month).capitalized, systemImage: "checkmark") } else { Text(MediaFormat.month(month).capitalized) }
                    }
                }
            } label: {
                HStack {
                    Label(model.monthTitle.capitalized, systemImage: "calendar")
                        .foregroundStyle(.primary)
                    Spacer()
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                }
            }
            .accessibilityLabel(L10n.string("media.recordings.month"))
            .accessibilityValue(model.monthTitle)
            .accessibilityIdentifier("recordings-month")
        } header: {
            Text(L10n.string("media.recordings.month"))
        }
    }

    @ViewBuilder
    private var content: some View {
        if model.page == nil {
            if model.isLoading {
                Section {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                }
            } else if let failure = model.failure {
                Section {
                    MediaNotice(symbol: "exclamationmark.circle.fill", tint: Brand.hangUp, text: failure.message(for: .recording))

                    if failure.isRetryable {
                        Button(L10n.string("action.retry")) { Task { await model.load() } }
                    }
                }
            }
        } else if model.isLoading {
            Section {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
            }
        } else if model.rows.isEmpty {
            Section {
                MediaEmpty(symbol: "waveform", title: L10n.string("media.recordings.empty.title"), message: L10n.string("media.recordings.empty.message"))
                    .listRowBackground(Color.clear)
            }
        } else {
            Section {
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
                    .listRowBackground(model.nowPlayingId == call.id ? Color(.secondarySystemFill) : nil)
                }
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    Text(L10n.string("media.recordings.footer"))

                    if model.isTruncated {
                        Text(L10n.string("media.recordings.truncated"))
                    }
                }
            }
        }
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
            HStack(spacing: 14) {
                ZStack {
                    Image(systemName: isPlaying ? "waveform" : "play.circle")
                        .font(.title3)
                        .symbolRenderingMode(.hierarchical)
                        .foregroundStyle(isGone ? Color.secondary.opacity(0.5) : Color.primary)
                }
                .frame(width: 28, height: 28)
                .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.body.weight(.medium))
                        .foregroundStyle(isGone ? Color.secondary : Color.primary)
                        .lineLimit(1)

                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)

                    if isGone {
                        Text(L10n.string("media.unavailable.row"))
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer(minLength: 0)
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isGone)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue([subtitle, isGone ? L10n.string("media.unavailable.row") : nil].compactMap { $0 }.joined(separator: ", "))
        .accessibilityHint(isGone ? "" : L10n.string("media.row.hint"))
        .accessibilityAddTraits(isPlaying ? [.isSelected] : [])
        .accessibilityIdentifier("media-row")
    }
}
