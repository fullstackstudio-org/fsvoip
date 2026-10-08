// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Value types of the SIP engine boundary. They are OURS: nothing in here (or anywhere outside the `linphoneengine`
// module) may mention a type of the SIP stack (plan D3).

package nl.fullstackstudio.fsvoip.sipengine

import java.util.UUID

/** A string that never prints (SIP password). Deliberately not shared with `core`: this module has no dependencies. */
class SipSecret(private val value: String) {
    fun reveal(): String = value

    override fun toString(): String = "SipSecret(•••)"

    override fun equals(other: Any?): Boolean = other is SipSecret && other.value == value

    override fun hashCode(): Int = value.hashCode()
}

enum class SipTransport(val wire: String) {
    UDP("udp"),
    TCP("tcp"),
    TLS("tls"),
}

enum class SrtpMode {
    DISABLED,

    /** Offer SRTP, accept plain RTP. */
    OPTIONAL,

    /** Refuse to talk without SRTP. */
    MANDATORY,
}

enum class AudioCodec(val mime: String) {
    OPUS("opus"),
    G722("g722"),
    PCMA("pcma"),
    PCMU("pcmu"),
}

/** Stable id of a SIP account inside the app (the app pairing id from the API, `account.id`). */
@JvmInline
value class SipAccountId(val raw: String) {
    override fun toString(): String = raw
}

/** Everything the engine needs to register one account. */
data class SipAccountConfig(
    val id: SipAccountId,
    val username: String,
    val password: SipSecret,
    /** SIP domain (registrar domain / realm), e.g. `acme.powervoip.nl`. */
    val domain: String,
    /** Outbound proxy host, e.g. `sip.powervoip.nl`. The engine resolves SRV records for it. */
    val proxy: String,
    val port: Int,
    val transport: SipTransport,
    /** The proxy publishes DNS SRV records: resolve them (failover between the two PBX nodes) instead of using `port`. */
    val useSrv: Boolean = true,
    /** Marker the PBX push gate uses to recognise this installation: Contact URI parameter `fss-dev=<installId>`. */
    val installId: String,
    /** Registration lifetime. 120 s keeps a dead contact short-lived on a sleeping phone. */
    val expiresSeconds: Int = 120,
    val codecs: List<AudioCodec> = listOf(AudioCodec.G722, AudioCodec.PCMA, AudioCodec.PCMU),
    val srtp: SrtpMode = SrtpMode.OPTIONAL,
) {
    /** `sip:<user>@<domain>` */
    val identity: String
        get() = "sip:$username@$domain"

    /** `sip:<proxy>;transport=<t>` with SRV (the port comes from DNS), otherwise `sip:<proxy>:<port>;transport=<t>`. */
    val route: String
        get() = if (useSrv) "sip:$proxy;transport=${transport.wire}" else "sip:$proxy:$port;transport=${transport.wire}"

    /** The Contact URI parameter the PBX push gate looks for. */
    val contactMarker: String
        get() = "fss-dev=$installId"

    override fun toString(): String = "SipAccountConfig(id=$id, domain=$domain, proxy=$proxy, transport=$transport)"
}

sealed interface RegistrationFailure {
    /** Wrong credentials (SIP 401/403 after authentication). */
    data object Authentication : RegistrationFailure

    /** The server could not be reached (network down, DNS, TLS). */
    data object Network : RegistrationFailure

    data class Other(val message: String) : RegistrationFailure
}

sealed interface RegistrationState {
    data object Unregistered : RegistrationState

    data object Registering : RegistrationState

    data object Registered : RegistrationState

    data class Failed(val failure: RegistrationFailure) : RegistrationState
}

@JvmInline
value class CallId(val raw: String = UUID.randomUUID().toString()) {
    override fun toString(): String = raw
}

enum class CallDirection { INCOMING, OUTGOING }

sealed interface CallEndReason {
    /** We hung up. */
    data object LocalHangup : CallEndReason

    /** The other side hung up (or cancelled before we answered). */
    data object RemoteHangup : CallEndReason

    /** We declined an incoming call. */
    data object Declined : CallEndReason

    /** Nobody answered an outgoing call, or an incoming call timed out. */
    data object Unanswered : CallEndReason

    data object Busy : CallEndReason

    data class Failed(val message: String) : CallEndReason
}

/** How an unanswered incoming call is rejected. */
enum class DeclineReason {
    /** 603 Decline. */
    DECLINED,

    /** 486 Busy Here. */
    BUSY,
}

sealed interface CallState {
    /** Incoming call, ringing, not yet answered. */
    data object IncomingRinging : CallState

    /** Outgoing call, INVITE sent. */
    data object OutgoingInitiated : CallState

    /** Outgoing call, the other side is ringing. */
    data object OutgoingRinging : CallState

    /** Answered, media is being set up. */
    data object Connecting : CallState

    data object Active : CallState

    /** We put the call on hold. */
    data object Held : CallState

    /** The other side put us on hold. */
    data object HeldByRemote : CallState

    data class Ended(val reason: CallEndReason) : CallState

    val isEnded: Boolean
        get() = this is Ended
}

/** A DTMF digit (`0-9`, `*`, `#`, `A-D`). */
@JvmInline
value class DtmfDigit private constructor(val character: Char) {
    companion object {
        fun of(character: Char): DtmfDigit? = if (character in "0123456789*#ABCD") DtmfDigit(character) else null
    }
}

/** An incoming call as the engine reports it. */
data class IncomingCall(
    val id: CallId,
    /** Caller number (user part of the SIP address); `null` = anonymous. */
    val from: String?,
    /** Caller name from the SIP display name. */
    val displayName: String?,
    val accountId: SipAccountId,
    /** Value of the `X-FSS-Call` header the PBX sets on the INVITE; equals `callRef` of the push that woke the app. */
    val fssCallRef: String?,
)

/** Snapshot of one call. */
data class CallInfo(
    val id: CallId,
    val direction: CallDirection,
    val accountId: SipAccountId,
    val remoteNumber: String?,
    val remoteName: String?,
    val state: CallState,
    val fssCallRef: String? = null,
)

sealed class SipEngineException(message: String) : Exception(message) {
    class NotStarted : SipEngineException("The SIP engine is not started")

    class UnknownAccount(val account: SipAccountId) : SipEngineException("Unknown account $account")

    class UnknownCall(val call: CallId) : SipEngineException("Unknown call $call")

    class InvalidNumber : SipEngineException("Invalid number")

    class InvalidState(detail: String) : SipEngineException(detail)

    class Engine(detail: String) : SipEngineException(detail)
}
