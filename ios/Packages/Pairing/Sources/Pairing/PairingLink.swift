// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation

/// A pairing token extracted from a QR code, a universal link or a `fsvoip://` deep link.
public struct PairingLink: Equatable, Sendable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    /// `fss_vpair_<43 base64url characters>`
    public let token: String

    public init(token: String) throws {
        guard Self.isWellFormed(token) else {
            throw PairingLinkError.malformedToken
        }

        self.token = token
    }

    // The token is a one-time credential: it never shows up in logs or print-outs.
    public var description: String {
        "PairingLink(•••)"
    }

    public var debugDescription: String {
        description
    }

    public var customMirror: Mirror {
        Mirror(self, children: [], displayStyle: .struct)
    }

    static func isWellFormed(_ token: String) -> Bool {
        guard token.hasPrefix("fss_vpair_"), token.count == 10 + 43 else {
            return false
        }

        return token.dropFirst(10).allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "_" || $0 == "-") }
    }
}

public enum PairingLinkError: Error, Equatable, Sendable {
    /// Not a URL, or not one of ours.
    case notAPairingLink
    /// One of ours, but without a usable token.
    case missingToken
    case malformedToken
}

public enum PairingLinkParser {
    /// Hosts that serve the universal link (the Associated Domains of the app cover `fullstackstudio.nl`).
    public static let universalLinkHosts: Set<String> = ["fullstackstudio.nl", "www.fullstackstudio.nl"]
    public static let universalLinkPath = "/fsvoip/pair"
    public static let customScheme = "fsvoip"

    /// Parse `https://fullstackstudio.nl/fsvoip/pair?t=<token>` or `fsvoip://pair?t=<token>`.
    public static func parse(_ url: URL) throws -> PairingLink {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false), let scheme = components.scheme?.lowercased() else {
            throw PairingLinkError.notAPairingLink
        }

        switch scheme {
        case "https":
            guard let host = components.host?.lowercased(), universalLinkHosts.contains(host), normalizedPath(components.path) == universalLinkPath else {
                throw PairingLinkError.notAPairingLink
            }
        case customScheme:
            // fsvoip://pair?t=...  (host = "pair"); tolerate fsvoip:///pair?t=...
            let target = (components.host ?? "").lowercased() + normalizedPath(components.path)

            guard target == "pair" || target == "/pair" else {
                throw PairingLinkError.notAPairingLink
            }
        default:
            throw PairingLinkError.notAPairingLink
        }

        guard let token = components.queryItems?.first(where: { $0.name == "t" })?.value, !token.isEmpty else {
            throw PairingLinkError.missingToken
        }

        return try PairingLink(token: token)
    }

    /// Parse the text a QR scanner delivers (or a pasted link).
    public static func parse(scanned text: String) throws -> PairingLink {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)

        guard let url = URL(string: trimmed), trimmed.count <= 512 else {
            throw PairingLinkError.notAPairingLink
        }

        return try parse(url)
    }

    private static func normalizedPath(_ path: String) -> String {
        path.count > 1 && path.hasSuffix("/") ? String(path.dropLast()) : path
    }
}
