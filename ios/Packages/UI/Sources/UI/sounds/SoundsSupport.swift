// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import SwiftUI

extension SoundFailure {
    /// The sentence for the user. No server text, no telecom words.
    var message: String {
        switch self {
        case .invalidAudio: return L10n.string("sounds.error.invalidAudio")
        case .tooLarge: return L10n.string("sounds.error.tooLarge")
        case .tooMany: return L10n.string("sounds.error.tooMany")
        case let .inUse(places): return SoundPlaces.inUseMessage(places)
        case let .rateLimited(seconds):
            if let seconds, seconds > 0 { return String(format: L10n.string("sounds.error.rateLimited.seconds"), seconds) }

            return L10n.string("sounds.error.rateLimited")
        case .accessDenied: return L10n.string("media.error.accessDenied")
        case .revoked: return L10n.string("media.error.revoked")
        case .readOnly: return L10n.string("media.error.readOnly")
        case .notFound: return L10n.string("sounds.error.notFound")
        case .offline: return L10n.string("media.error.offline")
        case .unavailable: return L10n.string("media.error.unavailable")
        case .other: return L10n.string("error.generic")
        }
    }

    var isRetryable: Bool {
        switch self {
        case .rateLimited, .offline, .unavailable, .other: return true
        default: return false
        }
    }
}

extension RecorderFailure {
    var message: String {
        switch self {
        case .permissionDenied: return L10n.string("sounds.record.error.permission")
        case .callInProgress: return L10n.string("sounds.record.error.call")
        case .tooShort: return L10n.string("sounds.record.error.tooShort")
        case .couldNotStart: return L10n.string("sounds.record.error.start")
        }
    }
}

enum SoundPlaces {
    /// "Welkomstbericht (Voorbeeld BV)": what the place is, then its name.
    static func label(_ place: APIErrorPlace) -> String {
        let key = "sounds.place." + place.kind
        let kind = L10n.string(key)

        guard kind != key else { return place.name }

        return place.name.isEmpty ? kind : "\(kind) (\(place.name))"
    }

    static func label(_ use: SoundUse) -> String {
        label(APIErrorPlace(kind: use.kind, name: use.name))
    }

    static func inUseMessage(_ places: [APIErrorPlace]) -> String {
        guard !places.isEmpty else { return L10n.string("sounds.error.inUse.unknown") }

        return String(format: L10n.string("sounds.error.inUse"), places.map(label).joined(separator: ", "))
    }
}

/// When "Toepassen" is possible in the audio picker.
enum SoundSelection {
    /// Something other than what is set now, nothing is being added, and a choice (or "Geen" where that is allowed).
    static func canApply(selection: String?, current: String?, allowsNone: Bool, isUploading: Bool) -> Bool {
        selection != current && !isUploading && (selection != nil || allowsNone)
    }
}

// MARK: - Environment

private struct SoundsModelKey: EnvironmentKey {
    static let defaultValue: SoundsModel? = nil
}

extension EnvironmentValues {
    /// The sounds of the open account, for the audio picker of the number screens. `nil` for anyone who may not manage sounds.
    var soundsModel: SoundsModel? {
        get { self[SoundsModelKey.self] }
        set { self[SoundsModelKey.self] = newValue }
    }
}

// MARK: - Pieces

struct SoundDurationChip: View {
    let seconds: TimeInterval?

    var body: some View {
        if let seconds {
            Text(MediaFormat.clock(seconds))
                .font(.caption.monospacedDigit())
                .foregroundStyle(Theme.textSecondary)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(Theme.separator, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                .accessibilityHidden(true)
        }
    }
}

/// Play/pause and a position bar for the sound that is open: under the row of the picker and of "Geluiden".
struct SoundInlinePlayer: View {
    @ObservedObject var player: AudioStreamer
    let id: String
    let onToggle: () -> Void

    @State private var dragPosition: Double?

    private var isCurrent: Bool { player.currentId == id }
    private var isPlaying: Bool { isCurrent && player.state == .playing }
    private var isLoading: Bool { isCurrent && player.state == .loading }

    var body: some View {
        HStack(spacing: Theme.Spacing.m) {
            Button(action: onToggle) {
                ZStack {
                    Circle().fill(Theme.accent)

                    if isLoading {
                        ProgressView().tint(Theme.onAccent)
                    } else {
                        Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                            .font(.body.weight(.bold))
                            .foregroundStyle(Theme.onAccent)
                            .offset(x: isPlaying ? 0 : 1.5)
                    }
                }
                .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L10n.string(isPlaying ? "media.player.pause" : "media.player.play"))
            .accessibilityIdentifier("sound-play")

            if isCurrent, let duration = player.duration, duration > 0 {
                Slider(
                    value: Binding(get: { dragPosition ?? player.position }, set: { dragPosition = $0 }),
                    in: 0 ... duration,
                    onEditingChanged: { editing in
                        if !editing, let value = dragPosition {
                            player.seek(to: value)
                            dragPosition = nil
                        }
                    }
                )
                .tint(Theme.accent)
                .accessibilityLabel(L10n.string("media.player.position"))
                .accessibilityValue(String(format: L10n.string("media.player.position.value"), MediaFormat.clock(player.position), MediaFormat.clock(duration)))

                Text("-" + MediaFormat.clock(max(0, duration - player.position)))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
                    .accessibilityHidden(true)
            } else if isCurrent, case let .failed(failure) = player.state {
                Text(failure.message(for: .recording))
                    .font(.footnote)
                    .foregroundStyle(Theme.danger)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, Theme.Spacing.l)
        .padding(.bottom, Theme.Spacing.m)
    }
}

/// An inline notice (an error or a hint) for the top of a list.
struct SoundNotice: View {
    let banner: MediaBanner

    var body: some View {
        NoticeCard(symbol: banner.isError ? "exclamationmark.circle.fill" : "info.circle.fill", tint: banner.isError ? Theme.danger : Theme.textSecondary, title: banner.text)
            .accessibilityIdentifier("sound-banner")
    }
}

/// The progress of an upload with a way out.
struct SoundUploadBar: View {
    let upload: SoundUploadState
    let onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.s) {
            HStack {
                Text(String(format: L10n.string("sounds.upload.progress"), upload.name))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.textPrimary)
                    .lineLimit(2)

                Spacer(minLength: Theme.Spacing.s)

                Button(L10n.string("action.cancel"), action: onCancel)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.accentText)
                    .accessibilityIdentifier("sound-upload-cancel")
            }

            ProgressView(value: upload.fraction)
                .tint(Theme.accent)
                .accessibilityLabel(L10n.string("sounds.upload.label"))
                .accessibilityValue(String(format: "%d%%", Int((upload.fraction * 100).rounded())))
        }
        .padding(Theme.Spacing.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.raised, in: Theme.card())
        .padding(.bottom, Theme.Spacing.m)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sound-upload")
    }
}
