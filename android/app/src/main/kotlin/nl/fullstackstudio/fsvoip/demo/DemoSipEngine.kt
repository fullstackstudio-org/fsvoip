// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.demo

import android.os.Handler
import android.os.Looper
import nl.fullstackstudio.fsvoip.sipengine.CallDirection
import nl.fullstackstudio.fsvoip.sipengine.CallEndReason
import nl.fullstackstudio.fsvoip.sipengine.CallId
import nl.fullstackstudio.fsvoip.sipengine.CallInfo
import nl.fullstackstudio.fsvoip.sipengine.CallOptions
import nl.fullstackstudio.fsvoip.sipengine.CallState
import nl.fullstackstudio.fsvoip.sipengine.DeclineReason
import nl.fullstackstudio.fsvoip.sipengine.DtmfDigit
import nl.fullstackstudio.fsvoip.sipengine.IncomingCall
import nl.fullstackstudio.fsvoip.sipengine.RegistrationState
import nl.fullstackstudio.fsvoip.sipengine.SipAccountConfig
import nl.fullstackstudio.fsvoip.sipengine.SipAccountId
import nl.fullstackstudio.fsvoip.sipengine.SipAudioControl
import nl.fullstackstudio.fsvoip.sipengine.SipEngine
import nl.fullstackstudio.fsvoip.sipengine.SipEngineListener

/** A SIP engine that registers instantly and lets calls ring for two seconds before they connect. */
class DemoSipEngine : SipEngine {
    override var listener: SipEngineListener? = null

    override val audio: SipAudioControl = object : SipAudioControl {
        override fun configure() = Unit
        override fun activate(active: Boolean) = Unit
    }

    private val handler = Handler(Looper.getMainLooper())
    private val registered = mutableMapOf<SipAccountId, SipAccountConfig>()
    private val live = linkedMapOf<CallId, CallInfo>()

    /** Every number the app dialled, newest last (screenshots and tests read it). */
    val dialled = mutableListOf<String>()

    override fun start() = Unit
    override fun stop() = Unit
    override fun enterBackground() = Unit
    override fun enterForeground() = Unit
    override fun refreshRegistrations() = Unit

    override fun register(account: SipAccountConfig) {
        registered[account.id] = account
        report(RegistrationState.Registering, account.id)
        handler.postDelayed({ report(RegistrationState.Registered, account.id) }, 400)
    }

    override fun unregister(account: SipAccountId) {
        registered.remove(account)
    }

    override fun registrationState(account: SipAccountId): RegistrationState =
        if (registered.containsKey(account)) RegistrationState.Registered else RegistrationState.Unregistered

    override fun setRegistrationEnabled(enabled: Boolean, account: SipAccountId) = Unit
    override fun refreshRegistration(account: SipAccountId) = Unit

    /** The options of every call started, newest last (screenshots and tests read the `X-FSS-From` header from it). */
    val callOptions = mutableListOf<CallOptions>()

    init {
        CallExpDemoHandler.engine = this
    }

    override fun call(number: String, account: SipAccountId, options: CallOptions): CallId {
        val id = CallId()
        dialled += number
        callOptions += options
        live[id] = CallInfo(id, CallDirection.OUTGOING, account, number, null, CallState.OutgoingInitiated)
        handler.postDelayed({ change(id, CallState.OutgoingRinging) }, 200)
        handler.postDelayed({ change(id, CallState.Active) }, 2000)

        return id
    }

    /** A SIP Call-ID of a pretend dialog, so parking works against the demo API. */
    override fun sipCallId(call: CallId): String? = if (live.containsKey(call)) "demo-${call.raw.take(8)}@demo.local" else null

    /** Does a running call have this SIP Call-ID? (The demo API checks it like the PBX does.) */
    fun hasSipCallId(sipCallId: String): Boolean = live.keys.any { sipCallId(it) == sipCallId }

    /** The PBX ends our leg of a call it parked (a BYE from the other side). */
    fun endBecauseParked(sipCallId: String) {
        val id = live.keys.firstOrNull { sipCallId(it) == sipCallId } ?: return
        change(id, CallState.Ended(CallEndReason.RemoteHangup))
    }

    fun simulateIncoming(number: String, name: String?, account: String, fssCallRef: String? = null) {
        val id = CallId()
        live[id] = CallInfo(id, CallDirection.INCOMING, SipAccountId(account), number, name, CallState.IncomingRinging, fssCallRef)
        listener?.onIncomingCall(this, IncomingCall(id, number, name, SipAccountId(account), fssCallRef))
    }

    override fun answer(call: CallId) {
        change(call, CallState.Connecting)
        handler.postDelayed({ change(call, CallState.Active) }, 300)
    }

    override fun decline(call: CallId, reason: DeclineReason) = change(call, CallState.Ended(CallEndReason.Declined))

    override fun hangup(call: CallId) = change(call, CallState.Ended(CallEndReason.LocalHangup))

    override fun setHold(call: CallId, onHold: Boolean) = change(call, if (onHold) CallState.Held else CallState.Active)

    override fun setMuted(muted: Boolean) = Unit
    override fun sendDtmf(digit: DtmfDigit, call: CallId) = Unit
    override fun transfer(call: CallId, number: String) = Unit
    override fun calls(): List<CallInfo> = live.values.toList()

    private fun report(state: RegistrationState, account: SipAccountId) {
        if (!registered.containsKey(account)) return
        listener?.onRegistrationChanged(this, state, account)
    }

    private fun change(id: CallId, state: CallState) {
        val info = live[id]?.copy(state = state) ?: return
        if (state.isEnded) live.remove(id) else live[id] = info
        listener?.onCallChanged(this, info)
    }
}
