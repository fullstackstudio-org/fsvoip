// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation
import SwiftUI

enum MediaKind {
    case recording
    case voicemail
}

extension MediaFailure {
    /// The sentence for the user. No server text, no telecom words.
    func message(for kind: MediaKind) -> String {
        switch self {
        case .accessDenied:
            return L10n.string("media.error.accessDenied")
        case .revoked:
            return L10n.string("media.error.revoked")
        case .gone:
            return L10n.string(kind == .recording ? "media.error.gone.recording" : "media.error.gone.voicemail")
        case .notFound:
            return L10n.string("media.error.notFound")
        case let .rateLimited(seconds):
            if let seconds, seconds > 0 {
                return String(format: L10n.string("media.error.rateLimited.seconds"), seconds)
            }

            return L10n.string("media.error.rateLimited")
        case .offline:
            return L10n.string("media.error.offline")
        case .unavailable:
            return L10n.string("media.error.unavailable")
        case .readOnly:
            return L10n.string("media.error.readOnly")
        case .callInProgress:
            return L10n.string("media.error.callInProgress")
        case .unplayable:
            return L10n.string("media.error.unplayable")
        case .other:
            return L10n.string("error.generic")
        }
    }

    /// Trying again can help (as opposed to "gone", "no access" or "unplayable").
    var isRetryable: Bool {
        switch self {
        case .rateLimited, .offline, .unavailable, .callInProgress, .other: return true
        default: return false
        }
    }
}

/// What the player bar shows about the item that plays.
struct NowPlaying: Equatable {
    var id: String
    var title: String
    var subtitle: String
}

struct MediaBanner: Identifiable, Equatable {
    let id = UUID()
    let text: String
    let isError: Bool
}

enum MediaFormat {
    /// `83` → `1:23`, `3725` → `1:02:05`.
    static func clock(_ seconds: TimeInterval) -> String {
        let total = max(0, Int(seconds.rounded(.down)))
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let secs = total % 60

        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, secs)
        }

        return String(format: "%d:%02d", minutes, secs)
    }

    /// `1×`, `1,5×`, `2×` (comma or point by language).
    static func speed(_ speed: PlaybackSpeed, locale: Locale = .current) -> String {
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.maximumFractionDigits = 1
        formatter.minimumFractionDigits = 0

        return (formatter.string(from: NSNumber(value: speed.rawValue)) ?? "\(speed.rawValue)") + "×"
    }

    /// `2026-10` → `oktober 2026`.
    static func month(_ key: String, locale: Locale = .current) -> String {
        let parts = key.split(separator: "-")

        guard parts.count == 2, let year = Int(parts[0]), let month = Int(parts[1]), (1 ... 12).contains(month) else {
            return key
        }

        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = locale
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.calendar = calendar
        formatter.dateFormat = "LLLL yyyy"

        guard let date = calendar.date(from: DateComponents(year: year, month: month, day: 1)) else {
            return key
        }

        return formatter.string(from: date)
    }

    static func daysLeft(_ days: Int?) -> String? {
        guard let days else { return nil }

        switch days {
        case ..<1: return L10n.string("media.daysLeft.today")
        case 1: return L10n.string("media.daysLeft.one")
        default: return String(format: L10n.string("media.daysLeft.many"), days)
        }
    }
}

/// Demo mode only (`-FSVoipDemoScreen voicemail|recordings`): the screen opens by itself and the first item starts playing.
enum MediaDemo {
    static var screen: String? {
        #if DEBUG
        UserDefaults.standard.string(forKey: "FSVoipDemoScreen")
        #else
        nil
        #endif
    }

    static var opensVoicemail: Bool { screen == "voicemail" }
    static var opensRecordings: Bool { screen == "recordings" }
}

// MARK: - Small pieces shared by Voicemail and Opnames

struct MediaNotice: View {
    let symbol: String
    let tint: Color
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol)
                .foregroundStyle(tint)
                .frame(width: 22)
                .accessibilityHidden(true)
            Text(text)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

struct MediaEmpty: View {
    let symbol: String
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: symbol)
                .font(.system(size: 34, weight: .regular))
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text(title)
                .font(.headline)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
        .accessibilityElement(children: .combine)
    }
}
