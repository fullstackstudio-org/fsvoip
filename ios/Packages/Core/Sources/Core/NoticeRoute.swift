// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Where a tap on a `notice` notification goes. The server sends an absolute portal link (`href`); the app never opens it. It reads the
// PATH only, and maps the paths it has a screen for. Everything else opens the home screen (the dialler).
//
//   /portal/voip/<pbx>/calls       → Geschiedenis (recents)
//   /portal/voip/<pbx>/voicemail   → Voicemail
//   /portal/contacts[/…]           → Contacten
//   /portal/content/<p>/tickets…   → no ticket screen in the app: home (the portal has it)

import Foundation

public enum NoticeDestination: Equatable, Sendable {
    case home
    case recents
    case voicemail
    case contacts
}

public enum NoticeRoute {
    /// - Returns: `.home` for anything unknown, empty or malformed. Never throws and never returns a URL.
    public static func destination(forHref href: String) -> NoticeDestination {
        guard let path = segments(of: href), path.first == "portal" else {
            return .home
        }

        let rest = Array(path.dropFirst())

        switch rest.first {
        case "voip":
            // /portal/voip/<pbx>/<section>
            guard rest.count >= 3 else {
                return .home
            }

            switch rest[2] {
            case "calls": return .recents
            case "voicemail": return .voicemail
            default: return .home
            }
        case "contacts":
            return .contacts
        default:
            return .home
        }
    }

    /// The decoded path segments of an absolute URL or a bare path; `nil` when it is not a plain path (control characters,
    /// `.` / `..` segments, an encoded slash).
    static func segments(of href: String) -> [String]? {
        let trimmed = href.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !trimmed.isEmpty, trimmed.count <= 500, !trimmed.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            return nil
        }

        let rawPath: String

        if trimmed.hasPrefix("/") {
            rawPath = trimmed
        } else if let components = URLComponents(string: trimmed), let scheme = components.scheme?.lowercased(), scheme == "https" || scheme == "http" {
            rawPath = components.percentEncodedPath
        } else {
            return nil
        }

        let withoutQuery = rawPath.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? rawPath
        let path = withoutQuery.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? withoutQuery
        var result: [String] = []

        for piece in path.split(separator: "/", omittingEmptySubsequences: true) {
            guard let decoded = String(piece).removingPercentEncoding, !decoded.contains("/"), decoded != ".", decoded != ".." else {
                return nil
            }

            result.append(decoded.lowercased())
        }

        return result
    }
}
