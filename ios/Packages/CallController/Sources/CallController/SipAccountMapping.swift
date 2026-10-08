// SPDX-License-Identifier: AGPL-3.0-or-later
import Core
import Foundation
import SipEngine

/// Turns a paired account into what the SIP engine registers (plan D4).
public enum SipAccountMapping {
    /// - TLS when the server says `tls` (port 5061, SRTP offered), otherwise TCP. A server answer of `udp` is also
    ///   registered over TCP: mobile NAT drops idle UDP mappings within seconds, TCP does not (D4). The PBX serves
    ///   UDP and TCP on the same port.
    /// - Domain = the tenant domain (`<slug>.powervoip.nl` or a legacy `<slug>.pbx.fullstackstudio.nl`), outbound
    ///   proxy = `sip.powervoip.nl` resolved via DNS SRV when the server says so (failover between the PBX nodes).
    /// - Contact marker `fss-dev=<installId>` and `Expires: 120`.
    public static func config(for account: StoredAccount, codecs: [AudioCodec] = [.g722, .pcma, .pcmu]) -> SipAccountConfig {
        let transport = transport(for: account.sip.transport)

        return SipAccountConfig(
            id: SipAccountID(account.id),
            username: account.sip.username,
            password: SipSecret(account.sip.password.reveal()),
            domain: account.sip.domain,
            proxy: account.sip.proxy,
            port: port(for: account.sip),
            transport: transport,
            useSRV: account.sip.srv,
            installId: account.installId,
            expiresSeconds: 120,
            codecs: codecs,
            srtp: transport == .tls ? .optional : .disabled
        )
    }

    public static func transport(for server: ServerTransport) -> SipTransport {
        switch server {
        case .tls:
            return .tls
        case .tcp, .udp:
            return .tcp
        }
    }

    /// The server's port, with sane defaults if it sent something unusable.
    static func port(for sip: SIPCredentials) -> Int {
        if (1 ... 65535).contains(sip.port) {
            return sip.port
        }

        return sip.transport == .tls ? 5061 : 5060
    }
}
