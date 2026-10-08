// SPDX-License-Identifier: AGPL-3.0-or-later
//
// `SipEngine` on top of linphone-sdk (liblinphone), the Android twin of the iOS `LinphoneSipEngine`. THE ONLY FILE(S)
// in the repository that may import `org.linphone.*` (enforced by scripts/check-imports.sh).
//
// Telecom and audio (plan D3/D16): the SDK has no Telecom integration of its own; the self-managed ConnectionService is
// ours (`callcontroller`). The SDK does not ring either: ringing is ours too (the call may be on the screen from a push
// before its INVITE exists), so the SDK's ringtone and vibration are off.
// linphone's push model (`pushNotificationEnabled = false`, no `pn-*` parameters) stays off: that model needs a
// Flexisip push gateway in front of the PBX; FSVoip uses the PBX push gate + FCM instead.
//
// TLS: the server certificate is verified (chain and name) against the root CAs shipped in the SDK (`rootca.pem`,
// includes ISRG Root X1/X2 for the Let's Encrypt certificate of `*.powervoip.nl`).
//
// No configuration file: the core runs without a config path, so liblinphone never writes the SIP password (auth info)
// to disk. The password lives in the Keystore-encrypted store only and is handed to the core at registration.
//
// Threading: liblinphone is not thread safe. Create the engine and call every method on the main thread; the core
// iterates there and the listener callbacks arrive there.

package nl.fullstackstudio.fsvoip.linphoneengine

import android.content.Context
import nl.fullstackstudio.fsvoip.sipengine.AudioCodec
import nl.fullstackstudio.fsvoip.sipengine.CallDirection
import nl.fullstackstudio.fsvoip.sipengine.CallEndReason
import nl.fullstackstudio.fsvoip.sipengine.CallId
import nl.fullstackstudio.fsvoip.sipengine.CallInfo
import nl.fullstackstudio.fsvoip.sipengine.CallState
import nl.fullstackstudio.fsvoip.sipengine.DeclineReason
import nl.fullstackstudio.fsvoip.sipengine.DtmfDigit
import nl.fullstackstudio.fsvoip.sipengine.IncomingCall
import nl.fullstackstudio.fsvoip.sipengine.RegistrationFailure
import nl.fullstackstudio.fsvoip.sipengine.RegistrationState
import nl.fullstackstudio.fsvoip.sipengine.SipAccountConfig
import nl.fullstackstudio.fsvoip.sipengine.SipAccountId
import nl.fullstackstudio.fsvoip.sipengine.SipAudioControl
import nl.fullstackstudio.fsvoip.sipengine.SipEngine
import nl.fullstackstudio.fsvoip.sipengine.SipEngineException
import nl.fullstackstudio.fsvoip.sipengine.SipEngineListener
import nl.fullstackstudio.fsvoip.sipengine.SipTransport
import nl.fullstackstudio.fsvoip.sipengine.SrtpMode
import org.linphone.core.Account
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.LogLevel
import org.linphone.core.MediaEncryption
import org.linphone.core.Reason
import org.linphone.core.TransportType
import org.linphone.core.RegistrationState as LinphoneRegistrationState

class LinphoneSipEngine(
    context: Context,
    private val appName: String = "FSVoip",
    private val appVersion: String = "0",
    /** Identity of this installation; the User-Agent becomes `FSVoip/<version> (<installId>)` (plan D4). */
    private val installId: String? = null,
) : SipEngine {
    private val context = context.applicationContext

    override var listener: SipEngineListener? = null
    override val audio: SipAudioControl = object : SipAudioControl {
        // Android: the SDK starts call audio itself when the call's media starts; Telecom owns focus and routing.
        override fun configure() = Unit
        override fun activate(active: Boolean) = Unit
    }

    private var core: Core? = null
    private var coreListener: CoreListenerStub? = null
    private val accounts = mutableMapOf<SipAccountId, Account>()
    private val accountConfigs = mutableMapOf<SipAccountId, SipAccountConfig>()
    private val callsById = mutableMapOf<CallId, Call>()
    private val idsByCall = mutableMapOf<Call, CallId>()

    /** Why a call ended when WE ended it (otherwise it is a remote hang-up). */
    private val localEndReasons = mutableMapOf<CallId, CallEndReason>()
    private val answered = mutableSetOf<CallId>()
    private val callAccounts = mutableMapOf<CallId, SipAccountId>()

    /** Accounts whose registration is switched off (the app is in the background without a call). */
    private val registrationDisabled = mutableSetOf<SipAccountId>()

    /**
     * The app was (or is) in the background: Doze may have frozen the process, so the SIP connections can be dead
     * without the stack knowing (NAT state gone, no FIN/RST). A fresh registration then starts on a new connection.
     */
    private var connectionsMayBeStale = false

    // MARK: Lifecycle

    override fun start() {
        if (core != null) {
            return
        }

        try {
            val factory = Factory.instance()
            // Warnings and errors only: SIP traces would put digest responses and phone numbers in logcat.
            factory.loggingService.setLogLevel(LogLevel.Warning)

            val core = factory.createCore(null, null, context)
            core.isPushNotificationEnabled = false
            core.setUserAgent(appName, installId?.let { "$appVersion ($it)" } ?: appVersion)
            core.maxCalls = 2
            core.isIpv6Enabled = true
            core.isKeepAliveEnabled = true
            core.verifyServerCertificates(true)
            core.verifyServerCn(true)
            // Ringing is ours (see the header).
            core.setRing(null)
            core.isNativeRingingEnabled = false
            core.setVibrationOnIncomingCallEnabled(false)

            val listener = object : CoreListenerStub() {
                override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
                    handleCall(call, state ?: Call.State.Idle, message)
                }

                override fun onAccountRegistrationStateChanged(core: Core, account: Account, state: LinphoneRegistrationState?, message: String) {
                    handleAccount(account, state ?: LinphoneRegistrationState.None, message)
                }
            }
            core.addListener(listener)
            coreListener = listener
            this.core = core

            if (core.start() != 0) {
                throw SipEngineException.Engine("core.start failed")
            }
        } catch (error: SipEngineException) {
            core = null
            coreListener = null
            throw error
        } catch (error: Throwable) {
            core = null
            coreListener = null
            throw SipEngineException.Engine(error.javaClass.simpleName)
        }
    }

    override fun stop() {
        core?.let { core ->
            coreListener?.let(core::removeListener)
            core.stop()
        }
        core = null
        coreListener = null
        accounts.clear()
        accountConfigs.clear()
        registrationDisabled.clear()
        connectionsMayBeStale = false
        callsById.clear()
        idsByCall.clear()
        localEndReasons.clear()
        answered.clear()
        callAccounts.clear()
    }

    override fun enterBackground() {
        connectionsMayBeStale = true
        core?.enterBackground()
    }

    override fun enterForeground() {
        val core = core ?: return
        core.enterForeground()
        reconnectIfStale(core)
    }

    override fun refreshRegistrations() {
        core?.refreshRegisters()
    }

    /** Close every SIP connection and open new ones (the enabled accounts re-register). Never during a call. */
    private fun reconnectIfStale(core: Core) {
        if (!connectionsMayBeStale || core.callsNb > 0) {
            return
        }

        connectionsMayBeStale = false
        core.setNetworkReachable(false)
        core.setNetworkReachable(true)
    }

    // MARK: Accounts

    override fun register(account: SipAccountConfig) {
        val core = requireCore()

        // Re-registering an account replaces it.
        unregister(account.id)

        try {
            val factory = Factory.instance()
            val params = core.createAccountParams()

            check(params.setIdentityAddress(factory.createAddress(account.identity)) == 0) { "identity" }
            check(params.setServerAddress(factory.createAddress("sip:${account.domain}")) == 0) { "server" }
            check(params.setRoutesAddresses(arrayOf(factory.createAddress(account.route))) == 0) { "route" }
            params.transport = transport(account.transport)
            params.isRegisterEnabled = account.id !in registrationDisabled
            params.expires = account.expiresSeconds
            params.isPublishEnabled = false
            // The marker the PBX push gate looks for in the stored contact (`;fss-dev=<installId>`).
            params.contactUriParameters = account.contactMarker
            params.pushNotificationAllowed = false
            params.remotePushNotificationAllowed = false

            val auth = factory.createAuthInfo(account.username, null, account.password.reveal(), null, account.domain, account.domain)
            core.addAuthInfo(auth)

            val created = core.createAccount(params)
            check(core.addAccount(created) == 0) { "addAccount" }

            accounts[account.id] = created
            accountConfigs[account.id] = account

            applyMedia(account, core)
        } catch (error: SipEngineException) {
            throw error
        } catch (error: Throwable) {
            // Never the message of a Throwable: it could quote the address with credentials.
            throw SipEngineException.Engine("register failed: ${error.javaClass.simpleName}")
        }
    }

    override fun unregister(account: SipAccountId) {
        val core = core ?: return
        val existing = accounts.remove(account) ?: return
        core.removeAccount(existing)
        accountConfigs.remove(account)
    }

    override fun setRegistrationEnabled(enabled: Boolean, account: SipAccountId) {
        if (enabled) registrationDisabled.remove(account) else registrationDisabled.add(account)

        val existing = accounts[account] ?: return
        val current = existing.params

        if (current.isRegisterEnabled == enabled) {
            return
        }

        // Switching it off sends a REGISTER with `Expires: 0`; switching it on registers again.
        val params = current.clone()
        params.isRegisterEnabled = enabled
        existing.setParams(params)
    }

    override fun refreshRegistration(account: SipAccountId) {
        val core = core ?: return
        val existing = accounts[account] ?: return
        reconnectIfStale(core)
        existing.refreshRegister()
    }

    override fun registrationState(account: SipAccountId): RegistrationState =
        accounts[account]?.let { registrationState(it.state, null) } ?: RegistrationState.Unregistered

    // MARK: Calls

    override fun call(number: String, account: SipAccountId): CallId {
        val core = requireCore()
        val existing = accounts[account] ?: throw SipEngineException.UnknownAccount(account)
        val config = accountConfigs[account] ?: throw SipEngineException.UnknownAccount(account)

        if (!isDialable(number)) {
            throw SipEngineException.InvalidNumber()
        }

        val address = Factory.instance().createAddress("sip:$number@${config.domain}") ?: throw SipEngineException.InvalidNumber()
        val params = core.createCallParams(null) ?: throw SipEngineException.Engine("no call params")
        params.account = existing
        params.isVideoEnabled = false

        val call = core.inviteAddressWithParams(address, params) ?: throw SipEngineException.Engine("The call could not be started")
        val id = track(call)
        callAccounts[id] = account

        return id
    }

    override fun answer(call: CallId) {
        val linphoneCall = requireCall(call)
        val params = requireCore().createCallParams(linphoneCall) ?: throw SipEngineException.Engine("no call params")
        params.isVideoEnabled = false

        if (linphoneCall.acceptWithParams(params) != 0) {
            throw SipEngineException.Engine("accept failed")
        }

        answered += call
    }

    override fun decline(call: CallId, reason: DeclineReason) {
        val linphoneCall = requireCall(call)
        localEndReasons[call] = CallEndReason.Declined

        // 603 Decline or 486 Busy Here.
        if (linphoneCall.decline(if (reason == DeclineReason.BUSY) Reason.Busy else Reason.Declined) != 0) {
            throw SipEngineException.Engine("decline failed")
        }
    }

    override fun hangup(call: CallId) {
        val linphoneCall = requireCall(call)
        localEndReasons[call] = CallEndReason.LocalHangup

        if (linphoneCall.terminate() != 0) {
            throw SipEngineException.Engine("terminate failed")
        }
    }

    override fun setHold(call: CallId, onHold: Boolean) {
        val linphoneCall = requireCall(call)
        val result = if (onHold) linphoneCall.pause() else linphoneCall.resume()

        if (result != 0) {
            throw SipEngineException.Engine("hold failed")
        }
    }

    override fun setMuted(muted: Boolean) {
        core?.setMicEnabled(!muted)
    }

    override fun sendDtmf(digit: DtmfDigit, call: CallId) {
        if (requireCall(call).sendDtmf(digit.character) != 0) {
            throw SipEngineException.Engine("dtmf failed")
        }
    }

    override fun transfer(call: CallId, number: String) {
        val linphoneCall = requireCall(call)

        if (!isDialable(number)) {
            throw SipEngineException.InvalidNumber()
        }

        val accountId = callAccounts[call] ?: throw SipEngineException.InvalidState("No account to transfer from")
        val config = accountConfigs[accountId] ?: throw SipEngineException.InvalidState("No account to transfer from")

        val target = Factory.instance().createAddress("sip:$number@${config.domain}") ?: throw SipEngineException.InvalidNumber()

        if (linphoneCall.transferTo(target) != 0) {
            throw SipEngineException.Engine("transfer failed")
        }
    }

    override fun calls(): List<CallInfo> = core?.calls.orEmpty().mapNotNull { call ->
        idsByCall[call]?.let { id -> info(call, id, callState(call.state, null)) }
    }

    // MARK: Event handling

    private fun handleAccount(account: Account, state: LinphoneRegistrationState, message: String) {
        val id = accounts.entries.firstOrNull { it.value == account }?.key ?: return
        listener?.onRegistrationChanged(this, registrationState(state, message), id)
    }

    private fun handleCall(call: Call, state: Call.State, message: String) {
        val id = track(call)

        when (state) {
            Call.State.IncomingReceived -> {
                val accountId = accountId(call, id)

                if (accountId == null) {
                    // Not for one of our accounts: do not let it ring into the void.
                    localEndReasons[id] = CallEndReason.Declined
                    call.decline(Reason.NotFound)
                    return
                }

                listener?.onIncomingCall(
                    this,
                    IncomingCall(
                        id = id,
                        from = call.remoteAddress.username,
                        displayName = nonEmpty(call.remoteAddress.displayName),
                        accountId = accountId,
                        fssCallRef = nonEmpty(call.remoteParams?.getCustomHeader(FSS_CALL_HEADER)),
                    ),
                )
            }
            Call.State.End, Call.State.Released, Call.State.Error -> {
                if (!callsById.containsKey(id)) {
                    return // Already reported (End is followed by Released).
                }

                val reason = endReason(id, call, state, message)
                info(call, id, CallState.Ended(reason))?.let { listener?.onCallChanged(this, it) }
                forget(call, id)
            }
            else -> info(call, id, callState(state, null))?.let { listener?.onCallChanged(this, it) }
        }
    }

    private fun endReason(id: CallId, call: Call, state: Call.State, message: String): CallEndReason {
        localEndReasons[id]?.let { return it }

        if (state == Call.State.Error) {
            return if (call.reason == Reason.Busy) CallEndReason.Busy else CallEndReason.Failed(message)
        }

        if (call.dir == Call.Dir.Incoming && id !in answered) {
            return CallEndReason.Unanswered
        }

        return if (call.reason == Reason.Busy) CallEndReason.Busy else CallEndReason.RemoteHangup
    }

    // MARK: Helpers

    private fun requireCore(): Core = core ?: throw SipEngineException.NotStarted()

    private fun requireCall(id: CallId): Call = callsById[id] ?: throw SipEngineException.UnknownCall(id)

    private fun track(call: Call): CallId {
        idsByCall[call]?.let { return it }

        val id = nonEmpty(call.callLog?.callId)?.let(::CallId) ?: CallId()
        callsById[id] = call
        idsByCall[call] = id

        return id
    }

    private fun forget(call: Call, id: CallId) {
        callsById.remove(id)
        idsByCall.remove(call)
        localEndReasons.remove(id)
        answered.remove(id)
        callAccounts.remove(id)
    }

    /** The account a call belongs to: the one we placed it with, or (incoming) the one whose identity is the callee. */
    private fun accountId(call: Call, id: CallId): SipAccountId? {
        callAccounts[id]?.let { return it }

        // 1. The account liblinphone matched the call to; 2. the callee address; 3. never guess between several.
        val byAccount = call.params?.account?.let { account -> accounts.entries.firstOrNull { it.value == account }?.key }
        val callee = call.toAddress
        val byAddress = accountConfigs.entries.firstOrNull { (_, config) -> config.username == callee?.username && config.domain == callee?.domain }?.key
        val match = byAccount ?: byAddress ?: accountConfigs.keys.singleOrNull()

        match?.let { callAccounts[id] = it }

        return match
    }

    private fun info(call: Call, id: CallId, state: CallState): CallInfo? {
        val accountId = accountId(call, id) ?: return null

        return CallInfo(
            id = id,
            direction = if (call.dir == Call.Dir.Incoming) CallDirection.INCOMING else CallDirection.OUTGOING,
            accountId = accountId,
            remoteNumber = call.remoteAddress.username,
            remoteName = nonEmpty(call.remoteAddress.displayName),
            state = state,
            fssCallRef = nonEmpty(call.remoteParams?.getCustomHeader(FSS_CALL_HEADER)),
        )
    }

    private fun applyMedia(config: SipAccountConfig, core: Core) {
        // Codecs: only what the account asks for (opus/g722/pcma/pcmu) plus DTMF events; everything else off.
        val wanted = AudioCodec.entries.associateBy { it.mime }

        for (payload in core.audioPayloadTypes) {
            val mime = payload.mimeType.lowercase()

            when {
                mime == "telephone-event" -> payload.enable(true)
                wanted.containsKey(mime) -> payload.enable(wanted.getValue(mime) in config.codecs)
                else -> payload.enable(false)
            }
        }

        // SRTP is a core-level setting in liblinphone (shared by all accounts): the strictest request wins.
        when (config.srtp) {
            SrtpMode.DISABLED -> {
                core.setMediaEncryption(MediaEncryption.None)
                core.isMediaEncryptionMandatory = false
            }
            SrtpMode.OPTIONAL -> {
                core.setMediaEncryption(MediaEncryption.SRTP)
                core.isMediaEncryptionMandatory = false
            }
            SrtpMode.MANDATORY -> {
                core.setMediaEncryption(MediaEncryption.SRTP)
                core.isMediaEncryptionMandatory = true
            }
        }
    }

    companion object {
        const val FSS_CALL_HEADER = "X-FSS-Call"

        fun transport(transport: SipTransport): TransportType = when (transport) {
            SipTransport.UDP -> TransportType.Udp
            SipTransport.TCP -> TransportType.Tcp
            SipTransport.TLS -> TransportType.Tls
        }

        fun registrationState(state: LinphoneRegistrationState, message: String?): RegistrationState = when (state) {
            LinphoneRegistrationState.None, LinphoneRegistrationState.Cleared -> RegistrationState.Unregistered
            LinphoneRegistrationState.Progress, LinphoneRegistrationState.Refreshing -> RegistrationState.Registering
            LinphoneRegistrationState.Ok -> RegistrationState.Registered
            LinphoneRegistrationState.Failed -> {
                val text = message.orEmpty().lowercase()

                when {
                    listOf("forbidden", "unauthorized", "403", "401").any(text::contains) -> RegistrationState.Failed(RegistrationFailure.Authentication)
                    listOf("timeout", "unreachable", "network", "io error").any(text::contains) -> RegistrationState.Failed(RegistrationFailure.Network)
                    else -> RegistrationState.Failed(RegistrationFailure.Other(message ?: "registration failed"))
                }
            }
        }

        fun callState(state: Call.State, ended: CallEndReason?): CallState = when (state) {
            Call.State.IncomingReceived, Call.State.PushIncomingReceived, Call.State.IncomingEarlyMedia -> CallState.IncomingRinging
            Call.State.OutgoingInit -> CallState.OutgoingInitiated
            Call.State.OutgoingProgress, Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> CallState.OutgoingRinging
            Call.State.Connected, Call.State.Resuming, Call.State.Updating, Call.State.UpdatedByRemote, Call.State.Referred,
            Call.State.EarlyUpdating, Call.State.EarlyUpdatedByRemote -> CallState.Connecting
            Call.State.StreamsRunning -> CallState.Active
            Call.State.Pausing, Call.State.Paused -> CallState.Held
            Call.State.PausedByRemote -> CallState.HeldByRemote
            Call.State.End, Call.State.Released, Call.State.Error, Call.State.Idle -> CallState.Ended(ended ?: CallEndReason.RemoteHangup)
        }

        /**
         * Digits, `+` (first only), `*`, `#`, 1 to 32 characters. Anything else could smuggle SIP syntax into the
         * request URI. (The same rule as `DialNumber.isDialable` in callcontroller; repeated on purpose, this module
         * must not depend on the app's modules.)
         */
        fun isDialable(number: String): Boolean {
            if (number.length !in 1..32 || number == "+") {
                return false
            }

            return number.withIndex().all { (index, c) -> c in '0'..'9' || c == '*' || c == '#' || (c == '+' && index == 0) }
        }

        private fun nonEmpty(text: String?): String? = text?.takeIf { it.isNotEmpty() }
    }
}
