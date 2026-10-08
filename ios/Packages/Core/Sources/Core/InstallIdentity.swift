// SPDX-License-Identifier: AGPL-3.0-or-later
import Foundation
import Security

/// Identity of THIS installation of the app (not of an account).
///
/// - `installId`: 16 lowercase hex characters. Sent with `POST /pair`, used as the Contact URI marker
///   `fss-dev=<installId>` and in the User-Agent, so the PBX push gate can recognise this phone (plan D4).
/// - `sipInstanceId`: a UUID for the RFC 5626 `+sip.instance` Contact parameter.
///
/// Both are created once and kept in the secret store (Keychain, this device only): a restore on another phone gets a
/// new identity, and pairs again.
public struct InstallIdentity: Equatable, Sendable {
    public let installId: String
    public let sipInstanceId: String

    public init(installId: String, sipInstanceId: String) {
        self.installId = installId
        self.sipInstanceId = sipInstanceId
    }

    static let installIdKey = "install.id"
    static let sipInstanceKey = "install.sip-instance"

    /// Load the identity, creating (and storing) the missing parts. A stored value that is not well formed is replaced.
    public static func load(from store: SecretStore, random: () -> [UInt8] = { secureRandomBytes(8) }) throws -> InstallIdentity {
        var installId = try store.get(installIdKey).flatMap { String(data: $0, encoding: .utf8) }
        var sipInstanceId = try store.get(sipInstanceKey).flatMap { String(data: $0, encoding: .utf8) }

        if installId.map(isValidInstallId) != true {
            let generated = random().map { String(format: "%02x", $0) }.joined()
            installId = generated
            try store.set(Data(generated.utf8), for: installIdKey)
        }

        if sipInstanceId.flatMap(UUID.init(uuidString:)) == nil {
            let generated = UUID().uuidString.lowercased()
            sipInstanceId = generated
            try store.set(Data(generated.utf8), for: sipInstanceKey)
        }

        return InstallIdentity(installId: installId ?? "", sipInstanceId: sipInstanceId ?? "")
    }

    /// The server accepts 8-32 lowercase or uppercase hex characters.
    public static func isValidInstallId(_ value: String) -> Bool {
        (8 ... 32).contains(value.count) && value.allSatisfy { $0.isHexDigit && $0.isASCII }
    }

    public static func secureRandomBytes(_ count: Int) -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: count)

        if SecRandomCopyBytes(kSecRandomDefault, count, &bytes) != errSecSuccess {
            // Extremely unlikely; fall back to the system generator rather than failing the app start.
            var generator = SystemRandomNumberGenerator()
            bytes = (0 ..< count).map { _ in UInt8.random(in: .min ... .max, using: &generator) }
        }

        return bytes
    }
}
