// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.callcontroller

import nl.fullstackstudio.fsvoip.core.ServerTransport
import nl.fullstackstudio.fsvoip.core.SipCredentials
import nl.fullstackstudio.fsvoip.core.StoredAccount
import nl.fullstackstudio.fsvoip.sipengine.AudioCodec
import nl.fullstackstudio.fsvoip.sipengine.SipAccountConfig
import nl.fullstackstudio.fsvoip.sipengine.SipAccountId
import nl.fullstackstudio.fsvoip.sipengine.SipSecret
import nl.fullstackstudio.fsvoip.sipengine.SipTransport
import nl.fullstackstudio.fsvoip.sipengine.SrtpMode

/** Turns a paired account into what the SIP engine registers (plan D4), exactly as on iOS. */
object SipAccountMapping {
    /**
     * - TLS when the server says `tls` (SRTP offered), otherwise TCP. A server answer of `udp` is also registered over
     *   TCP: mobile NAT drops idle UDP mappings within seconds, TCP does not (D4).
     * - Domain = the tenant domain, outbound proxy = `sip.powervoip.nl` resolved via DNS SRV when the server says so.
     * - Contact marker `fss-dev=<installId>` and `Expires: 120`.
     */
    fun config(account: StoredAccount, codecs: List<AudioCodec> = listOf(AudioCodec.G722, AudioCodec.PCMA, AudioCodec.PCMU)): SipAccountConfig {
        val transport = transport(account.sip.transport)

        return SipAccountConfig(
            id = SipAccountId(account.id),
            username = account.sip.username,
            password = SipSecret(account.sip.password.reveal()),
            domain = account.sip.domain,
            proxy = account.sip.proxy,
            port = port(account.sip),
            transport = transport,
            useSrv = account.sip.srv,
            installId = account.installId,
            expiresSeconds = 120,
            codecs = codecs,
            srtp = if (transport == SipTransport.TLS) SrtpMode.OPTIONAL else SrtpMode.DISABLED,
        )
    }

    fun transport(server: ServerTransport): SipTransport = when (server) {
        ServerTransport.TLS -> SipTransport.TLS
        ServerTransport.TCP, ServerTransport.UDP -> SipTransport.TCP
    }

    /** The server's port, with sane defaults if it sent something unusable. */
    fun port(sip: SipCredentials): Int = if (sip.port in 1..65535) sip.port else if (sip.transport == ServerTransport.TLS) 5061 else 5060
}
