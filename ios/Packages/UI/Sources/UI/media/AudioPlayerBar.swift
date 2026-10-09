// SPDX-License-Identifier: AGPL-3.0-or-later
import AVKit
import Core
import SwiftUI

/// The player at the bottom of Voicemail and Opnames: what plays, a scrubber, play/pause, ten seconds back and forward, the
/// speed (1×, 1,5×, 2×) and the output (speaker, earpiece, headphones). It is one component for both screens.
struct AudioPlayerBar: View {
    @ObservedObject var player: AudioStreamer
    let nowPlaying: NowPlaying
    let kind: MediaKind
    let onClose: () -> Void
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            header

            if case let .failed(failure) = player.state {
                failureView(failure)
            } else {
                Scrubber(position: player.position, duration: player.duration, isLoading: player.state == .loading) { player.seek(to: $0) }
                    .padding(.top, 2)
                times
                controls
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 14)
        .padding(.bottom, 10)
        .background {
            UnevenRoundedRectangle(topLeadingRadius: 22, topTrailingRadius: 22, style: .continuous)
                .fill(.regularMaterial)
                .shadow(color: .black.opacity(0.12), radius: 12, y: -2)
                .ignoresSafeArea(edges: .bottom)
        }
        .overlay(alignment: .top) {
            Capsule()
                .fill(Color.secondary.opacity(0.35))
                .frame(width: 36, height: 4)
                .padding(.top, 5)
                .accessibilityHidden(true)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("audio-player")
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(nowPlaying.title)
                    .font(.headline)
                    .lineLimit(1)
                Text(nowPlaying.subtitle)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .accessibilityElement(children: .combine)

            Spacer(minLength: 8)

            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(.secondary)
                    .frame(width: 32, height: 32)
                    .background(Color(.secondarySystemFill), in: Circle())
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(L10n.string("action.close"))
            .accessibilityIdentifier("player-close")
        }
        .padding(.top, 8)
    }

    private var times: some View {
        HStack {
            Text(MediaFormat.clock(player.position))
            Spacer()

            if let duration = player.duration {
                Text("-" + MediaFormat.clock(max(0, duration - player.position)))
            }
        }
        .font(.caption.monospacedDigit())
        .foregroundStyle(.secondary)
        .accessibilityHidden(true)
    }

    private var controls: some View {
        HStack(spacing: 0) {
            Button {
                player.cycleSpeed()
            } label: {
                Text(MediaFormat.speed(player.speed))
                    .font(.subheadline.weight(.semibold).monospacedDigit())
                    .frame(minWidth: 52, minHeight: 36)
                    .background(Color(.secondarySystemFill), in: Capsule())
            }
            .buttonStyle(.plain)
            .frame(minWidth: 64, minHeight: 44, alignment: .leading)
            .accessibilityLabel(L10n.string("media.player.speed"))
            .accessibilityValue(MediaFormat.speed(player.speed))
            .accessibilityIdentifier("player-speed")

            Spacer(minLength: 4)

            Button {
                player.skip(by: -10)
            } label: {
                Image(systemName: "gobackward.10")
                    .font(.title2)
                    .frame(width: 48, height: 48)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L10n.string("media.player.back"))
            .disabled(player.duration == nil)

            Button {
                player.togglePlayPause()
            } label: {
                ZStack {
                    Circle().fill(Color.primary)

                    if player.state == .loading {
                        ProgressView()
                            .tint(Color(.systemBackground))
                    } else {
                        Image(systemName: player.state == .playing ? "pause.fill" : "play.fill")
                            .font(.title2)
                            .foregroundStyle(Color(.systemBackground))
                            .offset(x: player.state == .playing ? 0 : 2)
                    }
                }
                .frame(width: 58, height: 58)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L10n.string(player.state == .playing ? "media.player.pause" : "media.player.play"))
            .accessibilityIdentifier("player-play")
            .disabled(player.state == .loading)

            Button {
                player.skip(by: 10)
            } label: {
                Image(systemName: "goforward.10")
                    .font(.title2)
                    .frame(width: 48, height: 48)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L10n.string("media.player.forward"))
            .disabled(player.duration == nil)

            Spacer(minLength: 4)

            RoutePicker()
                .frame(width: 44, height: 44)
                .frame(minWidth: 64, alignment: .trailing)
                .accessibilityLabel(L10n.string("media.player.route"))
        }
        .foregroundStyle(.primary)
    }

    private func failureView(_ failure: MediaFailure) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: failure == .gone ? "clock.badge.xmark" : "exclamationmark.circle.fill")
                .foregroundStyle(failure == .gone ? Color.secondary : Brand.hangUp)
                .frame(width: 22)
                .accessibilityHidden(true)

            Text(failure.message(for: kind))
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)

            if failure.isRetryable {
                Button(L10n.string("action.retry"), action: onRetry)
                    .font(.subheadline.weight(.semibold))
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("player-retry")
            }
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("player-failure")
    }
}

// MARK: - Scrubber

/// A thick capsule with a knob: drag anywhere on it. 44 points high to touch, 6 to see. VoiceOver gets a slider that moves in
/// steps of ten seconds.
private struct Scrubber: View {
    let position: TimeInterval
    let duration: TimeInterval?
    let isLoading: Bool
    let onSeek: (TimeInterval) -> Void

    @State private var dragFraction: Double?

    private var fraction: Double {
        if let dragFraction { return dragFraction }

        guard let duration, duration > 0 else { return 0 }

        return min(1, max(0, position / duration))
    }

    var body: some View {
        GeometryReader { proxy in
            let width = proxy.size.width
            let knob: CGFloat = dragFraction == nil ? 14 : 20

            ZStack(alignment: .leading) {
                Capsule()
                    .fill(Color.secondary.opacity(0.25))
                    .frame(height: 6)

                Capsule()
                    .fill(Color.primary)
                    .frame(width: max(6, width * fraction), height: 6)

                Circle()
                    .fill(Color.primary)
                    .frame(width: knob, height: knob)
                    .shadow(color: .black.opacity(0.2), radius: 2, y: 1)
                    .offset(x: min(max(0, width * fraction - knob / 2), width - knob))
                    .opacity(duration == nil ? 0 : 1)
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        guard duration != nil, width > 0 else { return }

                        dragFraction = min(1, max(0, value.location.x / width))
                    }
                    .onEnded { value in
                        defer { dragFraction = nil }

                        guard let duration, width > 0 else { return }

                        onSeek(min(1, max(0, value.location.x / width)) * duration)
                    }
            )
            .animation(.easeOut(duration: 0.12), value: dragFraction == nil)
        }
        .frame(height: 32)
        .opacity(isLoading ? 0.5 : 1)
        .accessibilityElement()
        .accessibilityLabel(L10n.string("media.player.position"))
        .accessibilityValue(accessibilityValue)
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: onSeek(position + 10)
            case .decrement: onSeek(position - 10)
            @unknown default: break
            }
        }
        .accessibilityIdentifier("player-scrubber")
    }

    private var accessibilityValue: String {
        guard let duration else { return MediaFormat.clock(position) }

        return String(format: L10n.string("media.player.position.value"), MediaFormat.clock(position), MediaFormat.clock(duration))
    }
}

// MARK: - Output

/// The system's output picker (speaker, earpiece, headphones, AirPlay).
private struct RoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.activeTintColor = UIColor.label
        view.tintColor = UIColor.label
        view.prioritizesVideoDevices = false

        return view
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}
